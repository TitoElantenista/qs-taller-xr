package com.qs.tallerxr.ar

import com.google.ar.core.Pose
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Experimental «Snap», second version (0.27.0): finds where the CAD stands on the floor.
 *
 * The first version refined a free pose from the aim, taking for every CAD edge the nearest image
 * edge.  On a real TV bench it failed the way the operator described it: the distance (the
 * apparent size) never matched, CAD edges jumped from one image edge to the next, and a pose one
 * drawer bay to the side was shown in green.  This version changes three things.
 *
 * 1. The part stands upright (model +Y up) on a surface ARCore found, floor or table.  That fixes
 *    height, tilt and, above all, the distance; only the place on the surface and the turn about
 *    the vertical remain.
 * 2. Those three are searched over a whole window around the aim instead of refined locally: every
 *    pose within ±30 % of the footprint and ±24° is scored on a coarse image, the best different
 *    ones again on a finer one.  The score is the mean support of the visible CAD edges: 1 with an
 *    image edge of the same direction under the edge, falling to 0 a few pixels away.  No image edge
 *    is ever assigned to a CAD edge, so there is nothing to jump between.
 * 3. Several candidate poses are kept and scored again in every new view.  The part stays put in the
 *    world and background edges do not, so wrong candidates lose support as the operator moves.
 *    «Good» needs two views and a clear lead over the next different candidate.  A part with equal
 *    bays can fit one bay aside almost as well; that is reported as ambiguous, and the operator can
 *    switch to the other candidate.
 *
 * On a real photo of such a bench (ARCore-like resolution and noise) the search found the
 * pose within 1 cm from aims 35 cm and 20° off; the pose one bay aside scored clearly lower.  It is
 * still an aid, not a measurement, and it never anchors by itself.
 */

/** A pose standing on the support plane: the bottom centre of the CAD box at (x, floor + lift, z), turned about the vertical. */
internal data class FloorPose(val x: Float, val z: Float, val yawRadians: Float, val lift: Float = 0f)

/** Points along the CAD feature edges with the edge direction at each, about [stepM] apart. */
internal class EdgeSamples(featureEdges: FloatArray, stepM: Float) {
    val points: FloatArray
    val directions: FloatArray
    val count: Int get() = points.size / 3

    init {
        require(stepM > 0f)
        val points = ArrayList<Float>()
        val directions = ArrayList<Float>()
        var index = 0
        while (index + 5 < featureEdges.size) {
            val dx = featureEdges[index + 3] - featureEdges[index]
            val dy = featureEdges[index + 4] - featureEdges[index + 1]
            val dz = featureEdges[index + 5] - featureEdges[index + 2]
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (length >= 1e-4f) {
                // Samples sit inside the edge, never on a corner where two edges disagree.
                val samples = max(1, (length / stepM).toInt())
                for (sample in 0 until samples) {
                    val t = (sample + 0.5f) / samples
                    points += featureEdges[index] + dx * t
                    points += featureEdges[index + 1] + dy * t
                    points += featureEdges[index + 2] + dz * t
                    directions += dx / length
                    directions += dy / length
                    directions += dz / length
                }
            }
            index += 6
        }
        this.points = points.toFloatArray()
        this.directions = directions.toFloatArray()
    }
}

/** The CAD as the snap sees it: feature edges sampled twice, and the box it stands in. */
internal class SnapModel(featureEdges: FloatArray, val boundsMin: FloatArray, val boundsMax: FloatArray) {
    val centre = FloatArray(3) { (boundsMin[it] + boundsMax[it]) / 2f }

    /** Bottom centre of the box: the point that stands on the floor. */
    val foot = floatArrayOf(centre[0], boundsMin[1], centre[2])
    private val halfX = (boundsMax[0] - boundsMin[0]) / 2f
    private val halfZ = (boundsMax[2] - boundsMin[2]) / 2f

    /** Diagonal of the footprint. */
    val footprintM: Float = 2f * hypot(halfX, halfZ)
    val heightM: Float = boundsMax[1] - boundsMin[1]
    val footprintAreaM2: Float = 4f * halfX * halfZ
    val coarse: EdgeSamples
    val fine: EdgeSamples

