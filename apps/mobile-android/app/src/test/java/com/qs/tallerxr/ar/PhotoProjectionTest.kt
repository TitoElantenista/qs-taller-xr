package com.qs.tallerxr.ar

import com.google.ar.core.Pose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoProjectionTest {
    private val width = 1000
    private val height = 800
    private val focal = 1000f

    private fun geometry(camera: Pose): CapturedAlignmentView {
        val projection = FloatArray(16).also {
            it[0] = 2f * focal / width
            it[5] = 2f * focal / height
            it[10] = -(60f + 0.05f) / (60f - 0.05f)
            it[11] = -1f
            it[14] = -2f * 60f * 0.05f / (60f - 0.05f)
        }
        return CapturedAlignmentView(width, height, projection,
            FloatArray(16).also { camera.inverse().toMatrix(it, 0) }, camera, Pose.IDENTITY, camera, focal)
    }

    // The camera stands 2 m in front of the model origin and looks at it.
    private val camera = Pose.makeTranslation(0f, 0f, 2f)

    @Test
    fun `a landmark projects where the camera sees it`() {
        val pixel = requireNotNull(PhotoProjection.pixel(geometry(camera), Pose.IDENTITY, floatArrayOf(0.2f, 0f, 0f)))
        assertEquals(500f + 0.2f / 2f * focal, pixel[0], 0.5f)
        assertEquals(400f, pixel[1], 0.5f)
    }

    @Test
    fun `a prediction grows with the expected error and gives up when too vague`() {
        val view = geometry(camera)
        val point = floatArrayOf(0f, 0f, 0f)
        // 10 mm at 2 m and 1000 px focal: 5 px, twice for two sigma, never under the finger size.
        val precise = requireNotNull(PhotoProjection.prediction(view, Pose.IDENTITY, 3, point, 10f, 30f, 200f))
        assertEquals(30f, precise.radiusPx, 0.01f)
        val loose = requireNotNull(PhotoProjection.prediction(view, Pose.IDENTITY, 3, point, 150f, 30f, 200f))
        assertEquals(150f, loose.radiusPx, 0.5f)
        assertEquals(3, loose.label)
        assertNull(PhotoProjection.prediction(view, Pose.IDENTITY, 3, point, 400f, 30f, 200f))
        // Behind the camera there is nothing to show.
        assertNull(PhotoProjection.prediction(view, Pose.IDENTITY, 3, floatArrayOf(0f, 0f, 3f), 10f, 30f, 200f))
        assertNull(PhotoProjection.prediction(view, Pose.IDENTITY, 3, point, Float.NaN, 30f, 200f))
    }

    @Test
    fun `edges hidden behind the part are left out of the photo overlay`() {
        // A cube with half size 0.1 m; its back face edges lie behind its front face.
        val h = 0.1f
        val corners = (0 until 8).map { c ->
            floatArrayOf(if (c and 1 == 0) -h else h, if (c and 2 == 0) -h else h, if (c and 4 == 0) -h else h)
        }
        val edges = listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7, 0 to 2, 1 to 3, 4 to 6, 5 to 7, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
            .flatMap { (a, b) -> (corners[a] + corners[b]).toList() }.toFloatArray()
        val faces = listOf(listOf(0, 1, 3, 2), listOf(4, 5, 7, 6), listOf(0, 1, 5, 4), listOf(2, 3, 7, 6),
            listOf(0, 2, 6, 4), listOf(1, 3, 7, 5))
        val triangles = faces.flatMap { f ->
            (corners[f[0]] + corners[f[1]] + corners[f[2]] + corners[f[0]] + corners[f[2]] + corners[f[3]]).toList()
        }.toFloatArray()
        // Seen from straight ahead only the front square shows; the back square coincides with it
        // on screen, so it must drop out by occlusion, not by projection.
        val view = geometry(camera)
        val all = PhotoProjection.visibleEdges(view, Pose.IDENTITY, edges, null, 0.01f)
        val visible = PhotoProjection.visibleEdges(view, Pose.IDENTITY, edges, LandmarkVisibility(triangles), 0.01f)
        assertTrue(all.isNotEmpty())
        assertTrue("visibles ${visible.size / 4} de ${all.size / 4}", visible.size < all.size * 0.6f)
        assertTrue(visible.isNotEmpty())
        assertNotNull(visible)
    }
}
