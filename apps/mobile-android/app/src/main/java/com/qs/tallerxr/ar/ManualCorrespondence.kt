package com.qs.tallerxr.ar

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Operator driven correspondence alignment.
 *
 * The workshop flow marks two to six CAD landmarks in the 3D viewer and then marks the same
 * landmarks on the real part inside the AR camera view.  Every camera mark is kept as a ray: a
 * marked pixel is far more precise than ARCore Depth at a silhouette corner, which is exactly
 * where the operator aims.
 *
 * A ray fixes a landmark across the view but says nothing about its distance.  Marks taken from a
 * single standing position can leave the distance to the part and its tilt towards the
 * camera weakly observed: the overlay matches at the marks and looks like a wrongly scaled part
 * everywhere else.  The solver accepts any number of marks per landmark, taken from different
 * positions (stations), and minimises the angular distance to all of them at once.  Two stations
 * a few steps apart make the six degrees of freedom well conditioned without any Depth reading.
 * Depth only supports the provisional, single station fit, behind a redescending robust weight.
 *
 * All geometry lives in one fixed frame chosen by the caller.  The AR viewport uses a reference
 * anchor so ARCore map corrections between stations cannot skew the rays.
 *
 * A line between two landmarks is an edge the operator held against the part while marking its
 * second end.  It adds no landmark of its own: the drawn line runs through both marks.  What it
 * adds is weight across the edge.  Each end is asked once more to lie in the plane of the drawn
 * line, so a mark placed a little too far along an edge (a cut end, a weld, a rounded corner)
 * pulls the edge less out of line than a mark that misses it sideways.  With exact marks the fit
 * is unchanged.  The accuracy estimate is computed from the landmarks only and does not count the
 * same marks twice.
 *
 * This is not a CAD recogniser and not a measuring instrument.  It reproduces the operator's own
 * correspondence, reports the residual of every mark and the expected accuracy of the fit, and
 * never anchors a pose by itself.
 */
data class CorrespondencePick(
    val label: Int,
    val modelPoint: FloatArray,
) {
    init {
        require(label >= 1) { "Die Nummer des CAD-Punkts beginnt bei 1." }
        require(modelPoint.size == 3 && modelPoint.all(Float::isFinite)) {
            "Der CAD-Punkt ist nicht endlich."
        }
    }
}

/** One camera mark of one landmark, expressed as a ray taken from a single AR frame. */
data class CorrespondenceSighting(
    val label: Int,
    /** Marking round the mark belongs to, starting at 1.  One round is one standing position. */
    val station: Int,
    val rayOrigin: FloatArray,
    val rayDirection: FloatArray,
    val measuredDistanceM: Float?,
    val focalLengthPx: Float,
) {
    init {
        require(label >= 1)
        require(station >= 1)
        require(rayOrigin.size == 3 && rayOrigin.all(Float::isFinite))
        require(rayDirection.size == 3 && rayDirection.all(Float::isFinite))
        require(measuredDistanceM == null || (measuredDistanceM.isFinite() && measuredDistanceM > 0f))
        require(focalLengthPx.isFinite() && focalLengthPx > 0f)
    }
}

data class CorrespondenceResidual(
    val label: Int,
    val station: Int,
    /** Distance from the aligned CAD point to the ray the operator marked. */
    val lateralMm: Float,
    val lateralPx: Float,
)

/** How far the aligned CAD segment of a line lies beside the line drawn between its two marks. */
data class CorrespondenceLineResidual(
    val link: CorrespondenceLink,
    val station: Int,
    /** Worst of both ends, measured across the drawn line only. */
    val acrossMm: Float,
    val acrossPx: Float,
)

enum class CorrespondenceQuality { ACCEPTABLE, DOUBTFUL, BAD }

/** What the operator should do next to improve a fit that is not yet trustworthy. */
enum class CorrespondenceAdvice {
    /** A mark is not on the landmark it claims, or the P1..Pn order differs. */
    CHECK_MARKS,

    /** Distance and tilt are weakly observed: mark the points again from another position. */
    ADD_VIEW,

    /** Two poses explain a single station almost equally well. */
    TILT_AMBIGUOUS,

    /** The rotation around the marked axis is not observed by these landmarks. */
    ROLL_FREE,
}

data class CorrespondencePose(
    val rotation: FloatArray,
    val translation: FloatArray,
    val quaternionXyzw: FloatArray,
)

data class CorrespondenceSolution(
    val rotation: FloatArray,
    val translation: FloatArray,
    val quaternionXyzw: FloatArray,
    val residuals: List<CorrespondenceResidual>,
    /** One entry per line and view in which both of its ends were marked. */
    val lineResiduals: List<CorrespondenceLineResidual> = emptyList(),
    val worstLateralMm: Float,
    val meanLateralMm: Float,
    val toleranceMm: Float,
    /**
     * Expected 3D error (one sigma) of the aligned CAD at its least favourable extent point,
     * propagated from the mark accuracy through the geometry of the rays.  This is what grows
     * when every mark comes from one position, however small the residuals are.
     */
    val expectedErrorMm: Float,
    val expectedErrorLimitMm: Float,
    val stationCount: Int,
    /** Median over the landmarks of the widest angle between two of their rays. */
    val parallaxDegrees: Float,
    /** True when at least two landmarks were marked from clearly different positions. */
    val multiView: Boolean,
    /** Distance between the two most separated CAD landmarks. */
    val modelSpanMm: Float,
    /** The same distance measured by intersecting the marks, independent of the fit. */
    val observedSpanMm: Float?,
    /** True when the marks cannot observe the rotation around the P1..Pn axis. */
    val rollUndetermined: Boolean,
    val axisOrigin: FloatArray?,
    val axisDirection: FloatArray?,
    /** The other pose a single station cannot tell apart from this one, when there is one. */
    val alternative: CorrespondencePose?,
    val quality: CorrespondenceQuality,
    val advice: List<CorrespondenceAdvice>,
) {
    /**
     * Rotates the aligned pose around the axis through the marked landmarks.  Every landmark lies
     * on that axis, so each residual is preserved exactly and only the undetermined degree of
     * freedom changes.
     */
    fun rolled(degrees: Float): CorrespondenceSolution? {
        val origin = axisOrigin ?: return null
        val axis = axisDirection ?: return null
        val radians = Math.toRadians(degrees.toDouble())
        val delta = rodrigues(DoubleArray(3) { axis[it].toDouble() }, radians)
        val nextRotation = multiplyMatrix(delta, DoubleArray(9) { rotation[it].toDouble() })
        val originD = DoubleArray(3) { origin[it].toDouble() }
        val translationD = DoubleArray(3) { translation[it].toDouble() }
        val offset = multiply(delta, subtract(translationD, originD))
        val nextTranslation = add(originD, offset)
        val nextQuaternion = rotationToQuaternion(nextRotation)
        return copy(
            rotation = FloatArray(9) { nextRotation[it].toFloat() },
            translation = FloatArray(3) { nextTranslation[it].toFloat() },
            quaternionXyzw = FloatArray(4) { nextQuaternion[it].toFloat() },
        )
    }

    /** Swaps this pose with [alternative]; the residual report stays with the solved pose. */
    fun withAlternativePose(): CorrespondenceSolution? {
        val other = alternative ?: return null
        return copy(
            rotation = other.rotation,
            translation = other.translation,
            quaternionXyzw = other.quaternionXyzw,
            alternative = CorrespondencePose(rotation, translation, quaternionXyzw),
        )
    }
}

