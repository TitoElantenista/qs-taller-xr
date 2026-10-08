package com.qs.tallerxr.ui

import android.content.Context
import android.util.Log
import android.os.SystemClock
import android.opengl.GLES30
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.ar.core.Config
import com.huawei.hiar.ARSession
import com.qs.tallerxr.ar.ArBackend
import com.qs.tallerxr.ar.ArFrameData
import com.qs.tallerxr.ar.ArSessionHandle
import com.qs.tallerxr.ar.ArTracking
import com.qs.tallerxr.ar.GoogleArFrame
import com.qs.tallerxr.ar.GoogleArSession
import com.qs.tallerxr.ar.HuaweiArFrame
import com.qs.tallerxr.ar.HuaweiArSession
import com.qs.tallerxr.ar.arFailureMessage
import com.qs.tallerxr.ar.huaweiWorldConfig
import com.qs.tallerxr.ar.ScreenGeometry
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberAREnvironment
import io.github.sceneview.node.CameraNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberScene
import io.github.sceneview.math.toTransform

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
    val context = LocalContext.current
    val host = LocalView.current
    val scene = rememberScene(resources.engine)
    val camera = rememberCameraNode(resources.engine)
    // No skybox: Huawei supplies the background inside the same Filament scene.
    val environment = rememberAREnvironment(resources.engine)
    val currentFrame = rememberUpdatedState(onFrame)
    val currentFailure = rememberUpdatedState(onFailure)
    val controller = remember(resources, scene, camera) { HuaweiArController(context, resources, scene, camera) }

    DisposableEffect(controller, lifecycle) {
        onDepthSupported(false)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) controller.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.destroy()
        }
    }
    SceneView(
        modifier = Modifier.fillMaxSize(),
        surfaceType = SurfaceType.Surface,
        engine = resources.engine,
        modelLoader = resources.modelLoader,
        materialLoader = resources.materialLoader,
        view = view,
        scene = scene,
        environment = environment,
        cameraNode = camera,
        cameraManipulator = null,
        autoCenterContent = false,
        autoFitContent = false,
        renderQuality = RenderQuality.Performance,
        // UiHelper puts a translucent SurfaceView ON TOP of the app window, hiding Compose
        // controls. Camera + CAD already fill this surface; keep it behind the app UI. This
        // surface setting is independent of the model materials' adjustable opacity.
        isOpaque = true,
        lifecycle = lifecycle,
        onFrame = {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                controller.update(host.display?.rotation ?: 0, view.viewport.width, view.viewport.height,
                    currentFrame.value, currentFailure.value)
            }
        },
        content = content,
    )
}

/** All native calls stay on the main/render thread, sharing SceneView's OpenGL context. */
private class HuaweiArController(
    private val context: Context,
    private val resources: ViewerRenderResources,
    private val scene: Scene,
    private val camera: CameraNode,
) {
    private var session: HuaweiArSession? = null
    private var background: HuaweiCameraBackground? = null
    private var resumed = false
    private var failed = false
    private var attached = false
    private var geometry: Triple<Int, Int, Int>? = null
    private var reportedCameraMatrix = false
    private var reportedFirstFrame = false
    private val startedAt = SystemClock.elapsedRealtime()
    private var nextCameraUpdateNanos = 0L

    fun update(rotation: Int, width: Int, height: Int,
               onFrame: (ArSessionHandle, ArFrameData) -> Unit, onFailure: (String) -> Unit) {
        if (failed || width <= 0 || height <= 0) return
        // The tablet display can run at 120/144 Hz while the camera supplies about 30 images/s.
        // Do not repeatedly stall the UI thread on GPU/native-camera work for duplicate frames.
        // The camera texture, pose, anchors and marking rays all advance together in this update.
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (nowNanos < nextCameraUpdateNanos) return
        // Keep an accumulated deadline: restarting a 33 ms delay on every update would
        // alternate between two and three display frames when a vsync arrives slightly late.
        val followingDeadline = nextCameraUpdateNanos + 33_333_333L
        nextCameraUpdateNanos = if (followingDeadline > nowNanos) followingDeadline
            else nowNanos + 33_333_333L
        try {
            val handle = session ?: HuaweiArSession(ARSession(context.applicationContext)).also {
                session = it
                val config = huaweiWorldConfig(it.native)
                check(it.native.isSupported(config)) { "Räumliches Tracking wird auf diesem Gerät nicht unterstützt." }
                it.native.configure(config)
                Log.i("QsAr", "Huawei requested camera focus: ${config.focusMode}")
                background = HuaweiCameraBackground(resources.materialLoader)
                it.native.setCameraTextureName(checkNotNull(background).textureId)
            }
            val display = Triple(rotation, width, height)
            if (geometry != display) {
                handle.native.setDisplayGeometry(rotation, width, height)
                geometry = display
            }
            if (!resumed) {
                handle.native.resume()
                resumed = true
                Log.i("QsAr", "Huawei session resumed")
            }
            // Huawei exposes one external texture, unlike ARCore's texture ring. Finish the
            // previous Filament command batch before Huawei writes that shared GL texture.
            resources.engine.flushAndWait()
            val nativeFrame = handle.native.update()
            // Huawei writes in the shared caller GL context; Filament consumes from its own
            // render context. Finish the producer as well as the previous consumer before the
            // one external camera texture is sampled again.
            GLES30.glFinish()
            if (nativeFrame.timestampNs == 0L) return
            if (!reportedFirstFrame) {
                Log.i("QsAr", "Huawei first camera frame after ${SystemClock.elapsedRealtime() - startedAt} ms")
                reportedFirstFrame = true
            }
            val frame = HuaweiArFrame(nativeFrame)
            background?.update(nativeFrame)
            if (!attached) {
                scene.addEntity(checkNotNull(background).entity)
                attached = true
            }
            if (frame.trackingState == ArTracking.TRACKING) {
                // Render with the inverse of the very view matrix used by hit rays and marks.
                // Do not assume a vendor's display-pose convention is identical to that matrix.
                val cameraView = frame.viewMatrix()
                val worldFromCamera = checkNotNull(ScreenGeometry.invert(cameraView))
                camera.worldTransform = worldFromCamera.toTransform()
                if (!reportedCameraMatrix) {
                    val poseView = FloatArray(16).also { frame.displayOrientedPose.inverse().toMatrix(it, 0) }
                    val difference = cameraView.indices.maxOf { kotlin.math.abs(cameraView[it] - poseView[it]) }
                    Log.i("QsAr", "Huawei native view / display pose difference: $difference")
                    reportedCameraMatrix = true
                }
                val projection = frame.projection(0.05f, 60f)
                camera.camera.setCustomProjection(DoubleArray(16) { projection[it].toDouble() }, 0.05, 60.0)
            }
            onFrame(handle, frame)
        } catch (error: Exception) {
            fail(error, onFailure)
        } catch (error: LinkageError) {
            fail(error, onFailure)
        }
    }

    private fun fail(error: Throwable, onFailure: (String) -> Unit) {
        failed = true
        Log.e("QsAr", "Huawei AR session failed", error)
        pause()
        onFailure(arFailureMessage(ArBackend.HUAWEI, error))
    }

    fun pause() {
        nextCameraUpdateNanos = 0L
        if (resumed) {
            runCatching { session?.native?.pause() }
            resumed = false
        }
    }

    fun destroy() {
        failed = true
        pause()
        session?.stop()
        session = null
        background?.let {
            if (attached) scene.removeEntity(it.entity)
            it.destroy()
        }
        background = null
        attached = false
    }
}
