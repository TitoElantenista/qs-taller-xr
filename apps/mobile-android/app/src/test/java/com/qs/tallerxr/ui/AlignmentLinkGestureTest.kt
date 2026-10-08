package com.qs.tallerxr.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentLinkGestureTest {
    private fun gesture() = LinkHoldGesture(holdMillis = 500L, slopPx = 20f)

    @Test
    fun `a quick tap on a point stays the tap that removes it`() {
        val gesture = gesture()
        gesture.down(Offset(100f, 100f), 1_000L, origin = 2)
        assertEquals(LinkTouchResult.CONSUME, gesture.move(Offset(104f, 101f)))
        assertFalse(gesture.activate(1_300L))
        // Released before the hold: no line, the viewer handles the tap as before.
        assertNull(gesture.up(Offset(104f, 101f)))
        assertEquals(LinkHoldGesture.State.IDLE, gesture.state)
    }

    @Test
    fun `holding still on a point starts a line that follows the finger`() {
        val gesture = gesture()
        gesture.down(Offset(100f, 100f), 1_000L, origin = 1)
        assertEquals(1L, gesture.millisUntilActive(1_499L))
        assertFalse(gesture.activate(1_499L))
        assertTrue(gesture.activate(1_500L))
        // The timer fires once; a late second call does nothing.
        assertFalse(gesture.activate(1_600L))
        assertEquals(1, gesture.origin)
        assertEquals(LinkTouchResult.CONSUME, gesture.move(Offset(400f, 250f)))
        assertEquals(Offset(400f, 250f), gesture.position)
        assertEquals(Offset(420f, 260f), gesture.up(Offset(420f, 260f)))
        assertEquals(LinkHoldGesture.State.IDLE, gesture.state)
    }

    @Test
    fun `an orbit that starts on a point is left to the camera`() {
        val gesture = gesture()
        gesture.down(Offset(100f, 100f), 0L, origin = 1)
        assertEquals(LinkTouchResult.PASS, gesture.move(Offset(160f, 100f)))
        assertEquals(LinkHoldGesture.State.IDLE, gesture.state)
        // Holding still after the orbit has begun never turns it into a line.
        assertFalse(gesture.activate(900L))
        assertEquals(LinkTouchResult.PASS, gesture.move(Offset(161f, 100f)))
        assertNull(gesture.up(Offset(161f, 100f)))
    }

    @Test
    fun `touches away from the points and cancellations never draw a line`() {
        val elsewhere = gesture()
        elsewhere.down(Offset(10f, 10f), 0L, origin = null)
        assertFalse(elsewhere.activate(1_000L))
        assertEquals(LinkTouchResult.PASS, elsewhere.move(Offset(12f, 10f)))
        val cancelled = gesture()
        cancelled.down(Offset(10f, 10f), 0L, origin = 3)
        assertTrue(cancelled.activate(500L))
        cancelled.cancel()
        assertNull(cancelled.up(Offset(300f, 300f)))
    }

    @Test
    fun `an existing point wins over a free corner and occupied corners are skipped`() {
        val points = listOf(AlignmentCandidate(0, 100f, 100f, 2f), AlignmentCandidate(1, 300f, 100f, 2f))
        val corners = listOf(
            AlignmentCandidate(10, 302f, 101f, 2f), // under P2
            AlignmentCandidate(11, 100f, 300f, 2f),
            AlignmentCandidate(12, 101f, 102f, 2f), // under P1, the origin
        )
        assertEquals(LinkTarget.Point(2, 300f, 100f), selectLinkTarget(1, points, corners, setOf(10, 12), 305f, 104f, 60f))
        assertEquals(LinkTarget.Corner(11, 100f, 300f), selectLinkTarget(1, points, corners, setOf(10, 12), 98f, 290f, 60f))
        // Back on the start point: neither itself nor the corner below it.
        assertNull(selectLinkTarget(1, points, corners, setOf(10, 12), 100f, 100f, 60f))
        assertNull(selectLinkTarget(1, points, corners, setOf(10, 12), 700f, 700f, 60f))
    }

    @Test
    fun `a press grabs the nearest mark by its ring or by its number`() {
        val marks = listOf(
            MarkHandle(1, Offset(100f, 100f), Offset(160f, 55f)),
            MarkHandle(2, Offset(130f, 100f), Offset(190f, 55f)),
        )
        assertEquals(2, markUnderFinger(marks, Offset(122f, 101f), ringRadiusPx = 72f, bubbleRadiusPx = 42f)?.label)
        assertEquals(1, markUnderFinger(marks, Offset(162f, 57f), ringRadiusPx = 20f, bubbleRadiusPx = 12f)?.label)
        assertNull(markUnderFinger(marks, Offset(400f, 400f), ringRadiusPx = 72f, bubbleRadiusPx = 42f))
    }

    @Test
    fun `a line needs the finger to leave the start mark`() {
        val start = MarkHandle(1, Offset(100f, 100f), null)
        assertNull(linkedMarkOrigin(start, Offset(110f, 104f), startRadiusPx = 72f))
        assertEquals(1, linkedMarkOrigin(start, Offset(400f, 90f), startRadiusPx = 72f))
        assertNull(linkedMarkOrigin(null, Offset(400f, 90f), startRadiusPx = 72f))
    }

    @Test
    fun `the lens repeats overlays around the cursor at the zoom factor`() {
        val centre = Offset(216f, 162f)
        assertEquals(centre, lensPoint(Offset(500f, 700f), Offset(500f, 700f), centre, 3f))
        assertEquals(Offset(246f, 132f), lensPoint(Offset(510f, 690f), Offset(500f, 700f), centre, 3f))
        // At an image edge the crosshair is off centre; the mapping follows it.
        assertEquals(Offset(30f, 162f), lensPoint(Offset(0f, 700f), Offset(0f, 700f), Offset(30f, 162f), 5f))
    }

    @Test
    fun `lines stop short of rings and vanish when nothing is left`() {
        val (start, end) = requireNotNull(trimmedSegment(Offset(0f, 0f), Offset(100f, 0f), 10f, 20f))
        assertEquals(Offset(10f, 0f), start)
        assertEquals(Offset(80f, 0f), end)
        assertNull(trimmedSegment(Offset(0f, 0f), Offset(25f, 0f), 10f, 20f))
    }
}
