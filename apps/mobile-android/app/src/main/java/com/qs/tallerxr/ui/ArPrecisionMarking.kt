package com.qs.tallerxr.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import com.qs.tallerxr.ar.CapturedImageFit

internal const val PRECISION_HOLD_MILLIS = 500L

internal enum class MagnifierZoom(val factor: Int) {
    TWO(2), THREE(3), FIVE(5);

    val label: String get() = "${factor}×"

    fun sampleSize(imageSize: IntSize, bufferScale: Offset = Offset(1f, 1f)): IntSize = IntSize(
        (imageSize.width / factor.toFloat() * bufferScale.x).roundToInt().coerceAtLeast(1),
        (imageSize.height / factor.toFloat() * bufferScale.y).roundToInt().coerceAtLeast(1),
    )

    companion object {
        fun fromFactor(factor: Int): MagnifierZoom = entries.firstOrNull { it.factor == factor } ?: THREE
    }
}

/** One touch owns one mark. Movement never bypasses the hold delay or shifts the target. */
internal class PrecisionMarkGesture(initialPosition: Offset, private val startedAtMillis: Long) {
    var position = initialPosition
        private set
    var precise = false
        private set
    var pressed = true
        private set

    fun millisUntilPrecision(nowMillis: Long): Long =
        (PRECISION_HOLD_MILLIS - (nowMillis - startedAtMillis)).coerceAtLeast(0L)

    fun advanceTime(nowMillis: Long) {
        if (pressed && millisUntilPrecision(nowMillis) == 0L) precise = true
    }

    fun move(position: Offset, nowMillis: Long) {
        if (!pressed) return
        this.position = position
        advanceTime(nowMillis)
    }

    fun release(position: Offset, nowMillis: Long): Offset? {
        if (!pressed) return null
        move(position, nowMillis)
        pressed = false
        return this.position
    }

    fun cancel() { pressed = false }
}

internal data class MagnifierSample(val source: IntRect, val targetFraction: Offset)

/** Keep the selected pixel exact when the source rectangle has to move at an image edge. */
internal fun magnifierSample(point: Offset, sourceSize: IntSize, sampleSize: IntSize): MagnifierSample {
    require(sourceSize.width > 0 && sourceSize.height > 0)
    val width = sampleSize.width.coerceIn(1, sourceSize.width)
    val height = sampleSize.height.coerceIn(1, sourceSize.height)
    val x = point.x.coerceIn(0f, (sourceSize.width - 1).toFloat())
    val y = point.y.coerceIn(0f, (sourceSize.height - 1).toFloat())
    val left = (x - width / 2f).roundToInt().coerceIn(0, sourceSize.width - width)
    val top = (y - height / 2f).roundToInt().coerceIn(0, sourceSize.height - height)
    return MagnifierSample(
        IntRect(left, top, left + width, top + height),
        Offset((x - left) / width, (y - top) / height),
    )
}

internal fun magnifierWindowPosition(
    point: Offset,
    viewport: IntSize,
    lens: IntSize,
    gap: Int,
    margin: Int,
): IntOffset {
    val maxX = (viewport.width - lens.width - margin).coerceAtLeast(margin)
    val maxY = (viewport.height - lens.height - margin).coerceAtLeast(margin)
    val x = (point.x - lens.width / 2f).roundToInt().coerceIn(margin, maxX)
    val above = point.y.roundToInt() - gap - lens.height
    val below = point.y.roundToInt() + gap
    if (above >= margin) return IntOffset(x, above.coerceAtMost(maxY))
    if (below <= maxY) return IntOffset(x, below.coerceAtLeast(margin))
    // On short landscape viewports, place the lens beside the finger instead.
    val side = if (point.x < viewport.width / 2f) maxX else margin
    return IntOffset(side, (point.y - lens.height / 2f).roundToInt().coerceIn(margin, maxY))
}

/** [cursor] is the viewport point the crop was centred on; the copy may trail the finger by a frame. */
private data class MagnifierImage(
    val bitmap: ImageBitmap,
    val targetFraction: Offset,
    val source: IntRect? = null,
    val cursor: Offset,
)

/**
 * Maps viewport points into the lens canvas, so overlays drawn by Compose (marks, lines, corners)
 * can be drawn again at the magnified scale: the copied surface never contains them.
 */
internal class LensMapping(private val cursor: Offset, private val centre: Offset, private val factor: Float, val size: Size) {
    fun map(point: Offset): Offset = lensPoint(point, cursor, centre, factor)

    fun contains(point: Offset): Boolean =
        point.x >= 0f && point.y >= 0f && point.x <= size.width && point.y <= size.height
}

/**
 * Copies only a small region of the AR SurfaceView, not the Compose window: the camera is on a
 * separate surface and a window/Modifier magnifier can magnify only the UI or its own overlay.
 * PixelCopy works from API 26, including our API 28 minimum. Images stay in memory only.
 */
