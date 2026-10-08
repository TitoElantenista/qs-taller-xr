package com.qs.tallerxr.ar

import kotlin.math.abs
import kotlin.math.sqrt

data class WorldRay(val origin: FloatArray, val direction: FloatArray) {
    init {
        require(origin.size == 3 && origin.all(Float::isFinite))
        require(direction.size == 3 && direction.all(Float::isFinite))
    }

    fun pointAt(distanceM: Float) = FloatArray(3) { origin[it] + direction[it] * distanceM }
}

/**
 * Column major 4x4 helpers shared by the AR marking overlay.  ARCore hands out view and
 * projection matrices in OpenGL layout, so index `column * 4 + row` addresses one element and the
 * translation lives at 12, 13 and 14, exactly as ADR 0001 requires.
 */
object ScreenGeometry {
    fun multiply(left: FloatArray, right: FloatArray): FloatArray {
        require(left.size >= 16 && right.size >= 16)
        val result = FloatArray(16)
        for (column in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (inner in 0..3) {
                    sum += left[inner * 4 + row] * right[column * 4 + inner]
                }
                result[column * 4 + row] = sum
            }
        }
        return result
    }

    fun invert(matrix: FloatArray): FloatArray? {
        require(matrix.size >= 16)
        val source = DoubleArray(16) { matrix[it].toDouble() }
        val target = DoubleArray(16)
        for (index in 0..3) target[index * 4 + index] = 1.0
        for (column in 0..3) {
            var pivot = column
            for (row in column + 1..3) {
                if (abs(source[column * 4 + row]) > abs(source[column * 4 + pivot])) pivot = row
            }
            if (abs(source[column * 4 + pivot]) < 1.0e-12) return null
            if (pivot != column) {
                for (index in 0..3) {
                    var swap = source[index * 4 + column]
                    source[index * 4 + column] = source[index * 4 + pivot]
                    source[index * 4 + pivot] = swap
                    swap = target[index * 4 + column]
                    target[index * 4 + column] = target[index * 4 + pivot]
                    target[index * 4 + pivot] = swap
                }
            }
            val diagonal = source[column * 4 + column]
            for (index in 0..3) {
                source[index * 4 + column] /= diagonal
                target[index * 4 + column] /= diagonal
            }
            for (row in 0..3) {
                if (row == column) continue
                val factor = source[column * 4 + row]
                if (factor == 0.0) continue
                for (index in 0..3) {
                    source[index * 4 + row] -= factor * source[index * 4 + column]
                    target[index * 4 + row] -= factor * target[index * 4 + column]
                }
            }
        }
        if (target.any { !it.isFinite() }) return null
        return FloatArray(16) { target[it].toFloat() }
    }

    /**
     * World ray through one viewport pixel.  [originWorld] is the camera centre, so a distance
     * measured from the camera can be used directly along the returned direction.
     */
    fun rayThroughPixel(
        inverseViewProjection: FloatArray,
        originWorld: FloatArray,
        xPx: Float,
        yPx: Float,
        widthPx: Int,
        heightPx: Int,
    ): WorldRay? {
        if (widthPx <= 0 || heightPx <= 0) return null
        if (!xPx.isFinite() || !yPx.isFinite()) return null
        val ndcX = 2f * xPx / widthPx - 1f
        val ndcY = 1f - 2f * yPx / heightPx
        val near = unproject(inverseViewProjection, ndcX, ndcY, -1f) ?: return null
        val far = unproject(inverseViewProjection, ndcX, ndcY, 1f) ?: return null
        val direction = normalize(FloatArray(3) { far[it] - near[it] }) ?: return null
        return WorldRay(origin = originWorld.copyOf(3), direction = direction)
    }

    /** Viewport pixel of a world point, or null when it is behind the camera. */
    fun projectPixel(
        viewProjection: FloatArray,
        world: FloatArray,
        widthPx: Int,
        heightPx: Int,
    ): FloatArray? {
        require(world.size >= 3)
        if (widthPx <= 0 || heightPx <= 0) return null
        val clip = transform(viewProjection, world[0], world[1], world[2], 1f)
        val w = clip[3]
        if (!w.isFinite() || w <= 1.0e-6f) return null
        val x = (clip[0] / w + 1f) * 0.5f * widthPx
        val y = (1f - clip[1] / w) * 0.5f * heightPx
        if (!x.isFinite() || !y.isFinite()) return null
        return floatArrayOf(x, y)
    }

    private fun unproject(
        inverseViewProjection: FloatArray,
        ndcX: Float,
        ndcY: Float,
        ndcZ: Float,
    ): FloatArray? {
        val result = transform(inverseViewProjection, ndcX, ndcY, ndcZ, 1f)
        val w = result[3]
        if (!w.isFinite() || abs(w) < 1.0e-9f) return null
        return FloatArray(3) { result[it] / w }
    }

    private fun transform(matrix: FloatArray, x: Float, y: Float, z: Float, w: Float): FloatArray {
        val vector = floatArrayOf(x, y, z, w)
        return FloatArray(4) { row ->
            (0..3).fold(0f) { sum, column -> sum + matrix[column * 4 + row] * vector[column] }
        }
    }

    private fun normalize(vector: FloatArray): FloatArray? {
        val length = sqrt(vector.fold(0f) { sum, value -> sum + value * value })
        if (!length.isFinite() || length < 1.0e-9f) return null
        return FloatArray(vector.size) { vector[it] / length }
    }
}