    init {
        var total = 0f
        var index = 0
        while (index + 5 < featureEdges.size) {
            val dx = featureEdges[index + 3] - featureEdges[index]
            val dy = featureEdges[index + 4] - featureEdges[index + 1]
            val dz = featureEdges[index + 5] - featureEdges[index + 2]
            total += sqrt(dx * dx + dy * dy + dz * dz)
            index += 6
        }
        val size = max(footprintM, heightM).coerceAtLeast(0.05f)
        coarse = EdgeSamples(featureEdges, max(total / COARSE_SAMPLES, size / 200f).coerceAtLeast(0.002f))
        fine = EdgeSamples(featureEdges, max(total / FINE_SAMPLES, size / 500f).coerceAtLeast(0.001f))
    }

    /** The CAD standing at [pose] on a surface at height [floorY] of the reference frame. */
    fun referenceFromModel(pose: FloorPose, floorY: Float): Pose {
        val c = cos(pose.yawRadians)
        val s = sin(pose.yawRadians)
        val footX = c * foot[0] + s * foot[2]
        val footZ = -s * foot[0] + c * foot[2]
        return Pose(
            floatArrayOf(pose.x - footX, floorY + pose.lift - foot[1], pose.z - footZ),
            floatArrayOf(0f, sin(pose.yawRadians / 2f), 0f, cos(pose.yawRadians / 2f)),
        )
    }

    /** Largest horizontal shift of a bottom corner of the box between two poses. */
    fun separationM(first: FloorPose, second: FloorPose): Float {
        var widest = 0f
        for (corner in 0..3) {
            val x = if (corner and 1 == 0) -halfX else halfX
            val z = if (corner and 2 == 0) -halfZ else halfZ
            val c1 = cos(first.yawRadians); val s1 = sin(first.yawRadians)
            val c2 = cos(second.yawRadians); val s2 = sin(second.yawRadians)
            val dx = (first.x + c1 * x + s1 * z) - (second.x + c2 * x + s2 * z)
            val dz = (first.z - s1 * x + c1 * z) - (second.z - s2 * x + c2 * z)
            widest = max(widest, hypot(dx, dz))
        }
        return widest
    }

    private companion object {
        const val COARSE_SAMPLES = 1_200f
        const val FINE_SAMPLES = 3_000f
    }
}

internal enum class SnapLevel { NO_HOLD, WEAK, GOOD }

/** One pose the snap considers possible, with its edge support per kept view. */
internal data class SnapCandidate(
    val id: Int,
    val pose: FloorPose,
    /** Mean support of the visible CAD edges over the kept views, 0 to 1. */
    val support: Float,
    val viewSupport: List<Float>,
)

/** What the snap knows after a frame: its candidates, best first, and the views behind them. */
internal data class SnapState(
    val candidates: List<SnapCandidate>,
    val views: Int,
    /** Widest angle between the kept camera positions, seen from the best candidate. */
    val spreadDegrees: Float,
)

internal data class SnapQuality(
    val support: Float,
    /** Support in the kept view that fits worst. */
    val worstViewSupport: Float,
    /** Lead over the best other candidate; negative for a candidate that is not the best. */
    val margin: Float,
    /** Other candidates about as good as this one. */
    val similar: Int,
    /** Candidates worth offering, close enough to the best one; this one included. */
    val plausible: Int,
    /** 1 for the best candidate. */
    val rank: Int,
    val views: Int,
    val spreadDegrees: Float,
    val level: SnapLevel,
)

/** Hold criteria.  Calibrated on synthetic scenes and on the operator's photo (see the tests). */
internal object SnapCriteria {
    /** Below this mean edge support nothing counts as found. */
    const val HOLD_SUPPORT = 0.45f
    const val GOOD_SUPPORT = 0.55f
    /**
     * Lead over the next different candidate needed for «eindeutig».  On the operator's photo the
     * bench led the pose one drawer bay aside by 0.07 to 0.16 in a single view.
     */
    const val GOOD_MARGIN = 0.08f
    /** Other candidates are offered only this close to the best; further down they only distract. */
    const val ALTERNATIVE_RANGE = 0.12f
    const val GOOD_VIEWS = 2
    const val GOOD_SPREAD_DEGREES = 10f
}

