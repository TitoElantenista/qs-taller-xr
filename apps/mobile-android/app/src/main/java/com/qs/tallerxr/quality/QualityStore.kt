package com.qs.tallerxr.quality

import com.qs.tallerxr.data.AssemblyCheckRecord
import com.qs.tallerxr.data.InstalledPackageSummary
import com.qs.tallerxr.data.JsonCodec
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Separate from model caches and the old check file, so imports and downgrades preserve the audit. */
class QualityStore(private val root: File) {
    private val mutex = Mutex()
    private val documentFile = File(root, "quality-v2.json")
    private val previousFile = File(root, "quality.json")
    private val reportRoot = File(root, "reports")
    private val photoRoot = File(root, "photos")
    private val logoFile = File(root, "report-logo.png")

    suspend fun load(
        legacy: Collection<AssemblyCheckRecord> = emptyList(),
        summaries: List<InstalledPackageSummary> = emptyList(),
    ): QualityDocument = transaction { document -> document.migrate(legacy, summaries) }

    suspend fun register(piece: QualityPiece, sourceVersion: String = ""): QualityDocument = transaction { document ->
        val records = if (document.record(piece.key) != null) document.records
            else document.records + QualityRecord(piece, Instant.now().toString())
        document.copy(records = records, bindings = document.bindings +
            (qualityBindingKey(piece.projectId, piece.sourceKey) to QualityBinding(piece.key, sourceVersion)))
    }


    suspend fun saveInspector(name: String): QualityDocument = transaction { document ->
        require(name.trim().isNotEmpty() && name.trim().length <= 120) { "Bitte einen Namen mit 1 bis 120 Zeichen angeben." }
        document.copy(inspector = name.trim())
    }

    suspend fun addCheck(key: String, status: QualityStatus?, inspector: String, comment: String,
        action: QualityAction? = null): QualityDocument =
        transaction { document ->
            require(status != QualityStatus.LEGACY) { "Altbestände können nicht neu angelegt werden." }
            require(inspector.trim().length in 1..120) { "Bitte den Namen der prüfenden Person angeben." }
            document.append(key, newEvent(inspector, status, comment).copy(action = action?.normalized()))
                .copy(inspector = inspector.trim())
        }

    suspend fun saveOrder(order: OrderDetails): QualityDocument = transaction { document ->
        val normalized = order.normalized().copy(updatedAt = Instant.now().toString())
        document.copy(orders = document.orders + (normalized.projectId to normalized))
    }

