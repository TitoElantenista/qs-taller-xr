package com.qs.tallerxr.ar

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Exact triangle occlusion, including holes and concave profiles. Built off the UI thread. */
internal class LandmarkVisibility(private val triangles: FloatArray) {
    init { require(triangles.size % 9 == 0 && triangles.all(Float::isFinite)) }
    private val order = IntArray(triangles.size / 9) { it }
    private class Branch(val bounds: FloatArray, val start: Int, val end: Int) {
        var left: Branch? = null
        var right: Branch? = null
    }
    private val root = if (order.isEmpty()) null else build(0, order.size)
    private val tolerance = root?.bounds?.let {
        max(0.00001, sqrt((0..2).sumOf { axis -> (it[axis + 3] - it[axis]).toDouble().let { d -> d * d } }) * 1e-6)
    } ?: 0.00001

    fun isVisible(camera: FloatArray, point: FloatArray): Boolean {
        if (camera.size < 3 || point.size < 3 || !camera.all(Float::isFinite) || !point.all(Float::isFinite)) return false
        val direction = DoubleArray(3) { point[it].toDouble() - camera[it] }
        val distance = sqrt(direction.sumOf { it * it })
        if (distance <= tolerance) return false
        // Segment parameter 1 is the vertex itself. Do not count its incident faces as occluders.
        val end = 1.0 - tolerance / distance
        return root?.let { !blocked(it, camera, direction, end) } ?: true
    }

    private fun build(start: Int, end: Int): Branch {
        val bounds = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (i in start until end) for (v in 0..2) for (axis in 0..2) {
            val value = triangles[order[i] * 9 + v * 3 + axis]
            bounds[axis] = min(bounds[axis], value)
            bounds[axis + 3] = max(bounds[axis + 3], value)
        }
        val branch = Branch(bounds, start, end)
        if (end - start <= 8) return branch
        val axis = (0..2).maxBy { bounds[it + 3] - bounds[it] }
        val midpoint = (bounds[axis].toDouble() + bounds[axis + 3]) / 2.0
        var split = start
        for (i in start until end) {
            val t = order[i] * 9 + axis
            val centre = (triangles[t].toDouble() + triangles[t + 3] + triangles[t + 6]) / 3.0
            if (centre < midpoint) {
                val previous = order[split]; order[split] = order[i]; order[i] = previous
                split++
            }
        }
        // Bound tree depth even for coincident triangles or very uneven imported meshes.
        if (split - start < (end - start) / 16 || end - split < (end - start) / 16 || split == start || split == end) {
            split = (start + end) / 2
        }
        branch.left = build(start, split)
        branch.right = build(split, end)
        return branch
    }

    private fun blocked(node: Branch, origin: FloatArray, direction: DoubleArray, end: Double): Boolean {
        var near = 0.0
        var far = end
        for (axis in 0..2) {
            val d = direction[axis]
            val lo = node.bounds[axis].toDouble() - origin[axis]
            val hi = node.bounds[axis + 3].toDouble() - origin[axis]
            if (abs(d) < 1e-15) {
                if (lo > 0.0 || hi < 0.0) return false
            } else {
                val a = lo / d; val b = hi / d
                near = max(near, min(a, b)); far = min(far, max(a, b))
                if (near > far) return false
            }
        }
        val left = node.left
        if (left != null) return blocked(left, origin, direction, end) || blocked(node.right!!, origin, direction, end)
        for (i in node.start until node.end) if (intersects(order[i] * 9, origin, direction, end)) return true
        return false
    }

    /** Two-sided Möller–Trumbore: occlusion must not depend on winding of imported geometry. */
    private fun intersects(i: Int, o: FloatArray, d: DoubleArray, end: Double): Boolean {
        val ax = triangles[i].toDouble(); val ay = triangles[i + 1].toDouble(); val az = triangles[i + 2].toDouble()
        val ux = triangles[i + 3] - ax; val uy = triangles[i + 4] - ay; val uz = triangles[i + 5] - az
        val vx = triangles[i + 6] - ax; val vy = triangles[i + 7] - ay; val vz = triangles[i + 8] - az
        val px = d[1] * vz - d[2] * vy; val py = d[2] * vx - d[0] * vz; val pz = d[0] * vy - d[1] * vx
        val determinant = ux * px + uy * py + uz * pz
        if (abs(determinant) < 1e-15) return false
        val tx = o[0] - ax; val ty = o[1] - ay; val tz = o[2] - az
        val u = (tx * px + ty * py + tz * pz) / determinant
        if (u < -1e-7 || u > 1.0 + 1e-7) return false
        val qx = ty * uz - tz * uy; val qy = tz * ux - tx * uz; val qz = tx * uy - ty * ux
        val v = (d[0] * qx + d[1] * qy + d[2] * qz) / determinant
        if (v < -1e-7 || u + v > 1.0 + 1e-7) return false
        val t = (vx * qx + vy * qy + vz * qz) / determinant
        return t > 1e-9 && t < end
    }
}
