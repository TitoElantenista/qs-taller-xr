package com.qs.tallerxr.ui

import android.graphics.Rect
import android.net.Uri
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import com.qs.tallerxr.ar.SavedArPhoto
import androidx.activity.compose.BackHandler
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.height
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import android.view.HapticFeedbackConstants
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.filament.Box as FilamentBox
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.google.ar.core.Pose
import com.qs.tallerxr.ar.AlignmentFrames
import com.qs.tallerxr.ar.ArBackend
import com.qs.tallerxr.ar.ArAnchorHandle
import com.qs.tallerxr.ar.ArTracking
import com.qs.tallerxr.ar.ArFrameData
import com.qs.tallerxr.ar.ArSessionHandle
import com.qs.tallerxr.ar.CapturedAlignmentView
import com.qs.tallerxr.ar.CapturedImageFit
import io.github.sceneview.ar.arcore.transform
import io.github.sceneview.math.Position
import androidx.compose.material3.AlertDialog
import com.qs.tallerxr.ar.AlignmentMarking
import com.qs.tallerxr.ar.AlignmentPattern
import com.qs.tallerxr.ar.AlignmentSeed
import com.qs.tallerxr.ar.ArPhotoCapture
import com.qs.tallerxr.ar.CorrespondenceAdvice
import com.qs.tallerxr.ar.CorrespondenceOutcome
import com.qs.tallerxr.ar.CorrespondencePick
import com.qs.tallerxr.ar.CorrespondenceQuality
import com.qs.tallerxr.ar.CorrespondenceSighting
import com.qs.tallerxr.ar.CorrespondenceSolution
import com.qs.tallerxr.ar.FeatureEdgeMesh
import com.qs.tallerxr.ar.ManualCorrespondenceSolver
import com.qs.tallerxr.ar.MarkerlessRegistrationModel
import com.qs.tallerxr.ar.MarkerlessRegistrationModelReader
import com.qs.tallerxr.ar.ScreenGeometry
import com.qs.tallerxr.ar.WorldRay
import com.qs.tallerxr.ar.FloorPose
import com.qs.tallerxr.ar.FloorSnapSession
import com.qs.tallerxr.ar.SnapModel
import com.qs.tallerxr.ar.SnapState
import com.qs.tallerxr.ar.nextSnapCandidate
import com.qs.tallerxr.ar.snapQuality
import com.qs.tallerxr.ar.supportHit
import com.qs.tallerxr.ar.uprightYaw
import com.qs.tallerxr.ar.LandmarkVisibility
import com.qs.tallerxr.ar.PhotoProjection
import com.qs.tallerxr.ar.SnapLevel
import com.qs.tallerxr.ar.SnapQuality
import com.qs.tallerxr.ar.SnapView
import com.qs.tallerxr.ar.arPhotoPositionLabel
import com.qs.tallerxr.data.InstalledPackage
import com.qs.tallerxr.importer.GlbNodeMesh
import com.qs.tallerxr.importer.GlbReader
import io.github.sceneview.SceneScope
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setColor
import io.github.sceneview.math.Scale
import io.github.sceneview.node.Node as SceneNode
import io.github.sceneview.rememberARView
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ArAlignmentPhase {
    /** Nothing placed yet: the camera runs and the operator may start marking. */
    IDLE,

    /** The operator is marking P1..Pn from one standing position. */
    MARKING,

    /** A fit exists and is previewed semi transparent; more views may be added. */
    REVIEW,

    /** The operator confirmed the fit; the model hangs from its own anchor. */
    ANCHORED,

    /** Snap: the model hangs in front of the camera as in 3D; the operator lays it over the part. */
    SNAP_AIM,

    /** Snap with the magnet on: the model stays in the world and is pulled onto image edges. */
    SNAP_TRACKING,

    /** Snap with the magnet off: the last pose is held in the world. */
    SNAP_HOLD,
}

private val HIDDEN_AR_MODEL_POSE: Pose = Pose.makeTranslation(0f, -1_000f, 0f)

/** A press this close to a mark of the round starts a line from it. */
private const val MARK_GRAB_RADIUS_DP = 24f
private const val DEFAULT_REFERENCE_DISTANCE_M = 1.5f
private const val REVIEW_OPACITY = 0.5f

/** Snap looks at a camera frame at most this often; the model is still drawn every frame. */
private const val SNAP_FRAME_MILLIS = 150L

internal fun snapQualityText(quality: SnapQuality?): String {
    if (quality == null) return "Magnet sucht die Lage…"
    val support = "${(quality.support * 100).roundToInt()} % Kanten"
    if (quality.level == SnapLevel.NO_HOLD) return "Kein Halt · beste Lage $support"
    val views = if (quality.views == 1) "1 Ansicht" else "${quality.views} Ansichten · ${quality.spreadDegrees.roundToInt()}°"
    return when {
        quality.rank > 1 -> "Lage ${quality.rank} von ${quality.plausible} · $support · $views"
        quality.similar == 0 -> "$support · eindeutig · $views"
        else -> "$support · ${quality.similar + 1} ähnliche Lagen · $views"
    }
}

/** A provisional fit may be chosen explicitly, but failed fits or lost tracking cannot anchor. */
internal fun canAnchorAlignment(
    quality: CorrespondenceQuality?,
    tracking: Boolean,
    referenceTracking: Boolean,
    hasReference: Boolean,
    allowProvisional: Boolean,
): Boolean = tracking && referenceTracking && hasReference &&
    (quality == CorrespondenceQuality.ACCEPTABLE ||
        (allowProvisional && quality == CorrespondenceQuality.DOUBTFUL))

internal fun alignmentQualityHeadline(quality: CorrespondenceQuality): String = when (quality) {
    CorrespondenceQuality.ACCEPTABLE -> "Punktausrichtung stimmig"
    CorrespondenceQuality.DOUBTFUL -> "Punktausrichtung vorläufig"
    CorrespondenceQuality.BAD -> "Punktausrichtung widersprüchlich"
}

internal fun alignmentResidualSummary(solution: CorrespondenceSolution?): String {
    if (solution == null) return "keine Abweichungen"
    val views = if (solution.stationCount == 1) "1 Ansicht" else "${solution.stationCount} Ansichten"
    return "max. Abweichung ${String.format(Locale.ROOT, "%.0f", solution.worstLateralMm)} mm · " +
        "geschätzte Genauigkeit ±${formatExpectedError(solution.expectedErrorMm)} · $views"
}

private fun formatExpectedError(valueMm: Float): String = when {
    !valueMm.isFinite() || valueMm > 2_000f -> "mehr als 2 m"
    valueMm >= 100f -> "${(valueMm / 10f).roundToInt()} cm"
    else -> "${valueMm.roundToInt()} mm"
}

internal fun alignmentResidualDetail(solution: CorrespondenceSolution): String =
    solution.residuals.joinToString(" · ") { residual ->
        "P${residual.label}" + (if (solution.stationCount > 1) "/A${residual.station}" else "") +
            " ${String.format(Locale.ROOT, "%.0f", residual.lateralMm)} mm"
    }

/** How far each fitted CAD line lies beside its drawn line, worst view per line. */
internal fun alignmentLineDetail(solution: CorrespondenceSolution): String? {
    if (solution.lineResiduals.isEmpty()) return null
    return "Linien, Abstand quer zur Kante: " + solution.lineResiduals
        .groupBy { it.link }
        .toList()
        .sortedWith(compareBy({ it.first.first }, { it.first.second }))
        .joinToString(" · ") { (link, residuals) ->
            "${link.caption} ${String.format(Locale.ROOT, "%.0f", residuals.maxOf { it.acrossMm })} mm"
        }
}

/**
 * The crossing of the marks measures the distance between the two extreme landmarks without using
 * the fit, so a gross mismatch against the CAD means the wrong corners were marked.
 */
internal fun alignmentSpanSummary(solution: CorrespondenceSolution): String? =
    solution.observedSpanMm?.let { observed ->
        "Abstand der äußersten Punkte: CAD ${String.format(Locale.ROOT, "%.0f", solution.modelSpanMm)} mm · " +
            "gemessen ${String.format(Locale.ROOT, "%.0f", observed)} mm"
    }

internal fun alignmentAdviceText(advice: CorrespondenceAdvice): String = when (advice) {
    CorrespondenceAdvice.CHECK_MARKS ->
        "Eine Markierung liegt nicht auf ihrem Punkt oder die Reihenfolge P1…Pn stimmt nicht. Rückgängig machen und wiederholen."
    CorrespondenceAdvice.ADD_VIEW ->
        "Aus nur einer Position sind Abstand und Neigung des Bauteils nur schwach " +
            "bestimmt. Ein bis zwei Schritte zur Seite gehen und eine weitere Ansicht markieren."
    CorrespondenceAdvice.TILT_AMBIGUOUS ->
        "Zwei Neigungen erklären diese Markierungen gleich gut. Eine weitere Ansicht klärt das; " +
            "alternativ die andere Neigung ausprobieren."
    CorrespondenceAdvice.ROLL_FREE ->
        "Die gewählten Punkte liegen auf einer Linie und legen die Drehung um ihre Achse nicht fest. " +
            "Unten anpassen oder einen dritten Punkt außerhalb dieser Linie wählen."
}

internal fun alignmentMarkPrompt(marking: AlignmentMarking): String {
    val label = marking.currentLabel ?: return "Ansicht ${marking.station} vollständig"
    return "Ansicht ${marking.station} · P$label antippen"
}

internal fun alignmentQualityColor(quality: CorrespondenceQuality): Color = when (quality) {
    CorrespondenceQuality.ACCEPTABLE -> Color(0xFF5AD67D)
    CorrespondenceQuality.DOUBTFUL -> Color(0xFFFFB703)
    CorrespondenceQuality.BAD -> Color(0xFFFFB4AB)
}

/** Camera data of the frame on screen; a mark is turned into a ray with exactly these values. */
private class ArFrameSnapshot {
    var valid = false
    var inverseViewProjection: FloatArray? = null
    var cameraOrigin = FloatArray(3)
    var focalLengthPx = 0f
    var width = 0
    var height = 0
    var worldFromReference: Pose? = null
}

private class ArModelNodeHolder {
    var node: SceneNode? = null
    var pose: Pose = HIDDEN_AR_MODEL_POSE
        set(value) {
            field = value
            node?.transform = value.transform
        }

    /** Child that carries the miniature scale of the point legend; 1 for the fitted model. */
    var scaleNode: SceneNode? = null
}

/** [linkedFrom] is the mark of this round the new one was dragged out of. */
private class PendingMark(val label: Int, val ray: WorldRay, val focalLengthPx: Float, val linkedFrom: Int?)

/** [linkOrigin] is the mark the finger started on; a line is drawn from it while it moves. */
private data class MarkCursor(val position: Offset, val precise: Boolean, val label: Int, val linkOrigin: Int? = null)