sealed interface CorrespondenceOutcome {
    data class Solved(val solution: CorrespondenceSolution) : CorrespondenceOutcome
    data class Rejected(val reason: String) : CorrespondenceOutcome
}

object ManualCorrespondenceSolver {
    const val MINIMUM_PAIRS = 2
    const val MAXIMUM_PAIRS = 6

    /** Rays of one landmark further apart than this count as a second viewpoint. */
    const val PARALLAX_DEGREES = 5.0

    /** Below this angle between stations the second view adds little; the UI says so. */
    const val RECOMMENDED_PARALLAX_DEGREES = 20.0

    private const val ITERATIONS = 80

    // Marks on one line leave the roll about that line unobserved.  Below 8 % of the span, or
    // 4 cm, a third point counts as on the line.  The absolute limit matters for long parts: the
    // corners of a 20 cm end plate are 2 % of a 10 m beam and were treated as collinear, so the
    // roll was taken from the 3D viewer instead of from the marks (0.33.0 and before).
    private const val COLLINEAR_OFF_AXIS_RATIO = 0.08
    private const val COLLINEAR_OFF_AXIS_M = 0.04

    // Two CAD points nearer than this are one corner tapped twice.  Only a mark's accuracy decides
    // that, not the size of the part: the relative share is capped so a long beam still accepts the
    // corners of one end plate.
    private const val MINIMUM_SEPARATION_M = 0.015
    private const val MINIMUM_SEPARATION_RATIO = 0.02
    private const val MAXIMUM_MINIMUM_SEPARATION_M = 0.04
    private const val MINIMUM_RAY_ANGLE_RADIANS = 0.005
    private const val TRIANGULATION_DEGREES = 3.0
    private const val ROLL_PENALTY = 1.0e8

    // Accuracy of one mark: crosshair placement, hand shake of the phone and the difference
    // between a CAD corner and a rolled, welded or painted real one.
    private const val MARK_SIGMA_PX = 6.0

    // Depth only supports the single station fit.  A Cauchy weight lets a reading that hit the
    // wall behind a silhouette corner fade out instead of dragging the part away.
    private const val DEPTH_SIGMA_M = 0.03
    private const val DEPTH_SIGMA_RATIO = 0.03
    private const val DEPTH_CAUCHY = 2.0

    private const val AMBIGUITY_COST_MARGIN = 6.0
    private const val AMBIGUITY_MINIMUM_DEGREES = 8.0

    private class Transform(val rotation: DoubleArray, val translation: DoubleArray)

    private class Observation(
        val point: Int,
        val label: Int,
        val station: Int,
        val origin: DoubleArray,
        val ray: DoubleArray,
        val sigmaRadians: Double,
        val focalLengthPx: Double,
        val depthM: Double?,
    )

    /**
     * One end of a line seen in one station: the CAD point must lie in the plane that the camera
     * centre spans with the drawn line.  [normal] is that plane's normal, perpendicular to both
     * rays, so the term measures only the part of the mark's offset that crosses the edge.
     */
    private class LineEnd(
        val point: Int,
        val observation: Observation,
        val normal: DoubleArray,
    )

    private class LineObservation(
        val link: CorrespondenceLink,
        val station: Int,
        val ends: List<LineEnd>,
    )

    private class Problem(
        val modelPoints: List<DoubleArray>,
        val observations: List<Observation>,
        val useDepth: Boolean,
        val freezeRoll: Boolean,
        val axisStart: Int,
        val axisEnd: Int,
        val lines: List<LineObservation> = emptyList(),
    )

    private class Candidate(val transform: Transform, val cost: Double)

