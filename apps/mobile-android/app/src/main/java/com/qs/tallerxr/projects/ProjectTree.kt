package com.qs.tallerxr.projects

import com.qs.tallerxr.data.InstalledPackageSummary
import java.security.MessageDigest
import kotlinx.serialization.Serializable

enum class ModelFormat(val label: String) {
    GLB("GLB"),
    IFC("IFC"),

    /** A Baugruppe installed from a `.qsproject` exported by the PC viewer. */
    QSXR("QSXR");

    companion object {
        /** Format of a file the folder scan should list, or null for anything else. */
        fun ofFileName(name: String): ModelFormat? = when (name.substringAfterLast('.', "").lowercase()) {
            "glb" -> GLB
            "ifc" -> IFC
            else -> null
        }
    }
}

/**
 * One model file found in a folder linked to the project.  [path] are the classifier folders above
 * it, inside the linked folder [folderId].
 */
@Serializable
data class SourceEntry(
    val folderId: String = "",
    val path: List<String>,
    val name: String,
    val documentUri: String,
    val format: ModelFormat,
    val sizeBytes: Long,
    val lastModified: Long,
) {
    val title: String get() = name.substringBeforeLast('.', name)

    /** Stable identity of the file inside its project: survives rescans, changes with a move. */
    val key: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest((listOf(folderId) + path + name).joinToString("/").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
}

/** A system folder the operator associated with a project.  Only the reference is stored. */
@Serializable
data class LinkedFolder(
    val id: String,
    val treeUri: String,
    val label: String,
    val linkedAt: String,
)

/**
 * Configuration of a project: its name and which folders are linked to it, nothing else.  Files
 * are never imported or copied into the project; they stay where the operator keeps them.
 */
@Serializable
data class FolderProjectDocument(
    val schemaVersion: String = "2.0",
    val id: String,
    val name: String,
    val createdAt: String,
    val folders: List<LinkedFolder> = emptyList(),
    // Read only, from projects created by 0.16.x, which held exactly one folder.
    val treeUri: String? = null,
    val folderLabel: String? = null,
)

/**
 * What the last look into the linked folders found.  It is a cache, kept apart from the project
 * configuration: it lets a project open at once and stay browsable while a card or a drive is not
 * connected, and it is rebuilt from the folders whenever they can be read.
 */
@Serializable
data class ProjectListing(
    val entries: List<SourceEntry> = emptyList(),
    val scannedAt: Map<String, String> = emptyMap(),
)

enum class ProjectSource { FOLDER, QSPROJECT }

/** How far a model is from opening instantly. */
enum class ModelState {
    /** Prepared and up to date: opens at once, also without access to the source folder. */
    READY,

    /** Never opened: it is prepared (IFC converted, corners derived) on first open. */
    PENDING,

    /** The source file changed after it was prepared. */
    STALE,

    /** The last preparation failed; the reason is shown on the card. */
    ERROR,
}

sealed interface ModelOrigin {
    data class FolderFile(val entry: SourceEntry) : ModelOrigin
    data class InstalledRevision(val summary: InstalledPackageSummary) : ModelOrigin
}

data class BrowserModel(
    val key: String,
    val title: String,
    val detail: String,
    val format: ModelFormat,
    val state: ModelState,
    val origin: ModelOrigin,
    val error: String? = null,
)

data class BrowserFolder(
    val name: String,
    val path: List<String>,
    val folders: List<BrowserFolder>,
    val models: List<BrowserModel>,
) {
    val modelCount: Int = models.size + folders.sumOf(BrowserFolder::modelCount)

    /** The folder at [target], or the deepest one that still exists when part of it is gone. */
    fun resolve(target: List<String>): BrowserFolder {
        var current = this
        for (segment in target) {
            current = current.folders.firstOrNull { it.name == segment } ?: return current
        }
        return current
    }

    fun allModels(): List<BrowserModel> = models + folders.flatMap(BrowserFolder::allModels)

    /** Every model below this folder whose name or folder matches, for the search box. */
    fun search(query: String): List<Pair<BrowserModel, List<String>>> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val own = models.filter { needle in it.title.lowercase() || needle in it.detail.lowercase() }
            .map { it to path }
        return own + folders.flatMap { folder ->
            if (needle in folder.name.lowercase()) {
                folder.allModelsWithPath()
            } else {
                folder.search(query)
            }
        }
    }

    private fun allModelsWithPath(): List<Pair<BrowserModel, List<String>>> =
        models.map { it to path } + folders.flatMap(BrowserFolder::allModelsWithPath)
}

