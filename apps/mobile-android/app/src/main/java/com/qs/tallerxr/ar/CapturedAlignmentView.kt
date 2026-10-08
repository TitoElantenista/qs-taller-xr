package com.qs.tallerxr.ar

import com.google.ar.core.Pose
import kotlin.math.min

/** Immutable camera geometry, expressed in the reference anchor AT CAPTURE time.
 * No native Frame/Camera/Image survives the callback. Later tracking/map updates cannot change
 * the rays of the photo, and no depth from a later live frame is assigned to these marks.
 */
internal class CapturedAlignmentView(
    val width: Int,
    val height: Int,
    projection: FloatArray,
    cameraView: FloatArray,
    cameraPose: Pose,
    worldFromReference: Pose,
    displayOrientedPose: Pose,
    val focalLengthPx: Float,
) {
    val projection = projection.copyOf()
    val displayPoseInReference = worldFromReference.inverse().compose(displayOrientedPose)
    /** Camera centre in the reference frame; every ray of this photo starts here. */
    val cameraOrigin: FloatArray = worldFromReference.inverse().transformPoint(cameraPose.translation)
    val viewProjection: FloatArray = ScreenGeometry.multiply(
        ScreenGeometry.multiply(projection, cameraView),
        FloatArray(16).also { worldFromReference.toMatrix(it, 0) },
    )
    private val inverse = requireNotNull(ScreenGeometry.invert(viewProjection))

    fun ray(x: Float, y: Float): WorldRay? {
        if (x < 0 || y < 0 || x >= width || y >= height) return null
        return ScreenGeometry.rayThroughPixel(inverse, cameraOrigin, x, y, width, height)
    }

    fun sighting(label: Int, station: Int, x: Float, y: Float): CorrespondenceSighting? =
        ray(x, y)?.let {
            CorrespondenceSighting(label, station, it.origin, it.direction, null, focalLengthPx)
        }

    fun pixel(ray: WorldRay): FloatArray? =
        ScreenGeometry.projectPixel(viewProjection, ray.pointAt(1f), width, height)
}

/** Letterbox rather than stretch a saved view after resizing/rotating the display. */
internal class CapturedImageFit(imageWidth: Int, imageHeight: Int, viewportWidth: Int, viewportHeight: Int,
    bottomInset: Int = 0,
) {
    private val availableHeight = (viewportHeight - bottomInset).coerceAtLeast(1)
    val scale = min(viewportWidth.toFloat() / imageWidth, availableHeight.toFloat() / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (viewportWidth - width) / 2f
    val top = (availableHeight - height) / 2f

    fun imagePoint(x: Float, y: Float): FloatArray? {
        if (scale <= 0 || x < left || y < top || x >= left + width || y >= top + height) return null
        return floatArrayOf((x - left) / scale, (y - top) / scale)
    }

    fun viewportPoint(x: Float, y: Float) = floatArrayOf(left + x * scale, top + y * scale)
}

/** Copied YUV planes: row/pixel strides may differ and the final row need not have padding. */
internal data class CameraPlane(val bytes: ByteArray, val rowStride: Int, val pixelStride: Int) {
    fun value(x: Int, y: Int): Int = bytes[y * rowStride + x * pixelStride].toInt() and 255
}

internal fun cameraArgb(width: Int, height: Int, planes: List<CameraPlane>): IntArray {
    require(planes.size == 3)
    return IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val luminance = (planes[0].value(x, y) - 16).coerceAtLeast(0) * 1192
        val u = planes[1].value(x / 2, y / 2) - 128
        val v = planes[2].value(x / 2, y / 2) - 128
        val r = ((luminance + 1634 * v) shr 10).coerceIn(0, 255)
        val g = ((luminance - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
        val b = ((luminance + 2066 * u) shr 10).coerceIn(0, 255)
        (255 shl 24) or (r shl 16) or (g shl 8) or b
    }
}