/** Quality of the candidate [selectedId], or of the best one; null before the first search. */
internal fun snapQuality(state: SnapState, selectedId: Int?): SnapQuality? {
    val candidates = state.candidates
    if (candidates.isEmpty()) return null
    val selected = candidates.firstOrNull { it.id == selectedId } ?: candidates.first()
    val others = candidates.filter { it.id != selected.id }
    val margin = selected.support - (others.maxOfOrNull { it.support } ?: 0f)
    val similar = others.count { it.support >= max(SnapCriteria.HOLD_SUPPORT, selected.support - SnapCriteria.GOOD_MARGIN) }
    val worst = selected.viewSupport.minOrNull() ?: selected.support
    val rank = candidates.indexOfFirst { it.id == selected.id } + 1
    val level = when {
        selected.support < SnapCriteria.HOLD_SUPPORT -> SnapLevel.NO_HOLD
        rank == 1 && selected.support >= SnapCriteria.GOOD_SUPPORT && worst >= SnapCriteria.HOLD_SUPPORT &&
            margin >= SnapCriteria.GOOD_MARGIN && state.views >= SnapCriteria.GOOD_VIEWS &&
            state.spreadDegrees >= SnapCriteria.GOOD_SPREAD_DEGREES -> SnapLevel.GOOD
        else -> SnapLevel.WEAK
    }
    return SnapQuality(selected.support, worst, margin, similar, plausible(state).size, rank, state.views,
        state.spreadDegrees, level)
}

/** Candidates with a hold and close enough to the best one to be worth a look, best first. */
private fun plausible(state: SnapState): List<SnapCandidate> {
    val best = state.candidates.firstOrNull() ?: return emptyList()
    val floor = max(SnapCriteria.HOLD_SUPPORT, best.support - SnapCriteria.ALTERNATIVE_RANGE)
    return state.candidates.filter { it.support >= floor }
}

/** The next plausible candidate after [selectedId] in rank order, wrapping round; null when there is no other. */
internal fun nextSnapCandidate(state: SnapState, selectedId: Int?): SnapCandidate? {
    val plausible = plausible(state)
    if (plausible.size < 2) return null
    val index = plausible.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    return plausible[(index + 1) % plausible.size]
}

/**
 * One snap from one aim.  Not thread safe: the viewport hands it one frame at a time on a
 * background thread and starts a new session for a new aim.
 */
