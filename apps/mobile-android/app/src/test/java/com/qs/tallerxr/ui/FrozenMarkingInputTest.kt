package com.qs.tallerxr.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Pose
import com.qs.tallerxr.ar.CapturedAlignmentView
import com.qs.tallerxr.ar.CapturedImageFit
import com.qs.tallerxr.ar.CorrespondenceSighting
import com.qs.tallerxr.ar.WorldRay
import org.junit.Assert.*
import org.junit.Test

class FrozenMarkingInputTest {
    private fun geometry(width: Int = 1080, height: Int = 2109, cameraX: Float = 0f): CapturedAlignmentView {
        val projection = FloatArray(16).also {
            it[0] = 2f * 1400 / width
            it[5] = 2f * 1400 / height
            it[8] = 0.06f
            it[9] = -0.04f
            it[10] = -(60f + 0.05f) / (60f - 0.05f)
            it[11] = -1f
            it[14] = -2f * 60f * 0.05f / (60f - 0.05f)
        }
        val camera = Pose.makeTranslation(cameraX, 1.4f, 0.2f)
        val view = FloatArray(16).also { camera.inverse().toMatrix(it, 0) }
        return CapturedAlignmentView(width, height, projection, view, camera,
            Pose.makeTranslation(0.1f, 0.8f, -1f), camera, 1400f)
    }

    @Test fun `release uses the measured toolbar fit rather than its initial size`() {
        val camera = geometry()
        var result: CorrespondenceSighting? = null
        val initial = CapturedImageFit(1080, 2109, 1080, 2109, bottomInset = 45)
        val measured = CapturedImageFit(1080, 2109, 1080, 2109, bottomInset = 192)
        // rememberUpdatedState uses structural equality. Exercise the REAL production callback
        // through that policy: local ::function references can compare equal despite new captures.
        val current = mutableStateOf(frozenMarkCommitter(camera, initial, 1, 1) { mark, _ -> result = mark })
        current.value = frozenMarkCommitter(camera, measured, 1, 1) { mark, _ -> result = mark }
        val touch = Offset(279f, 939f) // P1 crosshair in the supplied portrait recording, viewport-local.
        val gesture = PrecisionMarkGesture(touch, 0L)
        gesture.advanceTime(500L)
        current.value(MarkRelease(requireNotNull(gesture.release(touch, 1_000L))))
        val sighting = requireNotNull(result)
        val pixel = requireNotNull(camera.pixel(WorldRay(sighting.rayOrigin, sighting.rayDirection)))
        val drawn = measured.viewportPoint(pixel[0], pixel[1])
        assertEquals("release moved the mark horizontally", touch.x, drawn[0], 0.1f)
        assertEquals("release moved the mark vertically", touch.y, drawn[1], 0.1f)
    }

    @Test fun `callback updates after rotation and replacement with another photograph`() {
        var result: CorrespondenceSighting? = null
        val first = geometry()
        val second = geometry(cameraX = 0.4f)
        val portrait = CapturedImageFit(1080, 2109, 1080, 2109, 192)
        val landscape = CapturedImageFit(1080, 2109, 2109, 1080, 160)
        val current = mutableStateOf(frozenMarkCommitter(first, portrait, 1, 1) { mark, _ -> result = mark })
        current.value = frozenMarkCommitter(second, landscape, 3, 2) { mark, _ -> result = mark }
        val imagePixel = floatArrayOf(320f, 1250f)
        val screen = landscape.viewportPoint(imagePixel[0], imagePixel[1])
        current.value(MarkRelease(Offset(screen[0], screen[1])))
        val marked = requireNotNull(result)
        assertEquals(3, marked.label)
        assertEquals(2, marked.station)
        val expected = requireNotNull(second.sighting(3, 2, imagePixel[0], imagePixel[1]))
        assertArrayEquals(expected.rayOrigin, marked.rayOrigin, 0.00001f)
        assertArrayEquals(expected.rayDirection, marked.rayDirection, 0.00001f)
    }

    @Test fun `quick taps and precise releases share the same photo mapping and ignore black bars`() {
        val camera = geometry()
        for (viewport in listOf(1080 to 2109, 2340 to 980)) {
            val fit = CapturedImageFit(1080, 2109, viewport.first, viewport.second, 190)
            val marks = mutableListOf<CorrespondenceSighting>()
            val commit = frozenMarkCommitter(camera, fit, 1, 1) { mark, _ -> marks += mark }
            for (held in listOf(100L, 800L)) {
                for (pixel in listOf(Offset(20f, 20f), Offset(1060f, 2080f), Offset(320.5f, 1300.25f))) {
                    val shown = fit.viewportPoint(pixel.x, pixel.y).let { Offset(it[0], it[1]) }
                    val gesture = PrecisionMarkGesture(shown, 0L)
                    commit(MarkRelease(requireNotNull(gesture.release(shown, held))))
                    val mark = marks.last()
                    val restored = requireNotNull(camera.pixel(WorldRay(mark.rayOrigin, mark.rayDirection)))
                    assertEquals(pixel.x, restored[0], 0.1f)
                    assertEquals(pixel.y, restored[1], 0.1f)
                }
            }
            val count = marks.size
            commit(MarkRelease(Offset(viewport.first / 2f, viewport.second - 10f)))
            assertEquals(count, marks.size)
        }
    }

    @Test fun `a mark dragged out of another carries its start mark with the exact released pixel`() {
        val camera = geometry()
        val fit = CapturedImageFit(1080, 2109, 1080, 2109, 192)
        var linked: Int? = null
        var marked: CorrespondenceSighting? = null
        val commit = frozenMarkCommitter(camera, fit, 3, 1) { mark, from ->
            marked = mark
            linked = from
        }
        val release = fit.viewportPoint(640f, 820f).let { Offset(it[0], it[1]) }
        commit(MarkRelease(release, linkedFrom = 1))
        assertEquals(1, linked)
        val mark = requireNotNull(marked)
        assertEquals(3, mark.label)
        val pixel = requireNotNull(camera.pixel(WorldRay(mark.rayOrigin, mark.rayDirection)))
        assertEquals(640f, pixel[0], 0.1f)
        assertEquals(820f, pixel[1], 0.1f)
    }
}
