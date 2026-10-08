package com.qs.tallerxr.ar

import com.google.ar.core.Pose
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where the fitted CAD appears on one captured photo.  Every position is a pixel of the captured
 * viewport, the same space the photo's marks use, so the UI maps them with the photo's own fit.
 *
 * [referenceFromModel] is the pose of a [CorrespondenceSolution]: the solver works in the frame of
 * the reference anchor, which is also the frame the photo geometry was stored in.
 */
internal object PhotoProjection {
    /** A landmark expected at ([x], [y]) within [radiusPx], derived from the fit's own accuracy. */
    data class Prediction(val label: Int, val x: Float, val y: Float, val radiusPx: Float)

    fun pose(solution: CorrespondenceSolution): Pose = Pose(solution.translation, solution.quaternionXyzw)

    fun pixel(geometry: CapturedAlignmentView, referenceFromModel: Pose, modelPoint: FloatArray): FloatArray? =
        ScreenGeometry.projectPixel(
            geometry.viewProjection,
            referenceFromModel.transformPoint(modelPoint),
            geometry.width,
            geometry.height,
        )

    /**
     * Where [modelPoint] should appear on a new photo.  The circle grows with the fit's expected
     * error seen from this camera (two sigma), so a fit from one view shows a wide, honest area
     * instead of a precise looking spot.  Null when the point is behind the camera, far outside
     * the photo, or the area would be larger than [maximumRadiusPx].
     */
    fun prediction(
        geometry: CapturedAlignmentView,
        referenceFromModel: Pose,
        label: Int,
        modelPoint: FloatArray,
        expectedErrorMm: Float,
        minimumRadiusPx: Float,
        maximumRadiusPx: Float,
    ): Prediction? {
        if (!expectedErrorMm.isFinite()) return null
        val world = referenceFromModel.transformPoint(modelPoint)
        val pixel = ScreenGeometry.projectPixel(geometry.viewProjection, world, geometry.width, geometry.height)
            ?: return null
        val distance = distance(world, geometry.cameraOrigin)
        if (distance < 0.05f) return null
        val radius = max(minimumRadiusPx, 2f * expectedErrorMm / 1000f * geometry.focalLengthPx / distance)
        if (radius > maximumRadiusPx) return null
        if (pixel[0] < -radius || pixel[1] < -radius ||
            pixel[0] > geometry.width + radius || pixel[1] > geometry.height + radius) return null
        return Prediction(label, pixel[0], pixel[1], radius)
    }

    /**
     * The model's feature edges as seen on the photo, as pixel segments `x1 y1 x2 y2`.  Each edge
     * is sampled every [stepM]; a stretch hidden behind the part itself or behind the camera is
     * left out, so the overlay shows what the photo can show.  Without [visibility] every edge
     * is returned.
     */
    fun visibleEdges(
        geometry: CapturedAlignmentView,
        referenceFromModel: Pose,
        featureEdges: FloatArray,
        visibility: LandmarkVisibility?,
        stepM: Float,
        maximumSamples: Int = 12_000,
    ): FloatArray {
        require(stepM > 0f)
        var totalLength = 0f
        var index = 0
        while (index + 5 < featureEdges.size) {
            totalLength += edgeLength(featureEdges, index)
            index += 6
        }
        // A very long or detailed part is sampled more coarsely instead of freezing the UI.
        val step = max(stepM, totalLength / maximumSamples)
        val camera = referenceFromModel.inverse().transformPoint(geometry.cameraOrigin)
        val segments = ArrayList<Float>()
        index = 0
        while (index + 5 < featureEdges.size) {
            val length = edgeLength(featureEdges, index)
            val count = max(1, ceil(length / step).toInt())
            var previous: FloatArray? = null
            for (sample in 0..count) {
                val t = sample.toFloat() / count
                val point = FloatArray(3) { axis ->
                    featureEdges[index + axis] + (featureEdges[index + 3 + axis] - featureEdges[index + axis]) * t
                }
                val shown = visibility?.isVisible(camera, towards(point, camera, 0.0005f)) ?: true
                val pixel = if (shown) pixel(geometry, referenceFromModel, point) else null
                if (pixel != null && previous != null) {
                    segments += previous[0]; segments += previous[1]; segments += pixel[0]; segments += pixel[1]
                }
                previous = pixel
            }
            index += 6
        }
        return segments.toFloatArray()
    }

    private fun edgeLength(edges: FloatArray, index: Int): Float {
        val dx = edges[index + 3] - edges[index]
        val dy = edges[index + 4] - edges[index + 1]
        val dz = edges[index + 5] - edges[index + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * An edge point lies on the very faces that meet there.  Testing a point half a millimetre
     * towards the camera keeps those faces from counting as occluders through rounding.
     */
    private fun towards(point: FloatArray, camera: FloatArray, offsetM: Float): FloatArray {
        val length = distance(point, camera)
        if (length < offsetM * 2f) return point
        return FloatArray(3) { point[it] + (camera[it] - point[it]) * (offsetM / length) }
    }

    private fun distance(first: FloatArray, second: FloatArray): Float {
        val dx = first[0] - second[0]
        val dy = first[1] - second[1]
        val dz = first[2] - second[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
