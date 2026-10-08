package com.qs.tallerxr.projects

import android.content.Context
import android.net.Uri
import com.qs.tallerxr.data.InstalledPackage
import com.qs.tallerxr.data.allocatableStorageBytes
import com.qs.tallerxr.importer.GlbFormatException
import com.qs.tallerxr.importer.GlbReader
import com.qs.tallerxr.importer.IfcConverter
import com.qs.tallerxr.importer.IfcPartsDocument
import com.qs.tallerxr.importer.ModelIdentity
import com.qs.tallerxr.importer.PreparedModelWriter
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Turns a file of a folder project into a model the viewers can open, once, and reuses it while
 * the source file does not change.  A prepared model opens without touching the source folder,
 * which is what keeps a project usable when its card or drive is not connected.
 */
class ModelPreparer(
    context: Context,
    private val repository: FolderProjectRepository,
    private val ifcConverter: IfcConverter = IfcConverter(context),
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val workRoot = File(appContext.cacheDir, "model-preparation")
    private val mutex = Mutex()

    suspend fun clearWorkFiles() = withContext(Dispatchers.IO) { workRoot.deleteRecursively() }

    suspend fun prepare(
        project: FolderProjectDocument,
        entry: SourceEntry,
        force: Boolean = false,
        onStage: (String) -> Unit = {},
    ): InstalledPackage = mutex.withLock {
        val target = repository.modelDirectory(project.id, entry)
        val current = repository.sourceMetadata(entry)
        val sizeBytes = current?.first ?: entry.sizeBytes
        val lastModified = current?.second ?: entry.lastModified
        if (!force) {
            withContext(Dispatchers.IO) {
                val stamp = PreparedModelWriter.stamp(target)
                // An unreachable source must not invalidate what was already derived from it; but
                // a GLB that is rendered from the linked folder needs that folder to be there.
                val reusable = stamp != null &&
                    (if (current == null) stamp.modelUri == null else stamp.matches(sizeBytes, lastModified))
                if (reusable) PreparedModelWriter.load(target) else null
            }?.let { return@withLock it }
        }
        if (current == null) {
            throw FolderUnavailableException(
                "«${entry.name}» kann im Projektordner nicht gelesen werden. Bitte prüfen, ob der " +
                    "Ordner noch verfügbar ist, oder ihn erneut verknüpfen.",
            )
        }

        val work = File(workRoot, UUID.randomUUID().toString())
        try {
            withContext(Dispatchers.IO) { check(work.mkdirs()) { "Der Arbeitsordner konnte nicht angelegt werden." } }
            onStage("${entry.name} wird kopiert…")
            val source = copySource(entry, File(work, "source.${entry.format.name.lowercase()}"), sizeBytes)
            var parts: IfcPartsDocument? = null
            val glb = when (entry.format) {
                ModelFormat.IFC -> File(work, "converted.glb").also { converted ->
                    parts = ifcConverter.convert(source, converted, onStage).parts
                }
                else -> source
            }
            onStage("Ecken und Kanten des Bauteils werden berechnet…")
            withContext(Dispatchers.Default) {
                target.parentFile?.mkdirs()
                PreparedModelWriter.write(
                    glb = glb,
                    sourceUri = entry.documentUri.takeIf { entry.format == ModelFormat.GLB },
                    ifcParts = parts,
                    identity = ModelIdentity(project.id, project.name, entry.key, entry.title),
                    sourceSizeBytes = sizeBytes,
                    sourceLastModified = lastModified,
                    format = entry.format.name,
                    target = target,
                )
            }
        } catch (error: OutOfMemoryError) {
            throw GlbFormatException(
                "«${entry.name}» ist zu groß für den Arbeitsspeicher des Telefons. Bitte ein Bauteil oder eine " +
                    "Baugruppe pro Datei verwenden.",
            )
        } finally {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { work.deleteRecursively() }
        }
    }

    private suspend fun copySource(entry: SourceEntry, destination: File, declaredBytes: Long): File =
        withContext(Dispatchers.IO) {
            val limit = if (entry.format == ModelFormat.IFC) MAXIMUM_IFC_BYTES else GlbReader.MAXIMUM_BYTES
            if (declaredBytes > limit) {
                throw GlbFormatException(
                    "«${entry.name}» belegt ${declaredBytes / (1024 * 1024)} MB; das Maximum für " +
                        "${entry.format.label} auf dem Telefon beträgt ${limit / (1024 * 1024)} MB.",
                )
            }
            if (declaredBytes > 0 && declaredBytes * 3 > appContext.allocatableStorageBytes()) {
                throw GlbFormatException("Auf dem Telefon ist nicht genug Speicher frei, um «${entry.name}» vorzubereiten.")
            }
            val input = try {
                resolver.openInputStream(Uri.parse(entry.documentUri))
            } catch (error: FileNotFoundException) {
                null
            } catch (error: SecurityException) {
                null
            } ?: throw FolderUnavailableException(
                "«${entry.name}» kann nicht geöffnet werden. Bitte prüfen, ob der Projektordner noch verfügbar ist.",
            )
            input.use { stream ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > limit) throw GlbFormatException("«${entry.name}» überschreitet die maximal zulässige Größe.")
                        output.write(buffer, 0, count)
                    }
                }
            }
            destination
        }

    private companion object {
        const val MAXIMUM_IFC_BYTES = 256L * 1024L * 1024L
    }
}