    fun solve(
        picks: List<CorrespondencePick>,
        sightings: List<CorrespondenceSighting>,
        seedQuaternionXyzw: FloatArray,
        fallbackDistanceM: Float,
        modelDiameterM: Float,
        modelExtentPoints: List<FloatArray> = emptyList(),
        lines: List<CorrespondenceLink> = emptyList(),
    ): CorrespondenceOutcome {
        require(seedQuaternionXyzw.size == 4)
        val matched = picks
            .sortedBy(CorrespondencePick::label)
            .filter { pick -> sightings.any { it.label == pick.label } }
            .take(MAXIMUM_PAIRS)
        if (matched.size < MINIMUM_PAIRS) {
            return CorrespondenceOutcome.Rejected(
                "Mindestens zwei der in 3D gewählten Punkte in der Kamera markieren.",
            )
        }
        val diameterM = if (modelDiameterM.isFinite() && modelDiameterM > 0f) {
            modelDiameterM.toDouble()
        } else {
            1.0
        }
        val modelPoints = matched.map { pick -> DoubleArray(3) { pick.modelPoint[it].toDouble() } }
        val labels = matched.map(CorrespondencePick::label)
        val minimumSeparation = max(MINIMUM_SEPARATION_M, min(diameterM * MINIMUM_SEPARATION_RATIO, MAXIMUM_MINIMUM_SEPARATION_M))
        for (first in modelPoints.indices) {
            for (second in first + 1 until modelPoints.size) {
                if (distance(modelPoints[first], modelPoints[second]) < minimumSeparation) {
                    return CorrespondenceOutcome.Rejected(
                        "Die CAD-Punkte P${labels[first]} und P${labels[second]} liegen zu " +
                            "dicht beieinander (weniger als ${(minimumSeparation * 1000).toInt()} mm). " +
                            "Im 3D-Modell weiter auseinanderliegende Ecken wählen.",
                    )
                }
            }
        }
        val observations = ArrayList<Observation>(sightings.size)
        for (sighting in sightings) {
            val point = labels.indexOf(sighting.label)
            if (point < 0) continue
            val ray = normalize(DoubleArray(3) { sighting.rayDirection[it].toDouble() })
                ?: return CorrespondenceOutcome.Rejected("Eine der Markierungen hat keine gültige Richtung.")
            observations += Observation(
                point = point,
                label = sighting.label,
                station = sighting.station,
                origin = DoubleArray(3) { sighting.rayOrigin[it].toDouble() },
                ray = ray,
                sigmaRadians = MARK_SIGMA_PX / sighting.focalLengthPx,
                focalLengthPx = sighting.focalLengthPx.toDouble(),
                depthM = sighting.measuredDistanceM?.toDouble(),
            )
        }
        for (first in observations.indices) {
            for (second in first + 1 until observations.size) {
                val a = observations[first]
                val b = observations[second]
                if (a.station != b.station || a.point == b.point) continue
                if (acos(dot(a.ray, b.ray).coerceIn(-1.0, 1.0)) < MINIMUM_RAY_ANGLE_RADIANS) {
                    return CorrespondenceOutcome.Rejected(
                        "Die Markierungen P${a.label} und P${b.label} liegen fast auf demselben Pixel. " +
                            "Markierungen an unterscheidbaren Punkten des Bauteils wiederholen.",
                    )
                }
            }
        }

        val parallax = modelPoints.indices.map { point ->
            widestAngleDegrees(observations.filter { it.point == point })
        }
        val multiView = parallax.count { it >= PARALLAX_DEGREES } >= 2
        val (axisStart, axisEnd) = mostSeparated(modelPoints)
        val modelSpan = distance(modelPoints[axisStart], modelPoints[axisEnd])
        val offAxisM = offAxisRatio(modelPoints, axisStart, axisEnd, modelSpan) * modelSpan
        val degenerate = modelPoints.size < 3 ||
            offAxisM < min(COLLINEAR_OFF_AXIS_RATIO * modelSpan, COLLINEAR_OFF_AXIS_M)
        val problem = Problem(
            modelPoints = modelPoints,
            observations = observations,
            useDepth = !multiView,
            freezeRoll = degenerate,
            axisStart = axisStart,
            axisEnd = axisEnd,
            lines = lineObservations(lines.distinct(), labels, observations),
        )

        val fallback = if (fallbackDistanceM.isFinite() && fallbackDistanceM > 0f) {
            fallbackDistanceM.toDouble().coerceIn(0.2, 12.0)
        } else {
            1.5
        }
        val depths = observations.mapNotNull(Observation::depthM).sorted()
        val seedDistance = if (depths.isNotEmpty()) depths[depths.size / 2] else fallback
        val triangulated = modelPoints.indices.map { point ->
            triangulate(observations.filter { it.point == point })
        }
        val scenePoints = modelPoints.indices.map { point ->
            triangulated[point] ?: observations.first { it.point == point }.let { first ->
                val best = observations.firstOrNull { it.point == point && it.depthM != null } ?: first
                val reach = best.depthM ?: seedDistance
                DoubleArray(3) { best.origin[it] + best.ray[it] * reach }
            }
        }

        val seedRotation = quaternionToRotation(
            normalizeQuaternion(DoubleArray(4) { seedQuaternionXyzw[it].toDouble() }),
        )
        // Several starts, because the point-to-ray cost of few landmarks has more than one
        // valley: the orientation the operator saw while choosing the points in 3D, absolute
        // orientation on the intersected or Depth points, and for each of them the mirrored tilt
        // that a single station can hardly tell apart.  The rays decide which one is right.
        val starts = buildList {
            if (degenerate) {
                alignAxis(modelPoints, scenePoints, axisStart, axisEnd, seedRotation)?.let(::add)
            } else {
                add(centred(modelPoints, scenePoints, seedRotation))
                add(horn(modelPoints, scenePoints))
            }
        }
        if (starts.isEmpty()) {
            return CorrespondenceOutcome.Rejected(
                "Die beiden Markierungen fallen räumlich zusammen; am Bauteil weiter auseinander setzen.",
            )
        }
        val viewDirection = normalize(
            observations.fold(DoubleArray(3)) { sum, observation -> add(sum, observation.ray) },
        )
        val planeNormal = if (degenerate) null else pickPlaneNormal(modelPoints)
        val candidates = ArrayList<Candidate>()
        starts.forEach { start ->
            val refined = refine(problem, start)
            candidates += refined
            if (viewDirection != null && planeNormal != null) {
                candidates += refine(
                    problem,
                    mirroredTilt(refined.transform, modelPoints, viewDirection, planeNormal),
                )
            }
        }
        val ranked = candidates
            .filter { candidate ->
                candidate.cost.isFinite() &&
                    candidate.transform.rotation.all(Double::isFinite) &&
                    candidate.transform.translation.all(Double::isFinite)
            }
            .sortedBy(Candidate::cost)
        val best = ranked.firstOrNull() ?: return CorrespondenceOutcome.Rejected(
            "Die Punktausrichtung konvergiert nicht. Markierungen wiederholen und die Reihenfolge P1..Pn prüfen.",
        )
        val rival = ranked.drop(1).firstOrNull { candidate ->
            rotationAngleDegrees(candidate.transform.rotation, best.transform.rotation) >=
                AMBIGUITY_MINIMUM_DEGREES
        }
        // Judged on the landmarks alone: the line terms repeat the same marks and would make two
        // equally plausible tilts look more different than the marks can tell.
        val tiltAmbiguous = !degenerate && rival != null &&
            cost(problem, rival.transform, includeLines = false) -
            cost(problem, best.transform, includeLines = false) < AMBIGUITY_COST_MARGIN
        val refined = best.transform

        val residuals = ArrayList<CorrespondenceResidual>(observations.size)
        var worst = 0.0
        var total = 0.0
        observations.forEach { observation ->
            val world = add(multiply(refined.rotation, modelPoints[observation.point]), refined.translation)
            val offset = subtract(world, observation.origin)
            val along = dot(offset, observation.ray)
            val lateral = sqrt(max(0.0, dot(offset, offset) - along * along))
            worst = max(worst, lateral)
            total += lateral
            residuals += CorrespondenceResidual(
                label = observation.label,
                station = observation.station,
                lateralMm = (lateral * 1000).toFloat(),
                lateralPx = if (along > 0.05) {
                    (lateral / along * observation.focalLengthPx).toFloat()
                } else {
                    Float.NaN
                },
            )
        }

        val lineResiduals = problem.lines.map { line ->
            var acrossM = 0.0
            var acrossPx = 0.0
            line.ends.forEach { end ->
                val offset = subtract(
                    add(multiply(refined.rotation, modelPoints[end.point]), refined.translation),
                    end.observation.origin,
                )
                val across = abs(dot(offset, end.normal))
                val along = dot(offset, end.observation.ray)
                acrossM = max(acrossM, across)
                if (along > 0.05) acrossPx = max(acrossPx, across / along * end.observation.focalLengthPx)
            }
            CorrespondenceLineResidual(
                link = line.link,
                station = line.station,
                acrossMm = (acrossM * 1000).toFloat(),
                acrossPx = acrossPx.toFloat(),
            )
        }

        val extentPoints = modelExtentPoints
            .filter { it.size == 3 && it.all(Float::isFinite) }
            .map { point -> DoubleArray(3) { point[it].toDouble() } }
            .ifEmpty { modelPoints }
        val expectedErrorM = expectedError(
            problem,
            refined,
            cost(problem, refined, includeLines = false),
            extentPoints,
        )
        val toleranceMm = max(12.0, diameterM * 1000 * 0.015)
        val expectedLimitMm = max(25.0, diameterM * 1000 * 0.025)
        val observedSpan = triangulated[axisStart]?.let { start ->
            triangulated[axisEnd]?.let { end -> distance(start, end) }
        }

        val stationParallax = parallax.filter { it > 0.0 }.sorted()
        val medianParallax = if (stationParallax.isEmpty()) 0.0 else stationParallax[stationParallax.size / 2]
        val sufficientlySeparated = multiView && medianParallax >= RECOMMENDED_PARALLAX_DEGREES
        val advice = buildList {
            if (worst * 1000 > toleranceMm) add(CorrespondenceAdvice.CHECK_MARKS)
            if (tiltAmbiguous) add(CorrespondenceAdvice.TILT_AMBIGUOUS)
            if (!sufficientlySeparated || expectedErrorM * 1000 > expectedLimitMm) {
                add(CorrespondenceAdvice.ADD_VIEW)
            }
            if (degenerate) add(CorrespondenceAdvice.ROLL_FREE)
        }
        val quality = when {
            worst * 1000 > toleranceMm * 3 -> CorrespondenceQuality.BAD
            worst * 1000 > toleranceMm -> CorrespondenceQuality.DOUBTFUL
            expectedErrorM * 1000 > expectedLimitMm -> CorrespondenceQuality.DOUBTFUL
            tiltAmbiguous || degenerate || !sufficientlySeparated -> CorrespondenceQuality.DOUBTFUL
            else -> CorrespondenceQuality.ACCEPTABLE
        }
        val axisDirection = normalize(
            multiply(refined.rotation, subtract(modelPoints[axisEnd], modelPoints[axisStart])),
        )
        val axisOrigin = add(multiply(refined.rotation, modelPoints[axisStart]), refined.translation)
        return CorrespondenceOutcome.Solved(
            CorrespondenceSolution(
                rotation = FloatArray(9) { refined.rotation[it].toFloat() },
                translation = FloatArray(3) { refined.translation[it].toFloat() },
                quaternionXyzw = rotationToQuaternion(refined.rotation).let { quaternion ->
                    FloatArray(4) { quaternion[it].toFloat() }
                },
                residuals = residuals,
                lineResiduals = lineResiduals,
                worstLateralMm = (worst * 1000).toFloat(),
                meanLateralMm = (total / observations.size * 1000).toFloat(),
                toleranceMm = toleranceMm.toFloat(),
                expectedErrorMm = (expectedErrorM * 1000).toFloat(),
                expectedErrorLimitMm = expectedLimitMm.toFloat(),
                stationCount = observations.map(Observation::station).distinct().size,
                parallaxDegrees = if (stationParallax.isEmpty()) {
                    0f
                } else {
                    stationParallax[stationParallax.size / 2].toFloat()
                },
                multiView = multiView,
                modelSpanMm = (modelSpan * 1000).toFloat(),
                observedSpanMm = observedSpan?.let { (it * 1000).toFloat() },
                rollUndetermined = degenerate,
                axisOrigin = if (degenerate) FloatArray(3) { axisOrigin[it].toFloat() } else null,
                axisDirection = if (degenerate && axisDirection != null) {
                    FloatArray(3) { axisDirection[it].toFloat() }
                } else {
                    null
                },
                alternative = rival?.takeIf { tiltAmbiguous }?.transform?.let(::poseOf),
                quality = quality,
                advice = advice,
            ),
        )
    }

