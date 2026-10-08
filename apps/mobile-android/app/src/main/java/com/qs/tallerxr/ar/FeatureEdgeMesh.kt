package com.qs.tallerxr.ar

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Triangle mesh that draws the QSRG feature edges with a real width.
 *
 * GPU line primitives are one pixel wide on a phone, which disappears over a camera image.  Every
 * edge becomes two crossed ribbons instead, so it keeps a visible width from any direction, stays
 * rigidly attached to the model node and grows naturally as the operator walks closer.
 */
object FeatureEdgeMesh {
    class Mesh(val positions: FloatArray, val indices: IntArray) {
        val vertexCount: Int get() = positions.size / 3
    }

    const val VERTICES_PER_EDGE = 8
    const val INDICES_PER_EDGE = 12

    /** Ribbon width that reads as a marked edge for a part of the given size. */
    fun widthFor(modelDiameterM: Float): Float =
        (modelDiameterM * 0.002f).coerceIn(0.002f, 0.005f)

    fun ribbons(edges: FloatArray, widthM: Float): Mesh {
        require(widthM.isFinite() && widthM > 0f)
        val half = widthM / 2f
        val capacity = edges.size / 6
        val positions = FloatArray(capacity * VERTICES_PER_EDGE * 3)
        val indices = IntArray(capacity * INDICES_PER_EDGE)
        var vertex = 0
        var index = 0
        var edge = 0
        while (edge + 5 < edges.size) {
            val ax = edges[edge]
            val ay = edges[edge + 1]
            val az = edges[edge + 2]
            var dx = edges[edge + 3] - ax
            var dy = edges[edge + 4] - ay
            var dz = edges[edge + 5] - az
            edge += 6
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (!length.isFinite() || length < 1.0e-6f) continue
            dx /= length
            dy /= length
            dz /= length
            // Any vector not parallel to the edge spans the first ribbon; the second is
            // perpendicular to both.
            val (hx, hy, hz) = when {
                abs(dx) <= abs(dy) && abs(dx) <= abs(dz) -> Triple(1f, 0f, 0f)
                abs(dy) <= abs(dz) -> Triple(0f, 1f, 0f)
                else -> Triple(0f, 0f, 1f)
            }
            var ux = dy * hz - dz * hy
            var uy = dz * hx - dx * hz
            var uz = dx * hy - dy * hx
            val uLength = sqrt(ux * ux + uy * uy + uz * uz)
            ux /= uLength
            uy /= uLength
            uz /= uLength
            val vx = dy * uz - dz * uy
            val vy = dz * ux - dx * uz
            val vz = dx * uy - dy * ux
            // Half a width longer at both ends, so the ribbons of a corner meet.
            val sx = ax - dx * half
            val sy = ay - dy * half
            val sz = az - dz * half
            val reach = length + widthM
            for ((sideX, sideY, sideZ) in listOf(Triple(ux, uy, uz), Triple(vx, vy, vz))) {
                val base = vertex
                for (corner in 0..3) {
                    val along = if (corner >= 2) reach else 0f
                    val across = if (corner % 2 == 0) -half else half
                    positions[vertex * 3] = sx + dx * along + sideX * across
                    positions[vertex * 3 + 1] = sy + dy * along + sideY * across
                    positions[vertex * 3 + 2] = sz + dz * along + sideZ * across
                    vertex += 1
                }
                indices[index++] = base
                indices[index++] = base + 1
                indices[index++] = base + 2
                indices[index++] = base + 2
                indices[index++] = base + 1
                indices[index++] = base + 3
            }
        }
        return Mesh(positions.copyOf(vertex * 3), indices.copyOf(index))
    }
}
