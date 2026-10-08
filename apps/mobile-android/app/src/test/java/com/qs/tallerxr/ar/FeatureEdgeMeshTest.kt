package com.qs.tallerxr.ar

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureEdgeMeshTest {
    @Test
    fun `every edge becomes two crossed ribbons of the requested width`() {
        val edges = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 0f, 2f, 0f,
        )
        val mesh = FeatureEdgeMesh.ribbons(edges, widthM = 0.004f)
        assertEquals(2 * FeatureEdgeMesh.VERTICES_PER_EDGE, mesh.vertexCount)
        assertEquals(2 * FeatureEdgeMesh.INDICES_PER_EDGE, mesh.indices.size)
        assertTrue(mesh.indices.all { it in 0 until mesh.vertexCount })

        // First ribbon of the X edge: its two start corners are one width apart and the ribbon
        // is as long as the edge plus one width, so corners of adjacent edges meet.
        fun vertex(index: Int) = FloatArray(3) { mesh.positions[index * 3 + it] }
        fun distance(a: FloatArray, b: FloatArray) =
            sqrt((0..2).fold(0f) { sum, axis -> sum + (a[axis] - b[axis]).let { it * it } })
        assertEquals(0.004f, distance(vertex(0), vertex(1)), 1e-6f)
        assertEquals(1.004f, distance(vertex(0), vertex(2)), 1e-5f)

        // The two ribbons of one edge are perpendicular to each other.
        val first = FloatArray(3) { vertex(1)[it] - vertex(0)[it] }
        val second = FloatArray(3) { vertex(5)[it] - vertex(4)[it] }
        assertTrue(abs(first[0] * second[0] + first[1] * second[1] + first[2] * second[2]) < 1e-9f)
    }

    @Test
    fun `degenerate edges are dropped`() {
        val mesh = FeatureEdgeMesh.ribbons(
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0.5f),
            widthM = 0.003f,
        )
        assertEquals(FeatureEdgeMesh.VERTICES_PER_EDGE, mesh.vertexCount)
        assertTrue(mesh.positions.all(Float::isFinite))
    }

    @Test
    fun `edge width follows the part size within visible limits`() {
        assertEquals(0.002f, FeatureEdgeMesh.widthFor(0.3f), 1e-6f)
        assertEquals(0.004f, FeatureEdgeMesh.widthFor(2f), 1e-6f)
        assertEquals(0.005f, FeatureEdgeMesh.widthFor(12f), 1e-6f)
    }
}
