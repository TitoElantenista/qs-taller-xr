package com.qs.tallerxr.quality

/** Shared text for the PDF; every event keeps its own author and original timestamp. */
fun qualityProtocolLines(record: QualityRecord, exportedAt: String, exporter: String): List<String> = buildList {
    val piece = record.piece
    add("Qualitätssicherung · Prüfprotokoll")
    add("Projekt: ${piece.projectName}")
    add("Position / Baugruppe: ${piece.position}")
    add("Revision: ${piece.revision}")
    add("Status: ${record.status.label}")
    record.lastCheck?.let { event ->
        add("Zuletzt geprüft / geändert von: ${event.inspector}")
        add("Datum und Uhrzeit: ${qualityDate(event.at, event.zoneId)}")
        if (event.comment.isNotBlank()) add("Kommentar: ${event.comment}")
    } ?: add("Für diesen Modellstand wurde noch keine Prüfung dokumentiert.")
    add("")
    add("Identifikation des geprüften Modellstands")
    add("Quelle: ${piece.sourceLabel}")
    add("Projekt-ID: ${piece.projectId}")
    add("Baugruppen-GUID: ${piece.assemblyGuid}")
    add("Modelldatum: ${qualityDate(piece.modelCreatedAt)}")
    add("Modellkennung (SHA-256): ${piece.fingerprint.ifBlank { "Im Altbestand nicht erfasst" }}")
    add("Enthaltene Bauteile: ${piece.parts.size}")
    add("Prüfumfang: die oben genannte Position / Baugruppe mit diesem Modellstand.")
    add("")
    add("Bauteildaten und Modellattribute")
    piece.parts.forEachIndexed { index, part ->
        add("${index + 1}. Position: ${part.position} · Typ: ${part.type}")
        add("GUID: ${part.guid}")
        add("Hüllmaße im Modell (keine Messwerte): ${part.dimensions}")
        part.attributes.forEach { (label, value) -> add("$label: $value") }
        add("")
    }
    add("Prüfverlauf · ${record.events.size} Einträge")
    record.events.forEachIndexed { index, event ->
        add("${index + 1}. ${qualityDate(event.at, event.zoneId)} · ${event.inspector}")
        add(event.status?.label ?: if (event.photo != null) "AR-Foto hinzugefügt" else "Kommentar hinzugefügt")
        if (event.comment.isNotBlank()) add(event.comment)
        event.photo?.let { photo -> add("Foto: ${photo.displayName}"); add("Ausrichtung: ${photo.alignment}") }
        add("")
    }
    add("AR-Fotos: ${record.photos.size} (nachfolgender Fotoanhang)")
    add("AR-Überlagerungen dienen der visuellen Kontrolle und ersetzen keine Maßprüfung.")
    add("Exportiert: ${qualityDate(exportedAt)} · ${exporter.ifBlank { "Name nicht angegeben" }}")
    add("Die Prüfernamen sind lokale Angaben der Anwender; das Protokoll ist nicht digital signiert.")
}

/** Wraps even long GUIDs/IFC attributes; explicit blank lines and all text are retained. */
fun wrapQualityText(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
    require(maxWidth > 0)
    return text.replace("\r\n", "\n").replace('\r', '\n').split('\n').flatMap { paragraph ->
        val lines = mutableListOf<String>()
        var remaining = paragraph
        while (remaining.isNotEmpty() && measure(remaining) > maxWidth) {
            var low = 1
            var high = remaining.length
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (measure(remaining.take(middle)) <= maxWidth) low = middle else high = middle - 1
            }
            var end = low
            val space = remaining.lastIndexOf(' ', end.coerceAtMost(remaining.lastIndex))
            if (space > 0) end = space
            if (end < remaining.length && end > 1 && remaining[end - 1].isHighSurrogate()) end--
            lines += remaining.take(end)
            remaining = remaining.drop(end).trimStart(' ')
        }
        lines + remaining
    }
}
