package com.qs.tallerxr.ui

import com.google.ar.core.Pose

/** How the operator aligns the model in AR. */
internal enum class AlignmentMethod {
    /** Points chosen in 3D and marked on the real part: the standard method. */
    POINTS,

    /** Experimental: the model, turned in 3D like the part, is pulled onto the image edges. */
    SNAP,
}

/**
 * The 3D viewer camera the operator left the model under.  The model sits at the viewer origin,
 * so this is also the model as seen from that camera.  [projectionY] is the vertical scale of its
 * projection, used to show the model in AR at the same size on screen.
 */
internal class SnapViewerCamera(
    val position: FloatArray,
    val rotation: FloatArray,
    val projectionY: Float,
) {
    /**
     * The model in front of the AR camera as the viewer showed it.  Both cameras look along -Z
     * with +Y up on screen; the distance is scaled so the model covers the same part of the screen
     * despite a different field of view, and [distanceFactor] adds the operator's pinch.
     */
    fun cameraFromModel(arProjectionY: Float, distanceFactor: Float): Pose {
        val viewerFromModel = Pose(position, rotation).inverse()
        val scale = (arProjectionY / projectionY).takeIf { it.isFinite() && it > 0f } ?: 1f
        val translation = viewerFromModel.translation
        return Pose(
            FloatArray(3) { translation[it] * scale * distanceFactor },
            viewerFromModel.rotationQuaternion,
        )
    }
}

/**
 * The running snap session; replaced (never shared) when the operator aims again.  [running]
 * guarantees one frame in process at a time, also across a replaced session.
 */
internal class SnapSessionHolder {
    @Volatile
    var session: com.qs.tallerxr.ar.FloorSnapSession? = null
    val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastCamera: Pose? = null
    private var lastMillis = 0L

    /**
     * Whether the camera moved slowly enough since the previous frame for a sharp image; called on
     * every frame from the render thread.
     */
    fun steady(camera: Pose, nowMillis: Long): Boolean {
        val previous = lastCamera
        val previousMillis = lastMillis
        lastCamera = camera
        lastMillis = nowMillis
        if (previous == null || nowMillis <= previousMillis) return false
        val seconds = (nowMillis - previousMillis) / 1000f
        val turn = previous.inverse().compose(camera).rotationQuaternion
        val degrees = Math.toDegrees(2.0 * kotlin.math.acos(kotlin.math.abs(turn[3].toDouble()).coerceAtMost(1.0))).toFloat()
        val moved = kotlin.math.sqrt((0..2).sumOf { ((camera.translation[it] - previous.translation[it]).toDouble()).let { d -> d * d } })
        return degrees / seconds <= STEADY_DEGREES_PER_SECOND && moved / seconds <= STEADY_METRES_PER_SECOND
    }

    private companion object {
        const val STEADY_DEGREES_PER_SECOND = 25f
        const val STEADY_METRES_PER_SECOND = 0.4
    }
}
