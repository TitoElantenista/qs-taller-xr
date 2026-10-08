package com.qs.tallerxr.ui

import com.qs.tallerxr.BuildConfig
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.filament.Engine
import com.google.android.filament.View as FilamentView
import com.google.android.filament.gltfio.FilamentInstance
import com.qs.tallerxr.ar.AlignmentPattern
import com.qs.tallerxr.ar.CorrespondenceLink
import com.qs.tallerxr.ar.CorrespondencePick
import com.qs.tallerxr.ar.ManualCorrespondenceSolver
import com.qs.tallerxr.ar.ArBackend
import com.qs.tallerxr.ar.checkArAvailability
import com.qs.tallerxr.ar.probeArBackend
import androidx.compose.runtime.rememberCoroutineScope
import com.qs.tallerxr.ar.MarkerlessRegistrationModel
import com.qs.tallerxr.ar.MarkerlessRegistrationModelReader
import com.qs.tallerxr.ar.ModelLandmarkSet
import com.qs.tallerxr.ar.ModelLandmarks
import com.qs.tallerxr.ar.LandmarkVisibility
import com.qs.tallerxr.importer.GlbReader
import com.qs.tallerxr.quality.QualityRecord
import com.qs.tallerxr.quality.QualityStatus
import com.qs.tallerxr.ar.SavedArPhoto
import com.qs.tallerxr.data.InstalledPackage
import com.qs.tallerxr.data.Part
import com.qs.tallerxr.diagnostics.ProcessExitDiagnostics
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

private enum class ViewerMode { Model3D, AR }

internal enum class ViewerBackAction {
    IgnoreTransition,
    CollapseArControls,
    ExitArFullscreen,
    ExitAr,
    CloseViewer,
}

internal fun viewerBackAction(
    arActive: Boolean,
    arControlsExpanded: Boolean,
    arFullscreen: Boolean,
    transitionInProgress: Boolean = false,
): ViewerBackAction = when {
    transitionInProgress -> ViewerBackAction.IgnoreTransition
    arActive && arControlsExpanded -> ViewerBackAction.CollapseArControls
    arActive && arFullscreen -> ViewerBackAction.ExitArFullscreen
    arActive -> ViewerBackAction.ExitAr
    else -> ViewerBackAction.CloseViewer
}

internal class ViewerRenderResources(
    val engine: Engine,
    val modelLoader: ModelLoader,
    val materialLoader: MaterialLoader,
)

private const val VIEWER_APPEARANCE_RELEASE_MILLIS = 48L
private const val VIEWER_EMPTY_HANDOFF_MILLIS = 32L

internal enum class ViewerSurface { MODEL, AR }

internal enum class ViewerRenderStage(
    val surface: ViewerSurface?,
    val appearanceAttached: Boolean,
    val transitionInProgress: Boolean,
) {
    MODEL_ACTIVE(ViewerSurface.MODEL, true, false),
    MODEL_RELEASING_FOR_AR(ViewerSurface.MODEL, false, true),
    AWAITING_MODEL_DISPOSAL_FOR_AR(null, false, true),
    MODEL_DISPOSED_FOR_AR(null, false, true),
    AR_ACTIVE(ViewerSurface.AR, true, false),
    AR_RELEASING_FOR_MODEL(ViewerSurface.AR, false, true),
    AWAITING_AR_DISPOSAL_FOR_MODEL(null, false, true),
    AR_DISPOSED_FOR_MODEL(null, false, true),
    MODEL_RELEASING_FOR_CLOSE(ViewerSurface.MODEL, false, true),
    AWAITING_MODEL_DISPOSAL_FOR_CLOSE(null, false, true),
    MODEL_DISPOSED_FOR_CLOSE(null, false, true),
}

internal enum class ViewerRenderEvent {
    REQUEST_AR,
    REQUEST_MODEL,
    REQUEST_CLOSE,
    APPEARANCE_RELEASED,
    VIEWPORT_DISPOSED,
    EMPTY_HANDOFF_COMPLETE,
}

internal fun nextViewerRenderStage(
    stage: ViewerRenderStage,
    event: ViewerRenderEvent,
): ViewerRenderStage = when (stage) {
    ViewerRenderStage.MODEL_ACTIVE -> when (event) {
        ViewerRenderEvent.REQUEST_AR -> ViewerRenderStage.MODEL_RELEASING_FOR_AR
        ViewerRenderEvent.REQUEST_CLOSE -> ViewerRenderStage.MODEL_RELEASING_FOR_CLOSE
        else -> stage
    }
    ViewerRenderStage.MODEL_RELEASING_FOR_AR -> when (event) {
        ViewerRenderEvent.APPEARANCE_RELEASED -> ViewerRenderStage.AWAITING_MODEL_DISPOSAL_FOR_AR
        else -> stage
    }
    ViewerRenderStage.AWAITING_MODEL_DISPOSAL_FOR_AR -> when (event) {
        ViewerRenderEvent.VIEWPORT_DISPOSED -> ViewerRenderStage.MODEL_DISPOSED_FOR_AR
        else -> stage
    }
    ViewerRenderStage.MODEL_DISPOSED_FOR_AR -> when (event) {
        ViewerRenderEvent.EMPTY_HANDOFF_COMPLETE -> ViewerRenderStage.AR_ACTIVE
        else -> stage
    }
    ViewerRenderStage.AR_ACTIVE -> when (event) {
        ViewerRenderEvent.REQUEST_MODEL -> ViewerRenderStage.AR_RELEASING_FOR_MODEL
        else -> stage
    }
    ViewerRenderStage.AR_RELEASING_FOR_MODEL -> when (event) {
        ViewerRenderEvent.APPEARANCE_RELEASED -> ViewerRenderStage.AWAITING_AR_DISPOSAL_FOR_MODEL
        else -> stage
    }
    ViewerRenderStage.AWAITING_AR_DISPOSAL_FOR_MODEL -> when (event) {
        ViewerRenderEvent.VIEWPORT_DISPOSED -> ViewerRenderStage.AR_DISPOSED_FOR_MODEL
        else -> stage
    }
    ViewerRenderStage.AR_DISPOSED_FOR_MODEL -> when (event) {
        ViewerRenderEvent.EMPTY_HANDOFF_COMPLETE -> ViewerRenderStage.MODEL_ACTIVE
        else -> stage
    }
    ViewerRenderStage.MODEL_RELEASING_FOR_CLOSE -> when (event) {
        ViewerRenderEvent.APPEARANCE_RELEASED -> ViewerRenderStage.AWAITING_MODEL_DISPOSAL_FOR_CLOSE
        else -> stage
    }
    ViewerRenderStage.AWAITING_MODEL_DISPOSAL_FOR_CLOSE -> when (event) {
        ViewerRenderEvent.VIEWPORT_DISPOSED -> ViewerRenderStage.MODEL_DISPOSED_FOR_CLOSE
        else -> stage
    }
    ViewerRenderStage.MODEL_DISPOSED_FOR_CLOSE -> stage
}

private class PausableViewportLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private var active = false

    override val lifecycle: Lifecycle = registry

    fun update(active: Boolean, parentState: Lifecycle.State) {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        this.active = active
        registry.currentState = when {
            parentState == Lifecycle.State.DESTROYED -> Lifecycle.State.DESTROYED
            active -> parentState
            parentState.isAtLeast(Lifecycle.State.STARTED) -> Lifecycle.State.STARTED
            else -> parentState
        }
    }

    fun updateParent(parentState: Lifecycle.State) = update(active, parentState)

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

@Composable
private fun rememberViewportLifecycle(active: Boolean): Lifecycle {
    val parentLifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = remember { PausableViewportLifecycleOwner() }
    SideEffect { owner.update(active, parentLifecycle.currentState) }
    DisposableEffect(owner, parentLifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            owner.updateParent(parentLifecycle.currentState)
        }
        parentLifecycle.addObserver(observer)
        owner.update(active, parentLifecycle.currentState)
        onDispose {
            parentLifecycle.removeObserver(observer)
            owner.destroy()
        }
    }
    return owner.lifecycle
}

@Composable
internal fun rememberViewerRenderResources(): ViewerRenderResources {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val resources = remember(engine, modelLoader, materialLoader) {
        ViewerRenderResources(engine, modelLoader, materialLoader)
    }
    return resources
}

@Composable
private fun ExclusiveViewerSurfaceHost(
    onDisposed: () -> Unit,
    content: @Composable (ViewerRenderResources) -> Unit,
) {
    val currentOnDisposed = rememberUpdatedState(onDisposed)
    // Register this before every native resource and child effect. Compose forgets effects in
    // reverse order, so this callback is the final acknowledgement after nodes, loaders and the
    // engine have completed their synchronous onDispose work.
    DisposableEffect(Unit) {
        onDispose { currentOnDisposed.value() }
    }
    val resources = rememberViewerRenderResources()
    content(resources)
}

@Composable
internal fun rememberManagedModelInstance(
    renderResources: ViewerRenderResources,
    fileLocation: String,
): ManagedModelLoad {
    val modelLoader = renderResources.modelLoader
    val load = remember(modelLoader, fileLocation) { ManagedModelLoad() }
    LaunchedEffect(load) {
        try {
            // The SceneView helper returns as soon as asyncBeginLoad starts. Keep the model
            // private until the resources are ready, including when a new engine reloads the
            // same URI after AR. Node/material bindings must not capture an unfinished asset.
            val instance = modelLoader.loadModelInstance(fileLocation)
                ?: error("Die GLB-Datei konnte nicht geladen werden.")
            val ready = withTimeoutOrNull(30_000L) {
                do {
                    currentCoroutineContext().ensureActive()
                    modelLoader.updateLoad()
                    if (modelLoader.progress >= 1f) break
                    withFrameNanos { }
                } while (true)
                true
            } == true
            check(ready) { "Das 3D-Modell konnte nicht fertig geladen werden. Bitte erneut öffnen." }
            check(instance.asset.renderableEntities.isNotEmpty()) {
                "Die GLB-Datei enthält keine darstellbare Geometrie."
            }
            load.instance = instance
        } catch (cancelled: CancellationException) {
            // Never publish a result into a viewport whose native resources are being disposed.
            throw cancelled
        } catch (error: Exception) {
            load.error = error.message ?: "Das 3D-Modell konnte nicht geladen werden."
        }
    }
    return load
}

internal class ManagedModelLoad {
    var instance by mutableStateOf<FilamentInstance?>(null)
    var error by mutableStateOf<String?>(null)
}

internal data class PartLabelVisual(
    val part: Part,
    val text: String,
    val position: Float3,
)

private data class PartLabelCandidate(
    val part: Part,
    val text: String,
    val lower: List<Float>,
    val upper: List<Float>,
)

internal data class PartScreenBounds(
    val part: Part,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val depth: Float = 0f,
) {
    val area: Float = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)

    fun contains(x: Float, y: Float, tolerancePx: Float): Boolean =
        x >= left - tolerancePx &&
            x <= right + tolerancePx &&
            y >= top - tolerancePx &&
            y <= bottom + tolerancePx

    fun centerDistanceSquared(x: Float, y: Float): Float {
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val dx = centerX - x
        val dy = centerY - y
        return dx * dx + dy * dy
    }
}

internal data class CameraFrame(
    val target: Float3,
    val home: Float3,
)

internal data class TapPoint(val x: Float, val y: Float)

private data class PartScreenLabel(
    val text: String,
    val x: Float,
    val y: Float,
)

private class PartLabelOverlayState {
    var labels by mutableStateOf<List<PartScreenLabel>>(emptyList())
        private set

    fun update(value: List<PartScreenLabel>) {
        labels = value
    }

    fun clear() {
        if (labels.isNotEmpty()) labels = emptyList()
    }
}

private class PartPickCoordinator {
    private var generation = 0L

    fun begin(): Long {
        generation += 1L
        return generation
    }

    fun isCurrent(request: Long): Boolean = request == generation

    fun invalidate() {
        generation += 1L
    }
}

private class FrameRateLimiter(private val intervalNanos: Long) {
    private var lastFrameNanos = Long.MIN_VALUE

    fun shouldRun(frameTimeNanos: Long): Boolean {
        if (
            lastFrameNanos == Long.MIN_VALUE ||
            frameTimeNanos < lastFrameNanos ||
            frameTimeNanos - lastFrameNanos >= intervalNanos
        ) {
            lastFrameNanos = frameTimeNanos
            return true
        }
        return false
    }

    fun reset() {
        lastFrameNanos = Long.MIN_VALUE
    }
}

internal class PartEntityIndex(parts: List<Part>) {
    private val byNodeName = parts
        .mapNotNull { part -> part.gltfNodeName.trim().takeIf(String::isNotEmpty)?.let { it to part } }
        .toMap()
    private val byNodeNameLowercase = byNodeName.mapKeys { (name, _) -> name.lowercase() }
    private val byGuidLowercase = parts
        .mapNotNull { part -> part.guid.trim().takeIf(String::isNotEmpty)?.lowercase()?.let { it to part } }
        .toMap()
    private val fallbackParts = parts.filter {
        it.gltfNodeName.isNotBlank() || it.guid.isNotBlank()
    }

