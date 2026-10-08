package com.qs.tallerxr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import com.google.android.filament.View
import com.qs.tallerxr.ar.ArFrameData
import com.qs.tallerxr.ar.ArSessionHandle
import io.github.sceneview.SceneScope

/** Defensive error for an unsupported route; this source set has no Huawei dependency. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun HuaweiArScene(
    resources: ViewerRenderResources,
    view: View,
    lifecycle: Lifecycle,
    onDepthSupported: (Boolean) -> Unit,
    onFrame: (ArSessionHandle, ArFrameData) -> Unit,
    onFailure: (String) -> Unit,
    content: @Composable SceneScope.() -> Unit,
) {
    LaunchedEffect(Unit) {
        onDepthSupported(false)
        onFailure("Diese Ausgabe verwendet Google ARCore. Ohne kompatiblen AR-Dienst bleibt der 3D-Modus nutzbar.")
    }
}
