package com.qs.tallerxr.ar

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.huawei.hiar.AREnginesApk
import com.huawei.hiar.ARSession
import com.huawei.hiar.ARWorldTrackingConfig
import com.huawei.hiar.ARConfigBase

internal fun huaweiWorldConfig(session: ARSession) = ARWorldTrackingConfig(session).apply {
    planeFindingMode = ARConfigBase.PlaneFindingMode.DISABLED
    updateMode = ARConfigBase.UpdateMode.LATEST_CAMERA_IMAGE
    // Continuous focus is required; the earlier fixed-focus experiment did not cure vibration.
    focusMode = ARConfigBase.FocusMode.AUTO_FOCUS
    enableItem = ARConfigBase.ENABLE_NULL.toLong()
    lightingMode = ARConfigBase.LIGHT_MODE_NONE
    semanticMode = ARConfigBase.SEMANTIC_NONE
}

internal fun huaweiServiceState(context: Context): ArServiceState = try {
    context.packageManager.getPackageInfo("com.huawei.arengine.service", 0)
    if (AREnginesApk.isAREngineApkReady(context)) ArServiceState.READY else ArServiceState.UPDATE_REQUIRED
} catch (_: PackageManager.NameNotFoundException) {
    ArServiceState.MISSING
} catch (error: Exception) {
    Log.w("QsAr", "Huawei availability failed", error)
    ArServiceState.ERROR
} catch (error: LinkageError) {
    Log.w("QsAr", "Huawei native SDK unavailable", error)
    ArServiceState.ERROR
}
