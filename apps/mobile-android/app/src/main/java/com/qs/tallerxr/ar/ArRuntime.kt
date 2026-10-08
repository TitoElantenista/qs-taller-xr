package com.qs.tallerxr.ar

import com.google.ar.core.Pose

/** Pose is the ARCore SDK's pure Java rigid transform, not an ARCore session or native handle. */
internal enum class ArTracking { TRACKING, PAUSED, STOPPED }

internal interface ArAnchorHandle {
    val pose: Pose
    val trackingState: ArTracking
    fun detach()
}

internal interface ArSessionHandle {
    fun createAnchor(pose: Pose): ArAnchorHandle

    /** Horizontal surfaces found so far; empty unless plane finding is on (only for the snap). */
    fun horizontalPlanes(): List<SupportPlane> = emptyList()
}

/** A horizontal, upward-facing surface the AR system tracks, floor or table. */
internal class SupportPlane(
    /** World height of the surface. */
    val heightM: Float,
    /** Outline as world x, z pairs. */
    val outline: FloatArray,
)

/** A frame and its camera data are consumed together on the render/main thread. */
internal interface ArFrameData {
    val trackingState: ArTracking
    val cameraPose: Pose
    val displayOrientedPose: Pose
    fun projection(near: Float, far: Float): FloatArray
    fun viewMatrix(): FloatArray
    fun measuredDistance(origin: FloatArray, direction: FloatArray): Float?
    /** Optional, currently Google only. Never pauses or reopens the camera/session. */
    fun copyCameraImage(width: Int, height: Int): ArCameraImage? = null

    /** Grey levels of this frame for the experimental snap; Google only, like [copyCameraImage]. */
    fun copyLuminance(width: Int, height: Int): LuminanceImage? = null
}