    fun find(entityName: String?): Part? {
        val normalized = entityName?.trim()?.takeIf(String::isNotEmpty) ?: return null
        byNodeName[normalized]?.let { return it }
        val lowercase = normalized.lowercase()
        byNodeNameLowercase[lowercase]?.let { return it }
        byGuidLowercase[lowercase]?.let { return it }
        return fallbackParts.firstOrNull { part -> entityNameMatchesPart(normalized, part) }
    }
}

private class TapSelectionTracker {
    private var downX = 0f
    private var downY = 0f
    private var tracking = false

    fun onDown(event: MotionEvent) {
        tracking = event.pointerCount == 1
        downX = event.x
        downY = event.y
    }

    fun consumeTap(event: MotionEvent, slopPx: Float): TapPoint? {
        if (!tracking || event.pointerCount != 1) {
            reset()
            return null
        }
        val dx = event.x - downX
        val dy = event.y - downY
        val durationMs = event.eventTime - event.downTime
        val point = if (durationMs <= 450L && dx * dx + dy * dy <= slopPx * slopPx) {
            TapPoint(event.x, event.y)
        } else {
            null
        }
        reset()
        return point
    }

    fun reset() {
        tracking = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PackageViewer(
    packageData: InstalledPackage,
    availableRevisions: List<InstalledPackage>,
    qualityRecord: QualityRecord?,
    inspectorName: String,
    onOpenQuality: () -> Unit,
    onOpenQualitySettings: () -> Unit,
    onSaveQualityPhoto: suspend (SavedArPhoto, String, String) -> Unit,
    onBack: () -> Unit,
    active: Boolean,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    val packageId = packageData.manifest.packageId
    var renderStage by remember(packageId) { mutableStateOf(ViewerRenderStage.MODEL_ACTIVE) }
    var appearance by remember { mutableStateOf(AssemblyAppearance()) }
    var selectedPart by remember(packageData.manifest.packageId) { mutableStateOf<Part?>(null) }
    var compareWith by remember(packageData.manifest.packageId) {
        mutableStateOf<InstalledPackage?>(null)
    }
    var showComparisonDetails by remember { mutableStateOf(false) }
    var partLabelsVisible by remember(packageId) { mutableStateOf(false) }
    var centerRequest by remember(packageId) { mutableIntStateOf(0) }
    var arFullscreen by remember(packageId) { mutableStateOf(false) }
    var arControlsExpanded by remember(packageId) { mutableStateOf(false) }
    val arScope = rememberCoroutineScope()
    val currentActive = rememberUpdatedState(active)
    val currentPackageId = rememberUpdatedState(packageId)
    var arChecking by remember(packageId) { mutableStateOf(false) }
    var arCandidates by remember(packageId) { mutableStateOf<List<ArBackend>>(emptyList()) }
    var arBackend by remember(packageId) { mutableStateOf(ArBackend.GOOGLE) }
    var arPermissionPending by remember(packageId) { mutableStateOf(false) }
    var arPackageData by remember(packageId) { mutableStateOf(packageData) }
    var arStartError by remember(packageId) { mutableStateOf<String?>(null) }
    var landscapeAppearanceExpanded by remember { mutableStateOf(false) }
    // Landmarks and lines chosen in the 3D viewer and marked again on the real part inside AR.
    // They live here because both viewports are recreated independently while the operator
    // switches modes.  Earlier states back «Rückgängig» for points, lines and removals alike.
    var alignmentPattern by remember(packageId) { mutableStateOf(AlignmentPattern()) }
    var alignmentHistory by remember(packageId) { mutableStateOf<List<AlignmentPattern>>(emptyList()) }
    val alignmentPicks = alignmentPattern.picks
    // Last rendered view is captured before releasing the native 3D surface for AR.
    var alignmentViewerCamera by remember(packageId) { mutableStateOf<FloatArray?>(null) }
    var alignmentReference by remember(packageId) { mutableStateOf<ModelReferenceImage?>(null) }
    var referenceCapturePending by remember(packageId) { mutableStateOf(false) }
    // Experimental «Snap»: the operator turns the model the way the part lies, then AR pulls it
    // onto the real edges.  The 3D camera at that moment becomes the aim in AR.
    var snapMode by remember(packageId) { mutableStateOf(false) }
    var snapViewerCamera by remember(packageId) { mutableStateOf<SnapViewerCamera?>(null) }
    var snapCapturePending by remember(packageId) { mutableStateOf(false) }
    var alignmentPickMode by remember(packageId) { mutableStateOf(false) }
    val mode = if (renderStage == ViewerRenderStage.AR_ACTIVE) ViewerMode.AR else ViewerMode.Model3D
    val transitionInProgress = renderStage.transitionInProgress || arPermissionPending || arChecking ||
        referenceCapturePending || snapCapturePending
    val closeRequested = renderStage in setOf(
        ViewerRenderStage.MODEL_RELEASING_FOR_CLOSE,
        ViewerRenderStage.AWAITING_MODEL_DISPOSAL_FOR_CLOSE,
        ViewerRenderStage.MODEL_DISPOSED_FOR_CLOSE,
    )
    val hasMarkerlessModel = packageData.registrationModelFile.isFile
    val comparison = remember(packageData, compareWith) {
        compareWith?.let { compareRevisions(packageData, it) }
    }
    val partChanges = comparison?.currentChanges.orEmpty()
    val deviceLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current

    fun beginArTransition() {
        if (!active || renderStage != ViewerRenderStage.MODEL_ACTIVE) return
        arPermissionPending = false
        val probe = probeArBackend(context, arCandidates)
        val selected = probe.backend
        if (selected == null) {
            arStartError = probe.error
            return
        }
        arBackend = selected
        arControlsExpanded = false
        arPackageData = packageData
        if (snapMode) {
            // The orientation the operator just set is the aim; copy it before the surface goes.
            alignmentPickMode = false
            snapCapturePending = true
        } else if (alignmentPicks.isNotEmpty()) {
            // Copy the actual final view before its exclusive surface is released.
            referenceCapturePending = true
        } else {
            alignmentReference = null
            alignmentPickMode = false
            renderStage = nextViewerRenderStage(renderStage, ViewerRenderEvent.REQUEST_AR)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        arPermissionPending = false
        if (granted && active && renderStage == ViewerRenderStage.MODEL_ACTIVE) {
            beginArTransition()
        } else if (!granted && active && renderStage == ViewerRenderStage.MODEL_ACTIVE) {
            arStartError = "Für den AR-Modus wird die Kamera benötigt."
        }
    }

    fun prepareAr() {
        if (!active || transitionInProgress || renderStage != ViewerRenderStage.MODEL_ACTIVE) return
        ProcessExitDiagnostics.recordState(context, "viewer:ar-start")
        arChecking = true
        arScope.launch {
            try {
                val availability = checkArAvailability(context)
                if (!currentActive.value || currentPackageId.value != packageId ||
                    renderStage != ViewerRenderStage.MODEL_ACTIVE) return@launch
                arCandidates = availability.candidates
                if (arCandidates.isEmpty()) {
                    arStartError = availability.unavailableMessage()
                    return@launch
                }
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED) {
                    arPermissionPending = true
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    beginArTransition()
                }
            } finally {
                arChecking = false
            }
        }
    }

    fun requestClose() {
        if (renderStage == ViewerRenderStage.MODEL_ACTIVE && !arPermissionPending && !referenceCapturePending &&
            !snapCapturePending) {
            renderStage = nextViewerRenderStage(renderStage, ViewerRenderEvent.REQUEST_CLOSE)
        }
    }

    fun exitAr() {
        if (renderStage != ViewerRenderStage.AR_ACTIVE) return
        ProcessExitDiagnostics.recordState(context, "viewer:ar-exit")
        arControlsExpanded = false
        arFullscreen = false
        alignmentPickMode = false
        renderStage = nextViewerRenderStage(renderStage, ViewerRenderEvent.REQUEST_MODEL)
    }

    LaunchedEffect(packageId, renderStage) {
        ProcessExitDiagnostics.recordState(
            context,
            when (renderStage) {
                ViewerRenderStage.MODEL_ACTIVE -> "viewer:3d"
                ViewerRenderStage.MODEL_RELEASING_FOR_AR -> "viewer:model-appearance-detached"
                ViewerRenderStage.AWAITING_MODEL_DISPOSAL_FOR_AR -> "viewer:model-dispose"
                ViewerRenderStage.MODEL_DISPOSED_FOR_AR -> "viewer:model-disposed"
                ViewerRenderStage.AR_ACTIVE -> "viewer:ar-active"
                ViewerRenderStage.AR_RELEASING_FOR_MODEL -> "viewer:ar-appearance-detached"
                ViewerRenderStage.AWAITING_AR_DISPOSAL_FOR_MODEL -> "viewer:ar-dispose"
                ViewerRenderStage.AR_DISPOSED_FOR_MODEL -> "viewer:ar-disposed"
                ViewerRenderStage.MODEL_RELEASING_FOR_CLOSE -> "viewer:appearance-detached"
                ViewerRenderStage.AWAITING_MODEL_DISPOSAL_FOR_CLOSE -> "viewer:node-dispose"
                ViewerRenderStage.MODEL_DISPOSED_FOR_CLOSE -> "viewer:nodes-detached"
            },
        )
        when (renderStage) {
            ViewerRenderStage.MODEL_RELEASING_FOR_AR,
            ViewerRenderStage.AR_RELEASING_FOR_MODEL,
            ViewerRenderStage.MODEL_RELEASING_FOR_CLOSE,
            -> {
                delay(VIEWER_APPEARANCE_RELEASE_MILLIS)
                renderStage = nextViewerRenderStage(
                    renderStage,
                    ViewerRenderEvent.APPEARANCE_RELEASED,
                )
            }
            ViewerRenderStage.MODEL_DISPOSED_FOR_AR,
            ViewerRenderStage.AR_DISPOSED_FOR_MODEL,
            -> {
                // The previous host has left composition and destroyed its engine/loaders.
                // Keep one confirmed EMPTY frame before allocating the other renderer.
                withFrameNanos { }
                delay(VIEWER_EMPTY_HANDOFF_MILLIS)
                renderStage = nextViewerRenderStage(
                    renderStage,
                    ViewerRenderEvent.EMPTY_HANDOFF_COMPLETE,
                )
            }
            ViewerRenderStage.MODEL_DISPOSED_FOR_CLOSE -> {
                withFrameNanos { }
                ProcessExitDiagnostics.recordState(context, "viewer:dispose")
                onBack()
            }
            else -> Unit
        }
    }
    LaunchedEffect(active) {
        if (!active) {
            referenceCapturePending = false
            snapCapturePending = false
            arFullscreen = false
            arControlsExpanded = false
            alignmentPickMode = false
            showComparisonDetails = false
            selectedPart = null
        }
    }
    FullscreenSystemBars(active && renderStage == ViewerRenderStage.AR_ACTIVE && arFullscreen)
    BackHandler(enabled = active) {
        when (
            viewerBackAction(
                arActive = renderStage == ViewerRenderStage.AR_ACTIVE,
                arControlsExpanded = arControlsExpanded,
                arFullscreen = arFullscreen,
                transitionInProgress = transitionInProgress,
            )
        ) {
            ViewerBackAction.IgnoreTransition -> Unit
            ViewerBackAction.CollapseArControls -> arControlsExpanded = false
            ViewerBackAction.ExitArFullscreen -> arFullscreen = false
            ViewerBackAction.ExitAr -> exitAr()
            ViewerBackAction.CloseViewer -> requestClose()
        }
    }

    arStartError?.let { message ->
        AlertDialog(
            onDismissRequest = { arStartError = null },
            title = { Text("AR kann nicht gestartet werden") },
            text = { Text(message) },
            confirmButton = {
                Button(onClick = { arStartError = null }) { Text("Schließen") }
            },
        )
    }

    selectedPart?.let { part ->
        PartDetailsDialog(part = part, onDismiss = { selectedPart = null })
    }
    if (showComparisonDetails && comparison != null && compareWith != null) {
        ComparisonDetailsDialog(
            current = packageData,
            previous = compareWith!!,
            comparison = comparison,
            onDismiss = { showComparisonDetails = false },
        )
    }

    Scaffold(
        // Keep the 3D chrome measured until its surface is released. Hiding it merely because
        // an AR check/copy is busy resizes the viewport before PixelCopy consumes its old buffer.
        modifier = modifier.pointerInput(transitionInProgress) {
            if (transitionInProgress) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        },
        topBar = {
            if (renderStage.surface == ViewerSurface.MODEL && !deviceLandscape) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = ::requestClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    },
                    title = {
                        Column {
                            Text(packageData.manifest.assembly.positionNumber, fontWeight = FontWeight.Bold)
                            Text(
                                subtitle
                                    ?: "${packageData.manifest.project.name} · Rev. ${packageData.manifest.assembly.revision}",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenQuality) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Qualitätssicherung",
                                tint = if (qualityRecord?.status == QualityStatus.OK) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val landscape = maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                if (renderStage.surface == ViewerSurface.MODEL) {
                    if (!landscape) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ViewerModeButtons(
                                mode = mode,
                                arAvailable = hasMarkerlessModel,
                                onModeChange = { requested ->
                                    if (requested == ViewerMode.AR) prepareAr()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (!landscape || landscapeAppearanceExpanded) {
                        AppearanceControls(
                            appearance = appearance,
                            compact = landscape,
                            cavitySupported = true,
                            onChange = { appearance = it },
                        )
                    }
                    ViewerActions(
                        packageData = packageData,
                        availableRevisions = availableRevisions,
                        compareWith = compareWith,
                        comparison = comparison,
                        qualityRecord = qualityRecord,
                        onCompare = { compareWith = it },
                        onComparisonDetails = { showComparisonDetails = true },
                        onCheck = onOpenQuality,
                        partLabelsVisible = partLabelsVisible,
                        viewportActionsEnabled = true,
                        onPartLabelsVisibleChange = { partLabelsVisible = it },
                        onCenter = { centerRequest += 1 },
                        alignmentAvailable = hasMarkerlessModel,
                        alignmentPickCount = alignmentPicks.size,
                        alignmentPickMode = alignmentPickMode,
                        onAlignmentPickModeChange = { enabled ->
                            alignmentPickMode = enabled
                            if (enabled) {
                                selectedPart = null
                                snapMode = false
                            }
                        },
                        snapMode = snapMode,
                        onSnapModeChange = { enabled ->
                            snapMode = enabled
                            if (enabled) {
                                selectedPart = null
                                alignmentPickMode = false
                            }
                        },
                        leadingContent = if (landscape) {
                            {
                                IconButton(onClick = ::requestClose) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                                }
                                Text(
                                    if (availableRevisions.size > 1) {
                                        "${packageData.manifest.assembly.positionNumber} · R${packageData.manifest.assembly.revision}"
                                    } else {
                                        packageData.manifest.assembly.positionNumber
                                    },
                                    fontWeight = FontWeight.Bold,
                                )
                                ViewerModeButtons(
                                    mode = mode,
                                    arAvailable = hasMarkerlessModel,
                                    onModeChange = { requested ->
                                        if (requested == ViewerMode.AR) prepareAr()
                                    },
                                    modifier = Modifier.width(145.dp),
                                )
                                OutlinedButton(
                                    onClick = {
                                        landscapeAppearanceExpanded = !landscapeAppearanceExpanded
                                    },
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null)
                                    Spacer(Modifier.width(5.dp))
                                    Text(if (landscapeAppearanceExpanded) "Ansicht ausblenden" else "Ansicht")
                                }
                            }
                        } else {
                            {}
                        },
                    )
                }
                Box(Modifier.fillMaxSize()) {
                    when (renderStage.surface) {
                        ViewerSurface.MODEL -> key("$packageId:model-viewport") {
                            ExclusiveViewerSurfaceHost(
                                onDisposed = {
                                    renderStage = nextViewerRenderStage(
                                        renderStage,
                                        ViewerRenderEvent.VIEWPORT_DISPOSED,
                                    )
                                },
                            ) { renderResources ->
                                val lifecycle = rememberViewportLifecycle(
                                    active && renderStage == ViewerRenderStage.MODEL_ACTIVE,
                                )
                                ModelViewport(
                                    packageData = packageData,
                                    renderResources = renderResources,
                                    appearance = appearance,
                                    partChanges = partChanges,
                                    selectedPartGuid = selectedPart?.guid,
                                    onPartSelected = { selectedPart = it },
                                    partLabelsVisible = partLabelsVisible,
                                    centerRequest = centerRequest,
                                    alignmentPickMode = alignmentPickMode,
                                    alignmentPattern = alignmentPattern,
                                    onAlignmentPatternChange = { pattern, viewerCamera ->
                                        alignmentHistory = (alignmentHistory + alignmentPattern)
                                            .takeLast(ALIGNMENT_UNDO_STEPS)
                                        alignmentPattern = pattern
                                        alignmentViewerCamera = viewerCamera
                                    },
                                    canUndoAlignment = alignmentHistory.isNotEmpty(),
                                    onUndoAlignment = undo@{
                                        val previous = alignmentHistory.lastOrNull() ?: return@undo null
                                        val description = alignmentPattern.describeChangeTo(previous, restoring = true)
                                        alignmentHistory = alignmentHistory.dropLast(1)
                                        alignmentPattern = previous
                                        description
                                    },
                                    referenceCaptureRequested = referenceCapturePending,
                                    snapMode = snapMode,
                                    snapCameraRequested = snapCapturePending,
                                    onSnapCameraCaptured = { camera ->
                                        if (snapCapturePending && active && renderStage == ViewerRenderStage.MODEL_ACTIVE) {
                                            snapCapturePending = false
                                            snapViewerCamera = camera
                                            alignmentReference = null
                                            renderStage = nextViewerRenderStage(renderStage, ViewerRenderEvent.REQUEST_AR)
                                        }
                                    },
                                    interactionBlocked = transitionInProgress,
                                    onReferenceCaptured = { result ->
                                        if (referenceCapturePending && active && renderStage == ViewerRenderStage.MODEL_ACTIVE) {
                                            referenceCapturePending = false
                                            result.onSuccess { reference ->
                                                alignmentReference = reference
                                                alignmentViewerCamera = reference.cameraQuaternion
                                                alignmentPickMode = false
                                                renderStage = nextViewerRenderStage(renderStage, ViewerRenderEvent.REQUEST_AR)
                                            }.onFailure { error ->
                                                arStartError = "3D-Punkthilfe konnte nicht übernommen werden: ${error.message} Bitte AR erneut öffnen."
                                            }
                                        }
                                    },
                                    appearanceAttached = active && renderStage.appearanceAttached,
                                    lifecycle = lifecycle,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        ViewerSurface.AR -> key("$packageId:ar-viewport") {
                            ExclusiveViewerSurfaceHost(
                                onDisposed = {
                                    renderStage = nextViewerRenderStage(
                                        renderStage,
                                        ViewerRenderEvent.VIEWPORT_DISPOSED,
                                    )
                                },
                            ) { renderResources ->
                                val lifecycle = rememberViewportLifecycle(
                                    active && renderStage == ViewerRenderStage.AR_ACTIVE,
                                )
                                ArAlignmentViewport(
                                    backend = arBackend,
                                    packageData = arPackageData,
                                    renderResources = renderResources,
                                    appearance = appearance,
                                    partChanges = partChanges,
                                    selectedPartGuid = selectedPart?.guid,
                                    alignmentPattern = alignmentPattern,
                                    viewerCameraQuaternion = alignmentViewerCamera,
                                    alignmentMethod = if (snapMode) AlignmentMethod.SNAP else AlignmentMethod.POINTS,
                                    snapViewerCamera = snapViewerCamera,
                                    modelReference = alignmentReference,
                                    landscape = landscape,
                                    fullscreen = arFullscreen,
                                    controlsExpanded = arControlsExpanded,
                                    appearanceAttached = active && renderStage.appearanceAttached,
                                    onControlsExpandedChange = { arControlsExpanded = it },
                                    onFullscreenChange = { arFullscreen = it },
                                    qualityAvailable = qualityRecord != null,
                                    inspectorName = inspectorName,
                                    onOpenQuality = onOpenQuality,
                                    onOpenQualitySettings = onOpenQualitySettings,
                                    onSaveQualityPhoto = onSaveQualityPhoto,
                                    onExitAr = ::exitAr,
                                    lifecycle = lifecycle,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        null -> Unit
                    }
                    if (transitionInProgress) {
                        Column(
                            Modifier.fillMaxSize().background(Color(0xFF071019)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                            Text(
                                when {
                                    referenceCapturePending -> "3D-Punkthilfe wird übernommen…"
                                    snapCapturePending -> "Ausrichtung für Snap wird übernommen…"
                                    arPermissionPending -> "Warte auf Kameraberechtigung…"
                                    closeRequested -> "3D-Ansicht wird sicher geschlossen…"
                                    renderStage in setOf(
                                        ViewerRenderStage.MODEL_RELEASING_FOR_AR,
                                        ViewerRenderStage.AWAITING_MODEL_DISPOSAL_FOR_AR,
                                        ViewerRenderStage.MODEL_DISPOSED_FOR_AR,
                                    ) -> "AR-Sitzung wird vorbereitet…"
                                    else -> "AR wird beendet und das 3D-Modell wiederhergestellt…"
                                },
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ViewerModeButtons(
    mode: ViewerMode,
    arAvailable: Boolean,
    onModeChange: (ViewerMode) -> Unit,
    modifier: Modifier,
) {
    ModeButton(
        selected = mode == ViewerMode.Model3D,
        enabled = true,
        label = "3D",
        icon = { Icon(Icons.Default.ViewInAr, contentDescription = null) },
        onClick = { onModeChange(ViewerMode.Model3D) },
        modifier = modifier,
    )
    ModeButton(
        selected = mode == ViewerMode.AR,
        enabled = arAvailable,
        label = "AR",
        icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
        onClick = { onModeChange(ViewerMode.AR) },
        modifier = modifier,
    )
}

@Composable
private fun ModeButton(
    selected: Boolean,
    enabled: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    if (selected) {
        Button(enabled = enabled, onClick = onClick, modifier = modifier) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    } else {
        OutlinedButton(enabled = enabled, onClick = onClick, modifier = modifier) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
}

@Composable
private fun ModelViewport(
    packageData: InstalledPackage,
    renderResources: ViewerRenderResources,
    appearance: AssemblyAppearance,
    partChanges: Map<String, PartChange>,
    selectedPartGuid: String?,
    onPartSelected: (Part?) -> Unit,
    partLabelsVisible: Boolean,
    centerRequest: Int,
    alignmentPickMode: Boolean,
    alignmentPattern: AlignmentPattern,
    onAlignmentPatternChange: (AlignmentPattern, FloatArray) -> Unit,
    canUndoAlignment: Boolean,
    /** Restores the previous pattern and describes what changed, or null when nothing is left. */
    onUndoAlignment: () -> String?,
    referenceCaptureRequested: Boolean,
    snapMode: Boolean,
    snapCameraRequested: Boolean,
    onSnapCameraCaptured: (SnapViewerCamera) -> Unit,
    interactionBlocked: Boolean,
    onReferenceCaptured: (Result<ModelReferenceImage>) -> Unit,
    appearanceAttached: Boolean,
    lifecycle: Lifecycle,
    modifier: Modifier,
) {
    val engine = renderResources.engine
    val modelLoader = renderResources.modelLoader
    val materialLoader = renderResources.materialLoader
    val view = rememberView(engine)
    val packageId = packageData.manifest.packageId
    // Width over height of this viewport, in steps of 0.05 so small layout changes do not refit.
    var viewportAspect by remember(packageId) { mutableFloatStateOf(0f) }
    val cameraFrame = remember(packageId, packageData.parts.parts, viewportAspect) {
        cameraFrame(packageData.parts.parts, viewportAspect)
    }
    val cameraNode = rememberCameraNode(engine) {
        position = cameraFrame.home
        lookAt(cameraFrame.target)
    }
    // Free rotation without a fixed «bottom»: a part can be shown upside down or on its side.
    val cameraManipulator = remember(packageId) {
        FreeOrbitCameraManipulator(cameraFrame.home, cameraFrame.target) {
            val projection = cameraNode.camera.getProjectionMatrix(DoubleArray(16))
            2.0 * kotlin.math.atan(1.0 / projection[5])
        }
    }
    LaunchedEffect(cameraFrame, cameraManipulator) { cameraManipulator.updateHome(cameraFrame.home, cameraFrame.target) }
    val currentCameraManipulator = rememberUpdatedState(cameraManipulator)
    val twistTracker = remember(packageId) { TwistTracker() }
    val context = LocalContext.current
    val tapTracker = remember { TapSelectionTracker() }
    val pickCoordinator = remember(packageId) { PartPickCoordinator() }
    val tapSlopPx = remember(context) { ViewConfiguration.get(context).scaledTouchSlop * 1.6f }
    LaunchedEffect(centerRequest, cameraManipulator) {
        if (centerRequest > 0) cameraManipulator.resetToHome()
    }
    val modelLoad = rememberManagedModelInstance(
        renderResources = renderResources,
        fileLocation = packageData.modelLocation,
    )
    val modelInstance = modelLoad.instance
    ConfigureCavity(view, appearance.cavity)
    ConfigureTransparentPicking(
        view = view,
        enabled = appearance.opacity < 0.99f ||
            appearance.style == AssemblyVisualStyle.XRAY ||
            partChanges.values.any { it == PartChange.UNCHANGED },
    )
    ApplyAssemblyAppearance(
        engine,
        materialLoader,
        modelInstance.takeIf { appearanceAttached },
        packageData.parts.parts,
        appearance,
        partChanges,
        selectedPartGuid,
    )
    val selectableParts = remember(
        packageData.parts.parts,
        appearance.fastenersVisible,
        appearance.fabricationMarksVisible,
    ) {
        packageData.parts.parts.filter {
            (appearance.fastenersVisible || !it.isFastenerPart()) &&
                (appearance.fabricationMarksVisible || !it.isFabricationMarkPart())
        }
    }
    val partEntityIndex = remember(selectableParts) { PartEntityIndex(selectableParts) }
    val entityParts = remember(modelInstance, partEntityIndex, selectableParts) {
        modelInstance?.let { instance ->
            buildMap {
                instance.asset.renderableEntities.forEach { entity ->
                    partEntityIndex.find(instance.asset.getName(entity))
                        ?.let { part -> put(entity, part) }
                }
                selectableParts.forEach { part ->
                    part.gltfNodeName.takeIf(String::isNotBlank)?.let { nodeName ->
                        instance.asset.getEntitiesByName(nodeName)
                            .filter { it != 0 }
                            .forEach { entity -> put(entity, part) }
                    }
                }
            }
        }.orEmpty()
    }
    val partLabels = remember(packageData.parts.parts) {
        partLabelVisuals(packageData.parts.parts)
    }
    val visiblePartLabels = remember(
        partLabels,
        appearance.fastenersVisible,
        appearance.fabricationMarksVisible,
    ) {
        partLabels.filter {
            (appearance.fastenersVisible || !it.part.isFastenerPart()) &&
                (appearance.fabricationMarksVisible || !it.part.isFabricationMarkPart())
        }
    }
    val labelOverlayState = remember(packageId) { PartLabelOverlayState() }
    val labelRateLimiter = remember(packageId) { FrameRateLimiter(66_666_667L) }
    LaunchedEffect(partLabelsVisible) {
        if (!partLabelsVisible) {
            labelRateLimiter.reset()
            labelOverlayState.clear()
        }
    }
    val alignmentPicks = alignmentPattern.picks
    val alignmentOverlayState = remember(packageId) { AlignmentOverlayState() }
    var alignmentModel by remember(packageId) { mutableStateOf<MarkerlessRegistrationModel?>(null) }
    var alignmentHint by remember(packageId) { mutableStateOf<String?>(null) }
    val alignmentLandmarks = remember(alignmentModel) {
        alignmentModel?.let { ModelLandmarks.landmarks(it) } ?: ModelLandmarkSet.EMPTY
    }
    LaunchedEffect(packageId, alignmentPickMode) {
        if (!alignmentPickMode) return@LaunchedEffect
        if (alignmentModel == null) {
            runCatching {
                withContext(Dispatchers.IO) {
                    MarkerlessRegistrationModelReader.read(packageData.registrationModelFile)
                }
            }.onSuccess { loaded -> alignmentModel = loaded }
                .onFailure { error ->
                    alignmentHint = "Das Punktmodell konnte nicht gelesen werden: ${error.message}"
                }
        }
    }

    var landmarkVisibility by remember(packageId, selectableParts) { mutableStateOf<LandmarkVisibility?>(null) }
    var visibilityError by remember(packageId, selectableParts) { mutableStateOf<String?>(null) }
    val needsVisibility = alignmentPickMode || alignmentPicks.isNotEmpty()
    LaunchedEffect(packageId, needsVisibility, selectableParts) {
        if (!needsVisibility || landmarkVisibility != null) return@LaunchedEffect
        landmarkVisibility = null
        visibilityError = null
        try {
            landmarkVisibility = withContext(Dispatchers.Default) {
                val scene = withContext(Dispatchers.IO) {
                    if (packageData.modelUri == null) GlbReader.read(packageData.modelFile)
                    else context.contentResolver.openInputStream(Uri.parse(packageData.modelUri))?.use {
                        GlbReader.parse(it.readBytes())
                    } ?: error("Die verknüpfte GLB-Datei ist nicht verfügbar.")
                }
                val allParts = PartEntityIndex(packageData.parts.parts)
                val visibleGuids = selectableParts.map { it.guid }.toSet()
                val meshes = scene.meshes.filter { mesh ->
                    allParts.find(mesh.name)?.let { it.guid in visibleGuids } ?: true
                }
                val triangles = FloatArray(meshes.sumOf { it.triangles.size })
                var offset = 0
                meshes.forEach { mesh -> mesh.triangles.copyInto(triangles, offset); offset += mesh.triangles.size }
                LandmarkVisibility(triangles)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            visibilityError = "Sichtbare Ecken konnten nicht ermittelt werden: ${error.message}"
        }
    }

    fun pointIsVisible(point: FloatArray): Boolean = landmarkVisibility?.isVisible(
        cameraNode.worldPosition.let { floatArrayOf(it.x, it.y, it.z) }, point) == true

    val rootView = LocalView.current
    var viewportWindow by remember(packageId) { mutableStateOf<Rect?>(null) }
    val currentOnReferenceCaptured = rememberUpdatedState(onReferenceCaptured)
    LaunchedEffect(referenceCaptureRequested, landmarkVisibility, visibilityError, viewportWindow) {
        if (!referenceCaptureRequested) return@LaunchedEffect
        if (landmarkVisibility == null && visibilityError == null) return@LaunchedEffect
        try {
            check(visibilityError == null) { visibilityError.orEmpty() }
            // Freeze camera interaction, not rendering. Submit frames at the unchanged viewport
            // before PixelCopy: a surface resize must never leave us copying an old-sized buffer.
            // A layout change cancels this effect and retries with the new geometry.
            repeat(3) { withFrameNanos { } }
            val viewport = checkNotNull(viewportWindow)
            val width = view.viewport.width
            val height = view.viewport.height
            check(width == viewport.width() && height == viewport.height() && width > 0 && height > 0)
            val points = alignmentPicks.map { pick ->
                projectModelPoint(pick.modelPoint, cameraNode, width, height)?.let {
                    ReferencePoint(pick.label, it[0], it[1], pointIsVisible(pick.modelPoint) &&
                        it[0] in 0f..width.toFloat() && it[1] in 0f..height.toFloat())
                } ?: ReferencePoint(pick.label, 0f, 0f, false)
            }
            val bounds = selectableParts.mapNotNull { projectPartBounds(it, cameraNode, width, height) }
            val crop = if (bounds.isEmpty()) Rect(0, 0, width, height) else Rect(
                (bounds.minOf { it.left } - 16).toInt().coerceIn(0, width - 1),
                (bounds.minOf { it.top } - 16).toInt().coerceIn(0, height - 1),
                (bounds.maxOf { it.right } + 16).toInt().coerceIn(1, width),
                (bounds.maxOf { it.bottom } + 16).toInt().coerceIn(1, height),
            )
            check(crop.width() > 0 && crop.height() > 0) { "Das Bauteil liegt außerhalb der 3D-Ansicht." }
            check(modelInstance != null) { "Das 3D-Modell wird noch geladen." }
            val reference = withTimeoutOrNull(2_500L) {
                captureModelReference(rootView.rootView, viewport, crop, points,
                    cameraNode.worldQuaternion.let { floatArrayOf(it.x, it.y, it.z, it.w) })
            } ?: error("Zeitüberschreitung beim Kopieren der 3D-Ansicht.")
            currentOnReferenceCaptured.value(Result.success(reference))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentOnReferenceCaptured.value(Result.failure(error))
        }
    }

    fun viewerCameraQuaternion(): FloatArray = cameraNode.worldQuaternion.let { quaternion ->
        floatArrayOf(quaternion.x, quaternion.y, quaternion.z, quaternion.w)
    }

    // Snap: the camera the operator left the model under is the aim in AR.  Read after two
    // rendered frames so a last orbit step is included; the model itself sits at the origin.
    val currentOnSnapCameraCaptured = rememberUpdatedState(onSnapCameraCaptured)
    LaunchedEffect(snapCameraRequested) {
        if (!snapCameraRequested) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        val position = cameraNode.worldPosition
        val projection = cameraNode.camera.getProjectionMatrix(DoubleArray(16))
        currentOnSnapCameraCaptured.value(
            SnapViewerCamera(
                position = floatArrayOf(position.x, position.y, position.z),
                rotation = viewerCameraQuaternion(),
                projectionY = projection[5].toFloat(),
            ),
        )
    }

    fun alignmentTolerancePx(): Float {
        val viewport = view.viewport
        return (minOf(viewport.width, viewport.height) * 0.05f).coerceIn(44f, 96f)
    }

    /** Chosen points the camera sees now; the candidate index is the pick index (label - 1). */
    fun visiblePickCandidates(): List<AlignmentCandidate> {
        val viewport = view.viewport
        if (viewport.width <= 0 || viewport.height <= 0) return emptyList()
        return alignmentPicks.mapIndexedNotNull { index, pick ->
            if (!pointIsVisible(pick.modelPoint)) return@mapIndexedNotNull null
            projectModelPoint(pick.modelPoint, cameraNode, viewport.width, viewport.height)
                ?.let { projected -> AlignmentCandidate(index, projected[0], projected[1], projected[2]) }
        }
    }

    fun landmarkPoint(index: Int) = alignmentLandmarks.point(index)

    // Corners that already hold a point never become a second point on the same spot.
    val occupiedCorners = remember(alignmentLandmarks, alignmentPattern) {
        (0 until alignmentLandmarks.count).filter { index ->
            alignmentPicks.any { pick -> pick.modelPoint.contentEquals(landmarkPoint(index)) }
        }.toSet()
    }

    fun handleAlignmentTap(point: TapPoint) {
        val viewport = view.viewport
        if (viewport.width <= 0 || viewport.height <= 0) return
        val tolerancePx = alignmentTolerancePx()
        val chosenPick = selectAlignmentCandidate(visiblePickCandidates(), point.x, point.y, tolerancePx)
        if (chosenPick != null) {
            val next = alignmentPattern.withoutPoint(chosenPick.index + 1)
            alignmentHint = (alignmentPattern.describeChangeTo(next) ?: "Punkt entfernt") +
                ". «Rückgängig» holt ihn zurück."
            onAlignmentPatternChange(next, viewerCameraQuaternion())
            return
        }
        if (alignmentPattern.isFull) {
            alignmentHint = "Es sind bereits $ALIGNMENT_MAXIMUM_PICKS Punkte gesetzt. Zum Entfernen einen davon antippen."
            return
        }
        val landmark = selectAlignmentCandidate(
            candidates = alignmentCandidates(
                landmarks = alignmentLandmarks,
                cameraNode = cameraNode,
                width = viewport.width,
                height = viewport.height,
                visibility = landmarkVisibility,
            ),
            x = point.x,
            y = point.y,
            tolerancePx = tolerancePx,
        )
        if (landmark == null) {
            alignmentHint = if (visibilityError != null) {
                visibilityError
            } else if (alignmentLandmarks.count == 0 || landmarkVisibility == null) {
                "Sichtbare Ecken des Modells werden geladen…"
            } else {
                "Dort ist keine erkennbare Ecke. Ansicht drehen oder heranzoomen und einen gelben Punkt antippen."
            }
            return
        }
        val next = alignmentPattern.withPoint(landmarkPoint(landmark.index))
        onAlignmentPatternChange(next, viewerCameraQuaternion())
        alignmentHint = when {
            next.size < 2 -> "P1 gesetzt. Nächste Ecke antippen, oder P1 0,5 s halten und zur Ecke ziehen: " +
                "dann entsteht P2 mit einer Linie von P1."
            next.size == 2 ->
                "P2 gesetzt. Zwei Punkte lassen die Drehung um ihre Achse offen: einen dritten Punkt außerhalb dieser Linie wählen."
            else -> "P${next.size} gesetzt. Jetzt zu «AR» wechseln und die Punkte am Bauteil markieren."
        }
    }

    // A chosen point held still for 0.5 s becomes the start of a line: dragging to a corner sets
    // the next point with that line, dragging to another point joins both.  Before the hold the
    // camera keeps its orbit and a quick tap still removes the point.
    val linkGesture = remember(packageId, tapSlopPx) { LinkHoldGesture(PRECISION_HOLD_MILLIS, tapSlopPx) }
    var linkDrag by remember(packageId) { mutableStateOf<LinkDragUi?>(null) }
    val cornerCache = remember(packageId) { AlignmentCornerCache() }
    val magnifierZoom = remember(context) {
        MagnifierZoom.fromFactor(
            context.getSharedPreferences("ar-view-options", Context.MODE_PRIVATE).getInt("magnifier-zoom", 3),
        )
    }

    fun linkTargetAt(origin: Int, position: Offset): LinkTarget? = selectLinkTarget(
        origin = origin,
        points = visiblePickCandidates(),
        corners = cornerCache.corners,
        occupiedCorners = occupiedCorners,
        x = position.x,
        y = position.y,
        tolerancePx = alignmentTolerancePx(),
    )

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val currentLinkHold = rememberUpdatedState<() -> Unit> {
        if (linkGesture.activate(SystemClock.uptimeMillis())) {
            val origin = linkGesture.origin
            val viewport = view.viewport
            val projected = alignmentPicks.getOrNull(origin - 1)?.let { pick ->
                projectModelPoint(pick.modelPoint, cameraNode, viewport.width, viewport.height)
            }
            if (projected == null) {
                linkGesture.cancel()
            } else {
                rootView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                linkDrag = LinkDragUi(
                    origin = origin,
                    originPosition = Offset(projected[0], projected[1]),
                    position = linkGesture.position,
                    target = linkTargetAt(origin, linkGesture.position),
                )
                alignmentHint = "Linie von P$origin: zu einer gelben Ecke oder zu einem anderen Punkt ziehen und loslassen."
            }
        }
    }
    val linkHoldRunnable = remember(packageId) { Runnable { currentLinkHold.value() } }

    fun cancelLinkDrag() {
        mainHandler.removeCallbacks(linkHoldRunnable)
        linkGesture.cancel()
        if (linkDrag != null) linkDrag = null
    }

    fun finishLinkDrag(release: Offset) {
        val drag = linkDrag ?: return
        linkDrag = null
        when (val target = linkTargetAt(drag.origin, release)) {
            null -> alignmentHint = "Keine Ecke unter dem Finger. Es wurde keine Linie gesetzt."
            is LinkTarget.Point -> {
                val next = alignmentPattern.withLinkToggled(drag.origin, target.label)
                alignmentHint = (alignmentPattern.describeChangeTo(next) ?: "Linie geändert") + "."
                onAlignmentPatternChange(next, viewerCameraQuaternion())
            }
            is LinkTarget.Corner -> {
                if (alignmentPattern.isFull) {
                    alignmentHint = "Es sind bereits $ALIGNMENT_MAXIMUM_PICKS Punkte gesetzt. Eine Linie ist nur noch " +
                        "zwischen vorhandenen Punkten möglich."
                    return
                }
                val next = alignmentPattern.withLinkedPoint(drag.origin, landmarkPoint(target.index))
                alignmentHint = (alignmentPattern.describeChangeTo(next) ?: "Punkt gesetzt") +
                    ". In AR hilft die Linie beim Markieren und richtet diese Kante zusätzlich aus."
                onAlignmentPatternChange(next, viewerCameraQuaternion())
            }
        }
    }

    fun handleLinkTouch(event: MotionEvent): LinkTouchResult {
        if (!alignmentPickMode || modelInstance == null) {
            if (linkGesture.state != LinkHoldGesture.State.IDLE || linkDrag != null) cancelLinkDrag()
            return LinkTouchResult.PASS
        }
        val position = Offset(event.x, event.y)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelLinkDrag()
                val origin = selectAlignmentCandidate(
                    visiblePickCandidates(), event.x, event.y, alignmentTolerancePx(),
                )?.let { it.index + 1 }
                linkGesture.down(position, event.eventTime, origin)
                if (origin != null) mainHandler.postDelayed(linkHoldRunnable, PRECISION_HOLD_MILLIS)
                LinkTouchResult.PASS
            }
            MotionEvent.ACTION_MOVE -> {
                val result = linkGesture.move(position)
                if (linkGesture.state == LinkHoldGesture.State.IDLE) mainHandler.removeCallbacks(linkHoldRunnable)
                linkDrag?.let { drag ->
                    linkDrag = drag.copy(position = position, target = linkTargetAt(drag.origin, position))
                }
                result
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(linkHoldRunnable)
                val release = linkGesture.up(position)
                if (release != null && linkDrag != null) {
                    finishLinkDrag(release)
                    LinkTouchResult.FINISHED
                } else {
                    if (linkDrag != null) linkDrag = null
                    LinkTouchResult.PASS
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                if (linkDrag != null) alignmentHint = "Linie abgebrochen."
                cancelLinkDrag()
                LinkTouchResult.PASS
            }
            else -> if (linkGesture.state == LinkHoldGesture.State.IDLE) {
                LinkTouchResult.PASS
            } else {
                LinkTouchResult.CONSUME
            }
        }
    }

    LaunchedEffect(alignmentPickMode, modelInstance) {
        if (!alignmentPickMode || modelInstance == null) cancelLinkDrag()
    }
    DisposableEffect(linkHoldRunnable) {
        onDispose { mainHandler.removeCallbacks(linkHoldRunnable) }
    }

    // SceneView 4.18 keeps its touch handler from the first composition (only onFrame is wrapped
    // in rememberUpdatedState).  A plain `alignmentPickMode` read inside onTouchEvent would stay
    // false forever and every tap would open the part dialog, so the mode is read through State.
    val currentAlignmentPickMode = rememberUpdatedState(alignmentPickMode)
    val currentInteractionBlocked = rememberUpdatedState(interactionBlocked)
    val currentAlignmentTap = rememberUpdatedState<(TapPoint) -> Unit> { point ->
        handleAlignmentTap(point)
    }
    val currentLinkTouch = rememberUpdatedState<(MotionEvent) -> LinkTouchResult> { event ->
        handleLinkTouch(event)
    }
    val currentLinkCancel = rememberUpdatedState<() -> Unit> { cancelLinkDrag() }
    val currentSelectFromTap = rememberUpdatedState<(TapPoint, Node?) -> Unit> { point, node ->
        val request = pickCoordinator.begin()
        selectPartAt(
            parts = selectableParts,
            partEntityIndex = partEntityIndex,
            hitNode = node,
            cameraNode = cameraNode,
            view = view,
            entityParts = entityParts,
            point = point,
            handler = mainHandler,
            isRequestCurrent = { pickCoordinator.isCurrent(request) },
            onPartSelected = { part ->
                if (pickCoordinator.isCurrent(request)) onPartSelected(part)
            },
        )
    }
    LaunchedEffect(appearanceAttached, pickCoordinator) {
        if (!appearanceAttached) pickCoordinator.invalidate()
    }
    DisposableEffect(modelInstance, pickCoordinator) {
        onDispose { pickCoordinator.invalidate() }
    }
    Box(modifier.onGloballyPositioned { coordinates ->
        val b = coordinates.boundsInWindow()
        viewportWindow = Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
        if (b.width > 0f && b.height > 0f) viewportAspect = (b.width / b.height * 20f).roundToInt() / 20f
    }.background(Color(0xFF071019))) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            // Use a fresh native surface on both sides of the exclusive 3D/AR handoff.
            // This viewport has no content below it that needs TextureView compositing.
            surfaceType = SurfaceType.Surface,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            view = view,
            lifecycle = lifecycle,
            onTouchEvent = { event, hitResult ->
                if (currentInteractionBlocked.value) {
                    tapTracker.reset()
                    currentLinkCancel.value()
                    return@SceneView true
                }
                if (!appearanceAttached) {
                    tapTracker.reset()
                    return@SceneView false
                }
                when (currentLinkTouch.value(event)) {
                    // A line drag: the model must not orbit under the finger.
                    LinkTouchResult.CONSUME -> return@SceneView true
                    // The line was released: no tap, but the camera closes its (empty) gesture.
                    LinkTouchResult.FINISHED -> {
                        tapTracker.reset()
                        return@SceneView false
                    }
                    LinkTouchResult.PASS -> Unit
                }
                // Two fingers twisting roll the part in the screen plane (pinch and drag go on as before).
                if (event.pointerCount >= 2 && event.actionMasked == MotionEvent.ACTION_MOVE) {
                    val roll = twistTracker.update(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
                    if (roll != 0.0) currentCameraManipulator.value.roll(roll)
                }
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    if (twistTracker.finish()) currentCameraManipulator.value.rollFinished()
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> tapTracker.onDown(event)
                    MotionEvent.ACTION_CANCEL -> tapTracker.reset()
                    MotionEvent.ACTION_POINTER_DOWN -> tapTracker.reset()
                    MotionEvent.ACTION_UP -> {
                        tapTracker.consumeTap(event, tapSlopPx)?.let { point ->
                            if (currentAlignmentPickMode.value) {
                                currentAlignmentTap.value(point)
                            } else {
                                currentSelectFromTap.value(point, hitResult?.nodeOrNull)
                            }
                        }
                    }
                }
                // SceneView and its camera manipulator must still receive every event.
                false
            },
            onGestureListener = null,
            cameraNode = cameraNode,
            cameraManipulator = if (interactionBlocked) null else cameraManipulator,
            autoCenterContent = false,
            autoFitContent = false,
            onFrame = { frameTimeNanos ->
                if (partLabelsVisible && labelRateLimiter.shouldRun(frameTimeNanos)) {
                    labelOverlayState.update(projectPartLabels(
                        labels = visiblePartLabels,
                        cameraNode = cameraNode,
                        width = view.viewport.width,
                        height = view.viewport.height,
                    ))
                }
                // Every frame, from the camera this frame is drawn with: a rate limited overlay
                // trails behind the model while it is being orbited and the points seem to float.
                if (currentAlignmentPickMode.value && modelInstance != null) {
                    val corners = alignmentCandidates(
                        landmarks = alignmentLandmarks,
                        cameraNode = cameraNode,
                        width = view.viewport.width,
                        height = view.viewport.height,
                        visibility = landmarkVisibility,
                    )
                    // A line drag snaps to these; the camera stands still meanwhile.
                    cornerCache.corners = corners
                    val dots = alignmentDots(
                        candidates = corners,
                        picks = alignmentPicks.filter { pointIsVisible(it.modelPoint) },
                        cameraNode = cameraNode,
                        width = view.viewport.width,
                        height = view.viewport.height,
                    )
                    alignmentOverlayState.update(dots, alignmentLines(dots, alignmentPattern.links, AlignmentLineStyle.PICK))
                } else {
                    cornerCache.corners = emptyList()
                    alignmentOverlayState.clear()
                }
            },
        ) {
            modelInstance?.let { instance -> ModelNode(modelInstance = instance) }
        }
        PartLabelsOverlay(state = labelOverlayState, visible = partLabelsVisible)
        AlignmentDotsOverlay(
            state = alignmentOverlayState,
            visible = alignmentPickMode && modelInstance != null,
        )
        linkDrag?.let { drag -> LinkDragOverlay(drag, linkTargetColor(drag, alignmentPattern)) }
        if (modelInstance == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (modelLoad.error == null) CircularProgressIndicator()
                Text(
                    modelLoad.error ?: "GLB wird geladen…",
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        if (modelInstance != null && !alignmentPickMode && !snapMode) {
            OneTimeHint(
                GuidePreferences.HINT_VIEWER,
                "Erste Schritte im Modell",
                "Ein Finger dreht, zwei Finger zoomen und verschieben, «Zentrieren» stellt die Startansicht her. " +
                    "Bauteil antippen zeigt Profil und Werkstoff." + if (packageData.registrationModelFile.isFile) {
                        " Für AR zuerst «AR 0/$ALIGNMENT_MAXIMUM_PICKS»: Ecken oder Bohrungsmitten wählen."
                    } else "",
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            )
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (alignmentPickMode && canUndoAlignment) {
                    IconButton(
                        onClick = {
                            cancelLinkDrag()
                            alignmentHint = onUndoAlignment()?.let { "Rückgängig: $it." }
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Letzte Änderung an Punkten und Linien rückgängig machen",
                        )
                    }
                }
                Text(
                    if (snapMode && !alignmentPickMode) {
                        "Snap (experimentell): Das Modell so drehen, wie Sie das Bauteil jetzt vor sich sehen – " +
                            "gleiche Seite, ungefähr gleiche Blickrichtung. Das Bauteil muss aufrecht auf dem Boden oder " +
                            "einem Tisch stehen. Dann «AR» öffnen, kurz den Boden zeigen, die Bildmitte auf das Bauteil " +
                            "richten und den Magneten einschalten."
                    } else if (alignmentPickMode) {
                        (
                            alignmentHint
                                ?: visibilityError
                                ?: if (landmarkVisibility == null) "Sichtbare Ecken werden geladen…" else
                                    "Ecke (Punkt) oder Bohrung (Ring) antippen · halten + ziehen = Linie"
                            )
                    } else {
                        "${packageData.parts.parts.size} Bauteile · antippen: Info · ziehen: drehen · 2 Finger: zoomen, verschieben"
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                        .padding(horizontal = if (alignmentPickMode) 10.dp else 12.dp, vertical = if (alignmentPickMode) 4.dp else 8.dp),
                    // A thin strip while picking: the corners and rings need the screen.
                    style = if (alignmentPickMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                )
            }
        }
        val window = viewportWindow
        val drag = linkDrag
        if (drag != null && window != null) {
            val lensTarget = drag.target
            ArPrecisionMagnifier(
                hostView = rootView,
                viewport = window,
                position = drag.position,
                zoom = magnifierZoom,
                title = "P${drag.origin} → " + when (lensTarget) {
                    is LinkTarget.Point -> "P${lensTarget.label}"
                    is LinkTarget.Corner -> if (alignmentPattern.isFull) "voll" else "P${alignmentPattern.size + 1}"
                    null -> "?"
                } + " · ${magnifierZoom.label}",
                hint = when (lensTarget) {
                    is LinkTarget.Point -> if (alignmentPattern.hasLink(drag.origin, lensTarget.label)) {
                        "Loslassen entfernt die Linie"
                    } else {
                        "Loslassen verbindet"
                    }
                    is LinkTarget.Corner -> if (alignmentPattern.isFull) {
                        "Schon $ALIGNMENT_MAXIMUM_PICKS Punkte: nur zu Punkten"
                    } else {
                        "Loslassen setzt Punkt + Linie"
                    }
                    null -> "Keine Ecke unter dem Finger"
                },
                overlay = { lens ->
                    // Only the rendered model is copied; corners and the line are drawn again here.
                    cornerCache.corners.forEach { corner ->
                        val point = lens.map(Offset(corner.x, corner.y))
                        if (lens.contains(point)) drawCircle(Color(0xCCFFD166), 2.dp.toPx(), point)
                    }
                    val start = lens.map(drag.originPosition)
                    val end = lens.map(lensTarget?.let { Offset(it.x, it.y) } ?: drag.position)
                    drawAlignmentLine(
                        AlignmentLine(
                            start.x, start.y, end.x, end.y,
                            if (lensTarget == null) AlignmentLineStyle.DRAFT else AlignmentLineStyle.PICK,
                        ),
                        endGapPx = 14.dp.toPx(),
                    )
                    if (lensTarget != null) {
                        drawCircle(Color(0xE0071019), 11.dp.toPx(), end, style = Stroke(4.dp.toPx()))
                        drawCircle(linkTargetColor(drag, alignmentPattern), 11.dp.toPx(), end, style = Stroke(2.dp.toPx()))
                    }
                },
            )
        }
    }
}

/**
 * Yellow where releasing draws the line, red where it removes an existing line or where a full
 * pattern cannot take another point.
 */
private fun linkTargetColor(drag: LinkDragUi, pattern: AlignmentPattern): Color = when (val target = drag.target) {
    is LinkTarget.Point -> if (pattern.hasLink(drag.origin, target.label)) Color(0xFFFFB4AB) else Color(0xFFFFD166)
    is LinkTarget.Corner -> if (pattern.isFull) Color(0xFFFFB4AB) else Color(0xFFFFD166)
    null -> Color(0xFFFFD166)
}

/** State of a line being dragged out of a chosen 3D point. */
private data class LinkDragUi(
    val origin: Int,
    val originPosition: Offset,
    val position: Offset,
    val target: LinkTarget?,
)

/** Corners offered in the last drawn frame; a line drag snaps to them while the camera rests. */
private class AlignmentCornerCache {
    var corners: List<AlignmentCandidate> = emptyList()
}

@Composable
private fun LinkDragOverlay(drag: LinkDragUi, targetColor: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val target = drag.target
        val end = target?.let { Offset(it.x, it.y) } ?: drag.position
        drawAlignmentLine(
            AlignmentLine(
                drag.originPosition.x, drag.originPosition.y, end.x, end.y,
                if (target == null) AlignmentLineStyle.DRAFT else AlignmentLineStyle.PICK,
            ),
        )
        if (target != null) {
            // The corner or point the line will end on once the finger is lifted.
            drawCircle(Color(0xE0071019), 17.dp.toPx(), end, style = Stroke(5.dp.toPx()))
            drawCircle(targetColor, 17.dp.toPx(), end, style = Stroke(2.5.dp.toPx()))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewerActions(
    packageData: InstalledPackage,
    availableRevisions: List<InstalledPackage>,
    compareWith: InstalledPackage?,
    comparison: RevisionComparison?,
    qualityRecord: QualityRecord?,
    onCompare: (InstalledPackage?) -> Unit,
    onComparisonDetails: () -> Unit,
    onCheck: () -> Unit,
    partLabelsVisible: Boolean,
    viewportActionsEnabled: Boolean,
    onPartLabelsVisibleChange: (Boolean) -> Unit,
    onCenter: () -> Unit,
    alignmentAvailable: Boolean = false,
    alignmentPickCount: Int = 0,
    alignmentPickMode: Boolean = false,
    onAlignmentPickModeChange: (Boolean) -> Unit = {},
    snapMode: Boolean = false,
    onSnapModeChange: (Boolean) -> Unit = {},
    leadingContent: @Composable RowScope.() -> Unit = {},
) {
    var comparisonMenu by remember { mutableStateOf(false) }
    // Chips that wrap, not buttons that scroll: everything has to stay visible above the model on
    // a phone held upright, and «Positionsnummern» cut off at the edge was a button nobody found.
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        leadingContent()
        ViewerChip(onClick = onCenter, enabled = viewportActionsEnabled, icon = Icons.Default.CenterFocusStrong, label = "Zentrieren")
        if (alignmentAvailable) {
            ViewerChip(
                onClick = { onAlignmentPickModeChange(!alignmentPickMode) },
                enabled = viewportActionsEnabled || alignmentPickMode,
                icon = Icons.Default.TouchApp,
                label = "AR $alignmentPickCount/$ALIGNMENT_MAXIMUM_PICKS",
                selected = alignmentPickMode,
            )
            // Experimental method, separate from the points: turn the model like the part, open AR.
            // Hidden since 0.27.1: without a depth sensor its results on the phone were not usable.
            if (BuildConfig.SNAP_EXPERIMENT_ENABLED) {
                ViewerChip(
                    onClick = { onSnapModeChange(!snapMode) },
                    enabled = viewportActionsEnabled || snapMode,
                    icon = MagnetIcon,
                    label = "Snap",
                    selected = snapMode,
                    contentDescription = "Snap (experimentell)",
                )
            }
        }
        ViewerChip(
            onClick = { onPartLabelsVisibleChange(!partLabelsVisible) },
            enabled = viewportActionsEnabled,
            icon = Icons.AutoMirrored.Filled.Label,
            label = "Positionsnummern",
            selected = partLabelsVisible && viewportActionsEnabled,
        )
        if (availableRevisions.any { it.manifest.packageId != packageData.manifest.packageId }) Box {
            ViewerChip(
                onClick = { comparisonMenu = true },
                icon = Icons.Default.CompareArrows,
                label = compareWith?.let { "Vergleich mit Rev. ${it.manifest.assembly.revision}" } ?: "Revision vergleichen",
                selected = compareWith != null,
            )
            DropdownMenu(expanded = comparisonMenu, onDismissRequest = { comparisonMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Kein Vergleich") },
                    onClick = {
                        onCompare(null)
                        comparisonMenu = false
                    },
                )
                availableRevisions
                    .filter { it.manifest.packageId != packageData.manifest.packageId }
                    .sortedByDescending { it.manifest.assembly.revision }
                    .forEach { revision ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Rev. ${revision.manifest.assembly.revision} · ${revision.parts.parts.size} Bauteile",
                                )
                            },
                            onClick = {
                                onCompare(revision)
                                comparisonMenu = false
                            },
                        )
                    }
            }
        }
        comparison?.let {
            ViewerChip(onClick = onComparisonDetails, icon = null, label = "+${it.added.size} · Δ${it.modified.size} · −${it.removed.size}")
        }
        // QS: the status as the colour of the tick; its name and everything else one tap away.
        val status = qualityRecord?.status
        ViewerChip(
            onClick = onCheck,
            icon = Icons.Default.CheckCircle,
            label = "QS",
            selected = status == QualityStatus.OK,
            contentDescription = status?.label ?: "Qualitätssicherung",
            iconTint = when (status) {
                QualityStatus.OK -> Color(0xFF268844)
                QualityStatus.DEFECT, QualityStatus.REWORK -> MaterialTheme.colorScheme.error
                QualityStatus.RECHECK -> Color(0xFFB46C00)
                else -> null
            },
        )
    }
}

/** One compact control of the viewer bar: a toggle when [selected] matters, an action otherwise. */
@Composable
private fun ViewerChip(
    onClick: () -> Unit,
    icon: ImageVector?,
    label: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentDescription: String? = null,
    iconTint: Color? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1) },
        leadingIcon = icon?.let {
            {
                Icon(it, contentDescription = contentDescription, modifier = Modifier.size(18.dp),
                    tint = iconTint ?: LocalContentColor.current)
            }
        },
    )
}

@Composable
private fun PartDetailsDialog(part: Part, onDismiss: () -> Unit) {
    val dimensionsMm = part.boundsAssemblyM.min.indices.map { index ->
        (part.boundsAssemblyM.max[index] - part.boundsAssemblyM.min[index]) * 1000f
    }
    val dimensions = dimensionsMm.takeIf { it.size >= 3 }?.take(3)?.joinToString(" × ") { "%.0f".format(it) }?.plus(" mm")
    val attributes = part.properties.entries.associate { (key, value) -> key to value.toString().trim('"') }
    // Profile and material belong in the first line; the rest of the IFC attributes follow in
    // workshop order, and the identifiers only unfold on request.
    val summary = partSummary(attributes, dimensions)
    val details = labelledAttributes(attributes.filterKeys { key ->
        !key.trim().equals("profile", ignoreCase = true) && !key.trim().equals("material", ignoreCase = true)
    })
    var technical by remember(part.guid) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(part.positionNumber.ifBlank { "Bauteil ohne Positionsnummer" }) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                if (summary == null && dimensions != null) DetailRow("Hüllmaße", dimensions)
                if (!part.inspectable) {
                    Text("Nicht prüfbar (Verbindungsmittel oder Markierung)", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                details.forEach { (label, value) -> DetailRow(label, value) }
                TextButton(onClick = { technical = !technical }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (technical) "Technische Details ausblenden" else "Technische Details")
                }
                if (technical) {
                    DetailRow("Typ", part.kind)
                    DetailRow("GUID", part.guid)
                    DetailRow("glTF-Knoten", part.gltfNodeName)
                    dimensions?.let { DetailRow("Hüllmaße", it) }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ComparisonDetailsDialog(
    current: InstalledPackage,
    previous: InstalledPackage,
    comparison: RevisionComparison,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Rev. ${current.manifest.assembly.revision} gegenüber Rev. ${previous.manifest.assembly.revision}",
            )
        },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Grün: hinzugefügt · Gelb: geändert · Grau: unverändert")
                ChangeList("Hinzugefügt", comparison.added, Color(0xFF4ADB73))
                ChangeList("Geändert", comparison.modified, Color(0xFFFFB52E))
                ChangeList("Entfernt", comparison.removed, Color(0xFFF45B69))
                Text("Unverändert: ${comparison.unchangedCount}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun ChangeList(title: String, parts: List<Part>, color: Color) {
    Text("$title: ${parts.size}", color = color, fontWeight = FontWeight.Bold)
    parts.take(30).forEach { part ->
        Text(
            "• ${part.positionNumber.ifBlank { part.guid }}",
            style = MaterialTheme.typography.labelSmall,
        )
    }
    if (parts.size > 30) Text("…und ${parts.size - 30} weitere", style = MaterialTheme.typography.labelSmall)
}

private fun findPartForNode(partEntityIndex: PartEntityIndex, hitNode: Node?): Part? {
    val names = generateSequence(hitNode) { it.parent }
        .mapNotNull { node -> node.name?.takeIf { it.isNotBlank() } }
        .toSet()
    return names.firstNotNullOfOrNull(partEntityIndex::find)
}

private fun findPartForEntityName(parts: List<Part>, entityName: String?): Part? {
    return PartEntityIndex(parts).find(entityName)
}

private fun entityNameMatchesPart(entityName: String, part: Part): Boolean {
    val nodeName = part.gltfNodeName.trim()
    val guid = part.guid.trim()
    return (nodeName.isNotEmpty() && entityName.equals(nodeName, ignoreCase = true)) ||
        (nodeName.isNotEmpty() && entityName.contains(nodeName, ignoreCase = true)) ||
        (guid.isNotEmpty() && entityName.contains(guid, ignoreCase = true))
}

private fun selectPartAt(
    parts: List<Part>,
    partEntityIndex: PartEntityIndex,
    hitNode: Node?,
    cameraNode: CameraNode,
    view: FilamentView,
    entityParts: Map<Int, Part>,
    point: TapPoint,
    handler: Handler,
    isRequestCurrent: () -> Boolean,
    onPartSelected: (Part) -> Unit,
) {
    val directPart = findPartForNode(partEntityIndex, hitNode)
    if (directPart != null) {
        if (isRequestCurrent()) onPartSelected(directPart)
    } else {
        // Project now, while the tap and camera pose are current. MotionEvent instances are pooled
        // by Android and must never be retained until the asynchronous Filament callback runs.
        val projectedFallback = projectedPartAt(parts, cameraNode, view, point)
        pickPart(
            view = view,
            entityParts = entityParts,
            point = point,
            handler = handler,
            fallback = projectedFallback,
            isRequestCurrent = isRequestCurrent,
            onPartSelected = onPartSelected,
        )
    }
}

internal fun displayPartPositionNumber(positionNumber: String): String {
    val trimmed = positionNumber.trim()
    val dotIndex = trimmed.indexOf('.')
    return if (
        dotIndex == 6 &&
        dotIndex < trimmed.lastIndex &&
        trimmed.take(6).all { it.isDigit() }
    ) {
        trimmed.substring(dotIndex + 1)
    } else {
        trimmed
    }
}

internal fun partLabelVisuals(parts: List<Part>): List<PartLabelVisual> {
    val labelled = parts.mapNotNull { part ->
        val lower = part.boundsAssemblyM.min
        val upper = part.boundsAssemblyM.max
        val text = displayPartPositionNumber(part.positionNumber)
        if (text.isBlank() || lower.size < 3 || upper.size < 3) {
            null
        } else {
            PartLabelCandidate(part, text, lower, upper)
        }
    }
    if (labelled.isEmpty()) return emptyList()
    val minX = labelled.minOf { it.lower[0] }
    val minY = labelled.minOf { it.lower[1] }
    val minZ = labelled.minOf { it.lower[2] }
    val maxX = labelled.maxOf { it.upper[0] }
    val maxY = labelled.maxOf { it.upper[1] }
    val maxZ = labelled.maxOf { it.upper[2] }
    val diagonal = sqrt(
        (maxX - minX) * (maxX - minX) +
            (maxY - minY) * (maxY - minY) +
            (maxZ - minZ) * (maxZ - minZ),
    )
    val heightM = (diagonal * 0.045f).coerceIn(0.045f, 0.28f)
    return labelled.map { label ->
        PartLabelVisual(
            part = label.part,
            text = label.text,
            position = Float3(
                (label.lower[0] + label.upper[0]) / 2f,
                label.upper[1] + heightM * 0.8f,
                (label.lower[2] + label.upper[2]) / 2f,
            ),
        )
    }
}

private fun projectPartLabels(
    labels: List<PartLabelVisual>,
    cameraNode: CameraNode,
    width: Int,
    height: Int,
): List<PartScreenLabel> {
    if (width <= 0 || height <= 0) return emptyList()
    val centerX = width / 2f
    val centerY = height / 2f
    return labels
        .asSequence()
        .mapNotNull { label ->
            val cameraPosition = cameraNode.getLocalPosition(label.position)
            if (!cameraPosition.z.isFinite() || cameraPosition.z >= -0.001f) {
                return@mapNotNull null
            }
            val viewPosition = cameraNode.worldToView(label.position)
            val x = viewPosition.x * width
            val y = (1f - viewPosition.y) * height
            if (
                !x.isFinite() || !y.isFinite() ||
                x < -80f || x > width + 80f || y < -80f || y > height + 80f
            ) {
                null
            } else {
                PartScreenLabel(label.text, x, y)
            }
        }
        .sortedBy { label ->
            val dx = label.x - centerX
            val dy = label.y - centerY
            dx * dx + dy * dy
        }
        .take(300)
        .toList()
}

@Composable
private fun PartLabelsOverlay(state: PartLabelOverlayState, visible: Boolean) {
    if (!visible) return
    val labels = state.labels
    if (labels.isEmpty()) return
    val density = LocalDensity.current
    val textSizePx = with(density) { 13.sp.toPx() }
    val horizontalPaddingPx = with(density) { 6.dp.toPx() }
    val verticalPaddingPx = with(density) { 3.dp.toPx() }
    val anchorGapPx = with(density) { 5.dp.toPx() }
    val cornerRadiusPx = with(density) { 5.dp.toPx() }
    val collisionMarginPx = with(density) { 2.dp.toPx() }
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    val backgroundPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(220, 7, 19, 28)
            style = Paint.Style.FILL
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        textPaint.textSize = textSizePx
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val boxHeight = textHeight + verticalPaddingPx * 2f
        val occupied = ArrayList<RectF>(minOf(labels.size, 120))
        drawIntoCanvas { canvas ->
            labels.forEach { label ->
                if (occupied.size >= 120) return@forEach
                val boxWidth = textPaint.measureText(label.text) + horizontalPaddingPx * 2f
                val maxLeft = max(collisionMarginPx, size.width - boxWidth - collisionMarginPx)
                val maxTop = max(collisionMarginPx, size.height - boxHeight - collisionMarginPx)
                val left = (label.x - boxWidth / 2f).coerceIn(collisionMarginPx, maxLeft)
                val top = (label.y - boxHeight - anchorGapPx).coerceIn(collisionMarginPx, maxTop)
                val rect = RectF(left, top, left + boxWidth, top + boxHeight)
                val collisionRect = RectF(rect).apply { inset(-collisionMarginPx, -collisionMarginPx) }
                if (occupied.any { RectF.intersects(it, collisionRect) }) return@forEach
                occupied += collisionRect
                canvas.nativeCanvas.drawRoundRect(
                    rect,
                    cornerRadiusPx,
                    cornerRadiusPx,
                    backgroundPaint,
                )
                val baseline = rect.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f
                canvas.nativeCanvas.drawText(label.text, rect.centerX(), baseline, textPaint)
            }
        }
    }
}

/** Width over height of the 3D area of a phone held upright, assumed until the viewport is laid out. */
internal const val PORTRAIT_VIEWPORT_ASPECT = 0.68f
private const val MINIMUM_HOME_DISTANCE = 2.75f
private const val HOME_FIT_MARGIN = 1.08f

/** Vertical field of view of the viewer camera: Filament's default 28 mm lens on a 24 mm sensor. */
internal val VIEWER_VERTICAL_FOV_RADIANS = 2.0 * atan(12.0 / 28.0)

/**
 * Start view: the whole part inside the viewport.  The home camera looks along −Z, so the part's
 * X extent must fit the horizontal field of view and its Y extent the vertical one.  Upright the
 * horizontal field is the narrow one: a 6 m beam needs about eleven metres of distance, which
 * the fixed 1.15 × diagonal of 0.33.0 and before did not give; a compact part keeps the close
 * default distance.
 */
internal fun cameraFrame(parts: List<Part>, viewportAspect: Float = PORTRAIT_VIEWPORT_ASPECT): CameraFrame {
    val bounds = parts.mapNotNull { part ->
        val lower = part.boundsAssemblyM.min
        val upper = part.boundsAssemblyM.max
        if (lower.size < 3 || upper.size < 3) null else lower to upper
    }
    if (bounds.isEmpty()) {
        return CameraFrame(target = Float3(0f), home = Float3(0f, 0.4f, 2.75f))
    }
    val minX = bounds.minOf { it.first[0] }
    val minY = bounds.minOf { it.first[1] }
    val minZ = bounds.minOf { it.first[2] }
    val maxX = bounds.maxOf { it.second[0] }
    val maxY = bounds.maxOf { it.second[1] }
    val maxZ = bounds.maxOf { it.second[2] }
    val aspect = viewportAspect.takeIf { it.isFinite() && it > 0.05f } ?: PORTRAIT_VIEWPORT_ASPECT
    val halfVertical = tan(VIEWER_VERTICAL_FOV_RADIANS / 2.0).toFloat()
    val halfHorizontal = halfVertical * aspect
    val fit = max((maxX - minX) / 2f / halfHorizontal, (maxY - minY) / 2f / halfVertical) * HOME_FIT_MARGIN
    val distance = (fit + (maxZ - minZ) / 2f).coerceAtLeast(MINIMUM_HOME_DISTANCE)
    val target = Float3(
        (minX + maxX) / 2f,
        (minY + maxY) / 2f,
        (minZ + maxZ) / 2f,
    )
    return CameraFrame(
        target = target,
        home = Float3(target.x, target.y + distance * 0.145f, target.z + distance),
    )
}

internal fun cameraHomePosition(parts: List<Part>): Float3 = cameraFrame(parts).home

private fun pickPart(
    view: FilamentView,
    entityParts: Map<Int, Part>,
    point: TapPoint,
    handler: Handler,
    fallback: Part?,
    isRequestCurrent: () -> Boolean,
    onPartSelected: (Part) -> Unit,
) {
    val viewport = view.viewport
    if (viewport.width <= 0 || viewport.height <= 0) {
        if (isRequestCurrent()) fallback?.let(onPartSelected)
        return
    }
    val samples = pickSamples(point, viewport.width, viewport.height)
    if (samples.isEmpty()) {
        if (isRequestCurrent()) fallback?.let(onPartSelected)
        return
    }
    var pending = samples.size
    val results = arrayOfNulls<Part>(samples.size)
    fun completeOne(index: Int, part: Part?) {
        if (!isRequestCurrent()) return
        results[index] = part
        pending -= 1
        if (pending == 0) {
            results.firstNotNullOfOrNull { it }
                ?.let(onPartSelected)
                ?: fallback?.let(onPartSelected)
        }
    }
    samples.forEachIndexed { index, (x, y) ->
        runCatching {
            view.pick(x, y, handler) { result ->
                completeOne(index, entityParts[result.renderable])
            }
        }.onFailure {
            completeOne(index, null)
        }
    }
}

internal fun pickSamples(point: TapPoint, width: Int, height: Int): List<Pair<Int, Int>> {
    if (width <= 0 || height <= 0) return emptyList()
    val radius = (minOf(width, height) * 0.018f).coerceIn(18f, 42f)
    val diagonal = radius * 0.7f
    val offsets = listOf(
        0f to 0f,
        -radius to 0f,
        radius to 0f,
        0f to -radius,
        0f to radius,
        -diagonal to -diagonal,
        diagonal to -diagonal,
        -diagonal to diagonal,
        diagonal to diagonal,
    )
    return offsets.map { (dx, dy) ->
        val x = (point.x + dx).roundToInt().coerceIn(0, width - 1)
        val y = (height - (point.y + dy).roundToInt() - 1).coerceIn(0, height - 1)
        x to y
    }.distinct()
}

private fun projectedPartAt(
    parts: List<Part>,
    cameraNode: CameraNode,
    view: FilamentView,
    point: TapPoint,
): Part? {
    val viewport = view.viewport
    if (viewport.width <= 0 || viewport.height <= 0) return null
    val tolerancePx = (minOf(viewport.width, viewport.height) * 0.026f).coerceIn(34f, 76f)
    val bounds = parts.mapNotNull { part ->
        projectPartBounds(part, cameraNode, viewport.width, viewport.height)
    }
    return selectProjectedPart(bounds, point.x, point.y, tolerancePx)
}

private fun projectPartBounds(
    part: Part,
    cameraNode: CameraNode,
    width: Int,
    height: Int,
): PartScreenBounds? {
    val lower = part.boundsAssemblyM.min
    val upper = part.boundsAssemblyM.max
    if (lower.size < 3 || upper.size < 3 || width <= 0 || height <= 0) return null
    val center = Float3(
        (lower[0] + upper[0]) / 2f,
        (lower[1] + upper[1]) / 2f,
        (lower[2] + upper[2]) / 2f,
    )
    val cameraCenter = cameraNode.getLocalPosition(center)
    val depth = -cameraCenter.z
    if (!depth.isFinite() || depth <= 0f) return null
    val corners = listOf(
        Float3(lower[0], lower[1], lower[2]),
        Float3(lower[0], lower[1], upper[2]),
        Float3(lower[0], upper[1], lower[2]),
        Float3(lower[0], upper[1], upper[2]),
        Float3(upper[0], lower[1], lower[2]),
        Float3(upper[0], lower[1], upper[2]),
        Float3(upper[0], upper[1], lower[2]),
        Float3(upper[0], upper[1], upper[2]),
    )
    val projected = corners.map { corner ->
        val viewPosition = cameraNode.worldToView(corner)
        val x = viewPosition.x * width
        val y = (1f - viewPosition.y) * height
        if (x.isFinite() && y.isFinite()) x to y else return null
    }
    val left = projected.minOf { it.first }
    val top = projected.minOf { it.second }
    val right = projected.maxOf { it.first }
    val bottom = projected.maxOf { it.second }
    return PartScreenBounds(part, left, top, right, bottom, depth)
}

internal fun selectProjectedPart(
    bounds: List<PartScreenBounds>,
    x: Float,
    y: Float,
    tolerancePx: Float,
): Part? =
    bounds
        .asSequence()
        .filter { it.contains(x, y, tolerancePx) }
        .minWithOrNull(
            compareBy<PartScreenBounds> { it.depth }
                .thenBy { it.area }
                .thenBy { it.centerDistanceSquared(x, y) },
        )
        ?.part

private const val ALIGNMENT_DISTANCE_BUCKET_PX = 18f
internal const val ALIGNMENT_MAXIMUM_PICKS = ManualCorrespondenceSolver.MAXIMUM_PAIRS
private const val ALIGNMENT_UNDO_STEPS = 50

internal enum class AlignmentDotStyle {
    /** A CAD landmark the operator may tap in the 3D viewer. */
    CANDIDATE,

    /** The centre of a bolt hole the operator may tap in the 3D viewer: a ring, not a dot. */
    HOLE_CENTRE,

    /** A landmark already chosen in the 3D viewer. */
    PICK,

    /** Where a chosen landmark currently sits on the AR preview of the CAD. */
    GHOST,

    /** The landmark the operator is asked to mark next in the AR camera view. */
    NEXT,

    /** A landmark already marked in the current round, shown on the AR miniature. */
    MARK,

    /** A current-round mark on the camera, with an open centre and an offset number. */
    CAMERA_MARK,

    /** A mark being placed again: dimmed until the new one replaces it. */
    CAMERA_MARK_OLD,

    /** Where the fit expects a landmark on the camera image: a dashed circle of [AlignmentDot.radiusPx]. */
    PREDICTED,

    /** The same, for the landmark to be marked now. */
    PREDICTED_NEXT,
}

internal data class AlignmentDot(
    val x: Float,
    val y: Float,
    val label: Int?,
    val style: AlignmentDotStyle,
    /** Only for predictions: how far off the landmark may lie, in pixels. */
    val radiusPx: Float? = null,
)

internal enum class AlignmentLineStyle {
    /** A line between two points chosen in the 3D viewer. */
    PICK,

    /** A line between two marks of the current round on the camera image or photo. */
    MARK,

    /** The fitted CAD line over the camera while the fit is reviewed. */
    GHOST,

    /** The line that follows the finger while the next point is dragged out of another. */
    DRAFT,
}

internal data class AlignmentLine(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val style: AlignmentLineStyle,
)

internal class AlignmentOverlayState {
    var dots by mutableStateOf<List<AlignmentDot>>(emptyList())
        private set
    var lines by mutableStateOf<List<AlignmentLine>>(emptyList())
        private set

    fun update(value: List<AlignmentDot>, lines: List<AlignmentLine> = emptyList()) {
        dots = value
        this.lines = lines
    }

    fun clear() {
        if (dots.isNotEmpty()) dots = emptyList()
        if (lines.isNotEmpty()) lines = emptyList()
    }
}

/** Lines of [links] between labelled dots; a line with an undrawn end is left out. */
internal fun alignmentLines(
    dots: List<AlignmentDot>,
    links: List<CorrespondenceLink>,
    style: AlignmentLineStyle,
): List<AlignmentLine> = links.mapNotNull { link ->
    val first = dots.firstOrNull { it.label == link.first } ?: return@mapNotNull null
    val second = dots.firstOrNull { it.label == link.second } ?: return@mapNotNull null
    AlignmentLine(first.x, first.y, second.x, second.y, style)
}

/** Radius of the open ring that marks a point on the camera image. */
internal fun cameraMarkRingRadiusPx(density: Float): Float = ALIGNMENT_MARKER_RADIUS_DP * density * 0.65f

/** Centre of the number bubble drawn beside a camera mark; shared by drawing and grabbing. */
internal fun cameraMarkBubbleCentre(x: Float, y: Float, density: Float, width: Float, height: Float): Offset {
    val radius = ALIGNMENT_MARKER_RADIUS_DP * density
    return Offset(
        (x + radius * 2f).coerceIn(radius, (width - radius).coerceAtLeast(radius)),
        (y - radius * 1.5f).coerceIn(radius, (height - radius).coerceAtLeast(radius)),
    )
}

private const val ALIGNMENT_MARKER_RADIUS_DP = 10f

/** Draws one alignment line with a dark outline; ends are kept clear of open rings. */
internal fun DrawScope.drawAlignmentLine(line: AlignmentLine, startGapPx: Float = 0f, endGapPx: Float = 0f) {
    val (start, end) = trimmedSegment(
        Offset(line.startX, line.startY),
        Offset(line.endX, line.endY),
        startGapPx,
        endGapPx,
    ) ?: return
    val color = when (line.style) {
        AlignmentLineStyle.PICK -> Color(0xFFFFB703)
        AlignmentLineStyle.MARK -> Color(0xFF5AD67D)
        AlignmentLineStyle.GHOST -> Color(0xFF4CC9F0)
        AlignmentLineStyle.DRAFT -> Color(0xFFFFD166)
    }
    val width = if (line.style == AlignmentLineStyle.DRAFT) 2.dp.toPx() else 2.5.dp.toPx()
    val dash = if (line.style == AlignmentLineStyle.DRAFT) {
        PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 5.dp.toPx()))
    } else {
        null
    }
    drawLine(Color(0xD0071019), start, end, strokeWidth = width + 2.5.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, start, end, strokeWidth = width, cap = StrokeCap.Round, pathEffect = dash)
}

internal data class AlignmentCandidate(
    val index: Int,
    val x: Float,
    val y: Float,
    val depthM: Float,
    /** The centre of a bolt hole rather than a corner; drawn as a ring. */
    val hole: Boolean = false,
)

/**
 * Screen space choice of a CAD landmark.  Distance buckets let the nearest surface win over a
 * corner on the far side of the part, which is what an operator expects when tapping a visible
 * edge of the model.
 */
internal fun selectAlignmentCandidate(
    candidates: List<AlignmentCandidate>,
    x: Float,
    y: Float,
    tolerancePx: Float,
): AlignmentCandidate? = candidates
    .filter { candidate ->
        val dx = candidate.x - x
        val dy = candidate.y - y
        dx * dx + dy * dy <= tolerancePx * tolerancePx
    }
    .minWithOrNull(
        compareBy(
            { candidate: AlignmentCandidate ->
                val dx = candidate.x - x
                val dy = candidate.y - y
                (sqrt(dx * dx + dy * dy) / ALIGNMENT_DISTANCE_BUCKET_PX).toInt()
            },
            { candidate: AlignmentCandidate -> candidate.depthM },
        ),
    )

private fun projectModelPoint(
    point: FloatArray,
    cameraNode: CameraNode,
    width: Int,
    height: Int,
): FloatArray? {
    if (width <= 0 || height <= 0) return null
    val position = Float3(point[0], point[1], point[2])
    val local = cameraNode.getLocalPosition(position)
    if (!local.z.isFinite() || local.z >= -0.001f) return null
    val viewPosition = cameraNode.worldToView(position)
    val x = viewPosition.x * width
    val y = (1f - viewPosition.y) * height
    if (!x.isFinite() || !y.isFinite()) return null
    return floatArrayOf(x, y, -local.z)
}

private fun alignmentCandidates(
    landmarks: ModelLandmarkSet,
    cameraNode: CameraNode,
    width: Int,
    height: Int,
    visibility: LandmarkVisibility?,
): List<AlignmentCandidate> {
    val total = landmarks.count
    if (total == 0 || visibility == null) return emptyList()
    val camera = cameraNode.worldPosition.let { floatArrayOf(it.x, it.y, it.z) }
    val result = ArrayList<AlignmentCandidate>(total)
    for (index in 0 until total) {
        val point = landmarks.point(index)
        val projected = projectModelPoint(
            point,
            cameraNode,
            width,
            height,
        ) ?: continue
        if (projected[0] !in 0f..width.toFloat() || projected[1] !in 0f..height.toFloat() ||
            !landmarks.faces(index, camera) || !visibility.isVisible(camera, point)) continue
        result += AlignmentCandidate(index, projected[0], projected[1], projected[2], landmarks.holeCentre[index])
    }
    return result
}

private fun alignmentDots(
    candidates: List<AlignmentCandidate>,
    picks: List<CorrespondencePick>,
    cameraNode: CameraNode,
    width: Int,
    height: Int,
): List<AlignmentDot> = buildList {
    candidates.forEach { candidate ->
        add(AlignmentDot(candidate.x, candidate.y, null,
            if (candidate.hole) AlignmentDotStyle.HOLE_CENTRE else AlignmentDotStyle.CANDIDATE))
    }
    picks.forEach { pick ->
        projectModelPoint(pick.modelPoint, cameraNode, width, height)?.let { projected ->
            add(AlignmentDot(projected[0], projected[1], pick.label, AlignmentDotStyle.PICK))
        }
    }
}

@Composable
internal fun AlignmentDotsOverlay(state: AlignmentOverlayState, visible: Boolean) {
    if (!visible) return
    val dots = state.dots
    val lines = state.lines
    if (dots.isEmpty() && lines.isEmpty()) return
    val density = LocalDensity.current
    val candidateRadiusPx = with(density) { 2.5f.dp.toPx() }
    val markerRadiusPx = with(density) { ALIGNMENT_MARKER_RADIUS_DP.dp.toPx() }
    val ringGapPx = cameraMarkRingRadiusPx(density.density) + with(density) { 2.dp.toPx() }
    val textSizePx = with(density) { 11.sp.toPx() }
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(255, 7, 19, 28)
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        labelPaint.textSize = textSizePx
        val fontMetrics = labelPaint.fontMetrics
        // Beneath the markers.  Lines between camera marks stop at the open rings so the marked
        // pixel itself stays visible.
        lines.forEach { line ->
            val gap = if (line.style == AlignmentLineStyle.MARK) ringGapPx else 0f
            drawAlignmentLine(line, gap, gap)
        }
        drawIntoCanvas { canvas ->
            dots.forEach { dot ->
                val centre = Offset(dot.x, dot.y)
                if (dot.style == AlignmentDotStyle.CANDIDATE) {
                    drawCircle(
                        color = Color(0x99FFD166),
                        radius = candidateRadiusPx,
                        center = centre,
                    )
                    return@forEach
                }
                if (dot.style == AlignmentDotStyle.HOLE_CENTRE) {
                    drawCircle(Color(0x99FFD166), candidateRadiusPx * 1.9f, centre, style = Stroke(1.5.dp.toPx()))
                    return@forEach
                }
                if (dot.style == AlignmentDotStyle.PREDICTED || dot.style == AlignmentDotStyle.PREDICTED_NEXT) {
                    // Dashed and hollow: an area to look in, never a point to trust.
                    val next = dot.style == AlignmentDotStyle.PREDICTED_NEXT
                    val radius = dot.radiusPx ?: markerRadiusPx
                    val colour = if (next) Color(0xFFFFB703) else Color(0xCC8ED8F8)
                    val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
                    drawCircle(Color(0x99071019), radius, centre, style = Stroke(3.5.dp.toPx(), pathEffect = dash))
                    drawCircle(colour, radius, centre, style = Stroke((if (next) 2f else 1.5f).dp.toPx(), pathEffect = dash))
                    dot.label?.let { label ->
                        val text = "P$label?"
                        val labelCentre = Offset(
                            (dot.x + radius * 0.72f).coerceIn(markerRadiusPx, size.width - markerRadiusPx),
                            (dot.y - radius * 0.72f).coerceIn(markerRadiusPx, size.height - markerRadiusPx),
                        )
                        val half = labelPaint.measureText(text) / 2f + 4.dp.toPx()
                        drawRoundRect(
                            Color(0xE0071019),
                            topLeft = Offset(labelCentre.x - half, labelCentre.y - markerRadiusPx * 0.8f),
                            size = androidx.compose.ui.geometry.Size(half * 2f, markerRadiusPx * 1.6f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(markerRadiusPx * 0.8f),
                        )
                        labelPaint.color = if (next) android.graphics.Color.rgb(255, 183, 3)
                            else android.graphics.Color.rgb(142, 216, 248)
                        canvas.nativeCanvas.drawText(
                            text, labelCentre.x,
                            labelCentre.y - (fontMetrics.ascent + fontMetrics.descent) / 2f, labelPaint,
                        )
                        labelPaint.color = android.graphics.Color.argb(255, 7, 19, 28)
                    }
                    return@forEach
                }
                if (dot.style == AlignmentDotStyle.CAMERA_MARK || dot.style == AlignmentDotStyle.CAMERA_MARK_OLD) {
                    val old = dot.style == AlignmentDotStyle.CAMERA_MARK_OLD
                    val radius = markerRadiusPx * 0.65f
                    val ring = if (old) Color(0x995AD67D) else Color(0xFF5AD67D)
                    drawCircle(Color(0xE0071019), radius, centre, style = Stroke(3.dp.toPx()))
                    drawCircle(
                        ring, radius, centre,
                        style = Stroke(1.5.dp.toPx(),
                            pathEffect = if (old) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())) else null),
                    )
                    val labelCentre = cameraMarkBubbleCentre(dot.x, dot.y, density.density, size.width, size.height)
                    drawCircle(Color(0xE0071019), markerRadiusPx + 2.5f, labelCentre)
                    drawCircle(ring, markerRadiusPx, labelCentre)
                    dot.label?.let { label ->
                        canvas.nativeCanvas.drawText(
                            label.toString(), labelCentre.x,
                            labelCentre.y - (fontMetrics.ascent + fontMetrics.descent) / 2f, labelPaint,
                        )
                    }
                    return@forEach
                }
                val fill = when (dot.style) {
                    AlignmentDotStyle.PICK, AlignmentDotStyle.NEXT -> Color(0xFFFFB703)
                    AlignmentDotStyle.GHOST -> Color(0xFF4CC9F0)
                    else -> Color(0xFF5AD67D)
                }
                if (dot.style == AlignmentDotStyle.NEXT) {
                    drawCircle(
                        color = Color(0xFFFFB703),
                        radius = markerRadiusPx * 1.9f,
                        center = centre,
                        style = Stroke(width = markerRadiusPx * 0.3f),
                    )
                }
                drawCircle(
                    color = Color(0xE0071019),
                    radius = markerRadiusPx + 2.5f,
                    center = centre,
                )
                drawCircle(color = fill, radius = markerRadiusPx, center = centre)
                dot.label?.let { label ->
                    val baseline = dot.y - (fontMetrics.ascent + fontMetrics.descent) / 2f
                    canvas.nativeCanvas.drawText(label.toString(), dot.x, baseline, labelPaint)
                }
            }
        }
    }
}

@Composable
private fun ConfigureTransparentPicking(view: FilamentView, enabled: Boolean) {
    DisposableEffect(view, enabled) {
        val previous = view.isTransparentPickingEnabled
        view.isTransparentPickingEnabled = enabled
        onDispose { view.isTransparentPickingEnabled = previous }
    }
}

@Composable
private fun FullscreenSystemBars(enabled: Boolean) {
    val composeView = LocalView.current
    DisposableEffect(composeView, enabled) {
        val activity = composeView.context.findActivity()
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, composeView) }
        if (enabled) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (enabled) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
