package com.qs.tallerxr.ar

import kotlin.math.sqrt
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenGeometryTest {

    private fun perspective(
        verticalFieldOfViewDegrees: Float,
        aspect: Float,
        near: Float,
        far: Float,
    ): FloatArray {
        val focal = 1f / tan(Math.toRadians(verticalFieldOfViewDegrees / 2.0).toFloat())
        val matrix = FloatArray(16)
        matrix[0] = focal / aspect
        matrix[5] = focal
        matrix[10] = (far + near) / (near - far)
        matrix[11] = -1f
        matrix[14] = 2f * far * near / (near - far)
        return matrix
    }

    @Test
    fun `inverse restores the identity`() {
        val projection = perspective(60f, 1080f / 2400f, 0.05f, 40f)
        val inverse = requireNotNull(ScreenGeometry.invert(projection))
        val product = ScreenGeometry.multiply(projection, inverse)
        for (column in 0..3) {
            for (row in 0..3) {
                val expected = if (column == row) 1f else 0f
                assertEquals(expected, product[column * 4 + row], 1e-4f)
            }
        }
    }

    @Test
    fun `centre pixel looks along the camera forward axis`() {
        val projection = perspective(60f, 1080f / 2400f, 0.05f, 40f)
        val inverse = requireNotNull(ScreenGeometry.invert(projection))
        val ray = requireNotNull(
            ScreenGeometry.rayThroughPixel(
                inverseViewProjection = inverse,
                originWorld = floatArrayOf(0f, 0f, 0f),
                xPx = 540f,
                yPx = 1200f,
                widthPx = 1080,
                heightPx = 2400,
            ),
        )
        assertEquals(0f, ray.direction[0], 1e-5f)
        assertEquals(0f, ray.direction[1], 1e-5f)
        assertEquals(-1f, ray.direction[2], 1e-5f)
    }

    @Test
    fun `projection and unprojection agree on the same direction`() {
        val projection = perspective(60f, 1080f / 2400f, 0.05f, 40f)
        val inverse = requireNotNull(ScreenGeometry.invert(projection))
        val world = floatArrayOf(0.31f, -0.18f, -2.4f)
        val pixel = requireNotNull(
            ScreenGeometry.projectPixel(projection, world, 1080, 2400),
        )
        assertTrue(pixel[0] in 0f..1080f)
        assertTrue(pixel[1] in 0f..2400f)
        val ray = requireNotNull(
            ScreenGeometry.rayThroughPixel(
                inverseViewProjection = inverse,
                originWorld = floatArrayOf(0f, 0f, 0f),
                xPx = pixel[0],
                yPx = pixel[1],
                widthPx = 1080,
                heightPx = 2400,
            ),
        )
        val length = sqrt(world.fold(0f) { sum, value -> sum + value * value })
        for (axis in 0..2) {
            assertEquals(world[axis] / length, ray.direction[axis], 1e-4f)
        }
    }

    @Test
    fun `a point behind the camera has no pixel`() {
        val projection = perspective(60f, 1080f / 2400f, 0.05f, 40f)
        assertNull(ScreenGeometry.projectPixel(projection, floatArrayOf(0f, 0f, 1.5f), 1080, 2400))
    }
}
