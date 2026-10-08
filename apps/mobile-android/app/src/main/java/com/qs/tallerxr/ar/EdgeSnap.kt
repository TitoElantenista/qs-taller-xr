package com.qs.tallerxr.ar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Camera images for the experimental «Snap»: the grey levels of one frame in viewport pixels and,
 * built from them, where the image edges of each direction lie.  The search itself is in
 * [FloorSnapSession].
 */

/** Grey levels of one camera frame, addressed in viewport pixels through that frame's crop. */
internal class LuminanceImage(
    val width: Int,
    val height: Int,
    private val bytes: ByteArray,
    private val rowStride: Int,
    private val pixelStride: Int,
    /** Image pixels of the viewport corners (0,0), (w,0) and (0,h), as ARCore reports them. */
    viewportCornersInImage: FloatArray,
    val viewportWidth: Int,
    val viewportHeight: Int,
) {
    private val originX = viewportCornersInImage[0]
    private val originY = viewportCornersInImage[1]
    private val alongX = (viewportCornersInImage[2] - originX) / viewportWidth
    private val alongY = (viewportCornersInImage[3] - originY) / viewportWidth
    private val downX = (viewportCornersInImage[4] - originX) / viewportHeight
    private val downY = (viewportCornersInImage[5] - originY) / viewportHeight

    init {
        require(width > 1 && height > 1 && viewportWidth > 0 && viewportHeight > 0)
        require(bytes.size >= (height - 1) * rowStride + (width - 1) * pixelStride + 1)
    }

    /** Bilinear grey level at a viewport pixel, or NaN outside the camera image. */
    fun at(x: Float, y: Float): Float {
        val u = originX + alongX * x + downX * y
        val v = originY + alongY * x + downY * y
        if (!(u >= 0f && v >= 0f && u < width - 1 && v < height - 1)) return Float.NaN
        val column = u.toInt()
        val row = v.toInt()
        val fu = u - column
        val fv = v - row
        val base = row * rowStride + column * pixelStride
        val a = bytes[base].toInt() and 255
        val b = bytes[base + pixelStride].toInt() and 255
        val c = bytes[base + rowStride].toInt() and 255
        val d = bytes[base + rowStride + pixelStride].toInt() and 255
        return (a + (b - a) * fu) + ((c + (d - c) * fu) - (a + (b - a) * fu)) * fv
    }

    companion object {
        /** A viewport that shows the image unrotated and uncropped; used by tests and previews. */
        fun upright(width: Int, height: Int, grey: ByteArray): LuminanceImage =
            LuminanceImage(width, height, grey, width, 1,
                floatArrayOf(0f, 0f, width.toFloat(), 0f, 0f, height.toFloat()), width, height)
    }
}

/** One frame for the snap: its grey levels and the camera geometry they were taken with. */
internal class SnapView(val image: LuminanceImage, val geometry: CapturedAlignmentView)

/**
 * The edges of one frame at one resolution, kept as distance maps: for each of [BINS] edge
 * directions, how far every pixel lies from the nearest image edge running that way (or one bin
 * either side, about ±34°), in pixels of this level and capped at 255.  A CAD edge looks up the
 * map of its own direction, so an image edge crossing it at a steep angle never supports it.
 */
