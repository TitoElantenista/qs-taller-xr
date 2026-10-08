package com.qs.tallerxr.ui

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Mat4
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeOrbitCameraTest {
    private val pixelsPer180 = (PI / 0.0035).roundToInt()

    private fun camera() = FreeOrbitCameraManipulator(Float3(0f, 0f, 3f), Float3(0f)).also { it.setViewport(1000, 2000) }

    private fun FreeOrbitCameraManipulator.drag(dx: Int, dy: Int) {
        grabBegin(500, 1000, false)
        // Small steps, like a finger.
        val steps = maxOf(abs(dx), abs(dy)) / 10 + 1
        for (step in 1..steps) grabUpdate(500 + dx * step / steps, 1000 + dy * step / steps)
        grabEnd()
    }

    private fun eye(transform: Mat4) = floatArrayOf(transform.w.x, transform.w.y, transform.w.z)
    private fun up(transform: Mat4) = floatArrayOf(transform.y.x, transform.y.y, transform.y.z)

    /** Screen x (camera right) of a world point; larger is further right. */
    private fun screenX(transform: Mat4, point: FloatArray): Float {
        val e = eye(transform)
        return (0..2).sumOf { ((point[it] - e[it]) * transform.x[it]).toDouble() }.toFloat()
    }

    @Test fun theHomeViewLooksAtThePartUpright() {
        val transform = camera().getTransform()
        assertEquals(3f, eye(transform)[2], 1e-4f)
        assertEquals(1f, up(transform)[1], 1e-4f)
    }

    @Test fun aLaterHomeAppliesOnlyUntilTheOperatorMovesTheView() {
        val camera = camera()
        // The viewport is laid out: the part needs twice the distance.  Nothing was touched yet.
        camera.updateHome(Float3(0f, 0f, 6f), Float3(0f))
        assertEquals(6f, eye(camera.getTransform())[2], 1e-4f)
        camera.drag(80, 0)
        val eyeAfterDrag = eye(camera.getTransform())
        // A further layout change must not throw away the operator's view…
        camera.updateHome(Float3(0f, 0f, 9f), Float3(0f))
        assertEquals(eyeAfterDrag[2], eye(camera.getTransform())[2], 1e-4f)
        // …but «Zentrieren» uses the newest home.
        camera.resetToHome()
        assertEquals(9f, eye(camera.getTransform())[2], 1e-4f)
    }

    @Test fun thePartCanBeTurnedOverTheTopWithoutAStop() {
        val camera = camera()
        // Finger down by half a turn: the camera passes over the top and sees the part upside down.
        camera.drag(0, -pixelsPer180)
        val transform = camera.getTransform()
        assertEquals(-1f, up(transform)[1], 0.02f)
        assertEquals(-3f, eye(transform)[2], 0.05f)
        // And on, further than the old orbit's pole allowed.
        camera.drag(0, -pixelsPer180 / 2)
        assertEquals(-3f, eye(camera.getTransform())[1], 0.05f)
    }

    @Test fun afterTurningOverThePartStillTurnsAboutItsOwnVerticalTheWayTheFingerMoves() {
        val camera = camera()
        camera.drag(0, -pixelsPer180)
        val before = camera.getTransform()
        val front = floatArrayOf(0f, 0f, -0.5f) // the face now towards the camera
        val xBefore = screenX(before, front)
        camera.drag(60, 0)
        val after = camera.getTransform()
        // Turning about the part's own vertical keeps the camera's height.
        assertEquals(eye(before)[1], eye(after)[1], 1e-3f)
        assertTrue("Die Vorderseite folgt dem Finger", screenX(after, front) > xBefore)
        // The same holds the right way up.
        val upright = camera().apply { drag(0, 0) }
        val start = screenX(upright.getTransform(), floatArrayOf(0f, 0f, 0.5f))
        upright.drag(60, 0)
        assertTrue(screenX(upright.getTransform(), floatArrayOf(0f, 0f, 0.5f)) > start)
    }

    @Test fun aTwistLaysThePartOnItsSideAndTurningFollowsTheNewVertical() {
        val camera = camera()
        camera.roll(PI / 2)
        camera.rollFinished()
        // Screen up is now along the part's X axis: turning keeps the camera's X.
        val before = camera.getTransform()
        assertEquals(1f, abs(up(before)[0]), 1e-3f)
        camera.drag(80, 0)
        assertEquals(eye(before)[0], eye(camera.getTransform())[0], 1e-3f)
        assertTrue(abs(eye(camera.getTransform())[2] - eye(before)[2]) > 0.05f)
    }

    @Test fun pinchZoomsAndTwoFingersMoveThePart() {
        val camera = camera()
        camera.scrollUpdate(500, 1000, 100f, 200f)
        assertEquals(1.5f, eye(camera.getTransform())[2], 1e-4f)
        camera.grabBegin(500, 1000, true)
        camera.grabUpdate(600, 1000)
        camera.grabEnd()
        // Fingers to the right: the part moves right, so the camera moves left.
        assertTrue(eye(camera.getTransform())[0] < 0f)
        camera.resetToHome()
        assertEquals(3f, eye(camera.getTransform())[2], 1e-4f)
        assertEquals(0f, eye(camera.getTransform())[0], 1e-4f)
    }

    @Test fun smallTwistsWhilePinchingDoNotRoll() {
        val twist = TwistTracker()
        assertEquals(0.0, twist.update(0f, 0f, 100f, 0f), 1e-9)
        // About 5°: still a pinch.
        assertEquals(0.0, twist.update(0f, 0f, 100f, 9f), 1e-9)
        assertTrue(!twist.finish())
        // A clear clockwise twist rolls past the dead zone, clockwise positive.
        twist.update(0f, 0f, 100f, 0f)
        val roll = twist.update(0f, 0f, 70f, 70f)
        assertEquals(Math.toRadians(45.0 - 8.0), roll, 1e-6)
        assertTrue(twist.update(0f, 0f, 0f, 100f) > 0.0)
        assertTrue(twist.finish())
    }
}