internal class FloorSnapSession(
    private val model: SnapModel,
    /** Occlusion by the part itself; without it hidden edges would look for image edges too. */
    private val visibility: LandmarkVisibility?,
    /** Height of the support plane in the reference frame. */
    val floorY: Float,
    /** Where the operator had laid the model when the magnet was switched on. */
    val aim: FloorPose,
) {
    /** Half width of the search window on the floor. */
    val radiusM: Float = (model.footprintM * 0.3f).coerceIn(0.2f, 0.6f)
    private val coarseStepM = radiusM / GRID_HALF
    private val fineStepM = coarseStepM / 3f
    /** Two poses closer than this are the same candidate. */
    private val sameM = max(0.04f, 0.04f * model.footprintM)
    private val coarseTable = supportTable(COARSE_TAU_PX)
    private val fineTable = supportTable(FINE_TAU_PX)

    private val keyframes = ArrayList<Keyframe>()
    private val hypotheses = ArrayList<Hypothesis>()
    private var nextId = 1
    private var lastViewMillis = Long.MIN_VALUE

    @Volatile
    var state: SnapState = SnapState(emptyList(), 0, 0f)
        private set

    fun referenceFromModel(pose: FloorPose): Pose = model.referenceFromModel(pose, floorY)

    /**
     * Whether a frame from this camera would add anything; cheap, asked before the image is copied.
     * With a hold only new viewpoints count; without one a fresh frame is tried every second.
     */
    fun wantsView(geometry: CapturedAlignmentView, nowMillis: Long): Boolean {
        if (keyframes.isEmpty()) return true
        val best = hypotheses.firstOrNull()
        if (best == null || best.support < SnapCriteria.HOLD_SUPPORT) return nowMillis - lastViewMillis >= RETRY_MILLIS
        val centre = referenceFromModel(best.pose).transformPoint(model.centre)
        return keyframes.all { angleDegrees(centre, it.camera, geometry.cameraOrigin) >= KEYFRAME_DEGREES }
    }

    /** Milliseconds of the last [process]: image edges, wide search, fit of the candidates. */
    @Volatile
    var lastTimings: LongArray = LongArray(3)
        private set

    fun process(view: SnapView, nowMillis: Long): SnapState {
        val started = System.nanoTime()
        val features = SnapViewFeatures.of(view)
        val featuresDone = System.nanoTime()
        lastViewMillis = nowMillis
        if (hypotheses.firstOrNull()?.let { it.support < SnapCriteria.HOLD_SUPPORT } == true) {
            // Nothing held so far: this frame replaces the earlier ones instead of joining them.
            keyframes.clear()
            hypotheses.clear()
        }
        if (keyframes.size >= MAXIMUM_KEYFRAMES) keyframes.remove(mostRedundantKeyframe())
        val frame = Keyframe(features)
        keyframes += frame
        for (pose in coarseSearch(frame)) {
            if (hypotheses.none { model.separationM(it.pose, pose) < sameM }) hypotheses += Hypothesis(nextId++, pose)
        }
        val searchDone = System.nanoTime()
        forEachParallel(hypotheses) { climb(it) }
        deduplicate()
        forEachParallel(hypotheses.take(FINAL_CANDIDATES)) { refine(it, finalStage = true) }
        deduplicate()
        while (hypotheses.size > MAXIMUM_CANDIDATES) hypotheses.removeAt(hypotheses.lastIndex)
        state = SnapState(
            hypotheses.map { SnapCandidate(it.id, it.pose, it.support, it.viewSupport.toList()) },
            keyframes.size,
            spreadDegrees(),
        )
        lastTimings = longArrayOf((featuresDone - started) / 1_000_000, (searchDone - featuresDone) / 1_000_000,
            (System.nanoTime() - searchDone) / 1_000_000)
        return state
    }

    private class Keyframe(val features: SnapViewFeatures) {
        val camera: FloatArray get() = features.geometry.cameraOrigin

        /** Fine samples seen by this camera, per candidate, at the pose they were worked out for. */
        val visible = HashMap<Int, Visible>()
    }

    private class Hypothesis(val id: Int, var pose: FloorPose) {
        var support = 0f
        var viewSupport = FloatArray(0)
    }

    /** Samples a camera sees at [pose], each with the direction bin of its projected edge. */
    private class Visible(val pose: FloorPose, val indices: IntArray, val bins: ByteArray)

    /** Visible samples turned by one yaw and projected up to the translation, ready to be scored. */
    private class Prepared(
        val count: Int,
        val ax: FloatArray,
        val ay: FloatArray,
        val aw: FloatArray,
        val bins: ByteArray,
    )

    /** Every pose of the window, on the coarse image; the best clearly different ones. */
    private fun coarseSearch(frame: Keyframe): List<FloorPose> {
        val geometry = frame.features.geometry
        val side = 2 * GRID_HALF + 1
        val yaws = FloatArray(2 * YAW_STEPS + 1) { aim.yawRadians + (it - YAW_STEPS) * YAW_STEP_RADIANS }
        val scores = Array(yaws.size) { FloatArray(side * side) }
        // Occlusion changes slowly with the turn: worked out every other yaw and shared.
        val occlusion = arrayOfNulls<IntArray>(yaws.size)
        IntStream.range(0, (yaws.size + 1) / 2).parallel().forEach { half ->
            occlusion[half * 2] = unoccluded(geometry, model.coarse, FloorPose(aim.x, aim.z, yaws[half * 2]))
        }
        IntStream.range(0, yaws.size).parallel().forEach { k ->
            val pose = FloorPose(aim.x, aim.z, yaws[k])
            val visible = directions(geometry, model.coarse, occlusion[k] ?: occlusion[k - 1]!!, pose)
            val prepared = prepare(geometry, model.coarse, visible, yaws[k])
            for (row in 0 until side) for (column in 0 until side) {
                scores[k][row * side + column] = score(prepared, visible.indices.size, frame.features.coarse, geometry,
                    aim.x + (column - GRID_HALF) * coarseStepM, aim.z + (row - GRID_HALF) * coarseStepM, 0f, coarseTable)
            }
        }
        val order = (0 until yaws.size * side * side).sortedByDescending { scores[it / (side * side)][it % (side * side)] }
        val kept = ArrayList<FloorPose>()
        for (index in order) {
            val cell = index % (side * side)
            val pose = FloorPose(
                aim.x + (cell % side - GRID_HALF) * coarseStepM,
                aim.z + (cell / side - GRID_HALF) * coarseStepM,
                yaws[index / (side * side)],
            )
            if (kept.none { model.separationM(it, pose) < sameM }) kept += pose
            if (kept.size == COARSE_CANDIDATES) break
        }
        return kept
    }

    /**
     * Local search repeated while the best pose lies on the border of the searched patch, so a
     * candidate ends on a true local best: two that share one end up as one.
     */
    private fun climb(hypothesis: Hypothesis) {
        repeat(MAXIMUM_CLIMBS) {
            val before = hypothesis.pose
            refine(hypothesis, finalStage = false)
            val after = hypothesis.pose
            val turned = abs(after.yawRadians - before.yawRadians) >= Math.toRadians(REFINE_YAW_OFFSETS.last() - 0.01).toFloat()
            val shifted = abs(after.x - before.x) >= fineStepM * (REFINE_REACH - 0.01f) ||
                abs(after.z - before.z) >= fineStepM * (REFINE_REACH - 0.01f)
            val lifted = abs(after.lift - before.lift) >= REFINE_LIFT_STEP_M * 0.99f && abs(after.lift) < MAXIMUM_LIFT_M - 1e-4f
            if (!turned && !shifted && !lifted) return
        }
    }

    /**
     * Local search around a candidate on the fine images of all kept views together: place, turn
     * and height above the surface, then (for the best few) finer steps.  The height absorbs a
     * support plane a centimetre or two off and small errors of the AR scale; from one view it
     * trades against the distance, a second view separates them.
     */
    private fun refine(hypothesis: Hypothesis, finalStage: Boolean) {
        val start = hypothesis.pose
        val visible = keyframes.map { frame -> visibleFor(frame, hypothesis.id, start) }
        val yawOffsets = if (finalStage) FINAL_YAW_OFFSETS else REFINE_YAW_OFFSETS
        val step = if (finalStage) fineStepM / 2f else fineStepM
        val reach = if (finalStage) 1 else REFINE_REACH
        val lifts = (if (finalStage) FINAL_LIFT_OFFSETS else REFINE_LIFT_OFFSETS)
            .map { (start.lift + it).coerceIn(-MAXIMUM_LIFT_M, MAXIMUM_LIFT_M) }.distinct()
        var bestScore = -1f
        var best = start
        var bestViews = FloatArray(keyframes.size)
        val perView = FloatArray(keyframes.size)
        for (degrees in yawOffsets) {
            val yaw = start.yawRadians + Math.toRadians(degrees.toDouble()).toFloat()
            val prepared = keyframes.indices.map { prepare(keyframes[it].features.geometry, model.fine, visible[it], yaw) }
            for (lift in lifts) for (row in -reach..reach) for (column in -reach..reach) {
                val x = start.x + column * step
                val z = start.z + row * step
                var sum = 0f
                for (k in keyframes.indices) {
                    val frame = keyframes[k]
                    perView[k] = score(prepared[k], visible[k].indices.size, frame.features.fine, frame.features.geometry,
                        x, z, lift, fineTable)
                    sum += perView[k]
                }
                val mean = sum / keyframes.size
                if (mean > bestScore) {
                    bestScore = mean
                    best = FloorPose(x, z, yaw, lift)
                    bestViews = perView.copyOf()
                }
            }
        }
        hypothesis.pose = best
        hypothesis.support = bestScore.coerceAtLeast(0f)
        hypothesis.viewSupport = bestViews
    }

    /** Best first, and a candidate that ended up on a better one is dropped. */
    private fun deduplicate() {
        hypotheses.sortByDescending { it.support }
        val kept = ArrayList<Hypothesis>()
        for (candidate in hypotheses) if (kept.none { model.separationM(it.pose, candidate.pose) < sameM }) kept += candidate
        hypotheses.clear()
        hypotheses += kept
        val ids = kept.map { it.id }.toSet()
        keyframes.forEach { frame -> frame.visible.keys.retainAll(ids) }
    }

    /** Cached per candidate and view while the candidate stays within a few centimetres and degrees. */
    private fun visibleFor(frame: Keyframe, id: Int, pose: FloorPose): Visible {
        val cached = synchronized(frame.visible) { frame.visible[id] }
        if (cached != null && model.separationM(cached.pose, pose) < 2f * fineStepM + 0.01f) return cached
        val geometry = frame.features.geometry
        val fresh = directions(geometry, model.fine, unoccluded(geometry, model.fine, pose), pose)
        synchronized(frame.visible) { frame.visible[id] = fresh }
        return fresh
    }

    /** Samples in front of the camera and not hidden by the part itself at [pose]. */
    private fun unoccluded(geometry: CapturedAlignmentView, samples: EdgeSamples, pose: FloorPose): IntArray {
        val referenceFromModel = referenceFromModel(pose)
        val camera = referenceFromModel.inverse().transformPoint(geometry.cameraOrigin)
        val modelToClip = modelToClip(geometry.viewProjection, referenceFromModel)
        val indices = IntArray(samples.count)
        var count = 0
        val point = FloatArray(3)
        for (sample in 0 until samples.count) {
            val o = sample * 3
            val x = samples.points[o]
            val y = samples.points[o + 1]
            val z = samples.points[o + 2]
            if (modelToClip[3] * x + modelToClip[7] * y + modelToClip[11] * z + modelToClip[15] <= 1e-4f) continue
            if (visibility != null) {
                // Moved half a millimetre towards the camera so the edge's own faces do not hide it.
                val tx = camera[0] - x
                val ty = camera[1] - y
                val tz = camera[2] - z
                val length = sqrt(tx * tx + ty * ty + tz * tz)
                if (length < 0.01f) continue
                point[0] = x + tx * 0.0005f / length
                point[1] = y + ty * 0.0005f / length
                point[2] = z + tz * 0.0005f / length
                if (!visibility.isVisible(camera, point)) continue
            }
            indices[count++] = sample
        }
        return indices.copyOf(count)
    }

    /** The given samples with the direction bin of their projected edge at [pose]. */
    private fun directions(geometry: CapturedAlignmentView, samples: EdgeSamples, candidates: IntArray, pose: FloorPose): Visible {
        val modelToClip = modelToClip(geometry.viewProjection, referenceFromModel(pose))
        val indices = IntArray(candidates.size)
        val bins = ByteArray(candidates.size)
        var count = 0
        for (sample in candidates) {
            val o = sample * 3
            val first = pixel(modelToClip, samples.points[o], samples.points[o + 1], samples.points[o + 2],
                geometry.width, geometry.height) ?: continue
            val second = pixel(modelToClip,
                samples.points[o] + samples.directions[o] * DIRECTION_STEP_M,
                samples.points[o + 1] + samples.directions[o + 1] * DIRECTION_STEP_M,
                samples.points[o + 2] + samples.directions[o + 2] * DIRECTION_STEP_M,
                geometry.width, geometry.height) ?: continue
            val dx = second[0] - first[0]
            val dy = second[1] - first[1]
            // An edge pointing at the camera has no direction on screen.
            if (dx * dx + dy * dy < 0.25f) continue
            indices[count] = sample
            bins[count] = SnapEdgeLevel.bin(atan2(dy, dx) + (PI / 2).toFloat()).toByte()
            count++
        }
        return Visible(pose, indices.copyOf(count), bins.copyOf(count))
    }

    /** Clip coordinates (x, y, w) of the samples turned by [yaw] about the foot, without the translation. */
    private fun prepare(geometry: CapturedAlignmentView, samples: EdgeSamples, visible: Visible, yaw: Float): Prepared {
        val m = geometry.viewProjection
        val c = cos(yaw)
        val s = sin(yaw)
        val count = visible.indices.size
        val ax = FloatArray(count)
        val ay = FloatArray(count)
        val aw = FloatArray(count)
        for (j in 0 until count) {
            val o = visible.indices[j] * 3
            val px = samples.points[o] - model.foot[0]
            val py = samples.points[o + 1] - model.foot[1]
            val pz = samples.points[o + 2] - model.foot[2]
            val qx = c * px + s * pz
            val qz = -s * px + c * pz
            ax[j] = m[0] * qx + m[4] * py + m[8] * qz
            ay[j] = m[1] * qx + m[5] * py + m[9] * qz
            aw[j] = m[3] * qx + m[7] * py + m[11] * qz
        }
        return Prepared(count, ax, ay, aw, visible.bins)
    }

    /**
     * Mean support of the samples with the foot at (x, floor + lift, z).  Samples outside the frame
     * count as unknown as long as at least three quarters of the part is in it, beyond that as zero:
     * a pose must not win by leaving the picture.
     */
    private fun score(
        prepared: Prepared,
        visibleCount: Int,
        level: SnapEdgeLevel,
        geometry: CapturedAlignmentView,
        x: Float,
        z: Float,
        lift: Float,
        table: FloatArray,
    ): Float {
        if (visibleCount == 0) return 0f
        val m = geometry.viewProjection
        val y = floorY + lift
        val bx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val by = m[1] * x + m[5] * y + m[9] * z + m[13]
        val bw = m[3] * x + m[7] * y + m[11] * z + m[15]
        val sx = 0.5f * geometry.width / level.scale
        val sy = 0.5f * geometry.height / level.scale
        val width = level.width.toFloat()
        val height = level.height.toFloat()
        var sum = 0f
        var inside = 0
        for (j in 0 until prepared.count) {
            val w = prepared.aw[j] + bw
            if (w <= 1e-4f) continue
            val inverse = 1f / w
            val u = ((prepared.ax[j] + bx) * inverse + 1f) * sx
            val v = (1f - (prepared.ay[j] + by) * inverse) * sy
            if (!(u >= 0f && v >= 0f && u < width && v < height)) continue
            inside++
            sum += table[level.distance(prepared.bins[j].toInt(), u.toInt(), v.toInt())]
        }
        return sum / max(max(inside.toFloat(), 0.75f * visibleCount), 1f)
    }

    private fun mostRedundantKeyframe(): Keyframe {
        val best = hypotheses.firstOrNull()?.pose ?: aim
        val centre = referenceFromModel(best).transformPoint(model.centre)
        return keyframes.minBy { candidate ->
            keyframes.filter { it !== candidate }.minOfOrNull { angleDegrees(centre, it.camera, candidate.camera) }
                ?: Float.MAX_VALUE
        }
    }

    private fun spreadDegrees(): Float {
        val best = hypotheses.firstOrNull()?.pose ?: aim
        val centre = referenceFromModel(best).transformPoint(model.centre)
        var widest = 0f
        for (first in keyframes.indices) for (second in first + 1 until keyframes.size) {
            widest = max(widest, angleDegrees(centre, keyframes[first].camera, keyframes[second].camera))
        }
        return widest
    }

    private companion object {
        /** Window of 37 x 37 places and 25 turns: ±30 % of the footprint and ±24°. */
        const val GRID_HALF = 18
        const val YAW_STEPS = 12
        val YAW_STEP_RADIANS = Math.toRadians(2.0).toFloat()
        const val COARSE_CANDIDATES = 8
        const val MAXIMUM_CANDIDATES = 8
        const val FINAL_CANDIDATES = 3
        val REFINE_YAW_OFFSETS = floatArrayOf(-2f, -1f, 0f, 1f, 2f)
        const val REFINE_REACH = 3
        const val MAXIMUM_CLIMBS = 4
        val FINAL_YAW_OFFSETS = floatArrayOf(-0.5f, 0f, 0.5f)
        const val REFINE_LIFT_STEP_M = 0.0125f
        val REFINE_LIFT_OFFSETS = floatArrayOf(-REFINE_LIFT_STEP_M, 0f, REFINE_LIFT_STEP_M)
        val FINAL_LIFT_OFFSETS = floatArrayOf(-0.01f, -0.005f, 0f, 0.005f, 0.01f)
        const val MAXIMUM_LIFT_M = 0.025f
        /** Support falls from 1 on the edge to 0 at this many pixels of the level. */
        const val COARSE_TAU_PX = 4f
        const val FINE_TAU_PX = 5f
        const val DIRECTION_STEP_M = 0.01f
        const val MAXIMUM_KEYFRAMES = 5
        const val KEYFRAME_DEGREES = 6f
        const val RETRY_MILLIS = 1_000L

        fun supportTable(tau: Float) = FloatArray(256) { distance -> max(0f, 1f - distance / tau) }
    }
}

