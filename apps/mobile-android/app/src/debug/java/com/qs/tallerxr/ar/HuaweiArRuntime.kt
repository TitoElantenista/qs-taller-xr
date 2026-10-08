package com.qs.tallerxr.ar

import com.google.ar.core.Pose
import com.huawei.hiar.ARAnchor
import com.huawei.hiar.ARFrame
import com.huawei.hiar.ARPose
import com.huawei.hiar.ARSession

internal fun ARPose.toCommonPose(): Pose = Pose(
    floatArrayOf(tx(), ty(), tz()), floatArrayOf(qx(), qy(), qz(), qw()),
)

internal class HuaweiArSession(val native: ARSession) : ArSessionHandle {
    var stopped = false
        private set

    override fun createAnchor(pose: Pose): ArAnchorHandle {
        check(!stopped)
        return HuaweiArAnchor(this, native.createAnchor(ARPose(pose.translation, pose.rotationQuaternion)))
    }

    fun stop() {
        if (!stopped) {
            stopped = true
            runCatching { native.pause() }
            runCatching { native.stop() }
        }
    }
}

private class HuaweiArAnchor(private val session: HuaweiArSession, private val anchor: ARAnchor) : ArAnchorHandle {
    private var detached = false
    override val pose get() = anchor.pose.toCommonPose()
    override val trackingState get() = if (session.stopped || detached) ArTracking.STOPPED
        else ArTracking.valueOf(anchor.trackingState.name)
    override fun detach() {
        if (!detached && !session.stopped) anchor.detach()
        detached = true
    }
}

internal class HuaweiArFrame(private val frame: ARFrame) : ArFrameData {
    override val trackingState get() = ArTracking.valueOf(frame.camera.trackingState.name)
    override val cameraPose get() = frame.camera.pose.toCommonPose()
    override val displayOrientedPose get() = frame.camera.displayOrientedPose.toCommonPose()
    override fun projection(near: Float, far: Float) = FloatArray(16).also {
        frame.camera.getProjectionMatrix(it, 0, near, far)
    }
    override fun viewMatrix() = FloatArray(16).also { frame.camera.getViewMatrix(it, 0) }
    // The multi-view solver needs only calibrated rays. Do not substitute a plane hit for
    // measured depth at a silhouette corner; Huawei depth is deliberately optional here.
    override fun measuredDistance(origin: FloatArray, direction: FloatArray): Float? = null
}