object ProjectTree {
    /**
     * Arranges the scanned files the way the operator laid them out on disk: a folder without
     * subfolders lists its files directly; subfolders become classifier levels, as deep as they
     * go.  Folders that lead to no model are left out.
     */
    /**
     * Tree of a project with linked folders.  A single linked folder shows its content directly
     * in the project; several become the first level, named after the folders.
     */
    fun fromLinkedFolders(
        rootName: String,
        folders: List<LinkedFolder>,
        entries: List<SourceEntry>,
        describe: (SourceEntry) -> BrowserModel,
    ): BrowserFolder {
        if (folders.size <= 1) return fromEntries(rootName, entries, describe)
        val used = HashSet<String>()
        val labels = folders.associate { folder ->
            var label = folder.label
            var suffix = 2
            while (!used.add(label)) label = "${folder.label} ($suffix)".also { suffix += 1 }
            folder.id to label
        }
        val prefixed = entries.mapNotNull { entry ->
            labels[entry.folderId]?.let { label -> entry to listOf(label) + entry.path }
        }
        return fromPaths(rootName, prefixed, describe)
    }

    fun fromEntries(
        rootName: String,
        entries: List<SourceEntry>,
        describe: (SourceEntry) -> BrowserModel,
    ): BrowserFolder = fromPaths(rootName, entries.map { it to it.path }, describe)

    private fun fromPaths(
        rootName: String,
        entries: List<Pair<SourceEntry, List<String>>>,
        describe: (SourceEntry) -> BrowserModel,
    ): BrowserFolder {
        fun build(name: String, path: List<String>, scoped: List<Pair<SourceEntry, List<String>>>): BrowserFolder {
            val depth = path.size
            val here = scoped.filter { it.second.size == depth }.map { it.first }
            val below = scoped.filter { it.second.size > depth }.groupBy { it.second[depth] }
            return BrowserFolder(
                name = name,
                path = path,
                folders = below.entries
                    .map { (child, children) -> build(child, path + child, children) }
                    .sortedWith(compareBy(NaturalOrder) { it.name }),
                models = here.map(describe).sortedWith(compareBy(NaturalOrder) { it.title }),
            )
        }
        return build(rootName, emptyList(), entries)
    }

    /** A `.qsproject` has a fixed classifier: Zeichnung, then its positions and revisions. */
    fun fromInstalled(
        rootName: String,
        packages: List<InstalledPackageSummary>,
        describe: (InstalledPackageSummary) -> BrowserModel,
    ): BrowserFolder {
        val drawings = packages.groupBy { drawingNumber(it.assembly.positionNumber) }
        if (drawings.values.all { it.size == 1 }) {
            // One Baugruppe per Zeichnung, as in the demo project: a folder each would only add taps.
            return BrowserFolder(
                name = rootName,
                path = emptyList(),
                folders = emptyList(),
                models = packages.sortedWith(compareBy(NaturalOrder) { it.assembly.positionNumber }).map(describe),
            )
        }
        return BrowserFolder(
            name = rootName,
            path = emptyList(),
            folders = drawings.entries.map { (drawing, revisions) ->
                BrowserFolder(
                    name = "Zeichnung $drawing",
                    path = listOf("Zeichnung $drawing"),
                    folders = emptyList(),
                    models = revisions
                        .sortedWith(
                            compareBy<InstalledPackageSummary, String>(NaturalOrder) { it.assembly.positionNumber }
                                .thenByDescending { it.assembly.revision },
                        )
                        .map(describe),
                )
            }.sortedWith(compareBy(NaturalOrder) { it.name }),
            models = emptyList(),
        )
    }

    fun drawingNumber(position: String): String =
        position.substringBefore('.').ifBlank { "Ohne Zeichnung" }
}

/** "P2" before "P10": file and folder names are compared the way people read them. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(left: String, right: String): Int {
        val a = chunk.findAll(left.lowercase()).map { it.value }.toList()
        val b = chunk.findAll(right.lowercase()).map { it.value }.toList()
        for (index in 0 until minOf(a.size, b.size)) {
            val x = a[index]
            val y = b[index]
            val result = if (x[0].isDigit() && y[0].isDigit()) {
                val trimmedX = x.trimStart('0')
                val trimmedY = y.trimStart('0')
                compareValuesBy(trimmedX, trimmedY, { it.length }, { it })
            } else {
                x.compareTo(y)
            }
            if (result != 0) return result
        }
        return a.size.compareTo(b.size).takeIf { it != 0 } ?: left.compareTo(right)
    }
}