/** Model-to-clip matrix (column major) of a view-projection and a model pose. */
private fun modelToClip(viewProjection: FloatArray, referenceFromModel: Pose): FloatArray =
    ScreenGeometry.multiply(viewProjection, FloatArray(16).also { referenceFromModel.toMatrix(it, 0) })

private fun pixel(matrix: FloatArray, x: Float, y: Float, z: Float, width: Int, height: Int): FloatArray? {
    val w = matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15]
    if (w <= 1e-4f) return null
    val cx = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]
    val cy = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]
    return floatArrayOf((cx / w + 1f) * 0.5f * width, (1f - cy / w) * 0.5f * height)
}

private fun <T> forEachParallel(items: List<T>, action: (T) -> Unit) {
    val snapshot = items.toList()
    IntStream.range(0, snapshot.size).parallel().forEach { action(snapshot[it]) }
}

internal fun angleDegrees(centre: FloatArray, first: FloatArray, second: FloatArray): Float {
    val ax = (first[0] - centre[0]).toDouble(); val ay = (first[1] - centre[1]).toDouble(); val az = (first[2] - centre[2]).toDouble()
    val bx = (second[0] - centre[0]).toDouble(); val by = (second[1] - centre[1]).toDouble(); val bz = (second[2] - centre[2]).toDouble()
    val na = sqrt(ax * ax + ay * ay + az * az)
    val nb = sqrt(bx * bx + by * by + bz * bz)
    if (na < 1e-9 || nb < 1e-9) return 0f
    return Math.toDegrees(acos(((ax * bx + ay * by + az * bz) / (na * nb)).coerceIn(-1.0, 1.0))).toFloat()
}

