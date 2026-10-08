package com.qs.tallerxr.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.qs.tallerxr.ar.AlignmentMarking
import com.qs.tallerxr.ar.CorrespondenceSighting
import org.junit.Assert.*
import org.junit.Test

class ModelReferenceImageTest {
    @Test fun `reference crop keeps point attached to the same image pixel at different card sizes`() {
        val point = referencePointInCrop(ReferencePoint(3, 720f, 600f, true), 120, 200, 800, 500)
        assertEquals(0.75f, point.x, 0.0001f)
        assertEquals(0.8f, point.y, 0.0001f)
        for (scale in listOf(0.2f, 0.5f, 1f)) {
            assertEquals((720f - 120f) * scale, point.x * 800 * scale, 0.001f)
            assertEquals((600f - 200f) * scale, point.y * 500 * scale, 0.001f)
        }
        assertEquals(3, point.label)
        assertTrue(point.visible)
    }

    @Test fun `hidden selections remain in the reference without becoming visible markers`() {
        val point = referencePointInCrop(ReferencePoint(6, 720f, 600f, false), 120, 200, 800, 500)
        assertEquals(6, point.label)
        assertFalse(point.visible)
    }

    @Test fun `the whole part is fitted and centred inside the inset`() {
        val transform = referenceViewTransform(Size(800f, 400f), Size(200f, 200f), insetPx = 10f)
        assertEquals(0.225f, transform.scale, 1e-5f)
        assertEquals(Offset(10f, 55f), transform.map(0f, 0f))
        assertEquals(Offset(190f, 145f), transform.map(800f, 400f))
    }

    @Test fun `detail centres the point to be marked and never uncovers a side it can fill`() {
        val image = Size(800f, 400f)
        val canvas = Size(200f, 200f)
        val middle = referenceViewTransform(image, canvas, 10f, zoom = 2.5f, focus = Offset(400f, 200f))
        assertEquals(0.5625f, middle.scale, 1e-5f)
        assertEquals(100f, middle.map(400f, 200f).x, 0.01f)
        assertEquals(100f, middle.map(400f, 200f).y, 0.01f)
        // A corner at the left border: the image edge stays at the inset, the point is off centre.
        val corner = referenceViewTransform(image, canvas, 10f, zoom = 2.5f, focus = Offset(0f, 0f))
        assertEquals(Offset(10f, 10f), corner.map(0f, 0f))
        // The enlarged height (225 px) nearly fits: it is clamped to the inset on both sides.
        val bottom = referenceViewTransform(image, canvas, 10f, zoom = 2.5f, focus = Offset(800f, 400f))
        assertEquals(190f, bottom.map(800f, 400f).x, 0.01f)
        assertEquals(190f, bottom.map(800f, 400f).y, 0.01f)
    }

    @Test fun `numbers move away from close neighbours and default up and right`() {
        val alone = referenceLabelDirection(listOf(Offset(50f, 50f)), 0, 40f)
        assertEquals(0.7071f, alone.x, 1e-3f)
        assertEquals(-0.7071f, alone.y, 1e-3f)
        val pair = listOf(Offset(100f, 100f), Offset(120f, 100f))
        assertEquals(Offset(-1f, 0f), referenceLabelDirection(pair, 0, 40f))
        assertEquals(Offset(1f, 0f), referenceLabelDirection(pair, 1, 40f))
        // A distant marker does not push.
        val far = referenceLabelDirection(listOf(Offset(0f, 0f), Offset(300f, 0f)), 0, 40f)
        assertEquals(0.7071f, far.x, 1e-3f)
    }

    @Test fun `the reference looks at the point to be marked, then at the last marked one`() {
        val points = listOf(ReferencePoint(1, 0.1f, 0.1f, true), ReferencePoint(2, 0.5f, 0.5f, false),
            ReferencePoint(3, 0.9f, 0.9f, true))
        assertEquals(1, referenceFocusLabel(null, points))
        val marking = AlignmentMarking(listOf(1, 2, 3))
        assertEquals(1, referenceFocusLabel(marking, points))
        val sighting = CorrespondenceSighting(1, 1, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, -1f), null, 1_500f)
        // P2 is hidden in the 3D view: the reference stays on the last marked point.
        assertEquals(1, referenceFocusLabel(marking.marked(sighting), points))
        val complete = marking.marked(sighting).skipped().marked(sighting.copy(label = 3))
        assertEquals(3, referenceFocusLabel(complete, points))
    }

    @Test fun `the enlarged viewer zooms around its centre pixel and falls back to the fitted part`() {
        val image = Size(800f, 400f)
        val canvas = Size(400f, 400f)
        val fitted = referenceViewerTransform(image, canvas, zoom = 1f, centre = Offset(0f, 0f))
        assertEquals(0.5f, fitted.scale, 1e-5f)
        assertEquals(Offset(0f, 100f), fitted.map(0f, 0f))
        val zoomed = referenceViewerTransform(image, canvas, zoom = 4f, centre = Offset(600f, 100f))
        assertEquals(2f, zoomed.scale, 1e-5f)
        assertEquals(Offset(200f, 200f), zoomed.map(600f, 100f))
        // A centre beyond the image is held at its border.
        assertEquals(Offset(200f, 200f), referenceViewerTransform(image, canvas, 4f, Offset(900f, -50f)).map(800f, 0f))
    }
}