    /** Lateral distance in millimetres of one mark to its CAD landmark under a solved pose. */
    fun residualOf(
        solution: CorrespondenceSolution,
        pick: CorrespondencePick,
        sighting: CorrespondenceSighting,
    ): Float? {
        if (sighting.label != pick.label) return null
        val direction = normalize(DoubleArray(3) { sighting.rayDirection[it].toDouble() })
            ?: return null
        val world = add(
            multiply(
                DoubleArray(9) { solution.rotation[it].toDouble() },
                DoubleArray(3) { pick.modelPoint[it].toDouble() },
            ),
            DoubleArray(3) { solution.translation[it].toDouble() },
        )
        val offset = subtract(world, DoubleArray(3) { sighting.rayOrigin[it].toDouble() })
        val along = dot(offset, direction)
        return (sqrt(max(0.0, dot(offset, offset) - along * along)) * 1000).toFloat()
    }

    /**
     * Angle, seen from [point], between the position a landmark was marked from and the position
     * the camera has now.  The AR view shows it while the operator walks to the next station.
     */
    fun parallaxDegrees(point: FloatArray, markedFrom: FloatArray, cameraNow: FloatArray): Float {
        val first = normalize(DoubleArray(3) { (markedFrom[it] - point[it]).toDouble() }) ?: return 0f
        val second = normalize(DoubleArray(3) { (cameraNow[it] - point[it]).toDouble() }) ?: return 0f
        return Math.toDegrees(acos(dot(first, second).coerceIn(-1.0, 1.0))).toFloat()
    }

    /**
     * Lines whose two ends were marked in the same station.  A line with an end skipped from there
     * says nothing about that view; one whose marks nearly coincide has no direction.
     */
    private fun lineObservations(
        lines: List<CorrespondenceLink>,
        labels: List<Int>,
        observations: List<Observation>,
    ): List<LineObservation> = buildList {
        lines.forEach { link ->
            val firstPoint = labels.indexOf(link.first)
            val secondPoint = labels.indexOf(link.second)
            if (firstPoint < 0 || secondPoint < 0) return@forEach
            observations.map(Observation::station).distinct().sorted().forEach { station ->
                val first = observations.lastOrNull { it.point == firstPoint && it.station == station }
                val second = observations.lastOrNull { it.point == secondPoint && it.station == station }
                if (first == null || second == null) return@forEach
                if (acos(dot(first.ray, second.ray).coerceIn(-1.0, 1.0)) < MINIMUM_RAY_ANGLE_RADIANS) {
                    return@forEach
                }
                val normal = normalize(cross(first.ray, second.ray)) ?: return@forEach
                add(
                    LineObservation(
                        link = link,
                        station = station,
                        ends = listOf(LineEnd(firstPoint, first, normal), LineEnd(secondPoint, second, normal)),
                    ),
                )
            }
        }
    }

    private fun poseOf(transform: Transform): CorrespondencePose {
        val quaternion = rotationToQuaternion(transform.rotation)
        return CorrespondencePose(
            rotation = FloatArray(9) { transform.rotation[it].toFloat() },
            translation = FloatArray(3) { transform.translation[it].toFloat() },
            quaternionXyzw = FloatArray(4) { quaternion[it].toFloat() },
        )
    }

    private fun widestAngleDegrees(observations: List<Observation>): Double {
        var widest = 0.0
        for (first in observations.indices) {
            for (second in first + 1 until observations.size) {
                val cosine = dot(observations[first].ray, observations[second].ray).coerceIn(-1.0, 1.0)
                widest = max(widest, Math.toDegrees(acos(cosine)))
            }
        }
        return widest
    }

