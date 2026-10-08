package com.qs.tallerxr.ar

import com.google.ar.core.Pose
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole operator path without a phone: a synthetic ARCore camera projects the real corners to
 * pixels, the "operator" taps those pixels from two standing positions, ARCore corrects its world
 * frame in between, and the fitted model must land on the real part.
 */
class AlignmentPipelineTest {
    // Landscape, as an operator holds the phone in front of a long beam.
    private val width = 2340
    private val height = 1080
    private val focalPx = 1_800f

    private fun projection(): FloatArray {
        val near = 0.05f
        val far = 60f
        return FloatArray(16).also {
            it[0] = 2f * focalPx / width
            it[5] = 2f * focalPx / height
            it[10] = -(far + near) / (far - near)
            it[11] = -1f
            it[14] = -2f * far * near / (far - near)
        }
    }

    private fun axisAngle(x: Float, y: Float, z: Float, degrees: Float): FloatArray {
        val half = Math.toRadians(degrees.toDouble()) / 2.0
        val length = sqrt(x * x + y * y + z * z)
        val sine = sin(half).toFloat() / length
        return floatArrayOf(x * sine, y * sine, z * sine, cos(half).toFloat())
    }

    /** Camera at [position] turned by yaw then pitch; it looks along its own -Z. */
    private fun camera(position: FloatArray, yawDegrees: Float, pitchDegrees: Float): Pose =
        Pose(position, axisAngle(0f, 1f, 0f, yawDegrees))
            .compose(Pose.makeRotation(axisAngle(1f, 0f, 0f, pitchDegrees)))

    private fun viewProjection(cameraPose: Pose): FloatArray {
        val view = FloatArray(16).also { cameraPose.inverse().toMatrix(it, 0) }
        return ScreenGeometry.multiply(projection(), view)
    }

    private val picks = listOf(
        CorrespondencePick(1, floatArrayOf(0f, 0.2f, 0.1f)),
        CorrespondencePick(2, floatArrayOf(2.4f, 0.2f, 0.1f)),
        CorrespondencePick(3, floatArrayOf(2.4f, 0.2f, -0.1f)),
        CorrespondencePick(4, floatArrayOf(0.6f, 0f, -0.1f)),
    )
    private val extents = listOf(0f, 2.4f).flatMap { x ->
        listOf(0f, 0.2f).flatMap { y -> listOf(-0.1f, 0.1f).map { z -> floatArrayOf(x, y, z) } }
    }

