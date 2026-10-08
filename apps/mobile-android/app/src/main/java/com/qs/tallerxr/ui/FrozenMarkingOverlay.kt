package com.qs.tallerxr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.qs.tallerxr.ar.AlignmentMarking
import com.qs.tallerxr.ar.CapturedAlignmentView
import com.qs.tallerxr.ar.CapturedImageFit
import com.qs.tallerxr.ar.PhotoProjection
import com.qs.tallerxr.ar.WorldRay
import kotlin.math.roundToInt

internal class FrozenMarkingFrame(
    val image: ImageBitmap,
    val geometry: CapturedAlignmentView,
)

@Composable
internal fun FrozenCameraImage(frame: FrozenMarkingFrame, fit: CapturedImageFit) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color.Black)
        drawImage(frame.image, dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
            dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()), filterQuality = FilterQuality.Medium)
    }
}

/** Where the marks of this round sit on the displayed photo, through the same fit as the touch. */
internal fun frozenMarkPositions(
    frame: FrozenMarkingFrame,
    marking: AlignmentMarking,
    fit: CapturedImageFit,
): List<Pair<Int, Offset>> = marking.stationSightings.mapNotNull { sighting ->
    frame.geometry.pixel(WorldRay(sighting.rayOrigin, sighting.rayDirection))?.let { pixel ->
        val point = fit.viewportPoint(pixel[0], pixel[1])
        sighting.label to Offset(point[0], point[1])
    }
}

/**
 * Marks and lines of the round on the frozen photo.  A point being placed again keeps its old
 * mark dimmed and its lines are drawn towards the finger instead, so the photo shows the change.
 * [predictions] are the dashed areas where the fit expects the points still to be marked.
 */
@Composable
internal fun FrozenMarkingOverlay(
    frame: FrozenMarkingFrame,
    marking: AlignmentMarking,
    fit: CapturedImageFit,
    predictions: List<PhotoProjection.Prediction> = emptyList(),
) {
    val dots = remember(frame, marking, fit, predictions) {
        AlignmentOverlayState().also { state ->
            val replaced = marking.selectedLabel?.takeIf(marking::isMarkedInStation)
            val marks = frozenMarkPositions(frame, marking, fit).map { (label, point) ->
                AlignmentDot(point.x, point.y, label,
                    if (label == replaced) AlignmentDotStyle.CAMERA_MARK_OLD else AlignmentDotStyle.CAMERA_MARK)
            }
            val expected = predictions.map { prediction ->
                val point = fit.viewportPoint(prediction.x, prediction.y)
                AlignmentDot(point[0], point[1], prediction.label,
                    if (prediction.label == marking.currentLabel) AlignmentDotStyle.PREDICTED_NEXT else AlignmentDotStyle.PREDICTED,
                    radiusPx = prediction.radiusPx * fit.scale)
            }
            val lines = marking.stationLines.filterNot { replaced != null && it.touches(replaced) }
            state.update(expected + marks, alignmentLines(marks, lines, AlignmentLineStyle.MARK))
        }
    }
    AlignmentDotsOverlay(dots, visible = true)
}