    /** Least squares intersection of the rays of one landmark, when they really cross. */
    private fun triangulate(observations: List<Observation>): DoubleArray? {
        if (widestAngleDegrees(observations) < TRIANGULATION_DEGREES) return null
        val normal = DoubleArray(9)
        val vector = DoubleArray(3)
        observations.forEach { observation ->
            for (row in 0..2) {
                for (column in 0..2) {
                    val projection = (if (row == column) 1.0 else 0.0) -
                        observation.ray[row] * observation.ray[column]
                    normal[row * 3 + column] += projection
                    vector[row] += projection * observation.origin[column]
                }
            }
        }
        val point = solveLinear(normal, vector, 3) ?: return null
        // A landmark behind one of its cameras is a wrong mark, not a usable intersection.
        val inFront = observations.all { observation ->
            dot(subtract(point, observation.origin), observation.ray) > 0.05
        }
        return point.takeIf { inFront }
    }

    /** Places the CAD, in the given orientation, with its landmarks centred on the scene points. */
    private fun centred(
        modelPoints: List<DoubleArray>,
        scenePoints: List<DoubleArray>,
        rotation: DoubleArray,
    ): Transform = Transform(
        rotation,
        subtract(centroid(scenePoints), multiply(rotation, centroid(modelPoints))),
    )

    /**
     * The pose a distant camera confuses with [transform]: the landmark plane tilted the other
     * way.  Mirroring the rotated landmarks along the view direction and the CAD landmarks along
     * their own plane normal keeps the landmark layout and yields a proper rotation.
     */
    private fun mirroredTilt(
        transform: Transform,
        modelPoints: List<DoubleArray>,
        viewDirection: DoubleArray,
        planeNormal: DoubleArray,
    ): Transform {
        val rotation = multiplyMatrix(
            multiplyMatrix(householder(viewDirection), transform.rotation),
            householder(planeNormal),
        )
        val modelCentroid = centroid(modelPoints)
        val worldCentroid = add(multiply(transform.rotation, modelCentroid), transform.translation)
        return Transform(rotation, subtract(worldCentroid, multiply(rotation, modelCentroid)))
    }

    private fun householder(unit: DoubleArray) = DoubleArray(9) { index ->
        val row = index / 3
        val column = index % 3
        (if (row == column) 1.0 else 0.0) - 2.0 * unit[row] * unit[column]
    }

    /** Normal of the plane that best fits the landmarks: exact for three, least squares beyond. */
    private fun pickPlaneNormal(points: List<DoubleArray>): DoubleArray? {
        val centre = centroid(points)
        val covariance = DoubleArray(9)
        points.forEach { point ->
            val offset = subtract(point, centre)
            for (row in 0..2) {
                for (column in 0..2) covariance[row * 3 + column] += offset[row] * offset[column]
            }
        }
        val (values, vectors) = jacobi(covariance, 3)
        val smallest = values.indices.minBy { values[it] }
        return normalize(DoubleArray(3) { vectors[it * 3 + smallest] })
    }

    /** Index pair of the two most separated CAD landmarks. */
    private fun mostSeparated(points: List<DoubleArray>): Pair<Int, Int> {
        var best = 0 to 1
        var bestDistance = -1.0
        for (first in points.indices) {
            for (second in first + 1 until points.size) {
                val candidate = distance(points[first], points[second])
                if (candidate > bestDistance) {
                    bestDistance = candidate
                    best = first to second
                }
            }
        }
        return best
    }

    private fun offAxisRatio(
        points: List<DoubleArray>,
        axisStart: Int,
        axisEnd: Int,
        span: Double,
    ): Double {
        if (points.size < 3 || span <= 1e-9) return 0.0
        val axis = normalize(subtract(points[axisEnd], points[axisStart])) ?: return 0.0
        var worst = 0.0
        points.indices.forEach { index ->
            if (index == axisStart || index == axisEnd) return@forEach
            val offset = subtract(points[index], points[axisStart])
            val along = dot(offset, axis)
            val perpendicular = sqrt(max(0.0, dot(offset, offset) - along * along))
            worst = max(worst, perpendicular)
        }
        return worst / span
    }

    /**
     * Two marks, or marks on one line, leave the rotation around that line unobserved.  The
     * orientation the operator saw in the 3D viewer is kept for that degree of freedom instead of
     * guessing a gravity convention the assembly frame does not promise.
     */
    private fun alignAxis(
        modelPoints: List<DoubleArray>,
        scenePoints: List<DoubleArray>,
        axisStart: Int,
        axisEnd: Int,
        seedRotation: DoubleArray,
    ): Transform? {
        val modelAxis = normalize(
            multiply(seedRotation, subtract(modelPoints[axisEnd], modelPoints[axisStart])),
        ) ?: return null
        val sceneAxis = normalize(subtract(scenePoints[axisEnd], scenePoints[axisStart]))
            ?: return null
        return centred(
            modelPoints,
            scenePoints,
            multiplyMatrix(rotationBetween(modelAxis, sceneAxis), seedRotation),
        )
    }

    private fun depthSigma(depthM: Double) = max(DEPTH_SIGMA_M, depthM * DEPTH_SIGMA_RATIO)

    /**
     * Chi squared of the marks: angular ray distance plus, for one station, robust Depth, plus the
     * across-edge terms of the drawn lines unless [includeLines] is false.
     */
    private fun cost(problem: Problem, transform: Transform, includeLines: Boolean = true): Double {
        var sum = 0.0
        if (includeLines) problem.lines.forEach { line ->
            line.ends.forEach { end ->
                val offset = subtract(
                    add(multiply(transform.rotation, problem.modelPoints[end.point]), transform.translation),
                    end.observation.origin,
                )
                val reach = max(dot(offset, end.observation.ray), 0.05) * end.observation.sigmaRadians
                val across = dot(offset, end.normal)
                sum += across * across / (reach * reach)
            }
        }
        problem.observations.forEach { observation ->
            val offset = subtract(
                add(multiply(transform.rotation, problem.modelPoints[observation.point]), transform.translation),
                observation.origin,
            )
            val along = dot(offset, observation.ray)
            val lateralSquared = max(0.0, dot(offset, offset) - along * along)
            val reach = max(along, 0.05) * observation.sigmaRadians
            sum += lateralSquared / (reach * reach)
            // A landmark behind its own camera is never a valid fit.
            if (along < 0.05) sum += (0.05 - along) * (0.05 - along) / (reach * reach)
            if (problem.useDepth && observation.depthM != null) {
                val scaled = (along - observation.depthM) / depthSigma(observation.depthM)
                sum += DEPTH_CAUCHY * DEPTH_CAUCHY * ln(1.0 + scaled * scaled / (DEPTH_CAUCHY * DEPTH_CAUCHY))
            }
        }
        return sum
    }

