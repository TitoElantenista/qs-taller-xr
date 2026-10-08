package com.qs.tallerxr.ar

import com.google.ar.core.Pose
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentFramesTest {
    private fun yaw(degrees: Float): FloatArray {
        val half = Math.toRadians(degrees.toDouble()) / 2.0
        return floatArrayOf(0f, sin(half).toFloat(), 0f, cos(half).toFloat())
    }

    private fun assertClose(expected: FloatArray, actual: FloatArray, tolerance: Float = 1e-4f) {
        expected.indices.forEach { assertEquals(expected[it], actual[it], tolerance) }
    }

    @Test
    fun `a mark stored against the reference anchor survives an ARCore world correction`() {
        val worldFromReference = Pose(floatArrayOf(0.4f, 0.1f, -2f), yaw(30f))
        val ray = WorldRay(
            origin = floatArrayOf(0.2f, 1.4f, 0.3f),
            direction = floatArrayOf(0f, -0.6f, -0.8f),
        )
        val stored = AlignmentFrames.rayInReference(worldFromReference, ray)

        // ARCore relocalises: every world coordinate, the anchor included, shifts together.
        val correction = Pose(floatArrayOf(0.07f, -0.02f, 0.11f), yaw(-4f))
        val correctedReference = correction.compose(worldFromReference)
        val restored = AlignmentFrames.rayInWorld(correctedReference, stored)

        assertClose(correction.transformPoint(ray.origin), restored.origin)
        assertClose(correction.rotateVector(ray.direction), restored.direction)
    }

    @Test
    fun `the final anchor sits at the part centre and reproduces the fitted pose`() {
        val worldFromModel = Pose(floatArrayOf(1f, 0.2f, -3f), yaw(55f))
        val centre = floatArrayOf(2.4f, 0.1f, -0.3f)
        val anchorPose = AlignmentFrames.anchorPoseAtModelCentre(worldFromModel, centre)
        assertClose(worldFromModel.transformPoint(centre), anchorPose.translation)

        val restored = AlignmentFrames.worldFromModelAtAnchor(anchorPose, centre)
        val probe = floatArrayOf(-0.7f, 0.4f, 1.9f)
        assertClose(worldFromModel.transformPoint(probe), restored.transformPoint(probe))
    }

    @Test
    fun `the point legend is a miniature inside the view oriented like the 3D viewer`() {
        val camera = Pose(floatArrayOf(0f, 1.5f, 0f), yaw(20f))
        // Portrait phone: narrow horizontal field of view.
        val projection = FloatArray(16).also {
            it[0] = 3.2f
            it[5] = 1.5f
        }
        val centre = floatArrayOf(1.2f, 0f, 0f)
        val legend = requireNotNull(
            AlignmentFrames.legend(
                displayCameraPose = camera,
                projection = projection,
                viewerCameraQuaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
                modelCentre = centre,
                modelDiameterM = 2.4f,
            ),
        )
        assertTrue(legend.scale in 0.01f..0.2f)
        // The viewer looked along the model axes, so the miniature takes the camera orientation.
        assertClose(camera.rotationQuaternion, legend.worldFromModel.rotationQuaternion)

        val centreInCamera = camera.inverse().transformPoint(
            AlignmentFrames.posedPoint(legend.worldFromModel, legend.scale, centre),
        )
        val halfWidth = 0.6f / projection[0]
        val halfHeight = 0.6f / projection[5]
        val radius = 2.4f * legend.scale / 2f
        assertEquals(-0.6f, centreInCamera[2], 1e-4f)
        assertTrue(abs(centreInCamera[0]) + radius <= halfWidth + 1e-4f)
        assertTrue(abs(centreInCamera[1]) + radius <= halfHeight + 1e-4f)
        assertTrue(centreInCamera[0] > 0f && centreInCamera[1] > 0f)
    }

    @Test
    fun `a seed orientation is carried into the reference frame`() {
        val worldFromReference = Pose(floatArrayOf(3f, 0f, 1f), yaw(90f))
        val inReference = AlignmentFrames.quaternionInReference(worldFromReference, yaw(90f))
        assertNotNull(inReference)
        // World yaw equals the reference yaw, so the model is unrotated in the reference frame.
        assertEquals(1f, abs(inReference[3]), 1e-4f)
        assertEquals(1f, sqrt(inReference.fold(0f) { sum, value -> sum + value * value }), 1e-4f)
    }
}