@Composable
internal fun ArPrecisionMagnifier(
    hostView: View,
    viewport: Rect,
    position: Offset,
    zoom: MagnifierZoom,
    title: String,
    hint: String = "Loslassen zum Setzen",
    frozenImage: ImageBitmap? = null,
    capturedViewport: IntSize? = null,
    frozenFit: CapturedImageFit? = null,
    overlay: (DrawScope.(LensMapping) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val gap = with(density) { 44.dp.roundToPx() }
    val captionHeight = with(density) { 20.dp.roundToPx() }
    val imageSize = IntSize(
        with(density) { 144.dp.roundToPx() }.coerceAtMost((viewport.width() - 2 * margin).coerceAtLeast(1)),
        with(density) { 108.dp.roundToPx() }.coerceAtMost((viewport.height() - 2 * margin - 2 * captionHeight).coerceAtLeast(1)),
    )
    val lensSize = IntSize(imageSize.width, imageSize.height + 2 * captionHeight)
    val location = magnifierWindowPosition(
        position, IntSize(viewport.width(), viewport.height()), lensSize, gap, margin,
    )
    val currentPosition = rememberUpdatedState(position)
    var image by remember(hostView, viewport, imageSize, zoom) { mutableStateOf<MagnifierImage?>(null) }
    LaunchedEffect(hostView, viewport, imageSize, zoom, frozenImage) {
        if (frozenImage != null) return@LaunchedEffect
        var sourceView: SurfaceView? = null
        val sourceLocation = IntArray(2)
        while (isActive) {
            val source = sourceView?.takeIf { it.isShown && it.holder.surface.isValid }
                ?: findArSurface(hostView.rootView, viewport).also { sourceView = it }
            if (source != null && source.width > 0 && source.height > 0) {
                source.getLocationInWindow(sourceLocation)
                val buffer = source.holder.surfaceFrame
                if (buffer.width() > 0 && buffer.height() > 0) {
                    val scaleX = buffer.width().toFloat() / source.width
                    val scaleY = buffer.height().toFloat() / source.height
                    val point = currentPosition.value
                    val sample = magnifierSample(
                        Offset(
                            (point.x + viewport.left - sourceLocation[0]) * scaleX,
                            (point.y + viewport.top - sourceLocation[1]) * scaleY,
                        ),
                        IntSize(buffer.width(), buffer.height()),
                        zoom.sampleSize(imageSize, Offset(scaleX, scaleY)),
                    )
                    val bitmap = copyMagnifierRegion(source, sample.source)
                    image = bitmap?.let { MagnifierImage(it.asImageBitmap(), sample.targetFraction, cursor = point) }
                } else image = null
            } else image = null
            // About 30 updates/s, only while the finger is held. A small unscaled crop avoids
            // full-screen captures and bitmap churn; Compose enlarges it on the GPU.
            delay(32L)
        }
    }

    val frozenContent = if (frozenImage != null && capturedViewport != null) {
        val fit = frozenFit ?: CapturedImageFit(capturedViewport.width, capturedViewport.height, viewport.width(), viewport.height())
        fit.imagePoint(position.x, position.y)?.let { point ->
            val scaleX = frozenImage.width.toFloat() / capturedViewport.width / fit.scale
            val scaleY = frozenImage.height.toFloat() / capturedViewport.height / fit.scale
            val sample = magnifierSample(
                Offset(point[0] * frozenImage.width / capturedViewport.width,
                    point[1] * frozenImage.height / capturedViewport.height),
                IntSize(frozenImage.width, frozenImage.height), zoom.sampleSize(imageSize, Offset(scaleX, scaleY)))
            MagnifierImage(frozenImage, sample.targetFraction, sample.source, position)
        }
    } else null
    val displayedImage = if (frozenImage != null) frozenContent else image

    Surface(
        modifier = Modifier.offset { location }.width(with(density) { imageSize.width.toDp() }),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF071019),
        border = BorderStroke(1.dp, Color(0xFFFFD166)),
        shadowElevation = 8.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                modifier = Modifier.height(20.dp),
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFD166),
            )
            Canvas(Modifier.fillMaxWidth().height(with(density) { imageSize.height.toDp() })) {
                val content = displayedImage ?: return@Canvas
                drawImage(
                    content.bitmap,
                    srcOffset = content.source?.topLeft ?: IntOffset.Zero,
                    srcSize = content.source?.size ?: IntSize(content.bitmap.width, content.bitmap.height),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Low,
                )
                val centre = Offset(content.targetFraction.x * size.width, content.targetFraction.y * size.height)
                overlay?.let { drawOverlay ->
                    clipRect { drawOverlay(LensMapping(content.cursor, centre, zoom.factor.toFloat(), size)) }
                }
                for ((color, stroke) in listOf(Color.Black to 3.dp.toPx(), Color(0xFFFFD166) to 1.dp.toPx())) {
                    for (arm in listOf(Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f))) {
                        drawLine(color, centre + arm * 3.dp.toPx(), centre + arm * 13.dp.toPx(), stroke)
                    }
                }
                drawCircle(Color(0xFFFFD166), 1.dp.toPx(), centre)
            }
            Text(
                if (displayedImage == null) "Zoom wird geladen…" else hint,
                modifier = Modifier.height(20.dp),
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

/** The exclusive viewport host guarantees only one visible AR surface at these window bounds. */
private fun findArSurface(view: View, viewport: Rect): SurfaceView? {
    if (view is SurfaceView && view.isShown && view.holder.surface.isValid) {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        if (viewport.centerX() in location[0] until location[0] + view.width &&
            viewport.centerY() in location[1] until location[1] + view.height
        ) return view
    }
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            findArSurface(view.getChildAt(index), viewport)?.let { return it }
        }
    }
    return null
}

private suspend fun copyMagnifierRegion(source: SurfaceView, crop: IntRect): Bitmap? =
    suspendCancellableCoroutine { continuation ->
        val bitmap = Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                source,
                Rect(crop.left, crop.top, crop.right, crop.bottom),
                bitmap,
                { result ->
                    if (!continuation.isActive) bitmap.recycle()
                    else if (result == PixelCopy.SUCCESS) {
                        continuation.resume(bitmap) { _, unconsumed, _ -> unconsumed?.recycle() }
                    } else {
                        bitmap.recycle()
                        continuation.resume(null)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (_: IllegalArgumentException) {
            // Surface disappeared during a 3D/AR transition. Never retain or publish a stale crop.
            bitmap.recycle()
            if (continuation.isActive) continuation.resume(null)
        }
    }