    /** Gauss-Newton normal equations of [cost] around [transform], for a left rotation update. */
    private fun normalEquations(
        problem: Problem,
        transform: Transform,
        gradient: DoubleArray?,
        includeLines: Boolean = true,
    ): DoubleArray {
        val normal = DoubleArray(36)
        if (includeLines) problem.lines.forEach { line ->
            line.ends.forEach { end ->
                val rotated = multiply(transform.rotation, problem.modelPoints[end.point])
                val offset = subtract(add(rotated, transform.translation), end.observation.origin)
                val reach = max(dot(offset, end.observation.ray), 0.05) * end.observation.sigmaRadians
                // Same -[rotated]_x as below: the plane normal times the world point derivative.
                val jacobian = DoubleArray(6)
                for (column in 0..2) jacobian[column] = end.normal[column]
                jacobian[3] = -end.normal[1] * rotated[2] + end.normal[2] * rotated[1]
                jacobian[4] = end.normal[0] * rotated[2] - end.normal[2] * rotated[0]
                jacobian[5] = -end.normal[0] * rotated[1] + end.normal[1] * rotated[0]
                accumulate(normal, gradient, jacobian, dot(offset, end.normal), 1.0 / (reach * reach))
            }
        }
        problem.observations.forEach { observation ->
            val rotated = multiply(transform.rotation, problem.modelPoints[observation.point])
            val offset = subtract(add(rotated, transform.translation), observation.origin)
            val ray = observation.ray
            val along = dot(offset, ray)
            // -[rotated]_x, the derivative of the world point against a left rotation update.
            val cross = doubleArrayOf(
                0.0, rotated[2], -rotated[1],
                -rotated[2], 0.0, rotated[0],
                rotated[1], -rotated[0], 0.0,
            )
            val reach = max(along, 0.05) * observation.sigmaRadians
            val weight = 1.0 / (reach * reach)
            for (row in 0..2) {
                val projection = DoubleArray(3) { column ->
                    (if (row == column) 1.0 else 0.0) - ray[row] * ray[column]
                }
                val jacobian = DoubleArray(6)
                for (column in 0..2) {
                    jacobian[column] = projection[column]
                    jacobian[3 + column] = (0..2).sumOf { k -> projection[k] * cross[k * 3 + column] }
                }
                accumulate(normal, gradient, jacobian, dot(projection, offset), weight)
            }
            val alongJacobian = DoubleArray(6)
            for (column in 0..2) {
                alongJacobian[column] = ray[column]
                alongJacobian[3 + column] = (0..2).sumOf { k -> ray[k] * cross[k * 3 + column] }
            }
            if (along < 0.05) accumulate(normal, gradient, alongJacobian, along - 0.05, weight)
            if (problem.useDepth && observation.depthM != null) {
                val sigma = depthSigma(observation.depthM)
                val residual = along - observation.depthM
                val scaled = residual / sigma
                val robust = 1.0 / (1.0 + scaled * scaled / (DEPTH_CAUCHY * DEPTH_CAUCHY))
                accumulate(normal, gradient, alongJacobian, residual, robust / (sigma * sigma))
            }
        }
        if (problem.freezeRoll) {
            val axis = normalize(
                multiply(
                    transform.rotation,
                    subtract(problem.modelPoints[problem.axisEnd], problem.modelPoints[problem.axisStart]),
                ),
            )
            if (axis != null) {
                for (row in 0..2) {
                    for (column in 0..2) {
                        normal[(3 + row) * 6 + 3 + column] += ROLL_PENALTY * axis[row] * axis[column]
                    }
                }
            }
        }
        return normal
    }

    /**
     * Levenberg-Marquardt over SE(3).  A step is only kept when it lowers the cost, so a poor
     * start or a nearly singular single station geometry cannot make the fit diverge.
     */
    private fun refine(problem: Problem, seed: Transform): Candidate {
        var current = seed
        var currentCost = cost(problem, current)
        var damping = 1.0e-3
        var iteration = 0
        while (iteration < ITERATIONS && damping < 1.0e9) {
            iteration += 1
            val gradient = DoubleArray(6)
            val normal = normalEquations(problem, current, gradient)
            for (index in 0..5) {
                normal[index * 6 + index] += 1.0e-9 + damping * abs(normal[index * 6 + index])
            }
            val step = solveLinear(normal, DoubleArray(6) { -gradient[it] }, 6)
            if (step == null) {
                damping *= 8.0
                continue
            }
            val turn = DoubleArray(3) { step[3 + it] }
            val turnLength = sqrt(dot(turn, turn))
            val limit = Math.toRadians(25.0)
            val scale = if (turnLength > limit) limit / turnLength else 1.0
            val next = Transform(
                rotation = multiplyMatrix(
                    rodrigues(turn, turnLength * scale),
                    current.rotation,
                ),
                translation = DoubleArray(3) { current.translation[it] + step[it] * scale },
            )
            val nextCost = cost(problem, next)
            if (nextCost.isFinite() && nextCost < currentCost) {
                val gain = currentCost - nextCost
                current = next
                currentCost = nextCost
                damping = max(damping / 3.0, 1.0e-9)
                if (gain < 1.0e-10 * (1.0 + currentCost)) break
            } else {
                damping *= 4.0
            }
        }
        return Candidate(current, currentCost)
    }

