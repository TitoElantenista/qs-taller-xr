package com.qs.tallerxr.projects

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import com.qs.tallerxr.importer.PreparedModelWriter
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A linked folder cannot be read: deleted, moved, permission revoked or its drive removed. */
class FolderUnavailableException(message: String) : Exception(message)

class FolderScan(val entries: List<SourceEntry>, val truncated: Boolean)

/**
 * Projects and the system folders linked to them.
 *
 * A project is a name plus references to folders the operator picked.  Nothing is imported: the
 * app keeps a read permission per folder and never writes into it.  `project.json` holds only that
 * configuration.  Next to it live two caches that can be rebuilt at any time: the last listing of
 * the folders, and per model the prepared form the viewers open.
 */
class FolderProjectRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val root = File(appContext.filesDir, "projects")
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun list(): List<FolderProjectDocument> = withContext(Dispatchers.IO) {
        mutex.withLock {
            root.listFiles().orEmpty()
                .filter(File::isDirectory)
                .mapNotNull(::read)
                .sortedWith(compareBy(NaturalOrder) { it.name })
        }
    }

    /** A project starts empty; folders are linked to it afterwards. */
    suspend fun create(name: String): FolderProjectDocument = withContext(Dispatchers.IO) {
        mutex.withLock {
            FolderProjectDocument(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifBlank { "Projekt" },
                createdAt = Instant.now().toString(),
            ).also(::write)
        }
    }

    suspend fun rename(projectId: String, name: String): FolderProjectDocument? = update(projectId) {
        it.copy(name = name.trim().ifBlank { it.name })
    }

    /**
     * Links a folder by reference.  The persisted read grant is what lets the app find the folder
     * again after a restart; without it the link would silently die with the process.
     */
    suspend fun linkFolder(projectId: String, treeUri: Uri): FolderProjectDocument = withContext(Dispatchers.IO) {
        val label = displayName(treeUri)
            ?: throw FolderUnavailableException("Der gewählte Ordner kann nicht gelesen werden.")
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        mutex.withLock {
            val directory = directoryOf(projectId) ?: error("Das Projekt existiert nicht mehr.")
            val current = read(directory) ?: error("Das Projekt existiert nicht mehr.")
            if (current.folders.any { it.treeUri == treeUri.toString() }) {
                throw IllegalStateException("«$label» ist bereits mit diesem Projekt verknüpft.")
            }
            current.copy(
                folders = current.folders + LinkedFolder(
                    id = UUID.randomUUID().toString(),
                    treeUri = treeUri.toString(),
                    label = label,
                    linkedAt = Instant.now().toString(),
                ),
            ).also(::write)
        }
    }

    /** Points an existing link at a folder picked again, e.g. after it was moved to another card. */
    suspend fun relinkFolder(projectId: String, folderId: String, treeUri: Uri): FolderProjectDocument? {
        val label = withContext(Dispatchers.IO) { displayName(treeUri) }
            ?: throw FolderUnavailableException("Der gewählte Ordner kann nicht gelesen werden.")
        withContext(Dispatchers.IO) {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return update(projectId) { project ->
            project.copy(
                folders = project.folders.map { folder ->
                    if (folder.id == folderId) folder.copy(treeUri = treeUri.toString(), label = label) else folder
                },
            )
        }
    }

    /** Removes the link only; the folder itself is never touched. */
    suspend fun unlinkFolder(projectId: String, folderId: String): FolderProjectDocument? {
        val removed = list().firstOrNull { it.id == projectId }?.folders?.firstOrNull { it.id == folderId }
        val updated = update(projectId) { project -> project.copy(folders = project.folders.filterNot { it.id == folderId }) }
        if (updated != null) {
            saveListing(projectId, listing(projectId).let { current ->
                current.copy(
                    entries = current.entries.filterNot { it.folderId == folderId },
                    scannedAt = current.scannedAt - folderId,
                )
            })
            removed?.let { releaseIfUnused(it.treeUri) }
        }
        return updated
    }

    suspend fun delete(projectId: String): Boolean {
        val project = list().firstOrNull { it.id == projectId }
        val removed = withContext(Dispatchers.IO) {
            mutex.withLock { directoryOf(projectId)?.deleteRecursively() ?: false }
        }
        project?.folders?.forEach { releaseIfUnused(it.treeUri) }
        return removed
    }

    private suspend fun releaseIfUnused(treeUri: String) {
        if (list().any { project -> project.folders.any { it.treeUri == treeUri } }) return
        withContext(Dispatchers.IO) {
            runCatching {
                resolver.releasePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** True while the linked folder still exists and the app may read it. */
    suspend fun isAvailable(folder: LinkedFolder): Boolean = withContext(Dispatchers.IO) {
        displayName(Uri.parse(folder.treeUri)) != null
    }

    // ── Caches ───────────────────────────────────────────────────────────────────────────────────

    suspend fun listing(projectId: String): ProjectListing = withContext(Dispatchers.IO) {
        runCatching {
            json.decodeFromString<ProjectListing>(
                AtomicFile(File(checkedDirectory(projectId), LISTING_FILE)).readFully().toString(Charsets.UTF_8),
            )
        }.getOrDefault(ProjectListing())
    }

    suspend fun saveListing(projectId: String, listing: ProjectListing) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (directoryOf(projectId) != null) {
                writeAtomically(File(checkedDirectory(projectId), LISTING_FILE), json.encodeToString(listing))
            }
        }
    }

    fun modelDirectory(projectId: String, entry: SourceEntry): File =
        File(File(checkedDirectory(projectId), "models"), entry.key)

    fun stateOf(projectId: String, entry: SourceEntry): ModelState {
        val stamp = PreparedModelWriter.stamp(modelDirectory(projectId, entry)) ?: return ModelState.PENDING
        return if (stamp.matches(entry.sizeBytes, entry.lastModified)) ModelState.READY else ModelState.STALE
    }

    suspend fun deletePrepared(projectId: String, entry: SourceEntry) = withContext(Dispatchers.IO) {
        modelDirectory(projectId, entry).deleteRecursively()
    }

    /** Drops prepared models of files that are no longer listed and preparations cut short by a kill. */
    suspend fun prune(projectId: String, listing: ProjectListing): Int = withContext(Dispatchers.IO) {
        val wanted = listing.entries.mapTo(HashSet(), SourceEntry::key)
        File(checkedDirectory(projectId), "models").listFiles().orEmpty().count { directory ->
            (directory.name !in wanted) && directory.deleteRecursively()
        }
    }

    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        root.walkBottomUp().filter(File::isFile).sumOf(File::length)
    }

    // ── Reading a linked folder ──────────────────────────────────────────────────────────────────

    /**
     * Walks a linked folder.  Only `.glb` and `.ifc` files are listed; everything else, and any
     * folder that leads to none of them, is ignored.  The walk is iterative and bounded so a
     * mistaken pick of a whole storage root cannot hang the app.
     */
    suspend fun scan(
        folder: LinkedFolder,
        onProgress: (folders: Int, files: Int) -> Unit = { _, _ -> },
    ): FolderScan = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(folder.treeUri)
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (rootId == null || displayName(treeUri) == null) {
            throw FolderUnavailableException(
                "Der Ordner «${folder.label}» wurde nicht gefunden. Er wurde gelöscht oder verschoben, " +
                    "oder sein Speicher ist nicht verbunden.",
            )
        }
        val entries = ArrayList<SourceEntry>()
        val pending = ArrayDeque<Pair<String, List<String>>>()
        pending += rootId to emptyList()
        var folders = 0
        var truncated = false
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (documentId, path) = pending.removeFirst()
            folders += 1
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val cursor = try {
                resolver.query(children, PROJECTION, null, null, null)
            } catch (error: SecurityException) {
                throw FolderUnavailableException(
                    "Die App hat keine Berechtigung mehr, den Ordner «${folder.label}» zu lesen.",
                )
            } catch (error: Exception) {
                null
            } ?: continue
            cursor.use {
                while (it.moveToNext()) {
                    val childId = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (path.size < MAXIMUM_DEPTH && folders + pending.size < MAXIMUM_FOLDERS) {
                            pending += childId to (path + name)
                        } else {
                            truncated = true
                        }
                        continue
                    }
                    val format = ModelFormat.ofFileName(name) ?: continue
                    if (entries.size >= MAXIMUM_FILES) {
                        truncated = true
                        continue
                    }
                    entries += SourceEntry(
                        folderId = folder.id,
                        path = path,
                        name = name,
                        documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId).toString(),
                        format = format,
                        sizeBytes = if (it.isNull(3)) -1L else it.getLong(3),
                        lastModified = if (it.isNull(4)) 0L else it.getLong(4),
                    )
                }
            }
            onProgress(folders, entries.size)
        }
        FolderScan(entries, truncated)
    }

    /** Current size and date of one source file, or null when it cannot be reached. */
    suspend fun sourceMetadata(entry: SourceEntry): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(
                Uri.parse(entry.documentUri),
                arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                (if (cursor.isNull(0)) -1L else cursor.getLong(0)) to (if (cursor.isNull(1)) 0L else cursor.getLong(1))
            }
        }.getOrNull()
    }

    /** Name of the folder behind a tree URI, or null when it is gone or may not be read. */
    private fun displayName(treeUri: Uri): String? = runCatching {
        val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private suspend fun update(
        projectId: String,
        change: (FolderProjectDocument) -> FolderProjectDocument,
    ): FolderProjectDocument? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = directoryOf(projectId) ?: return@withLock null
            val current = read(directory) ?: return@withLock null
            change(current).also(::write)
        }
    }

    private fun checkedDirectory(projectId: String): File {
        // The id names a directory: it must be one of ours, never a path.
        require(runCatching { UUID.fromString(projectId) }.isSuccess) { "Ungültige Projektkennung." }
        return File(root, projectId)
    }

    private fun directoryOf(projectId: String): File? = checkedDirectory(projectId).takeIf(File::isDirectory)

    private fun read(directory: File): FolderProjectDocument? = runCatching {
        json.decodeFromString<FolderProjectDocument>(
            AtomicFile(File(directory, PROJECT_FILE)).readFully().toString(Charsets.UTF_8),
        ).migrated()
    }.getOrNull()

    /** 0.16.x stored exactly one folder inside the project; it becomes the first linked folder. */
    private fun FolderProjectDocument.migrated(): FolderProjectDocument {
        val legacyUri = treeUri ?: return this
        if (folders.isNotEmpty()) return copy(treeUri = null, folderLabel = null)
        return copy(
            schemaVersion = "2.0",
            folders = listOf(
                LinkedFolder(UUID.nameUUIDFromBytes(legacyUri.toByteArray()).toString(), legacyUri, folderLabel ?: name, createdAt),
            ),
            treeUri = null,
            folderLabel = null,
        )
    }

    private fun write(document: FolderProjectDocument) {
        val directory = checkedDirectory(document.id).apply { mkdirs() }
        writeAtomically(File(directory, PROJECT_FILE), json.encodeToString(document))
    }

    private fun writeAtomically(target: File, text: String) {
        val file = AtomicFile(target)
        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val PROJECT_FILE = "project.json"
        const val LISTING_FILE = "listing.cache.json"
        const val MAXIMUM_DEPTH = 12
        const val MAXIMUM_FOLDERS = 5_000
        const val MAXIMUM_FILES = 20_000
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
