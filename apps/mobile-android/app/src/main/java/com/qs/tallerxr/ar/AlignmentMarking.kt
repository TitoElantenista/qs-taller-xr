package com.qs.tallerxr.ar

/**
 * Log of the marks an operator makes in the AR camera view.
 *
 * Marking runs in rounds.  One round is one standing position (station): the operator marks the
 * chosen landmarks P1..Pn in order and may skip the ones hidden from there.  A second round from a
 * few steps to the side is what gives the alignment its depth, so the log keeps every round and
 * the solver receives all of them.
 *
 * The operator may also pick any point of the round to mark next ([selectedLabel]): out of order,
 * or again to correct it.  A later mark or skip of the same point and round replaces the earlier
 * one without deleting it, so undo brings the previous mark back exactly, and the marks placed
 * after it stay untouched.
 *
 * Lines come from the 3D pattern ([links]) and from marks the operator dragged out of an earlier
 * mark of the same round.  A dragged line belongs to its mark, so undoing the mark removes both.
 * Placing a point again keeps the lines it already had.
 *
 * The log is immutable; every operation returns the next state, which keeps undo trivial and the
 * behaviour testable without an AR session.
 */
data class AlignmentMarking(
    /** Landmark labels in marking order, as chosen in the 3D viewer. */
    val labels: List<Int>,
    val station: Int = 1,
    private val actions: List<Action> = emptyList(),
    /** Lines drawn between the landmarks in the 3D viewer. */
    val links: List<CorrespondenceLink> = emptyList(),
    /** A point the operator picked to mark next, out of order or again. */
    val selectedLabel: Int? = null,
) {
    init {
        require(labels.isNotEmpty() && labels.distinct().size == labels.size)
        require(station >= 1)
        require(links.all { it.first in labels && it.second in labels })
        require(selectedLabel == null || selectedLabel in labels)
    }

    sealed interface Action {
        val label: Int
        val station: Int
    }

    /** [linkedFrom] is the mark of this round the operator dragged the new mark out of. */
    data class Mark(val sighting: CorrespondenceSighting, val linkedFrom: Int? = null) : Action {
        override val label: Int get() = sighting.label
        override val station: Int get() = sighting.station
    }

    data class Skip(override val label: Int, override val station: Int) : Action

    /** Where a point stands in the round in progress; drives the progress chips. */
    enum class PointState { CURRENT, MARKED, SKIPPED, PENDING }

    /** The action in force for every point and round, in the order they were taken. */
    private val effective: List<Action> by lazy {
        val last = HashMap<Pair<Int, Int>, Int>()
        actions.forEachIndexed { index, action -> last[action.label to action.station] = index }
        actions.filterIndexed { index, action -> last[action.label to action.station] == index }
    }

    val sightings: List<CorrespondenceSighting>
        get() = effective.filterIsInstance<Mark>().map(Mark::sighting)

    /** Marks of the round in progress; the AR view draws these while the operator stands still. */
    val stationSightings: List<CorrespondenceSighting>
        get() = sightings.filter { it.station == station }

    /**
     * Every line of the alignment: the 3D pattern plus the lines dragged between marks.  A mark
     * replaced by a later one keeps contributing its line, so correcting a point keeps its lines.
     */
    val lines: List<CorrespondenceLink>
        get() = (links + actions.filterIsInstance<Mark>().mapNotNull { mark ->
            mark.linkedFrom?.let { CorrespondenceLink.between(it, mark.label) }
        }).distinct()

    /** Lines whose two ends are marked in the round in progress. */
    val stationLines: List<CorrespondenceLink>
        get() {
            val marked = stationSightings.map(CorrespondenceSighting::label).toSet()
            return lines.filter { it.first in marked && it.second in marked }
        }

    /** Marks of this round joined by a line to [label]; drawn towards the finger while marking it. */
    fun markedLinksOf(label: Int): List<Int> {
        val marked = stationSightings.map(CorrespondenceSighting::label).toSet()
        return lines.mapNotNull { it.other(label) }.filter { it in marked }
    }

    /** Next landmark to mark from this station, or null once the round is complete. */
    val currentLabel: Int?
        get() {
            selectedLabel?.let { return it }
            val handled = effective.filter { it.station == station }.map(Action::label)
            return labels.firstOrNull { it !in handled }
        }

    fun stateOf(label: Int): PointState {
        if (label == currentLabel) return PointState.CURRENT
        return when (effective.lastOrNull { it.station == station && it.label == label }) {
            is Mark -> PointState.MARKED
            is Skip -> PointState.SKIPPED
            null -> PointState.PENDING
        }
    }

    /** True when [label] already has a mark in this round that a new one would replace. */
    fun isMarkedInStation(label: Int): Boolean = stationSightings.any { it.label == label }

    val stationComplete: Boolean get() = currentLabel == null

    val markedLabelCount: Int get() = sightings.map(CorrespondenceSighting::label).distinct().size

    val canSolve: Boolean get() = markedLabelCount >= ManualCorrespondenceSolver.MINIMUM_PAIRS

    val canUndo: Boolean get() = actions.isNotEmpty()

    /** Rounds that contributed at least one mark. */
    val stationCount: Int get() = sightings.map(CorrespondenceSighting::station).distinct().size

    /** Picks [label] to be marked next; null returns to the P1..Pn order. */
    fun selected(label: Int?): AlignmentMarking {
        require(label == null || label in labels)
        return copy(selectedLabel = label)
    }

    fun marked(sighting: CorrespondenceSighting, linkedFrom: Int? = null): AlignmentMarking {
        require(sighting.label == currentLabel) { "Die Markierung gehört nicht zum erwarteten Punkt." }
        require(sighting.station == station) { "Die Markierung gehört zu einer anderen Ansicht." }
        require(
            linkedFrom == null ||
                (linkedFrom != sighting.label && stationSightings.any { it.label == linkedFrom }),
        ) { "Eine Linie beginnt an einer anderen Markierung derselben Ansicht." }
        return copy(actions = actions + Mark(sighting, linkedFrom), selectedLabel = null)
    }

    /** The pending landmark is not visible from this station; a mark it had is set aside. */
    fun skipped(): AlignmentMarking {
        val label = currentLabel ?: return this
        return copy(actions = actions + Skip(label, station), selectedLabel = null)
    }

    /** Retaking a photo invalidates only this round, never mixes two photos under one station. */
    fun restartedStation(): AlignmentMarking =
        copy(actions = actions.filter { it.station < station }, selectedLabel = null)

    /** Starts the next round.  A round without a single mark is simply reused. */
    fun nextStation(): AlignmentMarking =
        if (stationSightings.isEmpty()) {
            copy(actions = actions.filterNot { it.station == station }, selectedLabel = null)
        } else {
            copy(station = station + 1, selectedLabel = null)
        }

    /**
     * Removes the last mark (with the line dragged to it) or skip, stepping back a round when
     * needed.  When it had replaced an earlier mark or skip of the same point, that one is back.
     */
    fun undone(): AlignmentMarking {
        val last = actions.lastOrNull() ?: return this
        return copy(station = last.station, actions = actions.dropLast(1), selectedLabel = null)
    }

    /** What [undone] does, including the lines that end at that mark on the photo. */
    val undoCaption: String?
        get() {
            val last = actions.lastOrNull() ?: return null
            val earlier = actions.dropLast(1).lastOrNull { it.label == last.label && it.station == last.station }
            return when {
                last is Mark && earlier is Mark -> "neue Position von P${last.label} verworfen, die vorherige gilt wieder"
                last is Mark && earlier is Skip -> "P${last.label} wieder übersprungen"
                last is Mark -> {
                    val marked = sightings.filter { it.station == last.station }.map(CorrespondenceSighting::label)
                    val ending = lines.filter { it.touches(last.label) && it.other(last.label) in marked }
                    "P${last.label}" + (if (ending.isEmpty()) "" else
                        " und ${if (ending.size == 1) "Linie" else "Linien"} ${ending.joinToString(", ") { it.caption }}") +
                        " entfernt"
                }
                earlier is Mark -> "P${last.label} wieder markiert"
                else -> "Überspringen von P${last.label} aufgehoben"
            }
        }
}