    /**
     * One sigma 3D error of the posed CAD at its least favourable extent point.  The inverse of
     * the normal equations is the covariance of the six pose parameters for the assumed mark
     * accuracy; marks that disagree more than that accuracy explains inflate it.  Lines are left
     * out: they reuse the same marks and would otherwise claim accuracy nobody measured.
     */
    private fun expectedError(
        problem: Problem,
        transform: Transform,
        chiSquared: Double,
        extentPoints: List<DoubleArray>,
    ): Double {
        val normal = normalEquations(problem, transform, gradient = null, includeLines = false)
        for (index in 0..5) normal[index * 6 + index] += 1.0e-12
        val covariance = DoubleArray(36)
        for (column in 0..5) {
            val unit = DoubleArray(6).also { it[column] = 1.0 }
            val solved = solveLinear(normal, unit, 6) ?: return Double.POSITIVE_INFINITY
            for (row in 0..5) covariance[row * 6 + column] = solved[row]
        }
        val measurements = problem.observations.size * 2 +
            if (problem.useDepth) problem.observations.count { it.depthM != null } else 0
        val freedom = measurements - if (problem.freezeRoll) 5 else 6
        val inflation = if (freedom > 0) max(1.0, chiSquared / freedom) else 1.0
        var worst = 0.0
        extentPoints.forEach { point ->
            val rotated = multiply(transform.rotation, point)
            // World point derivative: identity for the translation, -[rotated]_x for the turn.
            val jacobian = arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0, 0.0, rotated[2], -rotated[1]),
                doubleArrayOf(0.0, 1.0, 0.0, -rotated[2], 0.0, rotated[0]),
                doubleArrayOf(0.0, 0.0, 1.0, rotated[1], -rotated[0], 0.0),
            )
            var variance = 0.0
            jacobian.forEach { row ->
                for (first in 0..5) {
                    for (second in 0..5) {
                        variance += row[first] * covariance[first * 6 + second] * row[second]
                    }
                }
            }
            worst = max(worst, variance * inflation)
        }
        return if (worst.isFinite() && worst >= 0.0) sqrt(worst) else Double.POSITIVE_INFINITY
    }

    private fun accumulate(
        normal: DoubleArray,
        gradient: DoubleArray?,
        jacobian: DoubleArray,
        residual: Double,
        weight: Double,
    ) {
        for (row in 0..5) {
            if (gradient != null) gradient[row] += weight * jacobian[row] * residual
            for (column in 0..5) {
                normal[row * 6 + column] += weight * jacobian[row] * jacobian[column]
            }
        }
    }

    /** Gauss-Jordan with partial pivoting for the small dense systems of this solver. */
    private fun solveLinear(matrix: DoubleArray, vector: DoubleArray, size: Int): DoubleArray? {
        val work = matrix.copyOf()
        val result = vector.copyOf()
        for (column in 0 until size) {
            var pivot = column
            for (row in column + 1 until size) {
                if (abs(work[row * size + column]) > abs(work[pivot * size + column])) pivot = row
            }
            if (abs(work[pivot * size + column]) < 1.0e-14) return null
            if (pivot != column) {
                for (index in 0 until size) {
                    val swap = work[column * size + index]
                    work[column * size + index] = work[pivot * size + index]
                    work[pivot * size + index] = swap
                }
                val swap = result[column]
                result[column] = result[pivot]
                result[pivot] = swap
            }
            val diagonal = work[column * size + column]
            for (row in 0 until size) {
                if (row == column) continue
                val factor = work[row * size + column] / diagonal
                if (factor == 0.0) continue
                for (index in 0 until size) {
                    work[row * size + index] -= factor * work[column * size + index]
                }
                result[row] -= factor * result[column]
            }
        }
        return DoubleArray(size) { result[it] / work[it * size + it] }
            .takeIf { row -> row.all(Double::isFinite) }
    }

    /**
     * Horn's absolute orientation.  A quaternion result is always a proper rotation, which
     * matters for the coplanar three point case.
     */
    private fun horn(modelPoints: List<DoubleArray>, scenePoints: List<DoubleArray>): Transform {
        val modelCentroid = centroid(modelPoints)
        val sceneCentroid = centroid(scenePoints)
        val cross = DoubleArray(9)
        modelPoints.indices.forEach { index ->
            val source = subtract(modelPoints[index], modelCentroid)
            val target = subtract(scenePoints[index], sceneCentroid)
            for (row in 0..2) {
                for (column in 0..2) {
                    cross[row * 3 + column] += source[row] * target[column]
                }
            }
        }
        val xx = cross[0]
        val xy = cross[1]
        val xz = cross[2]
        val yx = cross[3]
        val yy = cross[4]
        val yz = cross[5]
        val zx = cross[6]
        val zy = cross[7]
        val zz = cross[8]
        val trace = xx + yy + zz
        val symmetric = doubleArrayOf(
            trace, yz - zy, zx - xz, xy - yx,
            yz - zy, xx - yy - zz, xy + yx, zx + xz,
            zx - xz, xy + yx, -xx + yy - zz, yz + zy,
            xy - yx, zx + xz, yz + zy, -xx - yy + zz,
        )
        val decomposition = jacobi(symmetric, 4)
        val largest = decomposition.first.indices.maxBy { decomposition.first[it] }
        val quaternion = normalizeQuaternion(
            doubleArrayOf(
                decomposition.second[4 + largest],
                decomposition.second[8 + largest],
                decomposition.second[12 + largest],
                decomposition.second[largest],
            ),
        )
        val rotation = quaternionToRotation(quaternion)
        return Transform(rotation, subtract(sceneCentroid, multiply(rotation, modelCentroid)))
    }

    /** Eigenvalues and column eigenvectors of a small symmetric matrix. */
    private fun jacobi(source: DoubleArray, size: Int): Pair<DoubleArray, DoubleArray> {
        val matrix = source.copyOf()
        val vectors = DoubleArray(size * size)
        for (index in 0 until size) vectors[index * size + index] = 1.0
        repeat(64) {
            var offDiagonal = 0.0
            for (row in 0 until size) {
                for (column in row + 1 until size) {
                    offDiagonal += matrix[row * size + column] * matrix[row * size + column]
                }
            }
            if (offDiagonal < 1.0e-22) return eigen(matrix, vectors, size)
            for (row in 0 until size) {
                for (column in row + 1 until size) {
                    val offsetValue = matrix[row * size + column]
                    if (abs(offsetValue) < 1.0e-22) continue
                    val theta = (matrix[column * size + column] - matrix[row * size + row]) /
                        (2.0 * offsetValue)
                    val sign = if (theta >= 0) 1.0 else -1.0
                    val tangent = sign / (abs(theta) + sqrt(theta * theta + 1.0))
                    val cosine = 1.0 / sqrt(tangent * tangent + 1.0)
                    val sine = tangent * cosine
                    for (index in 0 until size) {
                        val rowValue = matrix[row * size + index]
                        val columnValue = matrix[column * size + index]
                        matrix[row * size + index] = cosine * rowValue - sine * columnValue
                        matrix[column * size + index] = sine * rowValue + cosine * columnValue
                    }
                    for (index in 0 until size) {
                        val rowValue = matrix[index * size + row]
                        val columnValue = matrix[index * size + column]
                        matrix[index * size + row] = cosine * rowValue - sine * columnValue
                        matrix[index * size + column] = sine * rowValue + cosine * columnValue
                    }
                    for (index in 0 until size) {
                        val rowValue = vectors[index * size + row]
                        val columnValue = vectors[index * size + column]
                        vectors[index * size + row] = cosine * rowValue - sine * columnValue
                        vectors[index * size + column] = sine * rowValue + cosine * columnValue
                    }
                }
            }
        }
        return eigen(matrix, vectors, size)
    }

    private fun eigen(
        matrix: DoubleArray,
        vectors: DoubleArray,
        size: Int,
    ): Pair<DoubleArray, DoubleArray> =
        DoubleArray(size) { matrix[it * size + it] } to vectors

    private fun rotationBetween(from: DoubleArray, to: DoubleArray): DoubleArray {
        val axis = cross(from, to)
        val sine = sqrt(dot(axis, axis))
        val cosine = dot(from, to).coerceIn(-1.0, 1.0)
        if (sine < 1.0e-9) {
            if (cosine > 0.0) return identity()
            val perpendicular = normalize(cross(from, doubleArrayOf(1.0, 0.0, 0.0)))
                ?: normalize(cross(from, doubleArrayOf(0.0, 1.0, 0.0)))
                ?: return identity()
            return rodrigues(perpendicular, Math.PI)
        }
        return rodrigues(DoubleArray(3) { axis[it] / sine }, acos(cosine))
    }
}

private fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

private fun rodrigues(axis: DoubleArray, radians: Double): DoubleArray {
    val length = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
    if (length < 1.0e-12 || abs(radians) < 1.0e-12) return identity()
    val x = axis[0] / length
    val y = axis[1] / length
    val z = axis[2] / length
    val sine = sin(radians)
    val cosine = cos(radians)
    val complement = 1.0 - cosine
    return doubleArrayOf(
        cosine + x * x * complement, x * y * complement - z * sine, x * z * complement + y * sine,
        y * x * complement + z * sine, cosine + y * y * complement, y * z * complement - x * sine,
        z * x * complement - y * sine, z * y * complement + x * sine, cosine + z * z * complement,
    )
}

private fun centroid(points: List<DoubleArray>): DoubleArray {
    val result = DoubleArray(3)
    points.forEach { point -> for (axis in 0..2) result[axis] += point[axis] }
    for (axis in 0..2) result[axis] /= points.size
    return result
}

private fun multiply(matrix: DoubleArray, vector: DoubleArray) = doubleArrayOf(
    matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2],
    matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2],
    matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2],
)

private fun multiplyMatrix(left: DoubleArray, right: DoubleArray): DoubleArray =
    DoubleArray(9) { index ->
        val row = index / 3
        val column = index % 3
        (0..2).sumOf { inner -> left[row * 3 + inner] * right[inner * 3 + column] }
    }

private fun subtract(left: DoubleArray, right: DoubleArray) = DoubleArray(left.size) {
    left[it] - right[it]
}

private fun add(left: DoubleArray, right: DoubleArray) = DoubleArray(left.size) {
    left[it] + right[it]
}

private fun dot(left: DoubleArray, right: DoubleArray): Double =
    left.indices.sumOf { left[it] * right[it] }

private fun cross(left: DoubleArray, right: DoubleArray) = doubleArrayOf(
    left[1] * right[2] - left[2] * right[1],
    left[2] * right[0] - left[0] * right[2],
    left[0] * right[1] - left[1] * right[0],
)

private fun distance(left: DoubleArray, right: DoubleArray): Double =
    sqrt(subtract(left, right).let { dot(it, it) })

private fun normalize(vector: DoubleArray): DoubleArray? {
    val length = sqrt(dot(vector, vector))
    if (!length.isFinite() || length < 1.0e-12) return null
    return DoubleArray(vector.size) { vector[it] / length }
}

private fun normalizeQuaternion(value: DoubleArray): DoubleArray {
    val length = sqrt(dot(value, value)).coerceAtLeast(1.0e-12)
    return DoubleArray(4) { value[it] / length }
}

private fun quaternionToRotation(value: DoubleArray): DoubleArray {
    val x = value[0]
    val y = value[1]
    val z = value[2]
    val w = value[3]
    return doubleArrayOf(
        1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
        2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
        2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
    )
}

private fun rotationToQuaternion(rotation: DoubleArray): DoubleArray {
    val trace = rotation[0] + rotation[4] + rotation[8]
    return if (trace > 0) {
        val scale = sqrt(trace + 1.0) * 2.0
        normalizeQuaternion(
            doubleArrayOf(
                (rotation[7] - rotation[5]) / scale,
                (rotation[2] - rotation[6]) / scale,
                (rotation[3] - rotation[1]) / scale,
                0.25 * scale,
            ),
        )
    } else if (rotation[0] > rotation[4] && rotation[0] > rotation[8]) {
        val scale = sqrt(1.0 + rotation[0] - rotation[4] - rotation[8]) * 2.0
        normalizeQuaternion(
            doubleArrayOf(
                0.25 * scale,
                (rotation[1] + rotation[3]) / scale,
                (rotation[2] + rotation[6]) / scale,
                (rotation[7] - rotation[5]) / scale,
            ),
        )
    } else if (rotation[4] > rotation[8]) {
        val scale = sqrt(1.0 + rotation[4] - rotation[0] - rotation[8]) * 2.0
        normalizeQuaternion(
            doubleArrayOf(
                (rotation[1] + rotation[3]) / scale,
                0.25 * scale,
                (rotation[5] + rotation[7]) / scale,
                (rotation[2] - rotation[6]) / scale,
            ),
        )
    } else {
        val scale = sqrt(1.0 + rotation[8] - rotation[0] - rotation[4]) * 2.0
        normalizeQuaternion(
            doubleArrayOf(
                (rotation[2] + rotation[6]) / scale,
                (rotation[5] + rotation[7]) / scale,
                0.25 * scale,
                (rotation[3] - rotation[1]) / scale,
            ),
        )
    }
}

private fun rotationAngleDegrees(first: DoubleArray, second: DoubleArray): Double {
    var trace = 0.0
    for (row in 0..2) {
        for (column in 0..2) {
            trace += first[row * 3 + column] * second[row * 3 + column]
        }
    }
    val cosine = ((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(cosine))
}

/**
 * Orientation seed from the 3D viewer.  The operator chose the points while looking at the model
 * from one side; in AR they normally stand on that same side.  The CAD is therefore seeded so the
 * AR camera sees it exactly as the viewer camera did: model rotation = AR camera · viewer camera⁻¹.
 * Both cameras look along −Z with +Y up on screen, so the AR quaternion must be the display
 * oriented one, not the sensor pose.
 */
object AlignmentSeed {
    fun modelRotationFromViewerCamera(
        arCameraQuaternionXyzw: FloatArray,
        viewerCameraQuaternionXyzw: FloatArray,
    ): FloatArray {
        require(arCameraQuaternionXyzw.size == 4 && viewerCameraQuaternionXyzw.size == 4)
        val ar = normalizeQuaternion(DoubleArray(4) { arCameraQuaternionXyzw[it].toDouble() })
        val viewer = normalizeQuaternion(DoubleArray(4) { viewerCameraQuaternionXyzw[it].toDouble() })
        val inverse = doubleArrayOf(-viewer[0], -viewer[1], -viewer[2], viewer[3])
        val product = normalizeQuaternion(multiplyQuaternion(ar, inverse))
        return FloatArray(4) { product[it].toFloat() }
    }
}

private fun multiplyQuaternion(left: DoubleArray, right: DoubleArray): DoubleArray = doubleArrayOf(
    left[3] * right[0] + left[0] * right[3] + left[1] * right[2] - left[2] * right[1],
    left[3] * right[1] - left[0] * right[2] + left[1] * right[3] + left[2] * right[0],
    left[3] * right[2] + left[0] * right[1] - left[1] * right[0] + left[2] * right[3],
    left[3] * right[3] - left[0] * right[0] - left[1] * right[1] - left[2] * right[2],
)