@Composable
internal fun ArAlignmentViewport(
    backend: ArBackend,
    packageData: InstalledPackage,
    renderResources: ViewerRenderResources,
    appearance: AssemblyAppearance,
    partChanges: Map<String, PartChange>,
    selectedPartGuid: String?,
    alignmentPattern: AlignmentPattern,
    viewerCameraQuaternion: FloatArray?,
    alignmentMethod: AlignmentMethod,
    snapViewerCamera: SnapViewerCamera?,
    modelReference: ModelReferenceImage?,
    landscape: Boolean,
    fullscreen: Boolean,
    controlsExpanded: Boolean,
    appearanceAttached: Boolean,
    onControlsExpandedChange: (Boolean) -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    qualityAvailable: Boolean,
    inspectorName: String,
    onOpenQuality: () -> Unit,
    onOpenQualitySettings: () -> Unit,
    onSaveQualityPhoto: suspend (SavedArPhoto, String, String) -> Unit,
    onExitAr: () -> Unit,
    lifecycle: Lifecycle,
    modifier: Modifier,
) {
    val engine = renderResources.engine
    val alignmentPicks = alignmentPattern.picks
    val view = rememberARView(engine)
    val modelLoad = rememberManagedModelInstance(
        renderResources = renderResources,
        fileLocation = packageData.modelLocation,
    )
    val modelInstance = modelLoad.instance
    val packageId = packageData.manifest.packageId
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hostView = LocalView.current
    val activity = context.findActivity()
    val arViewOptions = remember(context) { context.getSharedPreferences("ar-view-options", Context.MODE_PRIVATE) }
    var magnifierZoom by remember(arViewOptions) {
        mutableStateOf(MagnifierZoom.fromFactor(arViewOptions.getInt("magnifier-zoom", 3)))
    }
    var preferFrozenViews by remember(arViewOptions) {
        mutableStateOf(arViewOptions.getBoolean("frozen-views", true))
    }
    // The 3D reference can follow the point to be marked, enlarged, and open full size.
    var referenceDetail by remember(arViewOptions) {
        mutableStateOf(arViewOptions.getBoolean("reference-detail", false))
    }
    var referenceExpanded by remember(packageId) { mutableStateOf(false) }
    val useFrozenViews = backend == ArBackend.GOOGLE && preferFrozenViews
    val density = LocalDensity.current
    // Predicted positions: never smaller than a fingertip, and not shown when too vague to help.
    val predictionMinimumPx = with(density) { 14.dp.toPx() }
    val predictionMaximumPx = with(density) { 110.dp.toPx() }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val frozenBottomInset = bottomBarHeightPx + with(density) { 16.dp.roundToPx() }

    var viewportRect by remember(packageId) { mutableStateOf<Rect?>(null) }
    var viewportSize by remember(packageId) { mutableStateOf(IntSize.Zero) }
    var photoCaptureRunning by remember(packageId) { mutableStateOf(false) }
    // A back press during the short capture must not split gallery save from QS association.
    BackHandler(enabled = photoCaptureRunning) {}
    var positionLabelVisible by remember(packageId) { mutableStateOf(false) }
    val positionLabel = remember(packageId, packageData.manifest.assembly.positionNumber) {
        arPhotoPositionLabel(packageData.manifest.assembly.positionNumber)
    }
    var registrationModel by remember(packageId) {
        mutableStateOf<MarkerlessRegistrationModel?>(null)
    }
    var edgeMesh by remember(packageId) { mutableStateOf<FeatureEdgeMesh.Mesh?>(null) }
    var edgesVisible by remember(packageId) { mutableStateOf(true) }
    var edgesOpacity by remember(packageId) { mutableFloatStateOf(1f) }
    var visibleEdgesOnly by remember(arViewOptions) {
        mutableStateOf(arViewOptions.getBoolean("visible-edges-only", false))
    }
    var occlusionMesh by remember(packageId, appearance.fastenersVisible) { mutableStateOf<GlbNodeMesh?>(null) }
    var edgeOcclusionError by remember(packageId, appearance.fastenersVisible) { mutableStateOf<String?>(null) }
    var anchoredOpacity by remember(packageId) { mutableFloatStateOf(appearance.opacity) }
    var modelLoadError by remember(packageId) { mutableStateOf<String?>(null) }
    var depthSupported by remember(packageId) { mutableStateOf<Boolean?>(null) }
    var tracking by remember(packageId) { mutableStateOf(false) }
    var referenceTracking by remember(packageId) { mutableStateOf(false) }
    var frozenViews by remember(packageId) { mutableStateOf<Map<Int, FrozenMarkingFrame>>(emptyMap()) }
    var captureRequestedAt by remember(packageId) { mutableStateOf<Long?>(null) }
    var captureRunning by remember(packageId) { mutableStateOf(false) }
    var captureGeneration by remember(packageId) { mutableIntStateOf(0) }

    // Snap is experimental and needs the camera image, which only Google ARCore hands out here.
    val snapAvailable = alignmentMethod == AlignmentMethod.SNAP && backend == ArBackend.GOOGLE && snapViewerCamera != null
    var phase by remember(packageId) {
        mutableStateOf(if (snapAvailable) ArAlignmentPhase.SNAP_AIM else ArAlignmentPhase.IDLE)
    }
    var marking by remember(packageId) { mutableStateOf<AlignmentMarking?>(null) }
    var solution by remember(packageId) { mutableStateOf<CorrespondenceSolution?>(null) }
    var rejection by remember(packageId) { mutableStateOf<String?>(null) }
    var referenceAnchor by remember(packageId) { mutableStateOf<ArAnchorHandle?>(null) }
    var referenceRequested by remember(packageId) { mutableStateOf(false) }
    var referenceDistanceM by remember(packageId) { mutableFloatStateOf(DEFAULT_REFERENCE_DISTANCE_M) }
    var seedQuaternion by remember(packageId) { mutableStateOf<FloatArray?>(null) }
    var pendingMark by remember(packageId) { mutableStateOf<PendingMark?>(null) }
    var finalAnchor by remember(packageId) { mutableStateOf<ArAnchorHandle?>(null) }
    var finalAnchorState by remember(packageId) { mutableStateOf(ArTracking.STOPPED) }
    var confirmRequested by remember(packageId) { mutableStateOf(false) }
    var liveParallaxDegrees by remember(packageId) { mutableIntStateOf(-1) }
    var cursor by remember(packageId) { mutableStateOf<MarkCursor?>(null) }
    var modelScale by remember(packageId) { mutableFloatStateOf(1f) }
    var runtimeError by remember(packageId) { mutableStateOf<String?>(null) }
    var status by remember(packageId) { mutableStateOf("AR-Kamera wird vorbereitet…") }

    // The fitted CAD drawn on the frozen photo (off by default), and a fit that exists only for
    // that picture while the first round is still being marked.
    var photoCheck by remember(packageId) { mutableStateOf(false) }
    var previewSolution by remember(packageId) { mutableStateOf<CorrespondenceSolution?>(null) }
    var surfaceVisibility by remember(packageId, appearance.fastenersVisible) { mutableStateOf<LandmarkVisibility?>(null) }
    var photoEdges by remember(packageId) { mutableStateOf<FloatArray?>(null) }
    var surfaceError by remember(packageId, appearance.fastenersVisible) { mutableStateOf<String?>(null) }

    // Snap: the turn the operator adds with two fingers while aiming, why the model cannot stand
    // yet, the pose shown in the reference frame once the magnet runs, the candidates the snap
    // keeps and the one chosen.  One frame is processed at a time off the UI thread.
    var aimTwist by remember(packageId) { mutableFloatStateOf(0f) }
    var snapAimHint by remember(packageId) { mutableStateOf<String?>(null) }
    var snapPose by remember(packageId) { mutableStateOf<Pose?>(null) }
    var snapState by remember(packageId) { mutableStateOf<SnapState?>(null) }
    var snapChoice by remember(packageId) { mutableStateOf<Int?>(null) }
    val snapQuality = snapState?.let { snapQuality(it, snapChoice) }
    var snapStartRequested by remember(packageId) { mutableStateOf(false) }
    var snapGeneration by remember(packageId) { mutableIntStateOf(0) }
    var snapLastMillis by remember(packageId) { mutableLongStateOf(0L) }
    val snapSessionHolder = remember(packageId) { SnapSessionHolder() }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                // Keep completed photos, but do not publish an in-flight capture after backgrounding.
                captureGeneration++
                captureRequestedAt = null
                captureRunning = false
                tracking = false
                referenceTracking = false
                cursor = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(captureRequestedAt) {
        val request = captureRequestedAt ?: return@LaunchedEffect
        delay(3_000)
        if (captureRequestedAt == request) {
            captureRequestedAt = null
            status = "Kein Kamerabild verfügbar. Aufnahme wiederholen oder im Bedienfeld Live-Markierung wählen."
        }
    }

    LaunchedEffect(phase, tracking, finalAnchorState) {
        Log.i("QsAr", "Alignment phase=$phase cameraTracking=$tracking anchor=$finalAnchorState")
    }
    LaunchedEffect(phase) {
        if (phase != ArAlignmentPhase.MARKING) referenceExpanded = false
    }

    val frameSnapshot = remember(packageId) { ArFrameSnapshot() }
    val modelNodeHolder = remember(packageId) { ArModelNodeHolder() }
    val alignmentOverlayState = remember(packageId) { AlignmentOverlayState() }
    val currentAlignmentPicks = rememberUpdatedState(alignmentPicks)
    val currentViewerCameraQuaternion = rememberUpdatedState(viewerCameraQuaternion)
    val currentAppearanceAttached = rememberUpdatedState(appearanceAttached)

    val modelCentre = remember(registrationModel) {
        registrationModel?.let { model ->
            FloatArray(3) { axis -> (model.boundsMin[axis] + model.boundsMax[axis]) / 2f }
        } ?: FloatArray(3)
    }
    val modelExtentPoints = remember(registrationModel) {
        registrationModel?.let { model ->
            List(8) { corner ->
                FloatArray(3) { axis ->
                    if (corner shr axis and 1 == 0) model.boundsMin[axis] else model.boundsMax[axis]
                }
            }
        }.orEmpty()
    }
    val snapModel = remember(registrationModel) {
        registrationModel?.let { SnapModel(it.featureEdges, it.boundsMin, it.boundsMax) }
    }
    val displayedAppearance = when {
        phase == ArAlignmentPhase.REVIEW || phase == ArAlignmentPhase.SNAP_AIM ||
            phase == ArAlignmentPhase.SNAP_TRACKING || phase == ArAlignmentPhase.SNAP_HOLD ->
            appearance.copy(opacity = min(appearance.opacity, REVIEW_OPACITY))
        phase == ArAlignmentPhase.ANCHORED -> appearance.copy(opacity = anchoredOpacity)
        else -> appearance
    }
    val edgeOcclusionActive = phase == ArAlignmentPhase.ANCHORED && visibleEdgesOnly
    val needsEdgeOcclusion = edgeOcclusionActive && edgesVisible && appearanceAttached
    LaunchedEffect(packageId, needsEdgeOcclusion, appearance.fastenersVisible) {
        if (!needsEdgeOcclusion || occlusionMesh != null) return@LaunchedEffect
        edgeOcclusionError = null
        try {
            occlusionMesh = withContext(Dispatchers.Default) {
                val scene = withContext(Dispatchers.IO) {
                    if (packageData.modelUri == null) GlbReader.read(packageData.modelFile)
                    else context.contentResolver.openInputStream(Uri.parse(packageData.modelUri))?.use {
                        GlbReader.parse(it.readBytes())
                    } ?: error("Die verknüpfte GLB-Datei ist nicht verfügbar.")
                }
                edgeOcclusionMesh(scene.meshes, packageData.parts.parts, appearance.fastenersVisible)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            edgeOcclusionError = "Sichtbare Kanten konnten nicht vorbereitet werden: ${error.message}. " +
                "Bitte «Alle Kanten» wählen."
        }
    }
    // Occlusion by the part itself, for the edges drawn on a photo and for the snap.
    val needsSurfaces = (photoCheck && useFrozenViews) || snapAvailable
    LaunchedEffect(packageId, needsSurfaces, appearance.fastenersVisible) {
        if (!needsSurfaces || surfaceVisibility != null) return@LaunchedEffect
        try {
            surfaceVisibility = withContext(Dispatchers.Default) {
                val scene = withContext(Dispatchers.IO) {
                    if (packageData.modelUri == null) GlbReader.read(packageData.modelFile)
                    else context.contentResolver.openInputStream(Uri.parse(packageData.modelUri))?.use {
                        GlbReader.parse(it.readBytes())
                    } ?: error("Die verknüpfte GLB-Datei ist nicht verfügbar.")
                }
                LandmarkVisibility(edgeOcclusionMesh(scene.meshes, packageData.parts.parts, appearance.fastenersVisible).triangles)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("QsAr", "Surfaces for visibility unavailable", error)
            surfaceError = "Die Flächen des Modells konnten nicht gelesen werden: ${error.message}"
        }
    }
    ApplyAssemblyAppearance(
        engine,
        renderResources.materialLoader,
        modelInstance.takeIf { appearanceAttached },
        packageData.parts.parts,
        displayedAppearance,
        partChanges,
        selectedPartGuid,
        uniformOpacity = phase == ArAlignmentPhase.ANCHORED,
    )

    LaunchedEffect(packageId) {
        val registrationFile = packageData.registrationModelFile
        runCatching {
            withContext(Dispatchers.IO) { MarkerlessRegistrationModelReader.read(registrationFile) }
        }.onSuccess { loaded ->
            registrationModel = loaded
            edgeMesh = withContext(Dispatchers.Default) {
                FeatureEdgeMesh.ribbons(loaded.featureEdges, FeatureEdgeMesh.widthFor(loaded.diameterM))
                    .takeIf { it.vertexCount > 0 }
            }
        }.onFailure { error ->
            modelLoadError = error.message ?: "Das Punktmodell konnte nicht gelesen werden."
            status = modelLoadError!!
        }
    }

    fun releaseAlignment() {
        captureGeneration++
        captureRequestedAt = null
        captureRunning = false
        frozenViews = emptyMap()
        referenceTracking = false
        referenceAnchor?.detach()
        referenceAnchor = null
        finalAnchor?.detach()
        finalAnchor = null
        finalAnchorState = ArTracking.STOPPED
        referenceRequested = false
        confirmRequested = false
        pendingMark = null
        marking = null
        solution = null
        rejection = null
        seedQuaternion = null
        liveParallaxDegrees = -1
        cursor = null
        frameSnapshot.worldFromReference = null
        alignmentOverlayState.clear()
        previewSolution = null
        photoEdges = null
        snapGeneration++
        snapSessionHolder.session = null
        snapPose = null
        snapState = null
        snapChoice = null
        snapStartRequested = false
    }

    fun cancelAlignment(message: String) {
        releaseAlignment()
        phase = ArAlignmentPhase.IDLE
        status = message
    }

    fun startMarking() {
        if (alignmentPicks.size < ManualCorrespondenceSolver.MINIMUM_PAIRS) return
        if (registrationModel == null) return
        onControlsExpandedChange(false)
        releaseAlignment()
        marking = AlignmentMarking(
            labels = alignmentPicks.map(CorrespondencePick::label),
            links = alignmentPattern.links,
        )
        referenceRequested = true
        phase = ArAlignmentPhase.MARKING
        status = if (useFrozenViews) "Ansicht 1: Bauteil ins Bild nehmen und «Aufnehmen» drücken. Danach die Punkte im Standbild markieren."
            else "Ansicht 1: Die gewählten Punkte am echten Bauteil antippen. Die Miniatur zeigt die Punktnummern."
    }

    fun solve(next: AlignmentMarking): Boolean {
        val outcome = ManualCorrespondenceSolver.solve(
            picks = currentAlignmentPicks.value,
            sightings = next.sightings,
            seedQuaternionXyzw = seedQuaternion ?: floatArrayOf(0f, 0f, 0f, 1f),
            fallbackDistanceM = referenceDistanceM,
            modelDiameterM = registrationModel?.diameterM ?: 1f,
            modelExtentPoints = modelExtentPoints,
            lines = next.lines,
        )
        return when (outcome) {
            is CorrespondenceOutcome.Solved -> {
                solution = outcome.solution
                rejection = null
                true
            }
            is CorrespondenceOutcome.Rejected -> {
                solution = null
                rejection = outcome.reason
                status = outcome.reason
                false
            }
        }
    }

    /** Applies a changed marking log: refits as soon as the marks allow it. */
    fun applyMarking(next: AlignmentMarking) {
        marking = next
        liveParallaxDegrees = -1
        // Later views refine the fit mark by mark for quality and parallax feedback. The model
        // stays in the miniature until the round is complete, independently of that fit.
        val refit = next.canSolve && (next.station > 1 || next.stationComplete)
        val solved = if (refit) solve(next) else false
        if (!refit) {
            solution = null
            rejection = null
        }
        // For the photo only: the first round, three or more marks, not yet complete.
        previewSolution = if (!refit && next.stationSightings.size >= 3) {
            (ManualCorrespondenceSolver.solve(
                picks = currentAlignmentPicks.value,
                sightings = next.sightings,
                seedQuaternionXyzw = seedQuaternion ?: floatArrayOf(0f, 0f, 0f, 1f),
                fallbackDistanceM = referenceDistanceM,
                modelDiameterM = registrationModel?.diameterM ?: 1f,
                modelExtentPoints = modelExtentPoints,
                lines = next.lines,
            ) as? CorrespondenceOutcome.Solved)?.solution
        } else {
            null
        }
        if (next.stationComplete && (solved || next.stationSightings.isNotEmpty())) {
            phase = ArAlignmentPhase.REVIEW
            val current = solution
            status = if (current == null) {
                rejection ?: "Weitere Ansicht aufnehmen, um die Ausrichtung zu bestimmen."
            } else {
                alignmentQualityHeadline(current.quality) + ": " + alignmentResidualSummary(current) + "."
            }
        } else if (next.stationComplete && !next.canSolve) {
            phase = ArAlignmentPhase.MARKING
            status = "Es werden mindestens zwei markierte Punkte benötigt. Ein Überspringen rückgängig machen oder abbrechen."
        } else {
            phase = ArAlignmentPhase.MARKING
            next.currentLabel?.let { label ->
                if (rejection == null) status = when {
                    next.selectedLabel == label && next.isMarkedInStation(label) ->
                        "P$label neu setzen: an der richtigen Stelle antippen oder die alte Markierung dorthin " +
                            "ziehen. Die alte gilt, bis die neue gesetzt ist."
                    next.selectedLabel == label -> "Ansicht ${next.station}: P$label jetzt markieren."
                    else -> "Ansicht ${next.station}: P$label am echten Bauteil antippen."
                }
            }
        }
    }

    /** A point chip: mark that point next, or place it again; the highlighted chip returns to the order. */
    fun selectPoint(label: Int) {
        val current = marking ?: return
        if (phase != ArAlignmentPhase.MARKING && phase != ArAlignmentPhase.REVIEW) return
        cursor = null
        pendingMark = null
        applyMarking(current.selected(if (current.selectedLabel == label) null else label))
    }

    fun commitLiveMark(pixel: Offset, linkedFrom: Int?) {
        val current = marking ?: return
        val label = current.currentLabel ?: return
        val worldFromReference = frameSnapshot.worldFromReference
        val inverse = frameSnapshot.inverseViewProjection
        if (!frameSnapshot.valid || worldFromReference == null || inverse == null) {
            status = "Das AR-System verfolgt die Umgebung noch nicht. Das Telefon langsam bewegen und die Markierung wiederholen."
            return
        }
        val worldRay = ScreenGeometry.rayThroughPixel(
            inverseViewProjection = inverse,
            originWorld = frameSnapshot.cameraOrigin,
            xPx = pixel.x,
            yPx = pixel.y,
            widthPx = frameSnapshot.width,
            heightPx = frameSnapshot.height,
        )
        if (worldRay == null) {
            status = "Diese Markierung konnte nicht ausgewertet werden. Bitte am Bauteil wiederholen."
            return
        }
        pendingMark = PendingMark(
            label = label,
            ray = AlignmentFrames.rayInReference(worldFromReference, worldRay),
            focalLengthPx = frameSnapshot.focalLengthPx,
            linkedFrom = linkedFrom,
        )
    }

    /**
     * Applies a mark; a line only starts at another mark that still exists in this round.  Dragging
     * the old mark of the point being placed again just moves it.  A light tick confirms the mark.
     */
    fun addMark(current: AlignmentMarking, sighting: CorrespondenceSighting, linkedFrom: Int?) {
        val origin = linkedFrom?.takeIf { from ->
            from != sighting.label && current.stationSightings.any { it.label == from }
        }
        hostView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        applyMarking(current.marked(sighting, origin))
        if (origin != null && rejection == null && marking?.currentLabel != null) {
            status = "P${sighting.label} mit Linie von P$origin gesetzt. " + status
        }
    }

    fun skipMark() {
        val current = marking ?: return
        if (current.currentLabel == null) return
        applyMarking(current.skipped())
    }

    fun undoMark() {
        val current = marking ?: return
        if (!current.canUndo) return
        pendingMark = null
        rejection = null
        captureGeneration++
        captureRequestedAt = null
        captureRunning = false
        val removed = current.undoCaption
        val next = current.undone()
        // A discarded later round must not reuse a stale photo if its station number is reused.
        frozenViews = frozenViews.filterKeys { it <= next.station }
        applyMarking(next)
        if (removed != null && rejection == null) status = "Rückgängig: $removed. $status"
    }

    fun addView() {
        val current = marking ?: return
        onControlsExpandedChange(false)
        cursor = null
        captureGeneration++
        captureRequestedAt = null
        captureRunning = false
        rejection = null
        val next = current.nextStation()
        frozenViews = frozenViews - next.station
        marking = next
        phase = ArAlignmentPhase.MARKING
        // Clear the previous preview immediately, including while camera tracking is paused.
        modelNodeHolder.pose = HIDDEN_AR_MODEL_POSE
        alignmentOverlayState.clear()
        liveParallaxDegrees = -1
        status = "Ansicht ${next.station}: Ein bis zwei Schritte zur Seite gehen oder um das Bauteil herumgehen und " +
            "dieselben Punkte erneut antippen. Die Miniatur zeigt die Punkte; die Kamera bleibt frei. " +
            "Nicht sichtbare Punkte überspringen."
    }

    fun retakeView() {
        val current = marking ?: return
        captureGeneration++
        captureRequestedAt = null
        captureRunning = false
        frozenViews = frozenViews - current.station
        cursor = null
        val next = current.restartedStation()
        if (next.sightings.isEmpty()) seedQuaternion = null
        applyMarking(next)
        status = "Ansicht ${current.station}: Bild neu aufnehmen. Nur die Markierungen dieser Ansicht wurden entfernt."
    }

    fun requestCapture() {
        if (!tracking || !referenceTracking || captureRunning || captureRequestedAt != null) return
        onControlsExpandedChange(false)
        captureRequestedAt = SystemClock.uptimeMillis()
        status = "Kamerabild wird aufgenommen…"
    }

    fun confirmAlignment(provisional: Boolean = false) {
        val current = solution ?: return
        if (phase != ArAlignmentPhase.REVIEW || !canAnchorAlignment(current.quality, tracking,
                referenceTracking, referenceAnchor != null, provisional)) return
        onControlsExpandedChange(false)
        confirmRequested = true
        status = "Punktausrichtung wird in einem AR-Anker fixiert…"
    }

    fun rollAlignment(degrees: Float) {
        solution = solution?.rolled(degrees) ?: return
        onControlsExpandedChange(false)
        status = "Drehung um ${degrees.toInt()}° um die Achse der markierten Punkte. " +
            "Die Abweichungen ändern sich nicht: Punkte auf einer Linie erfassen diese Drehung nicht."
    }

    fun useAlternativeTilt() {
        solution = solution?.withAlternativePose() ?: return
        onControlsExpandedChange(false)
        status = "Die andere, zu den Markierungen passende Neigung wird angezeigt. Eine weitere Ansicht entscheidet."
    }

    /** Magnet on: the model stays in the world and the snap searches where it stands around the aim. */
    fun startSnap() {
        when (phase) {
            ArAlignmentPhase.SNAP_AIM -> {
                // Without the surfaces, edges on the far side would look for image edges too.
                if (!tracking || snapModel == null || surfaceVisibility == null || snapAimHint != null) return
                snapStartRequested = true
                status = "Magnet an: die Lage wird im Umkreis gesucht. Danach ein, zwei Schritte seitlich gehen; " +
                    "jede neue Blickrichtung prüft alle möglichen Lagen."
            }
            ArAlignmentPhase.SNAP_HOLD -> {
                phase = ArAlignmentPhase.SNAP_TRACKING
                status = "Magnet wieder an."
            }
            else -> Unit
        }
    }

    /** Magnet off: the last pose is held where it is. */
    fun pauseSnap() {
        if (phase != ArAlignmentPhase.SNAP_TRACKING) return
        snapGeneration++
        phase = ArAlignmentPhase.SNAP_HOLD
        status = "Magnet aus: die Lage bleibt stehen. «Verankern» fixiert sie, «Neu zielen» beginnt von vorn."
    }

    /** Back to aiming: the model follows the camera again, the snap starts from scratch. */
    fun reaimSnap() {
        releaseAlignment()
        phase = ArAlignmentPhase.SNAP_AIM
        status = "Neu zielen: Bildmitte auf die Mitte des Bauteils richten, mit zwei Fingern drehen, dann den Magneten einschalten."
    }

    /** Shows the next pose the snap considers possible, e.g. one drawer bay aside. */
    fun showNextSnapCandidate() {
        val state = snapState ?: return
        val snap = snapSessionHolder.session ?: return
        val next = nextSnapCandidate(state, snapChoice ?: state.candidates.firstOrNull()?.id) ?: return
        snapChoice = next.id
        snapPose = snap.referenceFromModel(next.pose)
        status = if (next.id == state.candidates.first().id) "Wieder die beste Lage angezeigt."
            else "Andere mögliche Lage angezeigt. Verankern übernimmt die angezeigte Lage."
    }

    fun confirmSnap() {
        if ((phase != ArAlignmentPhase.SNAP_TRACKING && phase != ArAlignmentPhase.SNAP_HOLD) || snapPose == null ||
            !tracking || !referenceTracking) return
        onControlsExpandedChange(false)
        snapGeneration++
        confirmRequested = true
        status = "Snap-Lage wird in einem AR-Anker fixiert…"
    }

    fun takeArPhoto() {
        if (!qualityAvailable) {
            status = "Die QS-Akte ist nicht verfügbar. Bitte Qualitätssicherung öffnen."
            onOpenQuality()
            return
        }
        if (inspectorName.isBlank()) {
            status = "Bitte zuerst den Prüfernamen speichern und danach AR-Foto erneut wählen."
            onOpenQualitySettings()
            return
        }
        val photoInspector = inspectorName
        val alignmentDescription = when {
            alignmentMethod == AlignmentMethod.SNAP -> "Verankert · Snap (experimentell)"
            solution?.quality == CorrespondenceQuality.ACCEPTABLE -> "Verankert · Ausrichtung bestätigt"
            else -> "Verankert · vorläufige Ausrichtung"
        }
        val currentActivity = activity ?: run {
            status = "Für das Foto konnte nicht auf das Fenster zugegriffen werden."
            return
        }
        val currentViewport = viewportRect ?: run {
            status = "Die AR-Ansicht ist noch nicht bereit für ein Foto."
            return
        }
        if (photoCaptureRunning || phase != ArAlignmentPhase.ANCHORED || !tracking ||
            finalAnchorState != ArTracking.TRACKING) return
        photoCaptureRunning = true
        scope.launch {
            try {
                withContext(kotlinx.coroutines.NonCancellable) {
                    // Keep capture and association together if this composable leaves the viewport.
                    // Read the AR render surface directly; Compose controls are on another layer.
                    val saved = ArPhotoCapture.captureAndSave(
                        activity = currentActivity,
                        rootView = hostView.rootView,
                        viewport = currentViewport,
                        positionNumber = packageData.manifest.assembly.positionNumber,
                        positionLabel = positionLabel.takeIf { positionLabelVisible },
                    )
                    try {
                        onSaveQualityPhoto(saved, alignmentDescription, photoInspector)
                    } catch (error: Exception) {
                        status = "Foto in der Galerie gespeichert, aber die QS-Zuordnung ist fehlgeschlagen: ${error.message}"
                        return@withContext
                    }
                    status = "AR-Foto in der QS-Akte und unter Pictures/QS Taller XR gespeichert."
                }
            } catch (error: Throwable) {
                status = "Das AR-Foto konnte nicht gespeichert werden: ${error.message}"
            } finally {
                photoCaptureRunning = false
            }
        }
    }

    /**
     * One camera frame in Snap mode.  Aiming: the model stands upright on the surface under the
     * screen point where the 3D view had its centre, turned as in 3D plus the operator's twist.
     * Magnet on or off: it hangs from a reference anchor and, while the magnet runs, a steady frame
     * from a new direction goes to the snap whenever the previous one is done.
     */
    fun snapFrame(
        session: ArSessionHandle,
        frame: ArFrameData,
        projection: FloatArray,
        cameraView: FloatArray,
        cameraPose: Pose,
        width: Int,
        height: Int,
    ) {
        alignmentOverlayState.clear()
        val model = snapModel
        val viewer = snapViewerCamera
        if (model == null || viewer == null) {
            modelNodeHolder.pose = HIDDEN_AR_MODEL_POSE
            return
        }
        if (phase == ArAlignmentPhase.SNAP_AIM) {
            if (status.startsWith("AR-Kamera wird")) {
                status = "Snap: Das Bauteil muss aufrecht auf dem Boden oder einem Tisch stehen. Telefon kurz über den " +
                    "Boden schwenken, dann die Bildmitte auf die Mitte des Bauteils richten und mit zwei Fingern drehen, " +
                    "bis das Modell ungefähr passt. Dann den Magneten einschalten."
            }
            val display = frame.displayOrientedPose
            val seen = display.compose(viewer.cameraFromModel(projection[5], 1f))
            val yaw = uprightYaw(seen) + aimTwist
            val origin = display.translation
            val centre = seen.transformPoint(model.centre)
            val length = kotlin.math.sqrt((0..2).sumOf { ((centre[it] - origin[it]).toDouble()).let { d -> d * d } }).toFloat()
            val direction = FloatArray(3) { (centre[it] - origin[it]) / length.coerceAtLeast(1e-6f) }
            val planes = session.horizontalPlanes()
            val hit = supportHit(planes, origin, direction, model.centre[1] - model.boundsMin[1], model.footprintAreaM2)
            val hint = when {
                planes.isEmpty() -> "Boden suchen: Telefon langsam über den Boden schwenken"
                hit == null -> "Telefon etwas nach unten neigen: die Bildmitte muss auf das Bauteil zeigen"
                else -> null
            }
            if (snapAimHint != hint) snapAimHint = hint
            if (hit == null) {
                // Until it can stand, the model hangs where the 3D view showed it, as a preview only.
                modelNodeHolder.pose = seen
                return
            }
            val aim = FloorPose(hit.x, hit.z, yaw)
            val worldFromModel = model.referenceFromModel(aim, hit.floorY)
            modelNodeHolder.pose = worldFromModel
            if (snapStartRequested) {
                snapStartRequested = false
                runCatching { session.createAnchor(Pose.makeTranslation(worldFromModel.transformPoint(model.centre))) }
                    .onSuccess { anchor ->
                        referenceAnchor = anchor
                        // The anchor is a pure translation: the reference axes are the world's, +Y up.
                        val foot = anchor.pose.inverse().transformPoint(floatArrayOf(hit.x, hit.floorY, hit.z))
                        val snap = FloorSnapSession(model, surfaceVisibility, foot[1], FloorPose(foot[0], foot[2], yaw))
                        snapGeneration++
                        snapSessionHolder.session = snap
                        snapPose = snap.referenceFromModel(snap.aim)
                        snapState = null
                        snapChoice = null
                        phase = ArAlignmentPhase.SNAP_TRACKING
                    }
                    .onFailure { error -> status = "Der Magnet konnte nicht starten: ${error.message}" }
            }
            return
        }
        val reference = referenceAnchor
        if (reference == null || reference.trackingState == ArTracking.STOPPED) {
            reaimSnap()
            status = "Das AR-System hat die Lage verloren. Bitte neu zielen."
            return
        }
        val worldFromReference = reference.pose.takeIf { reference.trackingState == ArTracking.TRACKING }
        referenceTracking = worldFromReference != null
        val pose = snapPose
        if (worldFromReference == null || pose == null) {
            modelNodeHolder.pose = HIDDEN_AR_MODEL_POSE
            return
        }
        val worldFromModel = worldFromReference.compose(pose)
        modelNodeHolder.pose = worldFromModel
        if (confirmRequested) {
            confirmRequested = false
            runCatching { session.createAnchor(AlignmentFrames.anchorPoseAtModelCentre(worldFromModel, modelCentre)) }
                .onSuccess { created ->
                    finalAnchor = created
                    finalAnchorState = created.trackingState
                    referenceAnchor?.detach()
                    referenceAnchor = null
                    snapGeneration++
                    snapSessionHolder.session = null
                    phase = ArAlignmentPhase.ANCHORED
                    status = "Snap-Lage verankert (experimentell). Keine Messung: Bleche, Bohrungen und Enden prüfen."
                }
                .onFailure { error -> status = "Der Anker konnte nicht erstellt werden: ${error.message}" }
            return
        }
        val snap = snapSessionHolder.session ?: return
        val now = SystemClock.uptimeMillis()
        // Measured every frame, so a quick turn just before is not missed.
        val steady = snapSessionHolder.steady(cameraPose, now)
        if (phase != ArAlignmentPhase.SNAP_TRACKING || now - snapLastMillis < SNAP_FRAME_MILLIS || !steady ||
            !snapSessionHolder.running.compareAndSet(false, true)) return
        val focal = (projection[5] * height / 2f).takeIf { it.isFinite() && it > 1f } ?: height.toFloat()
        val geometry = CapturedAlignmentView(width, height, projection, cameraView, cameraPose, worldFromReference,
            frame.displayOrientedPose, focal)
        // Nothing runs now, so the session may be asked on this thread.
        val image = if (snap.wantsView(geometry, now)) runCatching { frame.copyLuminance(width, height) }.getOrNull() else null
        if (image == null) {
            snapSessionHolder.running.set(false)
            return
        }
        snapLastMillis = now
        val generation = snapGeneration
        scope.launch {
            try {
                val started = SystemClock.uptimeMillis()
                val result = withContext(Dispatchers.Default) { snap.process(SnapView(image, geometry), now) }
                Log.i("QsSnap", "View ${result.views}: ${SystemClock.uptimeMillis() - started} ms, " +
                    result.candidates.take(3).joinToString { "${it.pose} ${"%.3f".format(Locale.ROOT, it.support)}" })
                if (generation == snapGeneration && snapSessionHolder.session === snap) {
                    val before = snapState?.let { snapQuality(it, snapChoice) }?.level
                    val choice = snapChoice?.takeIf { id -> result.candidates.any { it.id == id } }
                    snapState = result
                    snapChoice = choice
                    val quality = snapQuality(result, choice)
                    val shown = result.candidates.firstOrNull { it.id == choice } ?: result.candidates.firstOrNull()
                    // Without a hold the model stays where the operator put it.
                    if (shown != null && quality != null && quality.level != SnapLevel.NO_HOLD) {
                        snapPose = snap.referenceFromModel(shown.pose)
                    }
                    if (quality?.level == SnapLevel.GOOD && before != SnapLevel.GOOD) {
                        hostView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == snapGeneration) status = "Snap-Fehler: ${error.message}"
            } finally {
                snapSessionHolder.running.set(false)
            }
        }
    }

    val currentReferenceAnchor = rememberUpdatedState(referenceAnchor)
    val currentFinalAnchor = rememberUpdatedState(finalAnchor)
    DisposableEffect(packageId) {
        onDispose {
            modelNodeHolder.node = null
            modelNodeHolder.scaleNode = null
            currentReferenceAnchor.value?.detach()
            currentFinalAnchor.value?.detach()
        }
    }

    runtimeError?.let { message ->
        AlertDialog(
            onDismissRequest = onExitAr,
            title = { Text("AR nicht verfügbar") },
            text = { Text(message + "\n\nDer 3D-Modus bleibt nutzbar.") },
            confirmButton = { Button(onClick = onExitAr) { Text("Zurück zu 3D") } },
        )
    }

    BoxWithConstraints(
        modifier.onGloballyPositioned { coordinates ->
            viewportSize = coordinates.size
            val bounds = coordinates.boundsInWindow()
            viewportRect = Rect(
                bounds.left.roundToInt(),
                bounds.top.roundToInt(),
                bounds.right.roundToInt(),
                bounds.bottom.roundToInt(),
            )
        },
    ) {
        DeviceArScene(
            backend = backend,
            resources = renderResources,
            view = view,
            lifecycle = lifecycle,
            onDepthSupported = { depthSupported = it },
            horizontalPlanes = snapAvailable,
            onFrame = onSessionUpdated@{ session, frame ->
                if (!currentAppearanceAttached.value) return@onSessionUpdated
                val nowTracking = frame.trackingState == ArTracking.TRACKING
                if (tracking != nowTracking) tracking = nowTracking
                finalAnchorState = finalAnchor?.trackingState ?: ArTracking.STOPPED
                if (!nowTracking) {
                    referenceTracking = false
                    frameSnapshot.valid = false
                    // The video can continue while Huawei's pose is paused. Leaving the last
                    // camera/model transforms drawn would make an anchor look glued to the screen.
                    modelNodeHolder.pose = HIDDEN_AR_MODEL_POSE
                    alignmentOverlayState.clear()
                    if (!useFrozenViews) cursor = null
                    return@onSessionUpdated
                }
                val viewportWidth = view.viewport.width
                val viewportHeight = view.viewport.height
                if (viewportWidth <= 0 || viewportHeight <= 0) return@onSessionUpdated
                if (phase == ArAlignmentPhase.IDLE && status.startsWith("AR-Kamera wird")) {
                    status = "${backend.label} bereit."
                }

                // The same AR camera matrices SceneView renders this frame with: a mark, the model
                // and its labelled points all come from one camera pose.
                val projection = frame.projection(0.05f, 60f)
                val cameraView = frame.viewMatrix()
                val viewProjection = ScreenGeometry.multiply(projection, cameraView)
                val cameraPose = frame.cameraPose

                if (snapAvailable && phase != ArAlignmentPhase.ANCHORED) {
                    snapFrame(session, frame, projection, cameraView, cameraPose, viewportWidth, viewportHeight)
                    return@onSessionUpdated
                }

                if (referenceRequested && referenceAnchor == null) {
                    val axis = cameraPose.zAxis
                    val forward = floatArrayOf(-axis[0], -axis[1], -axis[2])
                    val distance = frame.measuredDistance(cameraPose.translation, forward)
                        ?: DEFAULT_REFERENCE_DISTANCE_M
                    val origin = cameraPose.translation
                    runCatching {
                        session.createAnchor(
                            Pose.makeTranslation(
                                origin[0] + forward[0] * distance,
                                origin[1] + forward[1] * distance,
                                origin[2] + forward[2] * distance,
                            ),
                        )
                    }.onSuccess { created ->
                        referenceAnchor = created
                        referenceDistanceM = distance
                        referenceRequested = false
                    }.onFailure { error ->
                        cancelAlignment("Die AR-Referenz konnte nicht erstellt werden: ${error.message}")
                    }
                }
                val reference = referenceAnchor
                if (reference?.trackingState == ArTracking.STOPPED) {
                    cancelAlignment("Das AR-System hat die Referenz der Markierungen verloren. Ausrichtung bitte wiederholen.")
                }
                val worldFromReference = referenceAnchor
                    ?.takeIf { it.trackingState == ArTracking.TRACKING }
                    ?.pose
                referenceTracking = worldFromReference != null

                frameSnapshot.inverseViewProjection = ScreenGeometry.invert(viewProjection)
                frameSnapshot.cameraOrigin = cameraPose.translation
                frameSnapshot.focalLengthPx = (projection[5] * viewportHeight / 2f)
                    .takeIf { it.isFinite() && it > 1f } ?: viewportHeight.toFloat()
                frameSnapshot.width = viewportWidth
                frameSnapshot.height = viewportHeight
                frameSnapshot.worldFromReference = worldFromReference
                frameSnapshot.valid = frameSnapshot.inverseViewProjection != null

                // Copy image bytes AND this frame's geometry together. The AR session continues
                // updating while the operator works on the immutable photo in Compose.
                if (useFrozenViews && captureRequestedAt != null && !captureRunning &&
                    phase == ArAlignmentPhase.MARKING && worldFromReference != null) {
                    try {
                        val image = frame.copyCameraImage(viewportWidth, viewportHeight)
                        if (image != null) {
                            Log.i("QsAr", "Frozen capture: ${image.width}x${image.height}, viewport ${viewportWidth}x$viewportHeight")
                            val station = checkNotNull(marking).station
                            val generation = captureGeneration
                            val geometry = CapturedAlignmentView(viewportWidth, viewportHeight, projection,
                                cameraView, cameraPose, worldFromReference, frame.displayOrientedPose,
                                frameSnapshot.focalLengthPx)
                            val viewerQuaternion = currentViewerCameraQuaternion.value?.copyOf()
                                ?: floatArrayOf(0f, 0f, 0f, 1f)
                            captureRequestedAt = null
                            captureRunning = true
                            scope.launch {
                                try {
                                    val captured = withContext(Dispatchers.Default) {
                                        FrozenMarkingFrame(image.bitmap().asImageBitmap(), geometry)
                                    }
                                    if (captureGeneration == generation && phase == ArAlignmentPhase.MARKING &&
                                        marking?.station == station) {
                                        frozenViews = frozenViews + (station to captured)
                                        if (seedQuaternion == null) {
                                            seedQuaternion = AlignmentSeed.modelRotationFromViewerCamera(
                                                geometry.displayPoseInReference.rotationQuaternion, viewerQuaternion)
                                        }
                                        status = "Standbild $station: Punkte markieren. 0,5 s halten öffnet die Lupe; Loslassen setzt den Punkt. " +
                                            "Von einer Markierung aus ziehen verbindet den nächsten Punkt mit einer Linie."
                                    }
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    if (captureGeneration == generation) status = "Aufnahme fehlgeschlagen: ${error.message}"
                                } finally {
                                    if (captureGeneration == generation) captureRunning = false
                                }
                            }
                        }
                    } catch (error: Exception) {
                        captureRequestedAt = null
                        status = "Aufnahme fehlgeschlagen: ${error.message}. Bitte wiederholen."
                    }
                }

                pendingMark?.let { pending ->
                    pendingMark = null
                    val current = marking
                    if (current == null || worldFromReference == null ||
                        current.currentLabel != pending.label
                    ) {
                        return@let
                    }
                    val worldRay = AlignmentFrames.rayInWorld(worldFromReference, pending.ray)
                    if (seedQuaternion == null) {
                        // The operator stands roughly where the 3D viewer looked from, so that
                        // view seeds the fit and supplies any rotation the marks cannot observe.
                        seedQuaternion = AlignmentFrames.quaternionInReference(
                            worldFromReference,
                            AlignmentSeed.modelRotationFromViewerCamera(
                                arCameraQuaternionXyzw =
                                    frame.displayOrientedPose.rotationQuaternion,
                                viewerCameraQuaternionXyzw = currentViewerCameraQuaternion.value
                                    ?: floatArrayOf(0f, 0f, 0f, 1f),
                            ),
                        )
                    }
                    addMark(
                        current,
                        CorrespondenceSighting(
                            label = pending.label,
                            station = current.station,
                            rayOrigin = pending.ray.origin,
                            rayDirection = pending.ray.direction,
                            measuredDistanceM = frame.measuredDistance(worldRay.origin, worldRay.direction),
                            focalLengthPx = pending.focalLengthPx,
                        ),
                        pending.linkedFrom,
                    )
                }

                if (confirmRequested) {
                    confirmRequested = false
                    val current = solution
                    if (current != null && worldFromReference != null) {
                        val worldFromModel = AlignmentFrames.worldFromModel(worldFromReference, current)
                        runCatching {
                            session.createAnchor(
                                AlignmentFrames.anchorPoseAtModelCentre(worldFromModel, modelCentre),
                            )
                        }.onSuccess { created ->
                            finalAnchor = created
                            finalAnchorState = created.trackingState
                            referenceAnchor?.detach()
                            referenceAnchor = null
                            frameSnapshot.worldFromReference = null
                            phase = ArAlignmentPhase.ANCHORED
                            frozenViews = emptyMap()
                            alignmentOverlayState.clear()
                            status = "Punktausrichtung verankert (" + alignmentResidualSummary(current) +
                                "). Keine Messung: Bleche, Bohrungen und Enden prüfen."
                        }.onFailure { error ->
                            status = "Der Anker konnte nicht erstellt werden: ${error.message}"
                        }
                    } else {
                        status = "Die AR-Referenz wird nicht verfolgt. Telefon bewegen und erneut fixieren."
                    }
                }

                // Pose of the model for this very frame, written straight to the node so it is
                // rendered with the camera pose it was computed from.
                var scale = 1f
                val anchored = finalAnchor
                val fitted = solution
                val picksForFrame = currentAlignmentPicks.value
                val worldFromModel: Pose? = when {
                    anchored != null -> anchored
                        .takeIf { finalAnchorState == ArTracking.TRACKING }
                        ?.let { AlignmentFrames.worldFromModelAtAnchor(it.pose, modelCentre) }
                    // The exact captured 3D view is shown by Compose in both live and frozen marking.
                    phase == ArAlignmentPhase.MARKING -> null
                    fitted != null && phase == ArAlignmentPhase.REVIEW ->
                        worldFromReference?.let { AlignmentFrames.worldFromModel(it, fitted) }
                    else -> null
                }
                modelNodeHolder.pose = worldFromModel ?: HIDDEN_AR_MODEL_POSE
                if (abs(modelScale - scale) > 1.0e-6f) {
                    // Pose and scale change in the same frame; the state only keeps the
                    // declarative parameter of the node in step for later recompositions.
                    modelNodeHolder.scaleNode?.scale = Scale(scale)
                    modelScale = scale
                }

                if (
                    phase == ArAlignmentPhase.MARKING ||
                    (worldFromModel != null && phase == ArAlignmentPhase.REVIEW)
                ) {
                    val current = marking
                    // Keep the fitted model and its ghosts away from the real part while marking.
                    // The current round's camera marks remain visible with open centres.
                    val markedLabels = current?.stationSightings?.map { it.label }.orEmpty()
                    val dots = ArrayList<AlignmentDot>(picksForFrame.size)
                    if (worldFromModel != null) picksForFrame.forEach { pick ->
                        ScreenGeometry.projectPixel(
                            viewProjection = viewProjection,
                            world = AlignmentFrames.posedPoint(worldFromModel, scale, pick.modelPoint),
                            widthPx = viewportWidth,
                            heightPx = viewportHeight,
                        )?.let { pixel ->
                            dots += AlignmentDot(
                                x = pixel[0],
                                y = pixel[1],
                                label = pick.label,
                                style = when {
                                    phase != ArAlignmentPhase.MARKING -> AlignmentDotStyle.GHOST
                                    pick.label == current?.currentLabel -> AlignmentDotStyle.NEXT
                                    pick.label in markedLabels -> AlignmentDotStyle.MARK
                                    else -> AlignmentDotStyle.GHOST
                                },
                            )
                        }
                    }
                    val replaced = current?.selectedLabel?.takeIf(current::isMarkedInStation)
                    if (phase == ArAlignmentPhase.MARKING && current != null && worldFromReference != null) {
                        current.stationSightings.forEach { sighting ->
                            val ray = AlignmentFrames.rayInWorld(
                                worldFromReference, WorldRay(sighting.rayOrigin, sighting.rayDirection),
                            )
                            ScreenGeometry.projectPixel(
                                viewProjection = viewProjection,
                                world = ray.pointAt(sighting.measuredDistanceM ?: referenceDistanceM),
                                widthPx = viewportWidth,
                                heightPx = viewportHeight,
                            )?.let { pixel ->
                                dots += AlignmentDot(pixel[0], pixel[1], sighting.label,
                                    if (sighting.label == replaced) AlignmentDotStyle.CAMERA_MARK_OLD
                                    else AlignmentDotStyle.CAMERA_MARK)
                            }
                        }
                        // From the second view on: where the fit so far expects the open points.
                        if (current.station > 1 && fitted != null && fitted.quality != CorrespondenceQuality.BAD) {
                            val worldFromFit = AlignmentFrames.worldFromModel(worldFromReference, fitted)
                            picksForFrame.filter {
                                !current.isMarkedInStation(it.label) &&
                                    current.stateOf(it.label) != AlignmentMarking.PointState.SKIPPED
                            }.forEach { pick ->
                                val world = worldFromFit.transformPoint(pick.modelPoint)
                                val pixel = ScreenGeometry.projectPixel(viewProjection, world, viewportWidth, viewportHeight)
                                    ?: return@forEach
                                val distance = kotlin.math.sqrt((0..2).sumOf {
                                    ((world[it] - cameraPose.translation[it]).toDouble()).let { d -> d * d }
                                }).toFloat()
                                val radius = kotlin.math.max(predictionMinimumPx,
                                    2f * fitted.expectedErrorMm / 1000f * frameSnapshot.focalLengthPx / distance.coerceAtLeast(0.05f))
                                if (radius <= predictionMaximumPx) dots += AlignmentDot(pixel[0], pixel[1], pick.label,
                                    if (pick.label == current.currentLabel) AlignmentDotStyle.PREDICTED_NEXT
                                    else AlignmentDotStyle.PREDICTED, radiusPx = radius)
                            }
                        }
                    }
                    // Marked lines of this round while marking; the fitted CAD lines in review, so
                    // the operator can hold each one against its real edge.
                    val lines = when {
                        current == null -> emptyList()
                        phase == ArAlignmentPhase.MARKING -> alignmentLines(
                            dots.filter { it.style == AlignmentDotStyle.CAMERA_MARK },
                            current.stationLines.filterNot { replaced != null && it.touches(replaced) },
                            AlignmentLineStyle.MARK,
                        )
                        else -> alignmentLines(
                            dots.filter { it.style == AlignmentDotStyle.GHOST },
                            current.lines,
                            AlignmentLineStyle.GHOST,
                        )
                    }
                    alignmentOverlayState.update(dots, lines)
                } else {
                    alignmentOverlayState.clear()
                }

                // How far the operator has walked round the part since the previous views.
                val current = marking
                if (
                    phase == ArAlignmentPhase.MARKING && current != null && current.station > 1 &&
                    fitted != null && worldFromReference != null && !frozenViews.containsKey(current.station)
                ) {
                    val label = current.currentLabel
                    val pick = picksForFrame.firstOrNull { it.label == label } ?: picksForFrame.firstOrNull()
                    val earlier = current.sightings.filter { it.station != current.station }
                    val candidates = earlier.filter { it.label == pick?.label }.ifEmpty { earlier }
                    if (pick != null && candidates.isNotEmpty()) {
                        val point = Pose(fitted.translation, fitted.quaternionXyzw).transformPoint(pick.modelPoint)
                        val cameraNow = worldFromReference.inverse().transformPoint(cameraPose.translation)
                        val degrees = candidates.maxOf { sighting ->
                            ManualCorrespondenceSolver.parallaxDegrees(point, sighting.rayOrigin, cameraNow)
                        }.roundToInt()
                        if (degrees != liveParallaxDegrees) liveParallaxDegrees = degrees
                    }
                }
            },
            onFailure = { message ->
                tracking = false
                frameSnapshot.valid = false
                cursor = null
                modelNodeHolder.pose = HIDDEN_AR_MODEL_POSE
                alignmentOverlayState.clear()
                runtimeError = message
            },
        ) {
            // This composable slot never changes while AR is open.  Only its pose changes, so
            // SceneView cannot destroy and then accidentally reuse the same native model entities.
            if (modelInstance != null) {
                Node(
                    position = Position(0f, -1_000f, 0f),
                    apply = { modelNodeHolder.node = this },
                ) {
                    Node(
                        scale = Scale(modelScale),
                        apply = { modelNodeHolder.scaleNode = this },
                    ) {
                        ModelNode(modelInstance = modelInstance)
                        val mesh = edgeMesh
                        val bounds = registrationModel
                        if (edgesVisible && mesh != null && bounds != null && appearanceAttached) {
                            if (edgeOcclusionActive) occlusionMesh?.let { occluder ->
                                EdgeOcclusionNode(engine, renderResources.materialLoader, occluder)
                            }
                            FeatureEdgesNode(
                                engine = engine,
                                materialLoader = renderResources.materialLoader,
                                mesh = mesh,
                                // Do not claim "visible only" while the depth mask is still loading.
                                opacity = if (edgeOcclusionActive && occlusionMesh == null) 0f else edgesOpacity,
                                depthTestEnabled = phase != ArAlignmentPhase.ANCHORED || edgeOcclusionActive,
                                boundsMin = bounds.boundsMin,
                                boundsMax = bounds.boundsMax,
                            )
                        }
                    }
                }
            }
        }

        val activeMarking = marking
        val activeLabel = activeMarking?.currentLabel
        val activeFrozen = if (phase == ArAlignmentPhase.MARKING) frozenViews[activeMarking?.station] else null
        val frozenFit = remember(activeFrozen, viewportSize, frozenBottomInset) {
            activeFrozen?.let { CapturedImageFit(it.geometry.width, it.geometry.height,
                viewportSize.width, viewportSize.height, frozenBottomInset) }
        }
        val currentCommitMark = rememberUpdatedState<(MarkRelease) -> Unit>(
            if (activeFrozen != null && frozenFit != null && activeMarking != null && activeLabel != null) {
                frozenMarkCommitter(activeFrozen.geometry, frozenFit, activeLabel, activeMarking.station) { sighting, linkedFrom ->
                    val current = marking
                    if (current?.currentLabel == sighting.label && current.station == sighting.station &&
                        frozenViews[current.station] === activeFrozen) {
                        addMark(current, sighting, linkedFrom)
                    }
                }
            } else {
                { release -> if (!useFrozenViews) commitLiveMark(release.position, release.linkedFrom) }
            },
        )
        // Marks of this round as drawn right now: fixed on a photo, reprojected every frame live.
        // A press that starts on one of them draws the next point out of it, with a line.
        val markGrabRadiusPx = with(density) { MARK_GRAB_RADIUS_DP.dp.toPx() }
        val markBubbleGrabRadiusPx = with(density) { 14.dp.toPx() }
        val currentMarkHandles = rememberUpdatedState<() -> List<MarkHandle>> {
            val positions = if (activeFrozen != null && frozenFit != null && activeMarking != null) {
                frozenMarkPositions(activeFrozen, activeMarking, frozenFit)
            } else {
                alignmentOverlayState.dots.mapNotNull { dot ->
                    dot.label?.takeIf {
                        dot.style == AlignmentDotStyle.CAMERA_MARK || dot.style == AlignmentDotStyle.CAMERA_MARK_OLD
                    }?.let { it to Offset(dot.x, dot.y) }
                }
            }
            positions.map { (label, point) ->
                MarkHandle(
                    label,
                    point,
                    cameraMarkBubbleCentre(point.x, point.y, density.density,
                        viewportSize.width.toFloat(), viewportSize.height.toFloat()),
                )
            }
        }
        if (activeFrozen != null && frozenFit != null) FrozenCameraImage(activeFrozen, frozenFit)
        if (phase == ArAlignmentPhase.SNAP_AIM && !photoCaptureRunning) {
            // Two fingers turn the model about the vertical; its distance comes from the floor.
            Box(Modifier.matchParentSize().pointerInput(Unit) {
                detectTransformGestures { _, _, _, rotation ->
                    // Clockwise on the screen is clockwise seen from above: a negative turn about +Y.
                    if (rotation.isFinite()) aimTwist -= Math.toRadians(rotation.toDouble()).toFloat()
                }
            })
        }
        if (
            phase == ArAlignmentPhase.MARKING && activeMarking?.currentLabel != null &&
            !photoCaptureRunning && appearanceAttached && (!useFrozenViews || activeFrozen != null)
        ) {
            // A stationary hold must activate without another MotionEvent. The timeout belongs
            // to this pointer gesture, so release/cancellation cannot leave a delayed mark behind.
            val label = activeMarking.currentLabel
            Box(
                // A photo/layout change cancels an in-flight hold instead of reinterpreting its
                // final pixel in a different image rectangle. Cursor recompositions keep this key.
                Modifier.matchParentSize().pointerInput(packageId, label, activeMarking.station, activeFrozen, frozenFit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        Log.d("QsArGesture", "Down P$label; viewportReady=${viewportRect != null}")
                        down.consume()
                        val gesture = PrecisionMarkGesture(down.position, down.uptimeMillis)
                        val start = markUnderFinger(currentMarkHandles.value(), down.position,
                            markGrabRadiusPx, markBubbleGrabRadiusPx)
                        try {
                            cursor = MarkCursor(gesture.position, precise = false, label = label ?: 0, linkOrigin = start?.label)
                            while (gesture.pressed) {
                                val event = if (gesture.precise) {
                                    awaitPointerEvent()
                                } else {
                                    withTimeoutOrNull(
                                        gesture.millisUntilPrecision(SystemClock.uptimeMillis()).coerceAtLeast(1L),
                                    ) { awaitPointerEvent() }
                                }
                                if (event == null) {
                                    gesture.advanceTime(SystemClock.uptimeMillis())
                                    Log.d("QsArGesture", "Hold timer P$label precise=${gesture.precise}")
                                } else {
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (
                                        change == null || event.changes.size > 1 || change.isConsumed ||
                                        change.position.x < 0f || change.position.x >= size.width ||
                                        change.position.y < 0f || change.position.y >= size.height
                                    ) {
                                        Log.d("QsArGesture", "Cancelled P$label consumed=${change?.isConsumed} pointers=${event.changes.size}")
                                        gesture.cancel()
                                        break
                                    }
                                    change.consume()
                                    if (!change.pressed) {
                                        Log.d("QsArGesture", "Released P$label precise=${gesture.precise}")
                                        gesture.release(change.position, change.uptimeMillis)?.let { position ->
                                            // Live marks move with the camera: compare with where
                                            // the start mark is drawn now, not at the press.
                                            val origin = start?.let { first ->
                                                currentMarkHandles.value().firstOrNull { it.label == first.label }
                                            }
                                            currentCommitMark.value(
                                                MarkRelease(position, linkedMarkOrigin(origin, position, markGrabRadiusPx)),
                                            )
                                        }
                                        break
                                    }
                                    gesture.move(change.position, change.uptimeMillis)
                                }
                                cursor = MarkCursor(gesture.position, gesture.precise, label ?: 0, start?.label)
                            }
                        } finally {
                            gesture.cancel()
                            cursor = null
                        }
                    }
                },
            )
        }
        AlignmentDotsOverlay(
            state = alignmentOverlayState,
            visible = !photoCaptureRunning &&
                activeFrozen == null && (phase == ArAlignmentPhase.MARKING || phase == ArAlignmentPhase.REVIEW),
        )
        if (activeFrozen != null && activeMarking != null && frozenFit != null) {
            val fitted = solution
            val predictions = remember(activeFrozen, activeMarking, fitted) {
                if (activeMarking.station < 2 || fitted == null || fitted.quality == CorrespondenceQuality.BAD) {
                    emptyList()
                } else {
                    val pose = PhotoProjection.pose(fitted)
                    alignmentPicks.filter {
                        !activeMarking.isMarkedInStation(it.label) &&
                            activeMarking.stateOf(it.label) != AlignmentMarking.PointState.SKIPPED
                    }.mapNotNull { pick ->
                        PhotoProjection.prediction(activeFrozen.geometry, pose, pick.label, pick.modelPoint,
                            fitted.expectedErrorMm, predictionMinimumPx / frozenFit.scale,
                            predictionMaximumPx / frozenFit.scale)
                    }
                }
            }
            val edgesFit = solution ?: previewSolution
            LaunchedEffect(photoCheck, activeFrozen, edgesFit, surfaceVisibility, registrationModel) {
                photoEdges = null
                val fit = edgesFit ?: return@LaunchedEffect
                val model = registrationModel ?: return@LaunchedEffect
                if (!photoCheck) return@LaunchedEffect
                val geometry = activeFrozen.geometry
                val visibility = surfaceVisibility
                // All edges at once; the hidden ones drop out as soon as the surfaces are ready.
                photoEdges = withContext(Dispatchers.Default) {
                    PhotoProjection.visibleEdges(geometry, PhotoProjection.pose(fit), model.featureEdges, visibility,
                        stepM = (model.diameterM / 150f).coerceIn(0.003f, 0.03f))
                }
            }
            if (photoCheck && cursor == null) {
                val checkFit = solution ?: previewSolution
                val residuals = remember(activeFrozen, activeMarking, checkFit) {
                    if (checkFit == null) emptyList() else {
                        val pose = PhotoProjection.pose(checkFit)
                        activeMarking.stationSightings.mapNotNull { sighting ->
                            val mark = activeFrozen.geometry.pixel(WorldRay(sighting.rayOrigin, sighting.rayDirection))
                                ?: return@mapNotNull null
                            val pick = alignmentPicks.firstOrNull { it.label == sighting.label } ?: return@mapNotNull null
                            val cad = PhotoProjection.pixel(activeFrozen.geometry, pose, pick.modelPoint) ?: return@mapNotNull null
                            ResidualVector(mark[0], mark[1], cad[0], cad[1],
                                residualTone(checkFit, sighting.label, sighting.station))
                        }
                    }
                }
                PhotoCheckOverlay(photoEdges, residuals, frozenFit)
            }
            FrozenMarkingOverlay(activeFrozen, activeMarking, frozenFit, predictions)
        }
        // Lines towards the finger: from the mark it started on (once it has left that mark) and
        // from marks joined to the pending point by a line of the 3D pattern.
        val shownCursor = cursor.takeIf { !photoCaptureRunning }
        val draftSources = if (shownCursor != null && activeMarking != null && phase == ArAlignmentPhase.MARKING) {
            val handles = currentMarkHandles.value()
            val linked = activeMarking.markedLinksOf(shownCursor.label)
            // Dragging the old mark of the point being placed again moves it; no line.
            val dragged = shownCursor.linkOrigin?.takeIf { origin ->
                origin != shownCursor.label &&
                    linkedMarkOrigin(handles.firstOrNull { it.label == origin }, shownCursor.position, markGrabRadiusPx) != null
            }
            handles.filter { it.label in linked || it.label == dragged }
        } else {
            emptyList()
        }
        val draggedOrigin = shownCursor?.linkOrigin?.takeIf { origin -> draftSources.any { it.label == origin } }
        MarkCursorOverlay(shownCursor, draftSources.map(MarkHandle::position))

        if (phase == ArAlignmentPhase.MARKING && !photoCaptureRunning && !controlsExpanded && modelReference != null) {
            ModelReferenceCard(
                reference = modelReference,
                marking = marking,
                detail = referenceDetail,
                onDetailChange = { enabled ->
                    referenceDetail = enabled
                    arViewOptions.edit().putBoolean("reference-detail", enabled).apply()
                },
                onExpand = {
                    cursor = null
                    referenceExpanded = true
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)
                    .size(width = (maxWidth * 0.36f).coerceIn(110.dp, 180.dp),
                        height = (maxHeight * 0.37f).coerceIn(120.dp, 200.dp)),
            )
        }

        if (!photoCaptureRunning && (!tracking ||
                (phase == ArAlignmentPhase.ANCHORED && finalAnchorState != ArTracking.TRACKING))) {
            Surface(
                // Below the toolbar, never across its buttons; while the panel is open, above the
                // bottom bar instead of behind the panel.
                modifier = if (controlsExpanded && !photoCaptureRunning) {
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp, start = 12.dp, end = 12.dp)
                } else {
                    Modifier.align(Alignment.TopCenter).padding(top = 60.dp, start = 12.dp, end = 12.dp)
                },
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    if (phase == ArAlignmentPhase.ANCHORED) {
                        if (finalAnchorState == ArTracking.STOPPED) {
                            "AR-Anker verloren. Ausrichtung bitte neu beginnen."
                        } else {
                            "AR-Tracking unterbrochen. Bauteil ausgeblendet, bis die Position wieder erkannt wird."
                        }
                    } else {
                        "Das AR-System erfasst die Umgebung: Telefon langsam seitlich bewegen."
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        if (!photoCaptureRunning && controlsExpanded) {
            Surface(
                modifier = if (landscape) {
                    Modifier.align(Alignment.TopEnd).padding(10.dp).widthIn(max = 400.dp)
                } else {
                    Modifier.align(Alignment.TopEnd)
                        .padding(start = 12.dp, top = 60.dp, end = 10.dp)
                        .widthIn(min = 260.dp, max = 430.dp)
                },
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = maxHeight * if (landscape) 0.58f else 0.44f)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (phase) {
                                ArAlignmentPhase.IDLE -> "Punktausrichtung"
                                ArAlignmentPhase.MARKING -> "Punkte in der Kamera markieren"
                                ArAlignmentPhase.REVIEW -> "Ausrichtung prüfen"
                                ArAlignmentPhase.ANCHORED -> "Bauteil verankert"
                                ArAlignmentPhase.SNAP_AIM, ArAlignmentPhase.SNAP_TRACKING, ArAlignmentPhase.SNAP_HOLD ->
                                    "Snap (experimentell)"
                            },
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = { onControlsExpandedChange(false) }) {
                            Icon(Icons.Default.ExpandLess, contentDescription = "AR-Bedienfeld ausblenden")
                        }
                    }
                    OutlinedButton(
                        onClick = { edgesVisible = !edgesVisible },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (edgesVisible) "Hervorgehobene Kanten ausblenden" else "Hervorgehobene Kanten anzeigen")
                    }
                    if (alignmentMethod == AlignmentMethod.SNAP) {
                        Text("Experimentell: Das Bauteil muss aufrecht auf dem Boden oder einem Tisch stehen, den " +
                            "ARCore erkannt hat; daraus ergibt sich der Abstand. Der Magnet sucht im Umkreis von etwa 30 % " +
                            "der Grundfläche und ±24° die Stelle, an der die sichtbaren Modellkanten am besten auf " +
                            "Bildkanten gleicher Richtung liegen, und prüft alle möglichen Lagen in jeder neuen " +
                            "Blickrichtung erneut. Bei gleichen Fächern kann eine Lage daneben ähnlich gut passen: dann " +
                            "«Andere Lage». Es verankert nie von selbst.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Anzeige: mittlere Kantenübereinstimmung, ob eine andere Lage ähnlich gut passt, Zahl und " +
                            "Winkel der Blickrichtungen. «Gut» erst ab zwei Richtungen mit 10° Abstand und deutlichem " +
                            "Vorsprung vor jeder anderen Lage. Die Punktausrichtung bleibt die Standardmethode.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (backend == ArBackend.GOOGLE) {
                        OutlinedButton(onClick = {
                            preferFrozenViews = !preferFrozenViews
                            arViewOptions.edit().putBoolean("frozen-views", preferFrozenViews).apply()
                            cancelAlignment("Markiermodus geändert. Ausrichtung neu beginnen.")
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (useFrozenViews) "Standbilder aktiv · zu Live wechseln" else "Live aktiv · zu Standbildern wechseln")
                        }
                    } else {
                        Text("Huawei AR Engine: Punkte im Livebild markieren. Die Standbild-Ausrichtung " +
                            "ist derzeit nur mit Google ARCore verfügbar.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (alignmentMethod == AlignmentMethod.POINTS) Text(
                        "Je Ansicht die sichtbaren Punkte antippen; 0,5 s halten öffnet die Lupe. Von einer Markierung " +
                        "aus ziehen verbindet mit einer Linie, «Rückgängig» entfernt den letzten Punkt. Zweite Ansicht " +
                        "etwa 20° seitlich versetzt: genauer.",
                        style = MaterialTheme.typography.bodySmall)
                    if (alignmentMethod == AlignmentMethod.POINTS) Text("Lupenzoom", style = MaterialTheme.typography.labelMedium)
                    if (alignmentMethod == AlignmentMethod.POINTS) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MagnifierZoom.entries.forEach { zoom ->
                            FilterChip(
                                selected = magnifierZoom == zoom,
                                onClick = {
                                    magnifierZoom = zoom
                                    arViewOptions.edit().putInt("magnifier-zoom", zoom.factor).apply()
                                },
                                label = { Text(zoom.label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (phase == ArAlignmentPhase.ANCHORED) {
                        Text("Kantendarstellung", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(false to "Alle Kanten", true to "Nur sichtbare Kanten").forEach { (visibleOnly, label) ->
                                FilterChip(
                                    selected = visibleEdgesOnly == visibleOnly,
                                    onClick = {
                                        visibleEdgesOnly = visibleOnly
                                        arViewOptions.edit().putBoolean("visible-edges-only", visibleOnly).apply()
                                    },
                                    enabled = edgesVisible,
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (edgeOcclusionActive && edgesVisible && occlusionMesh == null) {
                            Text(edgeOcclusionError ?: "Sichtbare Kanten werden vorbereitet…",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (edgeOcclusionError != null) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "Deckkraft des Bauteils ${(anchoredOpacity * 100).roundToInt()} %",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = anchoredOpacity,
                            onValueChange = { anchoredOpacity = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        "Deckkraft der Kanten ${(edgesOpacity * 100).roundToInt()} %",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = edgesOpacity,
                        onValueChange = { edgesOpacity = it },
                        valueRange = 0f..1f,
                        enabled = edgesVisible,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(backend.label, style = MaterialTheme.typography.labelSmall)
                    Text(modelLoad.error ?: status, style = MaterialTheme.typography.bodySmall)
                    if (alignmentMethod == AlignmentMethod.SNAP) {
                        snapQuality?.let { quality ->
                            Text(snapQualityText(quality), style = MaterialTheme.typography.labelMedium)
                        }
                    } else if (alignmentPicks.size < ManualCorrespondenceSolver.MINIMUM_PAIRS) {
                        Text(
                            "Zum 3D-Modus zurückkehren, «AR ${alignmentPicks.size}/$ALIGNMENT_MAXIMUM_PICKS» antippen und 2 bis " +
                                "$ALIGNMENT_MAXIMUM_PICKS Ecken des Modells antippen. Mindestens drei weit " +
                                "auseinanderliegende Punkte empfohlen, die nicht auf einer Linie liegen.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        Text(
                            "${alignmentPicks.size} Punkte in 3D gewählt · " +
                                (marking?.lines?.size ?: alignmentPattern.links.size).let { lines ->
                                    if (lines == 0) "" else if (lines == 1) "1 Linie · " else "$lines Linien · "
                                } +
                                "${marking?.sightings?.size ?: 0} Markierungen in " +
                                "${marking?.stationCount ?: 0} Ansichten. Jede Markierung ist eine Sichtlinie: " +
                                "Zwei getrennte Ansichten kreuzen diese Linien und legen Abstand, " +
                                "Neigung und scheinbare Größe des Bauteils fest.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    rejection?.let { reason ->
                        Text(
                            reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    solution?.let { current ->
                        if (phase == ArAlignmentPhase.REVIEW && current.quality == CorrespondenceQuality.DOUBTFUL) {
                            Text("Noch nicht ausreichend bestimmt. Vorläufiges Verankern erhält diese Unsicherheit.",
                                color = Color(0xFFFFB703), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { confirmAlignment(provisional = true) },
                                enabled = tracking && referenceTracking, modifier = Modifier.fillMaxWidth()) {
                                Text("Trotzdem vorläufig verankern")
                            }
                        }
                        Text(
                            alignmentQualityHeadline(current.quality),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = alignmentQualityColor(current.quality),
                        )
                        Text(alignmentResidualSummary(current), style = MaterialTheme.typography.labelMedium)
                        Text("Schätzung aus den Punktmarkierungen; AR-Trackingfehler sind darin nicht enthalten. " +
                            "Nach dem Verankern von beiden Seiten prüfen.",
                            style = MaterialTheme.typography.bodySmall)
                        Text(
                            alignmentResidualDetail(current),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        alignmentLineDetail(current)?.let { lines ->
                            Text(
                                lines,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        alignmentSpanSummary(current)?.let { span ->
                            Text(
                                span,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (phase != ArAlignmentPhase.ANCHORED) {
                            current.advice.forEach { advice ->
                                Text(alignmentAdviceText(advice), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (phase == ArAlignmentPhase.REVIEW && current.rollUndetermined) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                listOf(-90f, -15f, 15f, 90f).forEach { degrees ->
                                    OutlinedButton(
                                        onClick = { rollAlignment(degrees) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                                    ) {
                                        Text(if (degrees > 0) "+${degrees.toInt()}°" else "${degrees.toInt()}°")
                                    }
                                }
                            }
                        }
                        if (phase == ArAlignmentPhase.REVIEW && current.alternative != null) {
                            OutlinedButton(
                                onClick = ::useAlternativeTilt,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Andere Neigung ausprobieren")
                            }
                        }
                    }
                    if (alignmentMethod == AlignmentMethod.POINTS && alignmentPicks.size >= ManualCorrespondenceSolver.MINIMUM_PAIRS) {
                        Button(
                            onClick = ::startMarking,
                            enabled = tracking && registrationModel != null && modelInstance != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.TouchApp, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (phase == ArAlignmentPhase.IDLE) {
                                    "P1…P${alignmentPicks.size} in der Kamera markieren"
                                } else {
                                    "Ausrichtung von vorn beginnen"
                                },
                            )
                        }
                    }
                    if (depthSupported == false && alignmentMethod == AlignmentMethod.POINTS) {
                        Text(
                            "Für dieses AR-System wird keine Tiefenmessung verwendet. Das ist nicht nötig: Die Ausrichtung nutzt " +
                                "die Markierungen aus zwei Ansichten.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (!photoCaptureRunning) {
            // One compact strip at the real bottom edge. Photo controls exist only after
            // anchoring, so no reserved 70 dp strip pushes instructions over the real part.
            val bottomBar = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                .widthIn(max = 640.dp).fillMaxWidth().onSizeChanged { bottomBarHeightPx = it.height }
            Surface(modifier = bottomBar, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    when (phase) {
                        ArAlignmentPhase.IDLE -> if (alignmentMethod == AlignmentMethod.SNAP) {
                            Text("Snap ist derzeit nur mit Google ARCore verfügbar. Zurück zu 3D und «AR n/6» für die " +
                                "Punktausrichtung verwenden.", style = MaterialTheme.typography.labelMedium)
                        } else Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (alignmentPicks.size >= ManualCorrespondenceSolver.MINIMUM_PAIRS)
                                if (useFrozenViews) "Punkte im Standbild markieren" else "Punkte am Bauteil markieren"
                                else "Zuerst 2–$ALIGNMENT_MAXIMUM_PICKS Punkte im 3D-Modell wählen",
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Button(onClick = ::startMarking,
                                enabled = tracking && registrationModel != null && modelInstance != null &&
                                    alignmentPicks.size >= ManualCorrespondenceSolver.MINIMUM_PAIRS) { Text("Ausrichten") }
                        }
                        ArAlignmentPhase.MARKING -> {
                            val waitingForPhoto = useFrozenViews && activeFrozen == null
                            if (waitingForPhoto) {
                                Text(when {
                                    captureRunning || captureRequestedAt != null -> "Bild wird aufgenommen…"
                                    status.startsWith("Aufnahme fehlgeschlagen") || status.startsWith("Kein Kamerabild") -> status
                                    activeMarking != null && activeMarking.station > 1 && liveParallaxDegrees >= 0 ->
                                        "Seitlich um das Bauteil bewegen · ${liveParallaxDegrees.coerceAtLeast(0)}° / 20° empfohlen"
                                    activeMarking != null && activeMarking.station > 1 ->
                                        "Seitlich um das Bauteil bewegen und dieselben sichtbaren Punkte erneut aufnehmen."
                                    else -> "Alle sichtbaren Punkte ins Bild nehmen; danach in Ruhe markieren."
                                }, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            } else if (rejection != null) {
                                Text(rejection!!, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            if (!waitingForPhoto && activeMarking != null) {
                                PointChips(
                                    labels = activeMarking.labels,
                                    tone = { label -> chipTone(activeMarking.stateOf(label)) },
                                    describe = { label -> pointChipDescription(activeMarking, label) },
                                    onSelect = ::selectPoint,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (waitingForPhoto) "Ansicht ${activeMarking?.station ?: 1}"
                                    else "${if (activeFrozen != null) "Bild" else "Ansicht"} ${activeMarking?.station ?: 1} · " +
                                        (activeMarking?.currentLabel?.let { label ->
                                            if (activeMarking.selectedLabel == label && activeMarking.isMarkedInStation(label))
                                                "P$label neu setzen" else "P$label/${alignmentPicks.size}"
                                        } ?: "vollständig"),
                                    modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                if (waitingForPhoto) {
                                    Button(onClick = ::requestCapture,
                                        enabled = tracking && referenceTracking && !captureRunning && captureRequestedAt == null,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Aufnehmen") }
                                } else {
                                    if (activeFrozen != null) IconButton(onClick = { photoCheck = !photoCheck },
                                        modifier = Modifier.size(44.dp)) {
                                        Icon(Icons.Default.Layers,
                                            contentDescription = if (photoCheck) "CAD-Kanten im Bild ausblenden"
                                                else "CAD-Kanten im Bild zeigen",
                                            tint = if (photoCheck) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (useFrozenViews) IconButton(onClick = ::retakeView, modifier = Modifier.size(44.dp)) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Diese Ansicht neu aufnehmen")
                                    }
                                    IconButton(onClick = ::skipMark, enabled = activeMarking?.currentLabel != null,
                                        modifier = Modifier.size(44.dp)) {
                                        Icon(Icons.Default.SkipNext, contentDescription = "Punkt nicht sichtbar: überspringen")
                                    }
                                }
                                IconButton(onClick = ::undoMark, enabled = activeMarking?.canUndo == true,
                                    modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Letzte Markierung rückgängig machen")
                                }
                                IconButton(onClick = { cancelAlignment("Punktausrichtung abgebrochen.") },
                                    modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Ausrichtung abbrechen")
                                }
                            }
                        }
                        ArAlignmentPhase.REVIEW -> {
                            val current = solution
                            val ready = current?.quality == CorrespondenceQuality.ACCEPTABLE
                            activeMarking?.let { round ->
                                // Coloured by deviation: tap a point to place it again on its photo.
                                PointChips(
                                    labels = round.labels,
                                    tone = { label ->
                                        if (round.stateOf(label) == AlignmentMarking.PointState.SKIPPED) ChipTone.SKIPPED
                                        else residualTone(current, label, round.station)
                                    },
                                    describe = { label -> pointChipDescription(round, label, current) },
                                    onSelect = ::selectPoint,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (current == null) rejection ?: status else
                                    "${current.stationCount} ${if (current.stationCount == 1) "Ansicht" else "Ansichten"} · " +
                                        "${if (ready) "stimmig" else "vorläufig"} · ±${formatExpectedError(current.expectedErrorMm)}",
                                    modifier = Modifier.weight(1f),
                                    color = current?.let { alignmentQualityColor(it.quality) } ?: MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = ::undoMark, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Markierungen bearbeiten")
                                }
                                IconButton(onClick = { onControlsExpandedChange(!controlsExpanded) }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.Tune, contentDescription = "Abweichungen und Ausrichtung prüfen")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = { confirmAlignment(provisional = !ready) },
                                    enabled = canAnchorAlignment(current?.quality, tracking, referenceTracking,
                                        referenceAnchor != null, allowProvisional = true),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                                    Text("Verankern", maxLines = 1)
                                }
                                OutlinedButton(onClick = ::addView, modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                                    Text("Weitere Ansicht", maxLines = 1)
                                }
                            }
                            if (current?.quality == CorrespondenceQuality.DOUBTFUL) {
                                Text("Weitere Ansicht empfohlen; Verankern auch jetzt möglich.",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        ArAlignmentPhase.SNAP_AIM -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                surfaceError ?: if (snapModel == null || surfaceVisibility == null) "Modell wird vorbereitet…"
                                else snapAimHint ?: "Snap · Bildmitte auf die Mitte des Bauteils · zwei Finger: drehen",
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Button(onClick = ::startSnap,
                                enabled = tracking && snapModel != null && surfaceVisibility != null && snapAimHint == null,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                                Icon(MagnetIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Magnet")
                            }
                        }
                        ArAlignmentPhase.SNAP_TRACKING, ArAlignmentPhase.SNAP_HOLD -> {
                            val quality = snapQuality
                            Text(
                                if (phase == ArAlignmentPhase.SNAP_HOLD) "Magnet aus · Lage gehalten"
                                else snapQualityText(quality),
                                color = when (quality?.level) {
                                    SnapLevel.GOOD -> Color(0xFF5AD67D)
                                    SnapLevel.WEAK -> Color(0xFFFFB703)
                                    SnapLevel.NO_HOLD -> MaterialTheme.colorScheme.error
                                    null -> MaterialTheme.colorScheme.onSurface
                                },
                                style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = ::confirmSnap, enabled = tracking && referenceTracking && snapPose != null,
                                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                                    Text("Verankern", maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { if (phase == ArAlignmentPhase.SNAP_TRACKING) pauseSnap() else startSnap() },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Icon(MagnetIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (phase == ArAlignmentPhase.SNAP_TRACKING) "Magnet aus" else "Magnet an", maxLines = 1)
                                }
                                if ((quality?.plausible ?: 0) >= 2) {
                                    IconButton(onClick = ::showNextSnapCandidate, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Default.SwapHoriz, contentDescription = "Andere Lage")
                                    }
                                }
                                IconButton(onClick = ::reaimSnap, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Neu zielen")
                                }
                            }
                            if (phase == ArAlignmentPhase.SNAP_TRACKING && quality?.level != SnapLevel.GOOD) {
                                Text(
                                    when {
                                        quality == null -> "Die Suche dauert etwa eine Sekunde. Telefon ruhig halten."
                                        quality.level == SnapLevel.NO_HOLD -> "Keine passende Lage im Umkreis. Das ganze Teil " +
                                            "ins Bild nehmen, oder neu zielen und das Modell besser drehen."
                                        quality.rank > 1 || quality.similar > 0 -> "Zweideutig: ⇄ zeigt die andere Lage. " +
                                            "Seitlich gehen, damit der Magnet sie unterscheiden kann."
                                        else -> "Ein, zwei Schritte seitlich gehen: jede neue Blickrichtung prüft alle Lagen."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        ArAlignmentPhase.ANCHORED -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = ::takeArPhoto,
                                enabled = tracking && finalAnchorState == ArTracking.TRACKING && activity != null && viewportRect != null) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("AR-Foto")
                            }
                            Text(if (!tracking || finalAnchorState != ArTracking.TRACKING) "Tracking unterbrochen"
                                else if (alignmentMethod == AlignmentMethod.SNAP) "AR-Anker aktiv · Snap (experimentell)"
                                else if (solution?.quality != CorrespondenceQuality.ACCEPTABLE) "AR-Anker aktiv · vorläufig"
                                else "AR-Anker aktiv",
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                color = if (solution?.quality == CorrespondenceQuality.ACCEPTABLE)
                                    MaterialTheme.colorScheme.onSurface else Color(0xFFFFB703),
                                style = MaterialTheme.typography.labelMedium, maxLines = 2)
                            IconButton(onClick = { positionLabelVisible = !positionLabelVisible }) {
                                Icon(Icons.AutoMirrored.Filled.Label, contentDescription = "Positionsnummer ein-/ausblenden",
                                    tint = if (positionLabelVisible) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (positionLabelVisible && phase == ArAlignmentPhase.ANCHORED) {
                Surface(modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 72.dp),
                    color = Color(0xCD071019), shape = MaterialTheme.shapes.medium) {
                    Text(positionLabel, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Surface(modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shape = MaterialTheme.shapes.medium) {
                Row {
                    IconButton(onClick = onExitAr, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück zum 3D-Modell")
                    }
                    IconButton(onClick = { onControlsExpandedChange(!controlsExpanded) }, modifier = Modifier.size(44.dp)) {
                        Icon(if (controlsExpanded) Icons.Default.ExpandLess else Icons.Default.Tune,
                            contentDescription = "AR-Bedienfeld ein-/ausblenden")
                    }
                    TextButton(onClick = onOpenQuality, modifier = Modifier.height(44.dp)) {
                        Text("QS", fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { onFullscreenChange(!fullscreen) }, modifier = Modifier.size(44.dp)) {
                        Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = "Vollbild ein-/ausschalten")
                    }
                }
            }
        }
        val preciseCursor = cursor?.takeIf {
            it.precise && phase == ArAlignmentPhase.MARKING && !photoCaptureRunning && appearanceAttached
        }
        val magnifierViewport = viewportRect
        if (preciseCursor != null && magnifierViewport != null) {
            val ringPx = cameraMarkRingRadiusPx(density.density)
            val stationMarks = currentMarkHandles.value()
            ArPrecisionMagnifier(
                hostView = hostView,
                viewport = magnifierViewport,
                position = preciseCursor.position,
                zoom = magnifierZoom,
                title = (draggedOrigin?.let { "P$it → " } ?: "") + "P${preciseCursor.label} · ${magnifierZoom.label}",
                hint = if (draggedOrigin != null) "Loslassen: P${preciseCursor.label} mit Linie" else "Loslassen zum Setzen",
                frozenImage = activeFrozen?.image,
                capturedViewport = activeFrozen?.geometry?.let { IntSize(it.width, it.height) },
                frozenFit = frozenFit,
                overlay = { lens ->
                    // The copied image holds no marks: draw nearby ones and the line to the finger again.
                    stationMarks.forEach { mark ->
                        val point = lens.map(mark.position)
                        if (lens.contains(point)) {
                            drawCircle(Color(0xE0071019), ringPx, point, style = Stroke(3.dp.toPx()))
                            drawCircle(Color(0xFF5AD67D), ringPx, point, style = Stroke(1.5.dp.toPx()))
                        }
                    }
                    val centre = lens.map(preciseCursor.position)
                    draftSources.forEach { source ->
                        val start = lens.map(source.position)
                        drawAlignmentLine(
                            AlignmentLine(start.x, start.y, centre.x, centre.y, AlignmentLineStyle.DRAFT),
                            startGapPx = ringPx + 2.dp.toPx(),
                            endGapPx = 16.dp.toPx(),
                        )
                    }
                },
            )
        }
        if (referenceExpanded && phase == ArAlignmentPhase.MARKING && !photoCaptureRunning && modelReference != null) {
            ModelReferenceViewer(
                reference = modelReference,
                marking = marking,
                onClose = { referenceExpanded = false },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** Filament resources of the marked edges; they outlive the node that draws them. */
private class FeatureEdgeResources(
    private val engine: Engine,
    private val materialLoader: MaterialLoader,
    mesh: FeatureEdgeMesh.Mesh,
) {
    val vertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .bufferCount(1)
        .vertexCount(mesh.vertexCount)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
        .build(engine)
        .apply { setBufferAt(engine, 0, FloatBuffer.wrap(mesh.positions)) }
    val indexBuffer: IndexBuffer = IndexBuffer.Builder()
        .indexCount(mesh.indices.size)
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine)
        .apply { setBuffer(engine, IntBuffer.wrap(mesh.indices)) }
    // Select a blending material even when the slider starts at 100%. An opaque material
    // would ignore subsequent alpha changes. Keep this instance while adjusting the slider.
    val material: MaterialInstance = materialLoader.createUnlitColorInstance(Color(0x00F2FBFF)).apply {
        // Ribbons have no inside: both faces must draw from every direction.
        setCullingMode(Material.CullingMode.NONE)
        // Transparent (including fully invisible) edges must not hide the part or other edges.
        setDepthWrite(false)
    }

    fun destroy() {
        materialLoader.destroyMaterialInstance(material)
        runCatching { engine.destroyVertexBuffer(vertexBuffer) }
        runCatching { engine.destroyIndexBuffer(indexBuffer) }
    }
}

@Composable
private fun SceneScope.FeatureEdgesNode(
    engine: Engine,
    materialLoader: MaterialLoader,
    mesh: FeatureEdgeMesh.Mesh,
    opacity: Float,
    depthTestEnabled: Boolean,
    boundsMin: FloatArray,
    boundsMax: FloatArray,
) {
    // Registered before the node, so Compose forgets it after the node: the renderable is gone
    // before its buffers and material are destroyed.
    val resources = remember(engine, mesh) { FeatureEdgeResources(engine, materialLoader, mesh) }
    DisposableEffect(resources) {
        onDispose { resources.destroy() }
    }
    SideEffect {
        resources.material.setColor(Color(0xFFF2FBFF).copy(alpha = opacity.coerceIn(0f, 1f)))
        resources.material.setDepthCulling(depthTestEnabled)
    }
    MeshNode(
        primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
        vertexBuffer = resources.vertexBuffer,
        indexBuffer = resources.indexBuffer,
        boundingBox = FilamentBox(
            (boundsMin[0] + boundsMax[0]) / 2f,
            (boundsMin[1] + boundsMax[1]) / 2f,
            (boundsMin[2] + boundsMax[2]) / 2f,
            (boundsMax[0] - boundsMin[0]) / 2f + 0.01f,
            (boundsMax[1] - boundsMin[1]) / 2f + 0.01f,
            (boundsMax[2] - boundsMin[2]) / 2f + 0.01f,
        ),
        materialInstance = resources.material,
        apply = {
            setPriority(7)
            isShadowCaster = false
            isShadowReceiver = false
        },
    )
}

/** Crosshair of a held mark, and the lines from [draftSources] that will join the new point. */
@Composable
private fun MarkCursorOverlay(cursor: MarkCursor?, draftSources: List<Offset>) {
    if (cursor == null || (!cursor.precise && draftSources.isEmpty())) return
    val density = LocalDensity.current
    val armPx = with(density) { 26.dp.toPx() }
    val gapPx = with(density) { 5.dp.toPx() }
    val ringPx = with(density) { 15.dp.toPx() }
    val markGapPx = cameraMarkRingRadiusPx(density.density) + with(density) { 2.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        val centre = cursor.position
        draftSources.forEach { source ->
            // Stops at the crosshair ring so the edge under the centre stays visible.
            drawAlignmentLine(
                AlignmentLine(source.x, source.y, centre.x, centre.y, AlignmentLineStyle.DRAFT),
                startGapPx = markGapPx,
                endGapPx = if (cursor.precise) ringPx else 0f,
            )
        }
        if (!cursor.precise) return@Canvas
        listOf(Color(0xE0071019) to 5f, Color(0xFFFFD166) to 2.2f).forEach { (color, width) ->
            drawCircle(color = color, radius = ringPx, center = centre, style = Stroke(width = width))
            listOf(Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f)).forEach { arm ->
                drawLine(
                    color = color,
                    start = centre + arm * gapPx,
                    end = centre + arm * armPx,
                    strokeWidth = width,
                )
            }
        }
        drawCircle(color = Color(0xFFFFD166), radius = 1.6f, center = centre)
    }
}
