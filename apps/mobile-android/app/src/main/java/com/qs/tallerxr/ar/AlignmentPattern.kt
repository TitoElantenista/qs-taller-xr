package com.qs.tallerxr.ar

/**
 * A straight line between two chosen landmarks, stored with the smaller label first.
 *
 * The operator draws it by dragging from one point to the next.  On the CAD it is the segment
 * between both landmarks; on a photo it is the segment between both marks.  When it follows a
 * real edge of the part, the operator can hold the drawn line against that edge while marking.
 */
data class CorrespondenceLink(val first: Int, val second: Int) {
    init {
        require(first >= 1 && first < second) { "Eine Linie verbindet zwei verschiedene Punkte." }
    }

    fun touches(label: Int): Boolean = label == first || label == second

    fun other(label: Int): Int? = when (label) {
        first -> second
        second -> first
        else -> null
    }

    val caption: String get() = "P$first–P$second"

    companion object {
        fun between(one: Int, other: Int): CorrespondenceLink =
            CorrespondenceLink(minOf(one, other), maxOf(one, other))
    }
}

/**
 * Landmarks chosen in the 3D viewer, P1..Pn in the order they were set, and the lines drawn
 * between them.  Several lines may start at the same point.
 *
 * Immutable like [AlignmentMarking]: the viewer keeps previous states for undo.
 */
data class AlignmentPattern(
    val picks: List<CorrespondencePick> = emptyList(),
    val links: List<CorrespondenceLink> = emptyList(),
) {
    init {
        require(picks.map(CorrespondencePick::label) == (1..picks.size).toList()) {
            "Die Punkte müssen fortlaufend ab P1 nummeriert sein."
        }
        require(picks.size <= ManualCorrespondenceSolver.MAXIMUM_PAIRS)
        require(links.all { it.second <= picks.size }) { "Eine Linie verweist auf einen fehlenden Punkt." }
        require(links.distinct().size == links.size)
    }

    val size: Int get() = picks.size

    val labels: List<Int> get() = picks.map(CorrespondencePick::label)

    val isFull: Boolean get() = picks.size >= ManualCorrespondenceSolver.MAXIMUM_PAIRS

    fun hasLink(one: Int, other: Int): Boolean =
        one != other && CorrespondenceLink.between(one, other) in links

    /** Points joined to [label] by a line. */
    fun linkedTo(label: Int): List<Int> = links.mapNotNull { it.other(label) }

    /** Adds the next point, P(n+1). */
    fun withPoint(modelPoint: FloatArray): AlignmentPattern {
        require(!isFull) { "Es sind bereits ${ManualCorrespondenceSolver.MAXIMUM_PAIRS} Punkte gesetzt." }
        return copy(picks = picks + CorrespondencePick(picks.size + 1, modelPoint.copyOf()))
    }

    /** Adds the next point together with the line from [from] to it. */
    fun withLinkedPoint(from: Int, modelPoint: FloatArray): AlignmentPattern {
        require(from in 1..picks.size) { "Der Ausgangspunkt der Linie fehlt." }
        val next = withPoint(modelPoint)
        return next.copy(links = links + CorrespondenceLink.between(from, next.size))
    }

    /** Draws the line between two existing points, or removes it when it already exists. */
    fun withLinkToggled(one: Int, other: Int): AlignmentPattern {
        require(one in 1..picks.size && other in 1..picks.size && one != other)
        val link = CorrespondenceLink.between(one, other)
        return copy(links = if (link in links) links - link else links + link)
    }

    /**
     * Removes a point and every line touching it.  Later points move down by one so the AR
     * marking order stays P1..Pn without gaps; their lines follow them.
     */
    fun withoutPoint(label: Int): AlignmentPattern {
        require(label in 1..picks.size)
        fun renumbered(value: Int) = if (value > label) value - 1 else value
        return AlignmentPattern(
            picks = picks.filter { it.label != label }
                .mapIndexed { index, pick -> CorrespondencePick(index + 1, pick.modelPoint) },
            links = links.filterNot { it.touches(label) }
                .map { CorrespondenceLink.between(renumbered(it.first), renumbered(it.second)) },
        )
    }

    /**
     * Operator facing summary of the step from this pattern to [next], e.g. "P3 und Linie P1–P3
     * entfernt".  [restoring] words an undo that brings something back.
     */
    fun describeChangeTo(next: AlignmentPattern, restoring: Boolean = false): String? {
        val removedLinks = links.filterNot { it in next.links }
        val addedLinks = next.links.filterNot { it in links }
        return when {
            next.size < size -> {
                // A point leaves together with its lines, in the numbering it had.
                val removed = firstDifference(picks, next.picks) + 1
                val lines = links.filter { it.touches(removed) }
                "P$removed" + (if (lines.isEmpty()) "" else " und ${linesCaption(lines)}") + " entfernt"
            }
            next.size > size -> {
                val added = firstDifference(next.picks, picks) + 1
                val lines = next.links.filter { it.touches(added) }
                "P$added" + (if (lines.isEmpty()) "" else " mit ${linesCaption(lines)}") +
                    if (restoring) " wiederhergestellt" else " gesetzt"
            }
            addedLinks.isNotEmpty() ->
                linesCaption(addedLinks) + if (restoring) " wiederhergestellt" else " gezogen"
            removedLinks.isNotEmpty() -> "${linesCaption(removedLinks)} entfernt"
            else -> null
        }
    }

    private fun linesCaption(lines: List<CorrespondenceLink>): String =
        (if (lines.size == 1) "Linie " else "Linien ") + lines.joinToString(", ") { it.caption }

    /** Index of the first pick of [longer] that [shorter] does not hold at the same place. */
    private fun firstDifference(longer: List<CorrespondencePick>, shorter: List<CorrespondencePick>): Int =
        longer.indices.firstOrNull { index ->
            shorter.getOrNull(index)?.modelPoint?.contentEquals(longer[index].modelPoint) != true
        } ?: (longer.size - 1)
}
