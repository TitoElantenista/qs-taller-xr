package com.qs.tallerxr.ar

import com.google.ar.core.Pose
import com.huawei.hiar.ARPose
import org.junit.Assert.*
import org.junit.Test

class HuaweiArRuntimeTest {
    @Test fun `Huawei poses retain coordinates handedness and quaternion order`() {
        val rotation = floatArrayOf(0f, 0.70710677f, 0f, 0.70710677f)
        val native = ARPose(floatArrayOf(1f, -2f, 3f), rotation)
        val common = native.toCommonPose()
        val local = floatArrayOf(0.8f, 0.3f, -1.2f)
        assertArrayEquals(native.transformPoint(local), common.transformPoint(local), 1e-5f)
        assertArrayEquals(native.xAxis, common.xAxis, 1e-5f)
        assertArrayEquals(native.yAxis, common.yAxis, 1e-5f)
        assertArrayEquals(native.zAxis, common.zAxis, 1e-5f)
        assertArrayEquals(local, common.inverse().transformPoint(native.transformPoint(local)), 1e-5f)
    }

    @Test fun `Huawei anchor at model centre reconstructs model pose`() {
        val pose = Pose(floatArrayOf(-0.7f, 1.2f, -2f), floatArrayOf(0.258819f, 0f, 0f, 0.9659258f))
        val centre = floatArrayOf(0.6f, 0.3f, -0.2f)
        val anchor = AlignmentFrames.anchorPoseAtModelCentre(pose, centre)
        val fromHuawei = ARPose(anchor.translation, anchor.rotationQuaternion).toCommonPose()
        val reconstructed = AlignmentFrames.worldFromModelAtAnchor(fromHuawei, centre)
        assertArrayEquals(pose.translation, reconstructed.translation, 1e-5f)
        assertArrayEquals(pose.rotationQuaternion, reconstructed.rotationQuaternion, 1e-5f)
    }
}
