package com.qs.tallerxr.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentPatternTest {
    private fun point(x: Float) = floatArrayOf(x, 0f, 0f)

    @Test
    fun `a point dragged out of P1 becomes the next label and is joined to P1`() {
        // The operator's example: P1 and P2 tapped, P3 pulled out of P1.
        val pattern = AlignmentPattern()
            .withPoint(point(0f))
            .withPoint(point(1f))
            .withLinkedPoint(from = 1, modelPoint = point(2f))
        assertEquals(listOf(1, 2, 3), pattern.labels)
        assertEquals(listOf(CorrespondenceLink(1, 3)), pattern.links)
        assertEquals(2f, pattern.picks[2].modelPoint[0], 0f)
        assertEquals(listOf(3), pattern.linkedTo(1))
        assertTrue(pattern.hasLink(3, 1))
    }

    @Test
    fun `several lines may start at the same point`() {
        val pattern = AlignmentPattern()
            .withPoint(point(0f))
            .withLinkedPoint(1, point(1f))
            .withLinkedPoint(1, point(2f))
            .withLinkedPoint(1, point(3f))
        assertEquals(listOf(2, 3, 4), pattern.linkedTo(1))
        assertEquals(3, pattern.links.size)
    }

    @Test
    fun `dragging between two existing points toggles their line`() {
        val base = AlignmentPattern().withPoint(point(0f)).withPoint(point(1f)).withPoint(point(2f))
        val joined = base.withLinkToggled(3, 2)
        assertEquals(listOf(CorrespondenceLink(2, 3)), joined.links)
        assertEquals(3, joined.size)
        assertTrue(joined.withLinkToggled(2, 3).links.isEmpty())
    }

    @Test
    fun `removing a point drops its lines and renumbers the lines of later points`() {
        val pattern = AlignmentPattern()
            .withPoint(point(0f))
            .withLinkedPoint(1, point(1f)) // P2, line P1-P2
            .withLinkedPoint(2, point(2f)) // P3, line P2-P3
            .withLinkedPoint(1, point(3f)) // P4, line P1-P4
        val withoutSecond = pattern.withoutPoint(2)
        assertEquals(listOf(1, 2, 3), withoutSecond.labels)
        // P3 and P4 moved down; only P1-P4 survives and is now P1-P3.
        assertEquals(listOf(CorrespondenceLink(1, 3)), withoutSecond.links)
        assertEquals(3f, withoutSecond.picks[2].modelPoint[0], 0f)
        val withoutFirst = pattern.withoutPoint(1)
        assertEquals(listOf(CorrespondenceLink(1, 2)), withoutFirst.links)
    }

    @Test
    fun `the operator reads what a step or its undo changed`() {
        val two = AlignmentPattern().withPoint(point(0f)).withPoint(point(1f))
        val three = two.withLinkedPoint(1, point(2f))
        assertEquals("P3 mit Linie P1–P3 gesetzt", two.describeChangeTo(three))
        // «Rückgängig» after dragging P3 out of P1 takes the point and its line away.
        assertEquals("P3 und Linie P1–P3 entfernt", three.describeChangeTo(two))
        val removed = three.withoutPoint(1)
        assertEquals("P1 und Linie P1–P3 entfernt", three.describeChangeTo(removed))
        assertEquals("P1 mit Linie P1–P3 wiederhergestellt", removed.describeChangeTo(three, restoring = true))
        val joined = three.withLinkToggled(2, 3)
        assertEquals("Linie P2–P3 gezogen", three.describeChangeTo(joined))
        assertEquals("Linie P2–P3 entfernt", joined.describeChangeTo(three))
        assertEquals("P2 gesetzt", AlignmentPattern().withPoint(point(0f)).describeChangeTo(two))
    }

    @Test
    fun `the pattern stops at the solver maximum and refuses broken references`() {
        var pattern = AlignmentPattern()
        repeat(ManualCorrespondenceSolver.MAXIMUM_PAIRS) { pattern = pattern.withPoint(point(it.toFloat())) }
        assertTrue(pattern.isFull)
        assertFalse(runCatching { pattern.withPoint(point(9f)) }.isSuccess)
        assertFalse(runCatching { pattern.withLinkedPoint(1, point(9f)) }.isSuccess)
        // Lines between existing points remain possible when the pattern is full.
        assertEquals(1, pattern.withLinkToggled(1, 6).links.size)
        assertFalse(runCatching { AlignmentPattern().withLinkedPoint(1, point(0f)) }.isSuccess)
        assertFalse(runCatching { CorrespondenceLink(2, 2) }.isSuccess)
        assertEquals(CorrespondenceLink(2, 5), CorrespondenceLink.between(5, 2))
    }
}
