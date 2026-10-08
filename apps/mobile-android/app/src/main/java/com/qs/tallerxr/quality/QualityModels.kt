package com.qs.tallerxr.quality

import com.qs.tallerxr.data.AssemblyCheckRecord
import com.qs.tallerxr.data.InstalledPackageSummary
import com.qs.tallerxr.projects.BrowserModel
import com.qs.tallerxr.projects.ModelOrigin
import com.qs.tallerxr.projects.ModelState
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
enum class QualityStatus(val label: String) {
    OPEN("Offen / erneut prüfen"),
    OK("Geprüft – in Ordnung"),
    DEFECT("Mangel festgestellt"),
    REWORK("Nacharbeit erforderlich"),
    RECHECK("Nachkontrolle erforderlich"),
    LEGACY("Geprüft (Altbestand)"),
}

@Serializable
data class QualityPart(
    val position: String,
    val guid: String,
    val type: String,
    val dimensions: String,
    val attributes: Map<String, String> = emptyMap(),
)

/** An immutable snapshot: approval is tied to geometry AND attributes, never just a file name. */
@Serializable
data class QualityPiece(
    val key: String,
    val revisionKey: String,
    val sourceKey: String,
    val projectId: String,
    val projectName: String,
    val position: String,
    val assemblyGuid: String,
    val revision: Int,
    val fingerprint: String,
    val sourceLabel: String,
    val modelCreatedAt: String,
    val parts: List<QualityPart> = emptyList(),
)

@Serializable
data class QualityPhoto(
    val fileName: String,
    val displayName: String,
    val sha256: String,
    val alignment: String,
)

/** Null status means evidence/comment only; it must not silently change the inspection result. */
@Serializable
data class QualityEvent(
    val id: String,
    val at: String,
    val zoneId: String,
    val inspector: String,
    val status: QualityStatus? = null,
    val comment: String = "",
    val photo: QualityPhoto? = null,
    val action: QualityAction? = null,
)

@Serializable
data class QualityRecord(
    val piece: QualityPiece,
    val registeredAt: String,
    val events: List<QualityEvent> = emptyList(),
) {
    val lastCheck: QualityEvent? get() = events.lastOrNull { it.status != null }
    val status: QualityStatus get() = lastCheck?.status ?: QualityStatus.OPEN
    val photos: List<QualityEvent> get() = events.filter { it.photo != null }
    val currentAction: QualityAction? get() {
        for (event in events.asReversed()) {
            if (event.status == QualityStatus.OK || event.status == QualityStatus.OPEN) return null
            event.action?.let { return it.takeUnless(QualityAction::isEmpty) }
        }
        return null
    }
}

@Serializable
data class QualityBinding(val pieceKey: String, val sourceVersion: String)

@Serializable
data class QualityDocument(
    val schemaVersion: Int = 2,
    val inspector: String = "",
    val legacyImported: Boolean = false,
    val records: List<QualityRecord> = emptyList(),
    val bindings: Map<String, QualityBinding> = emptyMap(),
    val orders: Map<String, OrderDetails> = emptyMap(),
    val reports: List<IssuedQualityReport> = emptyList(),
) {
    fun record(key: String?): QualityRecord? = records.firstOrNull { it.piece.key == key }

    fun append(key: String, event: QualityEvent): QualityDocument {
        require(event.inspector.isNotBlank()) { "Bitte den Namen der prüfenden Person angeben." }
        require(event.comment.length <= 4000) { "Der Kommentar darf höchstens 4000 Zeichen enthalten." }
        require(event.status != null || event.comment.isNotBlank() || event.photo != null || event.action != null) { "Der Eintrag ist leer." }
        require(record(key) != null) { "Die QS-Akte wurde nicht gefunden." }
        require(records.none { record -> record.events.any { it.id == event.id } }) { "Der Eintrag existiert bereits." }
        return copy(records = records.map { if (it.piece.key == key) it.copy(events = it.events + event) else it })
    }

    fun migrate(checks: Collection<AssemblyCheckRecord>, summaries: List<InstalledPackageSummary>): QualityDocument {
        if (legacyImported) return this
        val legacy = checks.map { check ->
            val summary = summaries.firstOrNull { it.revisionKey() == check.revisionKey }
            QualityRecord(
                piece = QualityPiece(
                    key = "legacy:${check.revisionKey}", revisionKey = check.revisionKey,
                    sourceKey = "", projectId = check.projectId,
                    projectName = summary?.project?.name ?: check.projectId,
                    position = check.positionNumber, assemblyGuid = check.assemblyGuid,
                    revision = check.revision, fingerprint = "", sourceLabel = "Bisheriger Prüfvermerk",
                    modelCreatedAt = summary?.createdAt.orEmpty(),
                ),
                registeredAt = check.checkedAt,
                events = listOf(QualityEvent(
                    id = "legacy:${check.revisionKey}", at = check.checkedAt, zoneId = "",
                    inspector = "Nicht erfasst (Altbestand)", status = QualityStatus.LEGACY, comment = check.comment,
                )),
            )
        }
        return copy(legacyImported = true, records = records + legacy.filter { record(it.piece.key) == null })
    }
}

internal fun qualityDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

internal fun qualityPieceKey(revisionKey: String, fingerprint: String): String =
    qualityDigest("$revisionKey\n$fingerprint".toByteArray(Charsets.UTF_8))

fun qualityDate(at: String, zoneId: String = ZoneId.systemDefault().id): String = runCatching {
    val zone = if (zoneId.isBlank()) ZoneId.systemDefault() else ZoneId.of(zoneId)
    DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm:ss z", Locale.GERMAN).withZone(zone).format(Instant.parse(at))
}.getOrDefault(at.ifBlank { "Nicht erfasst" })

/** File metadata is only a browser hint. Opening the model always verifies the complete bytes. */
fun qualitySourceVersion(model: BrowserModel): String = when (val origin = model.origin) {
    is ModelOrigin.FolderFile -> with(origin.entry) { "$documentUri:$sizeBytes:$lastModified" }
    is ModelOrigin.InstalledRevision -> with(origin.summary) { "$packageId:$createdAt" }
}

fun qualityBindingKey(projectId: String, sourceKey: String): String = "$projectId:$sourceKey"

fun qualitySourceMatches(document: QualityDocument, projectId: String, model: BrowserModel): Boolean {
    val binding = document.bindings[qualityBindingKey(projectId, model.key)] ?: return false
    return model.state != ModelState.STALE && model.state != ModelState.ERROR &&
        binding.sourceVersion == qualitySourceVersion(model) && document.record(binding.pieceKey) != null
}
