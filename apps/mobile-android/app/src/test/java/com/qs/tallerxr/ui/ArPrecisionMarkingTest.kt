package com.qs.tallerxr.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArPrecisionMarkingTest {
    @Test
    fun shortTapCreatesOnePointOnReleaseWithoutZoom() {
        val gesture = PrecisionMarkGesture(Offset(100f, 200f), 1_000L)
        gesture.advanceTime(1_499L)
        assertFalse(gesture.precise)
        assertTrue(gesture.pressed)
        assertEquals(Offset(100f, 200f), gesture.release(Offset(100f, 200f), 1_499L))
        assertNull(gesture.release(Offset(300f, 400f), 1_600L))
        gesture.advanceTime(2_000L)
        assertFalse(gesture.precise)
    }

    @Test
    fun stationaryHoldActivatesAt500msWithoutMovingOrCreatingAPoint() {
        val start = Offset(100f, 200f)
        val gesture = PrecisionMarkGesture(start, 1_000L)
        gesture.advanceTime(1_499L)
        assertFalse(gesture.precise)
        assertEquals(1L, gesture.millisUntilPrecision(1_499L))
        gesture.advanceTime(1_500L)
        assertTrue(gesture.precise)
        assertTrue(gesture.pressed)
        assertEquals(start, gesture.position)
        assertEquals(start, gesture.release(start, 2_000L))
    }

    @Test
    fun fastDragDoesNotBypassTheHoldThreshold() {
        val gesture = PrecisionMarkGesture(Offset(100f, 200f), 0L)
        gesture.move(Offset(500f, 800f), 100L)
        assertFalse(gesture.precise)
        assertEquals(Offset(505f, 805f), gesture.release(Offset(505f, 805f), 200L))
    }

    @Test
    fun preciseReleaseUsesTheLastFingerPositionWithoutAnOffsetOrScaleChange() {
        val gesture = PrecisionMarkGesture(Offset(100f, 200f), 0L)
        gesture.advanceTime(500L)
        gesture.move(Offset(105.25f, 190.5f), 800L)
        assertEquals(Offset(105.5f, 190.75f), gesture.release(Offset(105.5f, 190.75f), 900L))
    }

    @Test
    fun cancellationBeforeOrAfterTheHoldNeverCommitsAPoint() {
        for (elapsed in listOf(100L, 500L, 2_000L)) {
            val gesture = PrecisionMarkGesture(Offset(100f, 200f), 0L)
            gesture.advanceTime(elapsed)
            gesture.cancel()
            gesture.advanceTime(3_000L)
            assertFalse(gesture.pressed)
            assertNull(gesture.release(Offset(100f, 200f), 3_100L))
        }
    }

    @Test
    fun selectedZoomChangesTheCapturedAreaWithoutChangingTheLensSize() {
        val lens = IntSize(300, 180)
        assertEquals(IntSize(150, 90), MagnifierZoom.TWO.sampleSize(lens))
        assertEquals(IntSize(100, 60), MagnifierZoom.THREE.sampleSize(lens))
        assertEquals(IntSize(60, 36), MagnifierZoom.FIVE.sampleSize(lens))
        // A camera buffer at twice the view resolution needs twice as many source pixels.
        assertEquals(IntSize(120, 72), MagnifierZoom.FIVE.sampleSize(lens, Offset(2f, 2f)))
    }

    @Test
    fun magnifierCrosshairCorrespondsToTheSelectedPixelEvenAtImageEdges() {
        for (zoom in MagnifierZoom.entries) {
            for (point in listOf(Offset(0f, 0f), Offset(1079f, 1919f), Offset(0f, 1919f),
                Offset(1079f, 0f), Offset(500.25f, 700.75f))) {
                val sample = magnifierSample(point, IntSize(1080, 1920), zoom.sampleSize(IntSize(432, 324)))
                assertTrue(sample.source.left >= 0 && sample.source.right <= 1080)
                assertTrue(sample.source.top >= 0 && sample.source.bottom <= 1920)
                assertEquals(point.x, sample.source.left + sample.targetFraction.x * sample.source.width, 0.001f)
                assertEquals(point.y, sample.source.top + sample.targetFraction.y * sample.source.height, 0.001f)
            }
        }
        val tiny = magnifierSample(Offset(10f, 8f), IntSize(20, 15), IntSize(144, 108))
        assertEquals(20, tiny.source.width)
        assertEquals(15, tiny.source.height)
    }

    @Test
    fun lensStaysWithinPortraitAndLandscapeViewportsAndAwayFromTheFinger() {
        val lens = IntSize(144, 148)
        for (viewport in listOf(IntSize(360, 720), IntSize(720, 280))) {
            for (point in listOf(Offset(5f, 5f), Offset(viewport.width - 5f, viewport.height - 5f),
                Offset(viewport.width / 2f, viewport.height / 2f))) {
                val window = magnifierWindowPosition(point, viewport, lens, gap = 44, margin = 8)
                assertTrue(window.x >= 8 && window.x + lens.width <= viewport.width - 8)
                assertTrue(window.y >= 8 && window.y + lens.height <= viewport.height - 8)
                assertFalse(point.x >= window.x && point.x < window.x + lens.width &&
                    point.y >= window.y && point.y < window.y + lens.height)
            }
        }
    }
}
