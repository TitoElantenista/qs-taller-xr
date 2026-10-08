package com.qs.tallerxr.ui

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.gesture.CameraGestureDetector
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Camera of the 3D viewer (0.29.0).  It replaces Filament's orbit, whose up axis was fixed and
 * whose tilt stopped at the poles: a part lying upside down in the workshop could not be shown
 * that way, and neither could the AR miniature and the alignment start derived from this view.
 *
 * - One finger, sideways: the part turns about its turning axis (at first its vertical, +Y), and
 *   always the way the finger moves, also when that axis points down on screen.
 * - One finger, up and down: tilt about the screen's horizontal axis, without any stop, so the
 *   part can be turned over the top or the bottom as far as wanted.
 * - Two fingers: pinch for distance, drag to move, twist to roll the part in the screen plane.
 *   After a clear twist the axis closest to the screen's vertical becomes the turning axis, so a
 *   part laid on its side turns about its new vertical.
 * - [resetToHome] restores the start view («Zentrieren»).
 *
 * Touch positions come from SceneView's gesture detector with y counted upwards from the bottom.
 */
internal class FreeOrbitCameraManipulator(
    private var homePosition: Float3,
    private var homeTarget: Float3,
    /** Vertical field of view of the rendering camera, for moving the part under the fingers. */
    private val verticalFovRadians: () -> Double = { DEFAULT_FOV },
) : CameraGestureDetector.CameraManipulator {
    private var homeDistance = length(sub(vector(homePosition), vector(homeTarget))).coerceAtLeast(1e-3)
    private var target = vector(homeTarget)
    private var distance = homeDistance
    /** True once a finger has moved the view: a later home update then no longer jumps the camera. */
    var interacted = false
        private set
    /** Camera-to-world rotation (x, y, z, w); the camera looks along its own −Z. */
    private var rotation = DoubleArray(4)
    private var turnAxis = doubleArrayOf(0.0, 1.0, 0.0)
    private var height = 1
    private var lastX = 0
    private var lastY = 0
    private var strafe = false
    private var grabbing = false

    init { resetToHome() }

    fun resetToHome() {
        target = vector(homeTarget)
        distance = homeDistance
        rotation = lookRotation(sub(vector(homePosition), target), doubleArrayOf(0.0, 1.0, 0.0))
        turnAxis = doubleArrayOf(0.0, 1.0, 0.0)
    }

    /**
     * The start view once the viewport is known: its aspect decides how far the camera must stand
     * to show a long part completely.  Applied at once while the operator has not touched the view.
     */
    fun updateHome(position: Float3, target: Float3) {
        homePosition = position
        homeTarget = target
        homeDistance = length(sub(vector(position), vector(target))).coerceAtLeast(1e-3)
        if (!interacted) resetToHome()
    }

    override fun setViewport(width: Int, height: Int) {
        this.height = height.coerceAtLeast(1)
    }

    override fun getTransform(): Mat4 {
        val right = rotate(rotation, doubleArrayOf(1.0, 0.0, 0.0))
        val up = rotate(rotation, doubleArrayOf(0.0, 1.0, 0.0))
        val back = rotate(rotation, doubleArrayOf(0.0, 0.0, 1.0))
        val eye = DoubleArray(3) { target[it] + back[it] * distance }
        return Mat4(
            Float4(right[0].toFloat(), right[1].toFloat(), right[2].toFloat(), 0f),
            Float4(up[0].toFloat(), up[1].toFloat(), up[2].toFloat(), 0f),
            Float4(back[0].toFloat(), back[1].toFloat(), back[2].toFloat(), 0f),
            Float4(eye[0].toFloat(), eye[1].toFloat(), eye[2].toFloat(), 1f),
        )
    }

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        lastX = x
        lastY = y
        this.strafe = strafe
        grabbing = true
    }

    override fun grabUpdate(x: Int, y: Int) {
        if (!grabbing) return
        val dx = (x - lastX).toDouble()
        val dy = (y - lastY).toDouble()
        lastX = x
        lastY = y
        if (dx != 0.0 || dy != 0.0) interacted = true
        if (strafe) pan(dx, dy) else orbit(dx, dy)
    }

    override fun grabEnd() {
        grabbing = false
    }

    override fun scrollBegin(x: Int, y: Int, separation: Float) = Unit

    /** Pinch: the distance follows the spread of the fingers. */
    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        if (!(prevSeparation > 1f && currSeparation > 1f)) return
        interacted = true
        distance = (distance * prevSeparation / currSeparation)
            .coerceIn(homeDistance * MINIMUM_ZOOM, homeDistance * MAXIMUM_ZOOM)
    }

    override fun scrollEnd() = Unit

    override fun update(deltaTime: Float) = Unit

    /** Two-finger twist, clockwise on screen positive: the part turns with the fingers. */
    fun roll(radians: Double) {
        if (!radians.isFinite()) return
        interacted = true
        val back = rotate(rotation, doubleArrayOf(0.0, 0.0, 1.0))
        rotation = normalized(multiply(axisAngle(back, radians), rotation))
    }

    /** End of a clear twist: turning continues about the part axis now closest to screen up. */
    fun rollFinished() {
        val up = rotate(rotation, doubleArrayOf(0.0, 1.0, 0.0))
        val axis = (0..2).maxBy { abs(up[it]) }
        turnAxis = DoubleArray(3) { if (it == axis) 1.0 else 0.0 }
    }

    private fun orbit(dx: Double, dy: Double) {
        val up = rotate(rotation, doubleArrayOf(0.0, 1.0, 0.0))
        // Follow the finger whichever way the turning axis points on screen.
        val sign = if (dot(turnAxis, up) >= 0.0) -1.0 else 1.0
        rotation = normalized(multiply(axisAngle(turnAxis, sign * dx * ORBIT_RADIANS_PER_PIXEL), rotation))
        val right = rotate(rotation, doubleArrayOf(1.0, 0.0, 0.0))
        rotation = normalized(multiply(axisAngle(right, dy * ORBIT_RADIANS_PER_PIXEL), rotation))
    }

    private fun pan(dx: Double, dy: Double) {
        val fov = verticalFovRadians().takeIf { it.isFinite() && it > 0.01 && it < 3.0 } ?: DEFAULT_FOV
        val perPixel = 2.0 * distance * tan(fov / 2.0) / height
        val right = rotate(rotation, doubleArrayOf(1.0, 0.0, 0.0))
        val up = rotate(rotation, doubleArrayOf(0.0, 1.0, 0.0))
        target = DoubleArray(3) { target[it] - (right[it] * dx + up[it] * dy) * perPixel }
    }

    private companion object {
        const val ORBIT_RADIANS_PER_PIXEL = 0.0035
        const val MINIMUM_ZOOM = 0.03
        const val MAXIMUM_ZOOM = 8.0
        val DEFAULT_FOV = 2.0 * atan(12.0 / 28.0)
    }
}