/**
 * Turn about the vertical that shows the model to a camera the way another camera showed it:
 * [worldFromModel] is the model as the 3D viewer's orientation puts it in front of the AR camera.
 * The heading of the model's +Z axis is kept, or of +X when +Z points almost straight up or down.
 */
internal fun uprightYaw(worldFromModel: Pose): Float {
    val z = worldFromModel.zAxis
    if (hypot(z[0], z[2]) >= 0.3f) return atan2(z[0], z[2])
    val x = worldFromModel.xAxis
    return atan2(-x[2], x[0])
}

/** Viewing ray (unit) from [origin] hitting the horizontal plane at [height]; the hit point, or null. */
internal fun horizontalHit(origin: FloatArray, direction: FloatArray, height: Float, maximumM: Float = 12f): FloatArray? {
    if (abs(direction[1]) < 1e-4f) return null
    val t = (height - origin[1]) / direction[1]
    if (!(t > 0.1f && t < maximumM)) return null
    return FloatArray(3) { origin[it] + direction[it] * t }
}

/** Where the aim ray stands the part: the surface height and the foot point on it. */
internal class SupportHit(val floorY: Float, val x: Float, val z: Float)

/**
 * The surface under the aim: the highest one below the camera whose outline contains the point
 * where the ray reaches the part's centre height above it; otherwise the lowest one, since the
 * floor goes on under the part even where the AR system has not seen it.
 */
