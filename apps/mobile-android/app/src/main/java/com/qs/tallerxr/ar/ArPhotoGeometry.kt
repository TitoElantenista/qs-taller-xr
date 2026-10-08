package com.qs.tallerxr.ar

import androidx.compose.ui.unit.IntRect
import kotlin.math.ceil
import kotlin.math.floor

/** SurfaceView placement uses window pixels; PixelCopy reads its independently sized buffer. */
internal data class ArPhotoSurfaceGeometry(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val bufferWidth: Int,
    val bufferHeight: Int,
)

internal fun arPhotoSourceCrop(viewport: IntRect, surface: ArPhotoSurfaceGeometry): IntRect? {
    if (viewport.width <= 0 || viewport.height <= 0 || surface.width <= 0 || surface.height <= 0 ||
        surface.bufferWidth <= 0 || surface.bufferHeight <= 0) return null
    val left = viewport.left - surface.left
    val top = viewport.top - surface.top
    val right = viewport.right - surface.left
    val bottom = viewport.bottom - surface.top
    // Reject a stale orientation or a different view, rather than stretching a partial intersection.
    if (left < 0 || top < 0 || right > surface.width || bottom > surface.height) return null
    val scaleX = surface.bufferWidth.toDouble() / surface.width
    val scaleY = surface.bufferHeight.toDouble() / surface.height
    return IntRect(
        floor(left * scaleX).toInt().coerceIn(0, surface.bufferWidth),
        floor(top * scaleY).toInt().coerceIn(0, surface.bufferHeight),
        ceil(right * scaleX).toInt().coerceIn(0, surface.bufferWidth),
        ceil(bottom * scaleY).toInt().coerceIn(0, surface.bufferHeight),
    ).takeIf { it.width > 0 && it.height > 0 }
}

/** Inspect unlabelled pixels: a position label must not make an empty capture look valid. */
internal fun isBlankArPhoto(samples: IntArray): Boolean = samples.isEmpty() || samples.all { pixel ->
    pixel ushr 24 == 0 || ((pixel ushr 16 and 255) <= 3 && (pixel ushr 8 and 255) <= 3 && (pixel and 255) <= 3)
}
