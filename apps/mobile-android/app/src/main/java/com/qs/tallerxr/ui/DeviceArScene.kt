package com.qs.tallerxr.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import com.google.android.filament.View
import com.google.ar.core.Config
import com.qs.tallerxr.ar.ArBackend
import com.qs.tallerxr.ar.ArFrameData
import com.qs.tallerxr.ar.ArSessionHandle
import com.qs.tallerxr.ar.GoogleArFrame
import com.qs.tallerxr.ar.GoogleArSession
import com.qs.tallerxr.ar.arFailureMessage
import io.github.sceneview.SceneScope
import io.github.sceneview.SurfaceType
import io.github.sceneview.ar.ARSceneView

@Composable
internal fun DeviceArScene(
    backend: ArBackend,
    resources: ViewerRenderResources,
    view: View,
    lifecycle: Lifecycle,
    onDepthSupported: (Boolean) -> Unit,
    /** Horizontal plane finding, for the snap: the part stands on floor or table. */
    horizontalPlanes: Boolean,
    onFrame: (ArSessionHandle, ArFrameData) -> Unit,
    onFailure: (String) -> Unit,
    content: @Composable SceneScope.() -> Unit,
) {
    val host = LocalView.current
    val planeFinding = if (horizontalPlanes) Config.PlaneFindingMode.HORIZONTAL else Config.PlaneFindingMode.DISABLED
    when (backend) {
        ArBackend.GOOGLE -> ARSceneView(
            modifier = Modifier.fillMaxSize(),
            surfaceType = SurfaceType.Surface,
            engine = resources.engine,
            modelLoader = resources.modelLoader,
            materialLoader = resources.materialLoader,
            view = view,
            lifecycle = lifecycle,
            planeFindingMode = planeFinding,
            depthMode = Config.DepthMode.AUTOMATIC,
            planeRenderer = false,
            sessionConfiguration = { session, config ->
                val supported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                onDepthSupported(supported)
                config.depthMode = if (supported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                config.planeFindingMode = planeFinding
            },
            onSessionUpdated = updated@{ session, frame ->
                val ar = session as? io.github.sceneview.ar.arcore.ARSession
                val rotation = host.display?.rotation ?: 0
                if (ar != null && view.viewport.width > 0 && view.viewport.height > 0 &&
                    (ar.displayWidth != view.viewport.width || ar.displayHeight != view.viewport.height ||
                        ar.displayRotation != rotation)
                ) {
                    ar.setDisplayGeometry(rotation, view.viewport.width, view.viewport.height)
                    // The image in this Frame predates that display change. Wait for the next
                    // update before pairing a frozen photo with the new viewport geometry.
                    return@updated
                }
                onFrame(GoogleArSession(session), GoogleArFrame(frame))
            },
            onSessionFailed = { onFailure(arFailureMessage(backend, it)) },
        ) { content(this) }
        ArBackend.HUAWEI -> HuaweiArScene(resources, view, lifecycle, onDepthSupported, onFrame, onFailure, content)
    }
}