internal fun supportHit(
    planes: List<SupportPlane>,
    origin: FloatArray,
    direction: FloatArray,
    centreHeightM: Float,
    footprintAreaM2: Float,
): SupportHit? {
    val below = planes.filter { it.heightM < origin[1] - 0.15f }.sortedByDescending { it.heightM }
    for (plane in below.dropLast(1)) {
        // A table must be larger than the part on it; the top of the part itself is not.
        if (area(plane.outline) < 1.2f * footprintAreaM2) continue
        val hit = horizontalHit(origin, direction, plane.heightM + centreHeightM) ?: continue
        if (contains(plane.outline, hit[0], hit[2])) return SupportHit(plane.heightM, hit[0], hit[2])
    }
    val floor = below.lastOrNull() ?: return null
    val hit = horizontalHit(origin, direction, floor.heightM + centreHeightM) ?: return null
    return SupportHit(floor.heightM, hit[0], hit[2])
}

/** Area enclosed by an outline of x, z pairs. */
private fun area(outline: FloatArray): Float {
    val vertices = outline.size / 2
    var twice = 0f
    for (current in 0 until vertices) {
        val next = (current + 1) % vertices
        twice += outline[current * 2] * outline[next * 2 + 1] - outline[next * 2] * outline[current * 2 + 1]
    }
    return abs(twice) / 2f
}

/** Even-odd test of a point in an outline of x, z pairs. */
private fun contains(outline: FloatArray, x: Float, z: Float): Boolean {
    val vertices = outline.size / 2
    if (vertices < 3) return false
    var inside = false
    var previous = vertices - 1
    for (current in 0 until vertices) {
        val xi = outline[current * 2]; val zi = outline[current * 2 + 1]
        val xj = outline[previous * 2]; val zj = outline[previous * 2 + 1]
        if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside
        previous = current
    }
    return inside
}
