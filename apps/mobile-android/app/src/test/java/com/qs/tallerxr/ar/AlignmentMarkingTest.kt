package com.qs.tallerxr.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentMarkingTest {
    private fun sighting(label: Int, station: Int) = CorrespondenceSighting(
        label = label,
        station = station,
        rayOrigin = floatArrayOf(station.toFloat(), 0f, 0f),
        rayDirection = floatArrayOf(0f, 0f, -1f),
        measuredDistanceM = null,
        focalLengthPx = 1_500f,
    )

    @Test
    fun `six point rounds preserve previous marks and undo the sixth point`() {
        var marking = AlignmentMarking(labels = (1..6).toList())
        for (label in 1..6) {
            assertEquals(label, marking.currentLabel)
            marking = marking.marked(sighting(label, 1))
        }
        assertTrue(marking.stationComplete)
        marking = marking.nextStation()
        assertTrue(marking.stationSightings.isEmpty())
        assertEquals(6, marking.sightings.size)
        for (label in 1..6) marking = marking.marked(sighting(label, 2))
        assertTrue(marking.stationComplete)
        assertEquals(12, marking.sightings.size)
        val undone = marking.undone()
        assertEquals(6, undone.currentLabel)
        assertEquals(5, undone.stationSightings.size)
        assertEquals(11, undone.sightings.size)
    }

    @Test
    fun `six selected points do not make the additional marks mandatory`() {
        var marking = AlignmentMarking(labels = (1..6).toList())
            .marked(sighting(1, 1))
            .marked(sighting(2, 1))
        repeat(4) { marking = marking.skipped() }
        assertTrue(marking.stationComplete)
        assertTrue(marking.canSolve)
        assertEquals(listOf(1, 2), marking.sightings.map { it.label })
        assertEquals(6, marking.undone().currentLabel)
    }

    @Test
    fun `a round asks for the landmarks in the order chosen in 3D`() {
        var marking = AlignmentMarking(labels = listOf(1, 2, 3))
        assertEquals(1, marking.currentLabel)
        marking = marking.marked(sighting(1, 1))
        assertEquals(2, marking.currentLabel)
        assertFalse(marking.canSolve)
        marking = marking.marked(sighting(2, 1)).marked(sighting(3, 1))
        assertNull(marking.currentLabel)
        assertTrue(marking.stationComplete)
        assertTrue(marking.canSolve)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a mark for another landmark is refused`() {
        AlignmentMarking(labels = listOf(1, 2)).marked(sighting(2, 1))
    }

    @Test
    fun `a hidden landmark can be skipped and still counts for completing the round`() {
        val marking = AlignmentMarking(labels = listOf(1, 2, 3))
            .marked(sighting(1, 1))
            .skipped()
            .marked(sighting(3, 1))
        assertTrue(marking.stationComplete)
        assertEquals(listOf(1, 3), marking.sightings.map(CorrespondenceSighting::label))
    }

    @Test
    fun `the second view keeps the marks of the first`() {
        val first = AlignmentMarking(labels = listOf(1, 2))
            .marked(sighting(1, 1))
            .marked(sighting(2, 1))
        val second = first.nextStation()
        assertEquals(2, second.station)
        assertEquals(1, second.currentLabel)
        assertTrue(second.stationSightings.isEmpty())
        val marked = second.marked(sighting(1, 2))
        assertEquals(3, marked.sightings.size)
        assertEquals(2, marked.stationCount)
        assertEquals(listOf(1), marked.stationSightings.map(CorrespondenceSighting::label))
    }

    @Test
    fun `a round without marks is reused instead of numbering an empty view`() {
        val skippedOnly = AlignmentMarking(labels = listOf(1, 2)).skipped().skipped()
        assertTrue(skippedOnly.stationComplete)
        val again = skippedOnly.nextStation()
        assertEquals(1, again.station)
        assertEquals(1, again.currentLabel)
    }

    @Test
    fun `undo walks back through skips, marks and views`() {
        val marking = AlignmentMarking(labels = listOf(1, 2))
            .marked(sighting(1, 1))
            .marked(sighting(2, 1))
            .nextStation()
            .marked(sighting(1, 2))
        val emptySecond = marking.undone()
        assertEquals(2, emptySecond.station)
        assertEquals(1, emptySecond.currentLabel)
        val backInFirst = emptySecond.undone()
        assertEquals(1, backInFirst.station)
        assertEquals(2, backInFirst.currentLabel)
        assertEquals(1, backInFirst.sightings.size)
        assertFalse(backInFirst.undone().undone().canUndo)
    }
    @Test
    fun `retaking a photo removes its marks and skips but preserves earlier views`() {
        val previous = AlignmentMarking(listOf(1, 2, 3)).marked(sighting(1, 1))
            .marked(sighting(2, 1)).skipped()
        val retaken = previous.nextStation().marked(sighting(1, 2)).skipped().restartedStation()
        assertEquals(2, retaken.station)
        assertEquals(1, retaken.currentLabel)
        assertTrue(retaken.stationSightings.isEmpty())
        assertEquals(previous.sightings, retaken.sightings)
        assertEquals(2, retaken.marked(sighting(1, 2)).currentLabel)
    }

    @Test
    fun `a mark dragged out of an earlier mark carries its line and undo removes both`() {
        val marking = AlignmentMarking(labels = listOf(1, 2, 3))
            .marked(sighting(1, 1))
            .marked(sighting(2, 1))
            .marked(sighting(3, 1), linkedFrom = 1)
        assertEquals(listOf(CorrespondenceLink(1, 3)), marking.lines)
        assertEquals(listOf(CorrespondenceLink(1, 3)), marking.stationLines)
        assertEquals("P3 und Linie P1–P3 entfernt", marking.undoCaption)
        val undone = marking.undone()
        assertEquals(3, undone.currentLabel)
        assertTrue(undone.lines.isEmpty())
        assertEquals(listOf(1, 2), undone.stationSightings.map(CorrespondenceSighting::label))
    }

    @Test
    fun `several marks may be dragged out of the same mark`() {
        val marking = AlignmentMarking(labels = listOf(1, 2, 3))
            .marked(sighting(1, 1))
            .marked(sighting(2, 1), linkedFrom = 1)
            .marked(sighting(3, 1), linkedFrom = 1)
        assertEquals(listOf(CorrespondenceLink(1, 2), CorrespondenceLink(1, 3)), marking.lines)
        assertEquals("P3 und Linie P1–P3 entfernt", marking.undoCaption)
    }

    @Test
    fun `lines of the 3D pattern join marks of each round without being dragged`() {
        val first = AlignmentMarking(labels = listOf(1, 2, 3), links = listOf(CorrespondenceLink(1, 3)))
            .marked(sighting(1, 1))
        // While P2 is pending nothing is joined; once P3 is due, the marked P1 draws towards it.
        assertTrue(first.markedLinksOf(2).isEmpty())
        val third = first.marked(sighting(2, 1))
        assertEquals(listOf(1), third.markedLinksOf(3))
        assertTrue(third.stationLines.isEmpty())
        val complete = third.marked(sighting(3, 1))
        assertEquals(listOf(CorrespondenceLink(1, 3)), complete.stationLines)
        // A new round starts without marks, so without drawn lines, but keeps the pattern.
        val second = complete.nextStation()
        assertTrue(second.stationLines.isEmpty())
        assertEquals(listOf(CorrespondenceLink(1, 3)), second.lines)
        assertEquals("P3 und Linie P1–P3 entfernt", complete.undoCaption)
    }

    @Test
    fun `a line can only start at a mark of the same round`() {
        val first = AlignmentMarking(labels = listOf(1, 2)).marked(sighting(1, 1))
        val second = first.nextStation()
        // P1 is marked in view 1 only: a mark of view 2 cannot be dragged out of it.
        assertFalse(runCatching { second.marked(sighting(1, 2), linkedFrom = 1) }.isSuccess)
        assertFalse(runCatching { first.marked(sighting(2, 1), linkedFrom = 2) }.isSuccess)
        assertTrue(runCatching { first.marked(sighting(2, 1), linkedFrom = 1) }.isSuccess)
        assertFalse(runCatching { AlignmentMarking(listOf(1, 2), links = listOf(CorrespondenceLink(1, 3))) }.isSuccess)
    }

    @Test
    fun `retaking a photo removes the lines dragged in it`() {
        val marking = AlignmentMarking(labels = listOf(1, 2))
            .marked(sighting(1, 1))
            .marked(sighting(2, 1), linkedFrom = 1)
            .nextStation()
            .marked(sighting(1, 2))
            .restartedStation()
        // The line came from view 1 and stays with its mark.
        assertEquals(listOf(CorrespondenceLink(1, 2)), marking.lines)
        val retakenFirst = AlignmentMarking(labels = listOf(1, 2))
            .marked(sighting(1, 1))
            .marked(sighting(2, 1), linkedFrom = 1)
            .restartedStation()
        assertTrue(retakenFirst.lines.isEmpty())
        assertEquals("Überspringen von P2 aufgehoben", AlignmentMarking(listOf(1, 2)).marked(sighting(1, 1)).skipped().undoCaption)
        assertEquals("P1 entfernt", AlignmentMarking(listOf(1, 2)).marked(sighting(1, 1)).undoCaption)
    }

    private fun aimed(label: Int, station: Int, x: Float) = CorrespondenceSighting(
        label = label,
        station = station,
        rayOrigin = floatArrayOf(0f, 0f, 0f),
        rayDirection = floatArrayOf(x, 0f, -1f),
        measuredDistanceM = null,
        focalLengthPx = 1_500f,
    )

    @Test
    fun `a point can be marked again without undoing the marks placed after it`() {
        val four = AlignmentMarking(labels = listOf(1, 2, 3, 4))
            .marked(aimed(1, 1, 0.0f))
            .marked(aimed(2, 1, 0.1f))
            .marked(aimed(3, 1, 0.2f))
            .marked(aimed(4, 1, 0.3f))
        assertTrue(four.stationComplete)
        val picked = four.selected(2)
        assertEquals(2, picked.currentLabel)
        assertFalse(picked.stationComplete)
        assertEquals(AlignmentMarking.PointState.CURRENT, picked.stateOf(2))
        // The old mark stays until the new one is placed.
        assertEquals(0.1f, picked.stationSightings.first { it.label == 2 }.rayDirection[0], 0f)
        val corrected = picked.marked(aimed(2, 1, 0.15f))
        assertTrue(corrected.stationComplete)
        assertNull(corrected.selectedLabel)
        assertEquals(listOf(1, 2, 3, 4), corrected.stationSightings.map { it.label }.sorted())
        assertEquals(0.15f, corrected.stationSightings.first { it.label == 2 }.rayDirection[0], 0f)
        assertEquals(4, corrected.sightings.size)
        // Undo brings the previous P2 back and leaves P3 and P4 alone.
        assertEquals("neue Position von P2 verworfen, die vorherige gilt wieder", corrected.undoCaption)
        val undone = corrected.undone()
        assertEquals(0.1f, undone.stationSightings.first { it.label == 2 }.rayDirection[0], 0f)
        assertEquals(4, undone.stationSightings.size)
        assertTrue(undone.stationComplete)
    }

    @Test
    fun `points can be marked out of order and the round continues with the first open one`() {
        val marking = AlignmentMarking(labels = listOf(1, 2, 3, 4)).marked(aimed(1, 1, 0f))
        val third = marking.selected(3).marked(aimed(3, 1, 0.2f))
        assertEquals(2, third.currentLabel)
        assertEquals(AlignmentMarking.PointState.MARKED, third.stateOf(3))
        assertEquals(AlignmentMarking.PointState.PENDING, third.stateOf(4))
        val second = third.marked(aimed(2, 1, 0.1f))
        assertEquals(4, second.currentLabel)
        // Deselecting returns to the order without changing marks.
        assertEquals(4, second.selected(1).selected(null).currentLabel)
    }

    @Test
    fun `skipping a picked point sets its mark aside and undo restores it`() {
        val marking = AlignmentMarking(labels = listOf(1, 2, 3))
            .marked(aimed(1, 1, 0f))
            .marked(aimed(2, 1, 0.1f))
            .marked(aimed(3, 1, 0.2f))
        val hidden = marking.selected(2).skipped()
        assertEquals(listOf(1, 3), hidden.stationSightings.map { it.label })
        assertEquals(AlignmentMarking.PointState.SKIPPED, hidden.stateOf(2))
        assertTrue(hidden.stationComplete)
        assertEquals("P2 wieder markiert", hidden.undoCaption)
        assertEquals(listOf(1, 2, 3), hidden.undone().stationSightings.map { it.label })
        // A point skipped first and marked later is skipped again by undo.
        val late = AlignmentMarking(labels = listOf(1, 2)).skipped().marked(aimed(2, 1, 0.1f))
        val remarked = late.selected(1).marked(aimed(1, 1, 0f))
        assertEquals("P1 wieder übersprungen", remarked.undoCaption)
        assertEquals(AlignmentMarking.PointState.SKIPPED, remarked.undone().stateOf(1))
    }

    @Test
    fun `a point marked again keeps its lines and cannot be dragged out of itself`() {
        val marking = AlignmentMarking(labels = listOf(1, 2, 3))
            .marked(aimed(1, 1, 0f))
            .marked(aimed(2, 1, 0.1f))
            .marked(aimed(3, 1, 0.2f), linkedFrom = 1)
        val again = marking.selected(3)
        // While P3 is placed again, P1 draws towards the finger along the kept line.
        assertEquals(listOf(1), again.markedLinksOf(3))
        assertFalse(runCatching { again.marked(aimed(3, 1, 0.25f), linkedFrom = 3) }.isSuccess)
        val moved = again.marked(aimed(3, 1, 0.25f))
        assertEquals(listOf(CorrespondenceLink(1, 3)), moved.stationLines)
        // A second line dragged while placing it again adds to the first one.
        val joined = moved.selected(3).marked(aimed(3, 1, 0.22f), linkedFrom = 2)
        assertEquals(listOf(CorrespondenceLink(1, 3), CorrespondenceLink(2, 3)), joined.stationLines)
        assertEquals(listOf(CorrespondenceLink(1, 3)), joined.undone().stationLines)
    }

    @Test
    fun `undo, a new view and a retake all return to the marking order`() {
        val marking = AlignmentMarking(labels = listOf(1, 2, 3)).marked(aimed(1, 1, 0f)).selected(3)
        assertNull(marking.undone().selectedLabel)
        assertNull(marking.nextStation().selectedLabel)
        assertNull(marking.restartedStation().selectedLabel)
        assertFalse(runCatching { marking.selected(7) }.isSuccess)
    }
}