    /** Freeze metadata and events, then atomically publish a complete private PDF and its catalog entry. */
    suspend fun issueReport(
        key: String,
        projectName: String,
        render: (IssuedQualityReport, OutputStream) -> Unit,
    ): Pair<QualityDocument, IssuedQualityReport> = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            val document = read()
            val record = document.record(key) ?: error("Die QS-Akte wurde nicht gefunden.")
            val order = document.orders[record.piece.projectId]
                ?: OrderDetails(record.piece.projectId, projectName.ifBlank { record.piece.projectName })
            val previous = document.reports.filter { it.record.piece.key == key }
            val edition = (previous.maxOfOrNull { it.edition } ?: 0) + 1
            val id = UUID.randomUUID().toString()
            val createdAt = Instant.now().toString()
            val shortKey = qualityDigest(key.toByteArray()).take(12).uppercase(java.util.Locale.ROOT)
            val report = IssuedQualityReport(id, "QS-$shortKey", edition, createdAt,
                ZoneId.systemDefault().id, document.inspector, order, record, "$id.pdf")
            check(reportRoot.isDirectory || reportRoot.mkdirs()) { "Der Berichtsordner konnte nicht angelegt werden." }
            val target = reportFile(report)
            val staging = File(reportRoot, "$id.tmp")
            try {
                FileOutputStream(staging).use { output -> render(report, output); output.fd.sync() }
                check(staging.length() > 0L) { "Das erzeugte PDF ist leer." }
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                val issued = report.copy(sha256 = fileDigest(target))
                val next = document.copy(reports = document.reports + issued)
                write(next)
                next to issued
            } catch (error: Throwable) {
                target.delete()
                throw error
            } finally { staging.delete() }
        }
    }

    fun reportFile(report: IssuedQualityReport): File {
        require(report.pdfFileName.matches(Regex("[a-f0-9-]+\\.pdf"))) { "Ungültiger Berichtspfad." }
        return File(reportRoot, report.pdfFileName)
    }

    suspend fun verifyReportFile(report: IssuedQualityReport): File = withContext(Dispatchers.IO) {
        reportFile(report).also {
            check(it.isFile && report.sha256.isNotEmpty() && fileDigest(it) == report.sha256) {
                "Das archivierte PDF fehlt oder wurde verändert. Bitte ein neues Protokoll erstellen."
            }
        }
    }

    private fun fileDigest(file: File): String = file.inputStream().use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The JPEG is durable before its reference is committed. Failed commits remove only the new JPEG. */
    suspend fun addPhoto(
        key: String,
        inspector: String,
        capturedAt: String,
        displayName: String,
        alignment: String,
        source: () -> InputStream,
    ): QualityDocument = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            val document = read()
            require(inspector.trim().length in 1..120) { "Bitte zuerst unter Einstellungen den Prüfernamen speichern." }
            require(document.record(key) != null) { "Die QS-Akte wurde nicht gefunden." }
            check(photoRoot.isDirectory || photoRoot.mkdirs()) { "Der Fotoordner konnte nicht angelegt werden." }
            val id = UUID.randomUUID().toString()
            val photo = File(photoRoot, "$id.jpg")
            try {
                FileOutputStream(photo).use { output ->
                    source().use { it.copyTo(output) }
                    output.fd.sync()
                }
                require(photo.length() > 0) { "Das AR-Foto ist leer." }
                val hash = photo.inputStream().use { input ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                val event = QualityEvent(id, capturedAt, ZoneId.systemDefault().id, inspector.trim(),
                    photo = QualityPhoto(photo.name, displayName, hash, alignment))
                val next = document.append(key, event)
                write(next)
                next
            } catch (error: Throwable) {
                photo.delete()
                throw error
            }
        }
    }

    /** Company logo for the reports, chosen in the settings; null when none is set. */
    fun reportLogo(): File? = logoFile.takeIf { it.isFile && it.length() > 0 }

    /** Replaces the logo atomically with what [write] produces (a PNG prepared by the caller). */
    suspend fun saveReportLogo(write: (OutputStream) -> Unit): File = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            check(root.isDirectory || root.mkdirs()) { "Der QS-Ordner konnte nicht angelegt werden." }
            val staging = File(root, "report-logo.png.tmp")
            try {
                FileOutputStream(staging).use { output -> write(output); output.fd.sync() }
                check(staging.length() > 0) { "Das Logo ist leer." }
                Files.move(staging.toPath(), logoFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                logoFile
            } finally { staging.delete() }
        }
    }

    suspend fun removeReportLogo() = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock { logoFile.delete() }
    }

    fun photoFile(photo: QualityPhoto): File {
        require(photo.fileName.matches(Regex("[a-f0-9-]+\\.jpg"))) { "Ungültiger Fotopfad." }
        return File(photoRoot, photo.fileName)
    }

    private suspend fun transaction(change: (QualityDocument) -> QualityDocument): QualityDocument =
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                val previous = read()
                val next = change(previous)
                if (next != previous || !documentFile.exists()) write(next)
                next
            }
        }

    private fun read(): QualityDocument {
        val source = if (documentFile.exists()) documentFile else previousFile
        if (!source.exists()) return QualityDocument()
        // Never replace damaged/unknown audit data with an empty document.
        return try {
            JsonCodec.value.decodeFromString<QualityDocument>(source.readText(Charsets.UTF_8)).also {
                check(it.schemaVersion == 2 || (source == previousFile && it.schemaVersion == 1)) { "Unbekannte QS-Datenversion ${it.schemaVersion}." }
                check(it.records.map { record -> record.piece.key }.distinct().size == it.records.size)
            }.copy(schemaVersion = 2)
        } catch (error: Exception) {
            throw IllegalStateException("Die QS-Daten konnten nicht gelesen werden. Die vorhandene Datei bleibt erhalten.", error)
        }
    }

    private fun write(document: QualityDocument) {
        check(root.isDirectory || root.mkdirs()) { "Der QS-Ordner konnte nicht angelegt werden." }
        val staging = File(root, "quality-v2.json.tmp")
        try {
            FileOutputStream(staging).use { output ->
                output.write(JsonCodec.value.encodeToString(document).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(staging.toPath(), documentFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staging.delete()
        }
    }

    private fun newEvent(inspector: String, status: QualityStatus?, comment: String) = QualityEvent(
        UUID.randomUUID().toString(), Instant.now().toString(), ZoneId.systemDefault().id,
        inspector.trim(), status, comment.trim(),
    )
}
