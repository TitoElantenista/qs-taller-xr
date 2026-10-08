package com.qs.tallerxr.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qs.tallerxr.ar.AlignmentMarking
import com.qs.tallerxr.ar.CorrespondenceLink
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class ReferencePoint(val label: Int, val x: Float, val y: Float, val visible: Boolean)

/** Pixels and labels from the SAME stationary 3D view; never reprojected with the AR camera. */
internal class ModelReferenceImage(
    val image: ImageBitmap,
    val points: List<ReferencePoint>,
    val cameraQuaternion: FloatArray,
)

// Enough pixels to zoom into a complex part on the card or the enlarged view without blurring
// its corners; one ARGB copy of at most about 8 MB, kept only while AR is open.
private const val REFERENCE_MAX_PIXELS = 1_440f

/** How much the card's «Detail» view enlarges the part around the point to be marked. */
internal const val REFERENCE_DETAIL_ZOOM = 2.5f

internal fun referencePointInCrop(point: ReferencePoint, left: Int, top: Int, width: Int, height: Int): ReferencePoint =
    point.copy(x = (point.x - left) / width, y = (point.y - top) / height)

internal suspend fun captureModelReference(
    root: View,
    viewport: Rect,
    crop: Rect,
    points: List<ReferencePoint>,
    cameraQuaternion: FloatArray,
): ModelReferenceImage {
    val source = findModelSurface(root, viewport) ?: error("Die 3D-Oberfläche ist noch nicht bereit.")
    val location = IntArray(2).also(source::getLocationInWindow)
    val sourceCrop = Rect(crop).apply { offset(viewport.left - location[0], viewport.top - location[1]) }
    check(sourceCrop.left >= 0 && sourceCrop.top >= 0 && sourceCrop.right <= source.width && sourceCrop.bottom <= source.height)
    val scale = min(1f, REFERENCE_MAX_PIXELS / maxOf(crop.width(), crop.height()))
    val bitmap = suspendCancellableCoroutine { continuation ->
        val target = Bitmap.createBitmap((crop.width() * scale).roundToInt().coerceAtLeast(1),
            (crop.height() * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(source, sourceCrop, target, { result ->
                if (!continuation.isActive) target.recycle()
                else if (result == PixelCopy.SUCCESS) {
                    continuation.resume(target) { _, unconsumed, _ -> unconsumed.recycle() }
                } else {
                    target.recycle()
                    continuation.resumeWithException(IllegalStateException("3D-Vorschau konnte nicht kopiert werden ($result)."))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (error: IllegalArgumentException) {
            target.recycle()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
    return ModelReferenceImage(bitmap.asImageBitmap(), points.map {
        referencePointInCrop(it, crop.left, crop.top, crop.width(), crop.height())
    }, cameraQuaternion.copyOf())
}

private fun findModelSurface(view: View, viewport: Rect): SurfaceView? {
    if (view is SurfaceView && view.isShown && view.holder.surface.isValid) {
        val location = IntArray(2).also(view::getLocationInWindow)
        if (viewport.centerX() in location[0] until location[0] + view.width &&
            viewport.centerY() in location[1] until location[1] + view.height) return view
    }
    if (view is ViewGroup) for (index in 0 until view.childCount) {
        findModelSurface(view.getChildAt(index), viewport)?.let { return it }
    }
    return null
}

/** Image pixel → canvas pixel: `canvas = offset + image * scale`. */
internal data class ReferenceViewTransform(val scale: Float, val offset: Offset) {
    fun map(imageX: Float, imageY: Float): Offset = Offset(offset.x + imageX * scale, offset.y + imageY * scale)
}

/**
 * Fits the reference image into [canvas] with [insetPx] around it, enlarged [zoom] times around
 * the image pixel [focus].  The enlarged image never leaves an empty strip on a side it could
 * still cover, so the focused corner may sit off centre near the border of the part.
 */
internal fun referenceViewTransform(
    image: Size,
    canvas: Size,
    insetPx: Float,
    zoom: Float = 1f,
    focus: Offset? = null,
): ReferenceViewTransform {
    if (image.width <= 0f || image.height <= 0f || canvas.width <= 0f || canvas.height <= 0f) {
        return ReferenceViewTransform(0f, Offset.Zero)
    }
    val fit = min((canvas.width - 2 * insetPx) / image.width, (canvas.height - 2 * insetPx) / image.height)
        .coerceAtLeast(0f)
    val scale = fit * zoom.coerceAtLeast(1f)
    fun axis(imageLength: Float, canvasLength: Float, focusAt: Float?): Float {
        val shown = imageLength * scale
        if (focusAt == null || shown <= canvasLength - 2 * insetPx) return (canvasLength - shown) / 2f
        val centred = canvasLength / 2f - focusAt * scale
        return centred.coerceIn(canvasLength - insetPx - shown, insetPx)
    }
    return ReferenceViewTransform(
        scale,
        Offset(axis(image.width, canvas.width, focus?.x), axis(image.height, canvas.height, focus?.y)),
    )
}

/**
 * Unit direction from a marker to its number bubble: away from the other markers nearby, so the
 * bubble never covers the corner it names nor its neighbours.  Up and right when alone.
 */
internal fun referenceLabelDirection(points: List<Offset>, index: Int, neighbourhoodPx: Float): Offset {
    val own = points[index]
    var x = 0f
    var y = 0f
    points.forEachIndexed { other, point ->
        if (other == index) return@forEachIndexed
        val dx = own.x - point.x
        val dy = own.y - point.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1e-3f || length > neighbourhoodPx) return@forEachIndexed
        // Closer neighbours push harder.
        x += dx / (length * length)
        y += dy / (length * length)
    }
    val length = sqrt(x * x + y * y)
    if (length < 1e-6f) return Offset(0.7071f, -0.7071f)
    return Offset(x / length, y / length)
}

/** The point to look at: the one to mark next, or the last one of a complete round. */
internal fun referenceFocusLabel(marking: AlignmentMarking?, points: List<ReferencePoint>): Int? {
    val visible = points.filter { it.visible }.map(ReferencePoint::label)
    marking?.currentLabel?.takeIf { it in visible }?.let { return it }
    return marking?.stationSightings?.lastOrNull()?.label?.takeIf { it in visible } ?: visible.firstOrNull()
}

private enum class ReferenceMarkerState { NEXT, MARKED, OTHER }

private fun ReferenceMarkerState.color(): Color = when (this) {
    ReferenceMarkerState.NEXT -> Color(0xFFFFB703)
    ReferenceMarkerState.MARKED -> Color(0xFF5AD67D)
    ReferenceMarkerState.OTHER -> Color(0xFF8ED8F8)
}

/**
 * Draws the reference with precise markers: an open ring with a centre dot exactly on the chosen
 * corner and the number in a bubble beside it, so neither the number nor a filled disc hides the
 * corner on a complex part.  Markers keep their size while the image is enlarged.
 */
private fun DrawScope.drawReference(
    reference: ModelReferenceImage,
    marking: AlignmentMarking?,
    transform: ReferenceViewTransform,
    labelPaint: Paint,
) {
    val image = reference.image
    clipRect {
        drawImage(
            image,
            dstOffset = IntOffset(transform.offset.x.roundToInt(), transform.offset.y.roundToInt()),
            dstSize = IntSize((image.width * transform.scale).roundToInt(), (image.height * transform.scale).roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        val shown = reference.points.filter { it.visible }
        val positions = shown.map { transform.map(it.x * image.width, it.y * image.height) }
        val byLabel = shown.map(ReferencePoint::label).zip(positions).toMap()
        val marked = marking?.stationSightings?.map { it.label }.orEmpty()
        val ringPx = 5.5.dp.toPx()
        // Lines first, stopping at the rings so the corners stay visible.
        marking?.lines.orEmpty().forEach { link: CorrespondenceLink ->
            val start = byLabel[link.first] ?: return@forEach
            val end = byLabel[link.second] ?: return@forEach
            val segment = trimmedSegment(start, end, ringPx + 1.dp.toPx(), ringPx + 1.dp.toPx()) ?: return@forEach
            drawLine(Color(0xD0071019), segment.first, segment.second, 3.5.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0xFFFFB703), segment.first, segment.second, 1.5.dp.toPx(), StrokeCap.Round)
        }
        labelPaint.textSize = 10.sp.toPx()
        val metrics = labelPaint.fontMetrics
        val bubblePx = 8.dp.toPx()
        shown.forEachIndexed { index, point ->
            val centre = positions[index]
            // Enlarged views crop the part: a marker outside would only leave its number stuck
            // to the border.  Its line still shows the direction.
            if (centre.x < -ringPx || centre.y < -ringPx || centre.x > size.width + ringPx ||
                centre.y > size.height + ringPx) return@forEachIndexed
            val state = when (point.label) {
                marking?.currentLabel -> ReferenceMarkerState.NEXT
                in marked -> ReferenceMarkerState.MARKED
                else -> ReferenceMarkerState.OTHER
            }
            val color = state.color()
            val direction = referenceLabelDirection(positions, index, 42.dp.toPx())
            val bubble = Offset(
                (centre.x + direction.x * 17.dp.toPx()).coerceIn(bubblePx, (size.width - bubblePx).coerceAtLeast(bubblePx)),
                (centre.y + direction.y * 17.dp.toPx()).coerceIn(bubblePx, (size.height - bubblePx).coerceAtLeast(bubblePx)),
            )
            trimmedSegment(centre, bubble, ringPx, bubblePx)?.let { (from, to) ->
                drawLine(Color(0xD0071019), from, to, 3.dp.toPx())
                drawLine(color, from, to, 1.2.dp.toPx())
            }
            if (state == ReferenceMarkerState.NEXT) {
                drawCircle(Color(0xD0071019), ringPx * 2.1f, centre, style = Stroke(4.dp.toPx()))
                drawCircle(color, ringPx * 2.1f, centre, style = Stroke(2.dp.toPx()))
            }
            drawCircle(Color(0xE0071019), ringPx, centre, style = Stroke(3.5.dp.toPx()))
            drawCircle(color, ringPx, centre, style = Stroke(1.8.dp.toPx()))
            drawCircle(Color(0xE0071019), 1.8.dp.toPx(), centre)
            drawCircle(color, 1.1.dp.toPx(), centre)
            drawCircle(Color(0xE0071019), bubblePx + 1.5.dp.toPx(), bubble)
            drawCircle(color, bubblePx, bubble)
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(
                    point.label.toString(), bubble.x, bubble.y - (metrics.ascent + metrics.descent) / 2f, labelPaint,
                )
            }
        }
    }
}

/**
 * Makes this element the target of every touch inside it, so the camera or photo underneath never
 * receives a mark from it.  Compose stops hit testing at the topmost sibling with pointer input;
 * nothing is consumed here, because consuming in the parent would cancel the taps of the buttons
 * and gestures inside it as soon as the finger moves a little.
 */
private fun Modifier.blockTouchesBelow(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun rememberReferenceLabelPaint(): Paint = remember {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(255, 7, 19, 28)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
}

/**
 * Shared by live AR and frozen photos. Only progress colours change, never its viewpoint.
 * «Detail» enlarges the region around the point to be marked; tapping the image opens it large.
 */
@Composable
internal fun ModelReferenceCard(
    reference: ModelReferenceImage,
    marking: AlignmentMarking?,
    detail: Boolean,
    onDetailChange: (Boolean) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier,
) {
    val labelPaint = rememberReferenceLabelPaint()
    val focusLabel = referenceFocusLabel(marking, reference.points)
    // The AR view recomposes often; a new key would restart the tap detector mid-tap.
    val currentOnExpand = rememberUpdatedState(onExpand)
    Surface(modifier.blockTouchesBelow(), shape = MaterialTheme.shapes.small) {
        Column {
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (detail && focusLabel != null) "3D · Detail P$focusLabel" else "3D · Punkthilfe",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
                IconButton(onClick = { onDetailChange(!detail) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (detail) Icons.Default.ZoomOut else Icons.Default.ZoomIn,
                        contentDescription = if (detail) "Ganzes Bauteil zeigen" else "Den zu markierenden Punkt vergrößern",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Box(
                Modifier.weight(1f).fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { currentOnExpand.value() })
                },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val focus = reference.points.firstOrNull { it.label == focusLabel }
                        ?.takeIf { detail }
                        ?.let { Offset(it.x * reference.image.width, it.y * reference.image.height) }
                    val transform = referenceViewTransform(
                        Size(reference.image.width.toFloat(), reference.image.height.toFloat()),
                        size,
                        insetPx = 19.dp.toPx(),
                        zoom = if (focus != null) REFERENCE_DETAIL_ZOOM else 1f,
                        focus = focus,
                    )
                    drawReference(reference, marking, transform, labelPaint)
                }
                Icon(
                    Icons.Default.OpenInFull,
                    contentDescription = "Punkthilfe vergrößern",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).size(14.dp)
                        .background(Color(0x99071019), MaterialTheme.shapes.extraSmall),
                )
            }
            val hidden = reference.points.filterNot { it.visible }.map { "P${it.label}" }
            if (hidden.isNotEmpty()) Text("Verdeckt: ${hidden.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

/**
 * The same reference, large: pinch to zoom, drag to move, double tap to toggle between the whole
 * part and the point to be marked.  It shows the captured 3D image only and never takes a mark.
 */
@Composable
internal fun ModelReferenceViewer(
    reference: ModelReferenceImage,
    marking: AlignmentMarking?,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    BackHandler(onBack = onClose)
    val labelPaint = rememberReferenceLabelPaint()
    val focusLabel = referenceFocusLabel(marking, reference.points)
    val imageSize = Size(reference.image.width.toFloat(), reference.image.height.toFloat())
    var canvasSize by remember(reference) { mutableStateOf(Size.Zero) }
    // Zoom relative to the fitted image, and the image pixel shown at the canvas centre.
    var zoom by remember(reference, focusLabel) { mutableFloatStateOf(if (focusLabel != null) 3f else 1f) }
    var centre by remember(reference, focusLabel) {
        mutableStateOf(
            reference.points.firstOrNull { it.label == focusLabel }
                ?.let { Offset(it.x * imageSize.width, it.y * imageSize.height) }
                ?: Offset(imageSize.width / 2f, imageSize.height / 2f),
        )
    }
    fun showWhole() {
        zoom = 1f
        centre = Offset(imageSize.width / 2f, imageSize.height / 2f)
    }
    fun showFocus() {
        val point = reference.points.firstOrNull { it.label == focusLabel } ?: return showWhole()
        zoom = 3f
        centre = Offset(point.x * imageSize.width, point.y * imageSize.height)
    }
    // Nothing below the viewer may receive a touch while it is open.
    Box(modifier.background(Color(0xE6071019)).blockTouchesBelow()) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "3D-Punkthilfe" + (focusLabel?.let { " · als Nächstes P$it" } ?: ""),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = ::showWhole, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("Ganzes Bauteil", color = Color.White, maxLines = 1)
                }
                if (focusLabel != null) {
                    OutlinedButton(onClick = ::showFocus, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("P$focusLabel", color = Color.White, maxLines = 1)
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Punkthilfe schließen", tint = Color.White)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Canvas(
                    Modifier.fillMaxSize()
                        .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                        .pointerInput(reference, focusLabel) {
                            detectTapGestures(onDoubleTap = { tap ->
                                if (zoom > 1.5f) {
                                    showWhole()
                                } else {
                                    val current = referenceViewerTransform(imageSize, canvasSize, zoom, centre)
                                    zoom = 3f
                                    centre = Offset(
                                        (tap.x - current.offset.x) / current.scale,
                                        (tap.y - current.offset.y) / current.scale,
                                    )
                                }
                            })
                        }
                        .pointerInput(reference, focusLabel) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val current = referenceViewerTransform(imageSize, canvasSize, zoom, centre)
                                if (current.scale > 0f) {
                                    zoom = (zoom * gestureZoom).coerceIn(1f, 12f)
                                    val moved = centre - pan / current.scale
                                    centre = Offset(
                                        moved.x.coerceIn(0f, imageSize.width),
                                        moved.y.coerceIn(0f, imageSize.height),
                                    )
                                }
                            }
                        },
                ) {
                    drawReference(reference, marking, referenceViewerTransform(imageSize, size, zoom, centre), labelPaint)
                }
            }
            Text(
                "Zwei Finger: vergrößern · ziehen: verschieben · doppelt tippen: Punkt/ganzes Bauteil. " +
                    "Orange: als Nächstes · grün: in dieser Ansicht markiert.",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Viewer transform: [zoom] times the fitted scale, with image pixel [centre] at the canvas centre. */
internal fun referenceViewerTransform(image: Size, canvas: Size, zoom: Float, centre: Offset): ReferenceViewTransform {
    val fitted = referenceViewTransform(image, canvas, insetPx = 0f)
    if (fitted.scale <= 0f || zoom <= 1.01f) return fitted
    val scale = fitted.scale * zoom
    // Keep the image on screen: its centre point may not wander past the image border.
    val clamped = Offset(centre.x.coerceIn(0f, image.width), centre.y.coerceIn(0f, image.height))
    return ReferenceViewTransform(
        scale,
        Offset(canvas.width / 2f - clamped.x * scale, canvas.height / 2f - clamped.y * scale),
    )
}
