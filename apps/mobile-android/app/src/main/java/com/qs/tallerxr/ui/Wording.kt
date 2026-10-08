package com.qs.tallerxr.ui

/** «1 Modell», «12 Modelle»: a count with the right German form. */
internal fun counted(count: Int, singular: String, plural: String): String =
    if (count == 1) "1 $singular" else "$count $plural"

/**
 * Workshop names for the attribute keys Tekla and other IFC exporters write, in the order a
 * fitter looks for them.  Unknown keys keep their own name and follow alphabetically.
 */
private val ATTRIBUTE_LABELS = linkedMapOf(
    "profile" to "Profil",
    "material" to "Werkstoff",
    "name" to "Benennung",
    "description" to "Beschreibung",
    "assembly position number" to "Baugruppe",
    "part position number" to "Teil",
    "phase" to "Phase",
    "lot number" to "Los",
    "weight" to "Gewicht",
    "length" to "Länge",
    "finish" to "Oberfläche",
    "class" to "Klasse",
    "comment" to "Kommentar",
    "ifcclass" to "IFC-Klasse",
)

internal fun attributeLabel(key: String): String = ATTRIBUTE_LABELS[key.trim().lowercase()] ?: key.trim()

/** Attributes as label/value pairs in display order. */
internal fun labelledAttributes(attributes: Map<String, String>): List<Pair<String, String>> =
    attributes.entries
        .sortedWith(compareBy({ ATTRIBUTE_LABELS.keys.indexOf(it.key.trim().lowercase()).let { index -> if (index < 0) ATTRIBUTE_LABELS.size else index } },
            { it.key.lowercase() }))
        .map { attributeLabel(it.key) to it.value }

/** The value of [key] (case-insensitive) when present and not blank. */
internal fun attributeValue(attributes: Map<String, String>, key: String): String? =
    attributes.entries.firstOrNull { it.key.trim().equals(key, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }

/** «HEA220 · S355J2 · 6340 × 210 × 220 mm»: what a fitter reads first about a part. */
internal fun partSummary(attributes: Map<String, String>, dimensions: String?): String? =
    listOfNotNull(attributeValue(attributes, "profile"), attributeValue(attributes, "material"), dimensions?.takeIf { it.isNotBlank() })
        .takeIf { it.isNotEmpty() }?.joinToString(" · ")
