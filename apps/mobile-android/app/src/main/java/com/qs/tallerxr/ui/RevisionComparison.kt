package com.qs.tallerxr.ui

import com.qs.tallerxr.data.InstalledPackage
import com.qs.tallerxr.data.Part
import kotlin.math.abs

internal enum class PartChange { ADDED, MODIFIED, UNCHANGED }

internal data class RevisionComparison(
    val currentChanges: Map<String, PartChange>,
    val added: List<Part>,
    val modified: List<Part>,
    val removed: List<Part>,
    val unchangedCount: Int,
)

internal fun compareRevisions(
    current: InstalledPackage,
    previous: InstalledPackage,
): RevisionComparison {
    val oldParts = previous.parts.parts
    val oldByGuid = oldParts.associateBy(Part::guid)
    val uniqueOldByPosition = oldParts
        .filter { it.positionNumber.isNotBlank() }
        .groupBy { it.positionNumber.trim() }
        .filterValues { it.size == 1 }
        .mapValues { it.value.single() }
    val matchedOld = mutableSetOf<String>()
    val changes = linkedMapOf<String, PartChange>()
    val added = mutableListOf<Part>()
    val modified = mutableListOf<Part>()

    current.parts.parts.forEach { part ->
        val previousPart = oldByGuid[part.guid]
            ?: uniqueOldByPosition[part.positionNumber.trim()]?.takeIf { it.guid !in matchedOld }
        when {
            previousPart == null -> {
                changes[part.guid] = PartChange.ADDED
                added += part
            }
            !part.isSemanticallyEquivalentTo(previousPart) -> {
                matchedOld += previousPart.guid
                changes[part.guid] = PartChange.MODIFIED
                modified += part
            }
            else -> {
                matchedOld += previousPart.guid
                changes[part.guid] = PartChange.UNCHANGED
            }
        }
    }
    val removed = oldParts.filter { it.guid !in matchedOld }
    return RevisionComparison(
        currentChanges = changes,
        added = added,
        modified = modified,
        removed = removed,
        unchangedCount = changes.values.count { it == PartChange.UNCHANGED },
    )
}

private fun Part.isSemanticallyEquivalentTo(other: Part): Boolean =
    positionNumber == other.positionNumber &&
        kind == other.kind &&
        inspectable == other.inspectable &&
        properties == other.properties &&
        boundsAssemblyM.min.closeTo(other.boundsAssemblyM.min) &&
        boundsAssemblyM.max.closeTo(other.boundsAssemblyM.max)

private fun List<Float>.closeTo(other: List<Float>): Boolean =
    size == other.size && indices.all { abs(this[it] - other[it]) <= 0.0001f }
