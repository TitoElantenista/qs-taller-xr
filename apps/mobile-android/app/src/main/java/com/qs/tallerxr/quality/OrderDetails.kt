package com.qs.tallerxr.quality

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlinx.serialization.Serializable

/** Project metadata deliberately lives outside the identity/hash of the inspected geometry. */
@Serializable
data class OrderDetails(
    val projectId: String,
    val projectName: String,
    val orderNumber: String = "",
    val projectNumber: String = "",
    val client: String = "",
    val projectResponsible: String = "",
    val qualityResponsible: String = "",
    val site: String = "",
    val inspectionLocation: String = "",
    val notes: String = "",
    val updatedAt: String = "",
) {
    fun normalized(): OrderDetails {
        require(projectId.isNotBlank()) { "Das Projekt fehlt." }
        require(projectName.trim().isNotEmpty()) { "Bitte einen Projektnamen angeben." }
        val fields = listOf(projectName, orderNumber, projectNumber, client, projectResponsible,
            qualityResponsible, site, inspectionLocation)
        require(fields.all { it.trim().length <= 200 }) { "Ein Auftragsfeld darf höchstens 200 Zeichen enthalten." }
        require(notes.trim().length <= 4000) { "Die Bemerkung darf höchstens 4000 Zeichen enthalten." }
        return copy(projectName = projectName.trim(), orderNumber = orderNumber.trim(), projectNumber = projectNumber.trim(),
            client = client.trim(), projectResponsible = projectResponsible.trim(), qualityResponsible = qualityResponsible.trim(),
            site = site.trim(), inspectionLocation = inspectionLocation.trim(), notes = notes.trim())
    }
}

/** Empty values explicitly clear a previous next step; null on an event leaves it unchanged. */
@Serializable
data class QualityAction(val description: String = "", val assignee: String = "", val dueDate: String = "") {
    val isEmpty: Boolean get() = description.isBlank() && assignee.isBlank() && dueDate.isBlank()

    fun normalized(): QualityAction {
        require(description.trim().length <= 2000) { "Die Maßnahme darf höchstens 2000 Zeichen enthalten." }
        require(assignee.trim().length <= 120) { "Der Name darf höchstens 120 Zeichen enthalten." }
        val date = dueDate.trim()
        if (date.isNotEmpty()) require(runCatching { LocalDate.parse(date) }.isSuccess) { "Bitte einen gültigen Termin angeben." }
        return copy(description = description.trim(), assignee = assignee.trim(), dueDate = date)
    }
}

fun qualityActionDate(isoDate: String): String = runCatching {
    LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.GERMAN))
}.getOrDefault(isoDate)

/** Blank stays blank; a strict parser must never silently adjust e.g. 31 February. */
fun parseQualityActionDate(text: String): String {
    if (text.isBlank()) return ""
    return try {
        LocalDate.parse(text.trim(), DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.GERMAN)
            .withResolverStyle(ResolverStyle.STRICT)).toString()
    } catch (_: Exception) { throw IllegalArgumentException("Bitte ein gültiges Datum im Format TT.MM.JJJJ angeben.") }
}

@Serializable
data class IssuedQualityReport(
    val id: String,
    val number: String,
    val edition: Int,
    val createdAt: String,
    val zoneId: String,
    val exporter: String,
    val order: OrderDetails,
    val record: QualityRecord,
    val pdfFileName: String,
    val sha256: String = "",
) {
    val suggestedFileName: String get() = "${number}-Stand-${edition.toString().padStart(2, '0')}.pdf"
}