/**
 * Turns the angle between two fingers into roll steps.  Small twists while pinching are ignored:
 * only past [deadZoneRadians] does the part start to roll, and only then counts as a twist.
 */
internal class TwistTracker(private val deadZoneRadians: Double = Math.toRadians(8.0)) {
    private var previous: Double? = null
    private var accumulated = 0.0
    private var active = false

    /** Screen pixels, y downwards.  Returns the roll to apply now, clockwise positive. */
    fun update(x0: Float, y0: Float, x1: Float, y1: Float): Double {
        val angle = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        val last = previous
        previous = angle
        if (last == null) return 0.0
        var delta = angle - last
        while (delta > PI) delta -= 2 * PI
        while (delta < -PI) delta += 2 * PI
        if (active) return delta
        accumulated += delta
        if (abs(accumulated) < deadZoneRadians) return 0.0
        active = true
        return accumulated - deadZoneRadians * kotlin.math.sign(accumulated)
    }

    /** Fingers lifted; true when this gesture was a twist. */
    fun finish(): Boolean {
        val twisted = active
        previous = null
        accumulated = 0.0
        active = false
        return twisted
    }
}

private fun vector(value: Float3) = doubleArrayOf(value.x.toDouble(), value.y.toDouble(), value.z.toDouble())
private fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }
private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
private fun length(a: DoubleArray) = sqrt(dot(a, a))
private fun cross(a: DoubleArray, b: DoubleArray) =
    doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

private fun axisAngle(axis: DoubleArray, radians: Double): DoubleArray {
    val n = length(axis)
    if (n < 1e-12) return doubleArrayOf(0.0, 0.0, 0.0, 1.0)
    val s = sin(radians / 2) / n
    return doubleArrayOf(axis[0] * s, axis[1] * s, axis[2] * s, cos(radians / 2))
}

private fun multiply(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
    a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
    a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
    a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
    a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
)

private fun normalized(q: DoubleArray): DoubleArray {
    val n = sqrt(q.sumOf { it * it })
    return DoubleArray(4) { q[it] / n }
}

private fun rotate(q: DoubleArray, v: DoubleArray): DoubleArray {
    val u = doubleArrayOf(q[0], q[1], q[2])
    val t = cross(u, v).map { it * 2 }.toDoubleArray()
    val c = cross(u, t)
    return DoubleArray(3) { v[it] + q[3] * t[it] + c[it] }
}

/** Rotation whose −Z looks from [back] towards the origin with [upHint] as near as possible to up. */
private fun lookRotation(back: DoubleArray, upHint: DoubleArray): DoubleArray {
    val z = length(back).let { n -> DoubleArray(3) { back[it] / n } }
    var x = cross(upHint, z)
    if (length(x) < 1e-9) x = cross(doubleArrayOf(0.0, 0.0, 1.0), z)
    x = length(x).let { n -> DoubleArray(3) { x[it] / n } }
    val y = cross(z, x)
    // Columns x, y, z of the rotation matrix to a quaternion.
    val m00 = x[0]; val m01 = y[0]; val m02 = z[0]
    val m10 = x[1]; val m11 = y[1]; val m12 = z[1]
    val m20 = x[2]; val m21 = y[2]; val m22 = z[2]
    val trace = m00 + m11 + m22
    val q = if (trace > 0) {
        val s = sqrt(trace + 1.0) * 2
        doubleArrayOf((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s)
    } else if (m00 > m11 && m00 > m22) {
        val s = sqrt(1.0 + m00 - m11 - m22) * 2
        doubleArrayOf(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
    } else if (m11 > m22) {
        val s = sqrt(1.0 + m11 - m00 - m22) * 2
        doubleArrayOf((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s)
    } else {
        val s = sqrt(1.0 + m22 - m00 - m11) * 2
        doubleArrayOf((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)
    }
    return normalized(q)
}