internal class SnapEdgeLevel(
    val width: Int,
    val height: Int,
    /** Viewport pixels per pixel of this level. */
    val scale: Float,
    private val distances: Array<ByteArray>,
    /** Share of the pixels that are edges; a blurred or flat frame has almost none. */
    val edgeDensity: Float,
) {
    fun distance(bin: Int, x: Int, y: Int): Int = distances[bin][y * width + x].toInt() and 255

    companion object {
        const val BINS = 8

        /** Gradient in grey levels per pixel, after a light blur, that counts as an edge. */
        const val EDGE_THRESHOLD = 3f

        /** Bin of a direction across an edge (its normal); opposite directions share a bin. */
        fun bin(normalRadians: Float): Int {
            var angle = normalRadians % PI.toFloat()
            if (angle < 0f) angle += PI.toFloat()
            return min(BINS - 1, (angle / (PI.toFloat() / BINS)).toInt())
        }

        /** Edges of a grey image (row major, width x height) as seen from [scale] viewport pixels per pixel. */
        fun build(grey: FloatArray, width: Int, height: Int, scale: Float): SnapEdgeLevel {
            require(width >= 3 && height >= 3 && grey.size >= width * height)
            val smooth = blur(grey, width, height)
            // Sobel gradient, non-maximum suppression across the edge, then the direction bin.
            val gx = FloatArray(width * height)
            val gy = FloatArray(width * height)
            val magnitude = FloatArray(width * height)
            for (y in 1 until height - 1) {
                val row = y * width
                for (x in 1 until width - 1) {
                    val i = row + x
                    val a = smooth[i - width - 1]; val b = smooth[i - width]; val c = smooth[i - width + 1]
                    val d = smooth[i - 1]; val f = smooth[i + 1]
                    val g = smooth[i + width - 1]; val h = smooth[i + width]; val k = smooth[i + width + 1]
                    val sx = ((c + 2f * f + k) - (a + 2f * d + g)) / 8f
                    val sy = ((g + 2f * h + k) - (a + 2f * b + c)) / 8f
                    gx[i] = sx
                    gy[i] = sy
                    magnitude[i] = sqrt(sx * sx + sy * sy)
                }
            }
            val bins = ByteArray(width * height) { -1 }
            var edges = 0
            for (y in 2 until height - 2) {
                val row = y * width
                for (x in 2 until width - 2) {
                    val i = row + x
                    val m = magnitude[i]
                    if (m <= EDGE_THRESHOLD) continue
                    val ax = abs(gx[i])
                    val ay = abs(gy[i])
                    val step = when {
                        ay <= TAN_22_5 * ax -> 1
                        ax <= TAN_22_5 * ay -> width
                        gx[i] * gy[i] > 0f -> width + 1
                        else -> width - 1
                    }
                    val first = magnitude[i - step]
                    val second = magnitude[i + step]
                    // Strictly above one neighbour: a flat top two pixels wide gives one edge.
                    if (m > first && m >= second) {
                        bins[i] = bin(atan2(gy[i], gx[i])).toByte()
                        edges++
                    }
                }
            }
            val distances = Array(BINS) { target ->
                val map = ByteArray(width * height)
                for (i in map.indices) {
                    val b = bins[i].toInt()
                    val near = b >= 0 && (b == target || b == (target + 1) % BINS || b == (target + BINS - 1) % BINS)
                    map[i] = if (near) 0 else CAP
                }
                distanceTransform(map, width, height)
                map
            }
            return SnapEdgeLevel(width, height, scale, distances, edges.toFloat() / (width * height))
        }

        private const val TAN_22_5 = 0.41421357f
        private const val CAP: Byte = -1 // 255 as unsigned

        /** Binomial 5-tap blur (sigma about one pixel), borders clamped. */
        private fun blur(grey: FloatArray, width: Int, height: Int): FloatArray {
            val across = FloatArray(width * height)
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    val l2 = grey[row + max(x - 2, 0)]; val l1 = grey[row + max(x - 1, 0)]
                    val r1 = grey[row + min(x + 1, width - 1)]; val r2 = grey[row + min(x + 2, width - 1)]
                    across[row + x] = (l2 + 4f * l1 + 6f * grey[row + x] + 4f * r1 + r2) / 16f
                }
            }
            val result = FloatArray(width * height)
            for (y in 0 until height) {
                val u2 = max(y - 2, 0) * width; val u1 = max(y - 1, 0) * width
                val d1 = min(y + 1, height - 1) * width; val d2 = min(y + 2, height - 1) * width
                val row = y * width
                for (x in 0 until width) {
                    result[row + x] = (across[u2 + x] + 4f * across[u1 + x] + 6f * across[row + x] +
                        4f * across[d1 + x] + across[d2 + x]) / 16f
                }
            }
            return result
        }

        /** City-block distance to the nearest zero, in place, saturating at 255. */
        private fun distanceTransform(map: ByteArray, width: Int, height: Int) {
            for (y in 0 until height) {
                val row = y * width
                for (x in 1 until width) relax(map, row + x, row + x - 1)
                for (x in width - 2 downTo 0) relax(map, row + x, row + x + 1)
            }
            for (x in 0 until width) {
                for (y in 1 until height) relax(map, y * width + x, (y - 1) * width + x)
                for (y in height - 2 downTo 0) relax(map, y * width + x, (y + 1) * width + x)
            }
        }

        private fun relax(map: ByteArray, index: Int, neighbour: Int) {
            val candidate = (map[neighbour].toInt() and 255) + 1
            if (candidate < (map[index].toInt() and 255)) map[index] = candidate.toByte()
        }
    }
}

/** Both levels of one frame: coarse for the wide search, fine for the final fit. */
internal class SnapViewFeatures(
    val geometry: CapturedAlignmentView,
    val coarse: SnapEdgeLevel,
    val fine: SnapEdgeLevel,
) {
    companion object {
        /** About [FINE_SIDE_PX] pixels along the longer side for the fine level, half of that for the coarse one. */
        const val FINE_SIDE_PX = 1200

        fun of(view: SnapView): SnapViewFeatures {
            val geometry = view.geometry
            val scale = max(1, (max(geometry.width, geometry.height).toFloat() / FINE_SIDE_PX).roundToInt())
            val width = geometry.width / scale
            val height = geometry.height / scale
            val grey = FloatArray(width * height)
            var last = 128f
            for (y in 0 until height) for (x in 0 until width) {
                // 2 x 2 samples per pixel: the camera image has about as many pixels as the viewport.
                var sum = 0f
                var count = 0
                for (sub in 0..3) {
                    val value = view.image.at((x + 0.25f + 0.5f * (sub and 1)) * scale, (y + 0.25f + 0.5f * (sub shr 1)) * scale)
                    if (!value.isNaN()) {
                        sum += value
                        count++
                    }
                }
                if (count > 0) last = sum / count
                grey[y * width + x] = last
            }
            val fine = SnapEdgeLevel.build(grey, width, height, scale.toFloat())
            val coarseWidth = width / 2
            val coarseHeight = height / 2
            val coarseGrey = FloatArray(coarseWidth * coarseHeight) { index ->
                val x = (index % coarseWidth) * 2
                val y = (index / coarseWidth) * 2
                (grey[y * width + x] + grey[y * width + x + 1] + grey[(y + 1) * width + x] + grey[(y + 1) * width + x + 1]) / 4f
            }
            val coarse = SnapEdgeLevel.build(coarseGrey, coarseWidth, coarseHeight, scale * 2f)
            return SnapViewFeatures(geometry, coarse, fine)
        }
    }
}
