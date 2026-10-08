package com.qs.tallerxr.ar

import com.qs.tallerxr.importer.GlbReader
import org.junit.Assert.*
import org.junit.Test

class LandmarkVisibilityTest {
    private fun plate(x0: Float, x1: Float, y0: Float, y1: Float, z: Float) = floatArrayOf(
        x0,y0,z, x1,y0,z, x1,y1,z, x0,y0,z, x1,y1,z, x0,y1,z,
    )

    @Test fun `front points stay visible and rear points change visibility when orbiting`() {
        val front = plate(-1f, 1f, -1f, 1f, 0.1f)
        val rear = plate(-1f, 1f, -1f, 1f, -0.1f)
        val mesh = LandmarkVisibility(front + rear)
        val near = floatArrayOf(0f, 0f, 0.1f)
        val far = floatArrayOf(0f, 0f, -0.1f)
        assertTrue(mesh.isVisible(floatArrayOf(0f, 0f, 3f), near))
        assertFalse(mesh.isVisible(floatArrayOf(0f, 0f, 3f), far))
        assertFalse(mesh.isVisible(floatArrayOf(0f, 0f, -3f), near))
        assertTrue(mesh.isVisible(floatArrayOf(0f, 0f, -3f), far))
    }

    @Test fun `holes remain visible although the mesh bounding box blocks the ray`() {
        val mesh = LandmarkVisibility(
            plate(-2f, -0.3f, -2f, 2f, 0f) + plate(0.3f, 2f, -2f, 2f, 0f) +
                plate(-0.3f, 0.3f, -2f, -0.3f, 0f) + plate(-0.3f, 0.3f, 0.3f, 2f, 0f))
        val camera = floatArrayOf(0f, 0f, 3f)
        assertTrue(mesh.isVisible(camera, floatArrayOf(0f, 0f, -1f)))
        assertFalse(mesh.isVisible(camera, floatArrayOf(1f, 0f, -1f)))
        assertFalse(mesh.isVisible(camera, floatArrayOf(0f, 1f, -1f)))
    }

    @Test fun `vertices do not occlude themselves and a thin sheet still occludes rear points`() {
        val mesh = LandmarkVisibility(plate(-1f, 1f, -1f, 1f, 0f))
        val camera = floatArrayOf(0f, 0f, 2f)
        assertTrue(mesh.isVisible(camera, floatArrayOf(1f, 1f, 0f)))
        assertTrue(mesh.isVisible(camera, floatArrayOf(0f, 0f, 0f))) // shared triangle diagonal
        assertFalse(mesh.isVisible(camera, floatArrayOf(0f, 0f, -0.001f)))
        assertTrue(mesh.isVisible(camera, floatArrayOf(0f, 0f, 0.001f)))
    }

    @Test fun `occlusion works from either side independent of winding`() {
        val vertices = plate(-1f, 1f, -1f, 1f, 0f)
        val reversed = vertices.copyOf()
        for (i in vertices.indices step 9) for (axis in 0..2) {
            reversed[i + 3 + axis] = vertices[i + 6 + axis]
            reversed[i + 6 + axis] = vertices[i + 3 + axis]
        }
        for (mesh in listOf(LandmarkVisibility(vertices), LandmarkVisibility(reversed))) {
            assertFalse(mesh.isVisible(floatArrayOf(0f,0f,3f), floatArrayOf(0f,0f,-1f)))
            assertFalse(mesh.isVisible(floatArrayOf(0f,0f,-3f), floatArrayOf(0f,0f,1f)))
            assertTrue(mesh.isVisible(floatArrayOf(3f,0f,0f), floatArrayOf(2f,0f,0f)))
        }
    }

    @Test fun `spatial hierarchy preserves gaps and occluders in an assembly`() {
        val triangles = (0 until 128).map { i -> plate(i * 2f, i * 2f + 1f, -1f, 1f, 0f) }
            .fold(FloatArray(0)) { a, b -> a + b }
        val mesh = LandmarkVisibility(triangles)
        repeat(128) { i ->
            val x = i * 2f + 0.5f
            assertFalse(mesh.isVisible(floatArrayOf(x,0f,3f), floatArrayOf(x,0f,-1f)))
            assertTrue(mesh.isVisible(floatArrayOf(x + 1f,0f,3f), floatArrayOf(x + 1f,0f,-1f)))
        }
    }

    @Test fun `real HEB asset loses its hidden corners and exposes others from the opposite side`() {
        val bytes = javaClass.getResourceAsStream("/importer/heb-assembly.glb")!!.use { it.readBytes() }
        val scene = GlbReader.parse(bytes)
        val mesh = LandmarkVisibility(scene.meshes.fold(FloatArray(0)) { a, b -> a + b.triangles })
        val model = MarkerlessRegistrationModelReader.decode(javaClass.getResourceAsStream(
            "/importer/heb-registration_model.bin")!!.use { it.readBytes() })
        val points = ModelLandmarks.corners(model).toList().chunked(3).map { it.toFloatArray() }
        val centre = FloatArray(3) { (model.boundsMin[it] + model.boundsMax[it]) / 2f }
        fun visible(sign: Int) = points.indices.filter { index ->
            mesh.isVisible(FloatArray(3) { centre[it] + sign * model.diameterM * (it + 1) }, points[index])
        }.toSet()
        val front = visible(1); val rear = visible(-1)
        assertTrue(front.isNotEmpty() && front.size < points.size)
        assertTrue(rear.isNotEmpty() && rear.size < points.size)
        assertNotEquals(front, rear)
    }
}