    @Test
    fun `tapped pixels from two stations place the model on the real part`() {
        // The real part: a beam lying at an odd angle, about two and a half metres ahead.
        val worldFromModelTrue = Pose(floatArrayOf(-1.1f, 0.7f, -2.6f), axisAngle(0.2f, 1f, 0.1f, 28f))
        val referenceTrue = Pose.makeTranslation(0f, 0.8f, -2.4f)
        // Between the stations ARCore refines its map: every world coordinate shifts together.
        val corrections = listOf(
            Pose.IDENTITY,
            Pose(floatArrayOf(0.06f, -0.015f, 0.09f), axisAngle(0f, 1f, 0f, -3f)),
        )
        val cameras = listOf(
            camera(floatArrayOf(0f, 1.5f, 0f), yawDegrees = 0f, pitchDegrees = -14f),
            camera(floatArrayOf(1.9f, 1.6f, -0.9f), yawDegrees = 40f, pitchDegrees = -17f),
        )

        var marking = AlignmentMarking(labels = picks.map(CorrespondencePick::label))
        val tapped = ArrayList<Triple<Int, Int, FloatArray>>()
        cameras.forEachIndexed { index, cameraTrue ->
            val correction = corrections[index]
            val cameraPose = correction.compose(cameraTrue)
            val worldFromReference = correction.compose(referenceTrue)
            val worldFromModel = correction.compose(worldFromModelTrue)
            val viewProjection = viewProjection(cameraPose)
            val inverse = requireNotNull(ScreenGeometry.invert(viewProjection))
            if (index > 0) marking = marking.nextStation()
            picks.forEach { pick ->
                val pixel = requireNotNull(
                    ScreenGeometry.projectPixel(
                        viewProjection,
                        worldFromModel.transformPoint(pick.modelPoint),
                        width,
                        height,
                    ),
                )
                assertTrue("la esquina debe verse en pantalla", pixel[0] in 0f..width.toFloat())
                assertTrue("la esquina debe verse en pantalla", pixel[1] in 0f..height.toFloat())
                // A finger lands on whole pixels, not on the exact projection.
                val tap = floatArrayOf(pixel[0].roundToInt().toFloat(), pixel[1].roundToInt().toFloat())
                val worldRay = requireNotNull(
                    ScreenGeometry.rayThroughPixel(
                        inverse,
                        cameraPose.translation,
                        tap[0],
                        tap[1],
                        width,
                        height,
                    ),
                )
                val stored = AlignmentFrames.rayInReference(worldFromReference, worldRay)
                marking = marking.marked(
                    CorrespondenceSighting(
                        label = pick.label,
                        station = marking.station,
                        rayOrigin = stored.origin,
                        rayDirection = stored.direction,
                        measuredDistanceM = null,
                        focalLengthPx = focalPx,
                    ),
                )
                tapped += Triple(index, pick.label, tap)
            }
        }

        val solution = (
            ManualCorrespondenceSolver.solve(
                picks = picks,
                sightings = marking.sightings,
                seedQuaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
                fallbackDistanceM = 1.5f,
                modelDiameterM = 2.42f,
                modelExtentPoints = extents,
            ) as CorrespondenceOutcome.Solved
            ).solution
        assertEquals(CorrespondenceQuality.ACCEPTABLE, solution.quality)
        assertTrue(solution.multiView)

        // Posed in the corrected world of the last frame, the model lies on the real part.
        val correction = corrections.last()
        val worldFromModel = AlignmentFrames.worldFromModel(correction.compose(referenceTrue), solution)
        val expected = correction.compose(worldFromModelTrue)
        extents.forEach { point ->
            val a = worldFromModel.transformPoint(point)
            val b = expected.transformPoint(point)
            val errorMm = sqrt((0..2).fold(0f) { sum, axis -> sum + (a[axis] - b[axis]).let { it * it } }) * 1000f
            assertTrue("error de $errorMm mm en un extremo de la pieza", errorMm < 4f)
        }

        // And its labelled points are drawn on the pixels the operator tapped.
        val lastViewProjection = viewProjection(correction.compose(cameras.last()))
        tapped.filter { it.first == cameras.lastIndex }.forEach { (_, label, tap) ->
            val pick = picks.first { it.label == label }
            val pixel = requireNotNull(
                ScreenGeometry.projectPixel(
                    lastViewProjection,
                    AlignmentFrames.posedPoint(worldFromModel, 1f, pick.modelPoint),
                    width,
                    height,
                ),
            )
            assertEquals(tap[0], pixel[0], 1.5f)
            assertEquals(tap[1], pixel[1], 1.5f)
        }
    }
    @Test
    fun `frozen photos stay registered after camera movement map correction and viewport rotation`() {
        val trueModel = Pose(floatArrayOf(-1.1f, 0.7f, -2.6f), axisAngle(0.2f, 1f, 0.1f, 28f))
        val reference = Pose.makeTranslation(0f, 0.8f, -2.4f)
        val cameras = listOf(
            camera(floatArrayOf(0f, 1.5f, 0f), 0f, -14f),
            camera(floatArrayOf(1.9f, 1.6f, -0.9f), 40f, -17f),
        )
        var marking = AlignmentMarking(picks.map { it.label })
        cameras.forEachIndexed { index, captureCamera ->
            val mapCorrection = if (index == 0) Pose.IDENTITY else
                Pose(floatArrayOf(0.06f, -0.015f, 0.09f), axisAngle(0f, 1f, 0f, -3f))
            val cameraAtCapture = mapCorrection.compose(captureCamera)
            val projection = projection()
            val view = FloatArray(16).also { cameraAtCapture.inverse().toMatrix(it, 0) }
            val frozen = CapturedAlignmentView(width, height, projection, view, cameraAtCapture,
                mapCorrection.compose(reference), cameraAtCapture, focalPx)
            // Later frames overwrite scratch arrays; the captured geometry must remain immutable.
            projection.fill(0f)
            view.fill(0f)
            if (index > 0) marking = marking.nextStation()
            // The phone has rotated to portrait while showing the same landscape photo.
            val fit = CapturedImageFit(width, height, 1080, 2200)
            picks.forEach { pick ->
                val pixel = requireNotNull(ScreenGeometry.projectPixel(viewProjection(cameraAtCapture),
                    mapCorrection.compose(trueModel).transformPoint(pick.modelPoint), width, height))
                val touch = fit.viewportPoint(pixel[0], pixel[1])
                val restored = requireNotNull(fit.imagePoint(touch[0], touch[1]))
                marking = marking.marked(requireNotNull(frozen.sighting(pick.label, marking.station, restored[0], restored[1])))
            }
        }
        val solution = (ManualCorrespondenceSolver.solve(picks, marking.sightings,
            floatArrayOf(0f, 0f, 0f, 1f), 1.5f, 2.42f, extents) as CorrespondenceOutcome.Solved).solution
        assertEquals(CorrespondenceQuality.ACCEPTABLE, solution.quality)
        val liveCorrection = Pose(floatArrayOf(-0.08f, 0.03f, 0.1f), axisAngle(0f, 1f, 0f, 4f))
        val liveCamera = liveCorrection.compose(camera(floatArrayOf(-0.4f, 1.3f, -0.2f), -12f, -10f))
        val placed = AlignmentFrames.worldFromModel(liveCorrection.compose(reference), solution)
        val truth = liveCorrection.compose(trueModel)
        val anchor = AlignmentFrames.anchorPoseAtModelCentre(placed, floatArrayOf(1.2f, 0.1f, 0f))
        val anchored = AlignmentFrames.worldFromModelAtAnchor(anchor, floatArrayOf(1.2f, 0.1f, 0f))
        extents.forEach { point ->
            val expected = requireNotNull(ScreenGeometry.projectPixel(viewProjection(liveCamera), truth.transformPoint(point), width, height))
            val actual = requireNotNull(ScreenGeometry.projectPixel(viewProjection(liveCamera), anchored.transformPoint(point), width, height))
            assertEquals(expected[0], actual[0], 1f)
            assertEquals(expected[1], actual[1], 1f)
        }
    }

}
