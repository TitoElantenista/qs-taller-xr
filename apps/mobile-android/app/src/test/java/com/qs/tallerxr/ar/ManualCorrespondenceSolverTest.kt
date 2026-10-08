package com.qs.tallerxr.ar

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCorrespondenceSolverTest {

    private val focalLengthPx = 1_500f

    /** Rotation of [degrees] around a normalised axis, row major like the solver output. */
    private fun rotation(axis: FloatArray, degrees: Float): FloatArray {
        val radians = Math.toRadians(degrees.toDouble())
        val length = sqrt(axis.fold(0f) { sum, value -> sum + value * value })
        val x = axis[0] / length
        val y = axis[1] / length
        val z = axis[2] / length
        val sine = sin(radians).toFloat()
        val cosine = cos(radians).toFloat()
        val complement = 1f - cosine
        return floatArrayOf(
            cosine + x * x * complement, x * y * complement - z * sine, x * z * complement + y * sine,
            y * x * complement + z * sine, cosine + y * y * complement, y * z * complement - x * sine,
            z * x * complement - y * sine, z * y * complement + x * sine, cosine + z * z * complement,
        )
    }

    private fun quaternion(rotation: FloatArray): FloatArray {
        val trace = rotation[0] + rotation[4] + rotation[8]
        val scale = sqrt(trace + 1f) * 2f
        return floatArrayOf(
            (rotation[7] - rotation[5]) / scale,
            (rotation[2] - rotation[6]) / scale,
            (rotation[3] - rotation[1]) / scale,
            0.25f * scale,
        )
    }

    private fun apply(rotation: FloatArray, translation: FloatArray, point: FloatArray) =
        floatArrayOf(
            rotation[0] * point[0] + rotation[1] * point[1] + rotation[2] * point[2] + translation[0],
            rotation[3] * point[0] + rotation[4] * point[1] + rotation[5] * point[2] + translation[1],
            rotation[6] * point[0] + rotation[7] * point[1] + rotation[8] * point[2] + translation[2],
        )

    /**
     * A mark of [worldPoint] from [cameraOrigin].  [pixelNoise] displaces it across the view the
     * way an imprecise mark does: by an angle of noise / focal length.
     */
    private fun sighting(
        label: Int,
        cameraOrigin: FloatArray,
        worldPoint: FloatArray,
        depthErrorM: Float?,
        station: Int = 1,
        pixelNoise: FloatArray = floatArrayOf(0f, 0f),
    ): CorrespondenceSighting {
        val offset = FloatArray(3) { worldPoint[it] - cameraOrigin[it] }
        val distance = sqrt(offset.fold(0f) { sum, value -> sum + value * value })
        val forward = FloatArray(3) { offset[it] / distance }
        val helper = if (abs(forward[1]) < 0.9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
        val right = normalised(cross(forward, helper))
        val up = cross(right, forward)
        val direction = normalised(
            FloatArray(3) {
                forward[it] + right[it] * pixelNoise[0] / focalLengthPx + up[it] * pixelNoise[1] / focalLengthPx
            },
        )
        return CorrespondenceSighting(
            label = label,
            station = station,
            rayOrigin = cameraOrigin,
            rayDirection = direction,
            measuredDistanceM = depthErrorM?.let { distance + it },
            focalLengthPx = focalLengthPx,
        )
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun normalised(vector: FloatArray): FloatArray {
        val length = sqrt(vector.fold(0f) { sum, value -> sum + value * value })
        return FloatArray(3) { vector[it] / length }
    }

    private fun degreesBetween(first: FloatArray, second: FloatArray): Float {
        var trace = 0f
        for (index in 0..8) trace += first[index] * second[index]
        val cosine = ((trace - 1f) / 2f).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cosine.toDouble())).toFloat()
    }

    private fun solved(outcome: CorrespondenceOutcome): CorrespondenceSolution =
        (outcome as CorrespondenceOutcome.Solved).solution

    /** Largest distance in millimetres between the solved and the true pose over [points]. */
    private fun worstErrorMm(
        solution: CorrespondenceSolution,
        trueRotation: FloatArray,
        trueTranslation: FloatArray,
        points: List<FloatArray>,
    ): Float = points.maxOf { point ->
        val expected = apply(trueRotation, trueTranslation, point)
        val actual = apply(solution.rotation, solution.translation, point)
        sqrt((0..2).fold(0f) { sum, axis -> sum + (expected[axis] - actual[axis]).let { it * it } }) * 1000f
    }

    private val plateCorners = listOf(
        CorrespondencePick(1, floatArrayOf(-0.4f, -0.12f, 0f)),
        CorrespondencePick(2, floatArrayOf(0.4f, -0.12f, 0f)),
        CorrespondencePick(3, floatArrayOf(0.4f, 0.12f, 0.06f)),
        CorrespondencePick(4, floatArrayOf(-0.4f, 0.1f, -0.05f)),
    )

    // A 2.4 m beam whose operator picks the four corners of the visible flange: coplanar and
    // narrow, the common and least favourable workshop choice.
    private val beamFlange = listOf(
        CorrespondencePick(1, floatArrayOf(-1.2f, 0.1f, 0.1f)),
        CorrespondencePick(2, floatArrayOf(1.2f, 0.1f, 0.1f)),
        CorrespondencePick(3, floatArrayOf(1.2f, 0.1f, -0.1f)),
        CorrespondencePick(4, floatArrayOf(-1.2f, 0.1f, -0.1f)),
    )
    private val beamExtents = listOf(
        floatArrayOf(-1.2f, -0.1f, -0.1f), floatArrayOf(-1.2f, -0.1f, 0.1f),
        floatArrayOf(-1.2f, 0.1f, -0.1f), floatArrayOf(-1.2f, 0.1f, 0.1f),
        floatArrayOf(1.2f, -0.1f, -0.1f), floatArrayOf(1.2f, -0.1f, 0.1f),
        floatArrayOf(1.2f, 0.1f, -0.1f), floatArrayOf(1.2f, 0.1f, 0.1f),
    )

    @Test
    fun `fifth and sixth landmarks participate in the fit and resolve roll`() {
        // The first four are collinear. Only P5/P6 can constrain rotation around their axis;
        // silently truncating the picks to four must fail this regression.
        val sixPicks = listOf(
            CorrespondencePick(1, floatArrayOf(-0.6f, 0f, 0f)),
            CorrespondencePick(2, floatArrayOf(-0.2f, 0f, 0f)),
            CorrespondencePick(3, floatArrayOf(0.2f, 0f, 0f)),
            CorrespondencePick(4, floatArrayOf(0.6f, 0f, 0f)),
            CorrespondencePick(5, floatArrayOf(-0.4f, 0.3f, 0.1f)),
            CorrespondencePick(6, floatArrayOf(0.4f, -0.2f, -0.15f)),
        )
        val trueRotation = rotation(floatArrayOf(1f, 0.3f, 0.1f), 37f)
        val trueTranslation = floatArrayOf(0.1f, -0.1f, -2f)
        val stations = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(1.1f, 0.2f, -0.2f))
        for (count in 5..6) {
            val picks = sixPicks.take(count)
            val sightings = stations.flatMapIndexed { station, camera ->
                picks.map { pick ->
                    sighting(
                        label = pick.label,
                        cameraOrigin = camera,
                        worldPoint = apply(trueRotation, trueTranslation, pick.modelPoint),
                        depthErrorM = null,
                        station = station + 1,
                    )
                }
            }
            val solution = solved(
                ManualCorrespondenceSolver.solve(
                    picks = picks,
                    sightings = sightings,
                    seedQuaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
                    fallbackDistanceM = 1.6f,
                    modelDiameterM = 1.4f,
                ),
            )
            assertFalse("$count points must resolve roll", solution.rollUndetermined)
            assertEquals(count * 2, solution.residuals.size)
            assertEquals((1..count).toSet(), solution.residuals.map { it.label }.toSet())
            assertTrue(solution.multiView)
            assertTrue(worstErrorMm(solution, trueRotation, trueTranslation, sixPicks.map { it.modelPoint }) < 1.5f)
        }
    }

    @Test
    fun `four marks recover the pose despite noisy depth`() {
        val trueRotation = rotation(floatArrayOf(0.3f, 1f, 0.2f), 37f)
        val trueTranslation = floatArrayOf(0.15f, -0.08f, -1.9f)
        val camera = floatArrayOf(0f, 0f, 0f)
        val depthErrors = listOf(0.03f, -0.025f, 0.018f, -0.035f)
        val sightings = plateCorners.mapIndexed { index, pick ->
            sighting(
                label = pick.label,
                cameraOrigin = camera,
                worldPoint = apply(trueRotation, trueTranslation, pick.modelPoint),
                depthErrorM = depthErrors[index],
            )
        }
        val solution = solved(
            ManualCorrespondenceSolver.solve(
                picks = plateCorners,
                sightings = sightings,
                seedQuaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
                fallbackDistanceM = 1.6f,
                modelDiameterM = 0.9f,
            ),
        )
        assertFalse(solution.rollUndetermined)
        assertTrue("residuo lateral ${solution.worstLateralMm} mm", solution.worstLateralMm < 6f)
        for (axis in 0..2) {
            assertEquals(trueTranslation[axis], solution.translation[axis], 0.03f)
        }
        assertTrue(
            "error angular ${degreesBetween(trueRotation, solution.rotation)}",
            degreesBetween(trueRotation, solution.rotation) < 3f,
        )
    }

    @Test
    fun `three marks are enough for a full pose`() {
        val trueRotation = rotation(floatArrayOf(0f, 1f, 0f), -22f)
        val trueTranslation = floatArrayOf(-0.2f, 0.05f, -2.3f)
        val picks = plateCorners.take(3)
        val camera = floatArrayOf(0f, 0f, 0f)
        val sightings = picks.map { pick ->
            sighting(
                label = pick.label,
                cameraOrigin = camera,
                worldPoint = apply(trueRotation, trueTranslation, pick.modelPoint),
                depthErrorM = 0.02f,
            )
        }
        val solution = solved(
            ManualCorrespondenceSolver.solve(
                picks = picks,
                sightings = sightings,
                seedQuaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
                fallbackDistanceM = 2f,
                modelDiameterM = 0.9f,
            ),
        )
        assertFalse(solution.rollUndetermined)
        assertTrue(solution.worstLateralMm < 6f)
        assertTrue(degreesBetween(trueRotation, solution.rotation) < 4f)
    }

    @Test
    fun `one station with background depth readings is flagged instead of trusted`() {
        val trueRotation = rotation(floatArrayOf(0.2f, 1f, 0.1f), 25f)
        val trueTranslation = floatArrayOf(0.1f, -0.2f, -2.6f)
        val camera = floatArrayOf(0f, 0f, 0f)
        // Two of the four corner marks read the wall behind the part, as Depth does on a
        // silhouette.  The marks themselves are perfect.
        val depthErrors = listOf(0.01f, 1.4f, -0.02f, 2.1f)
        val sightings = beamFlange.mapIndexed { index, pick ->
            sighting(
                label = pick.label,
                cameraOrigin = camera,
                worldPoint = apply(trueRotation, trueTranslation, pick.modelPoint),
                depthErrorM = depthErrors[index],
            )
        }
        val solution = solved(
            ManualCorrespondenceSolver.solve(
                picks = beamFlange,
                sightings = sightings,
                seedQuaternionXyzw = quaternion(trueRotation),
                fallbackDistanceM = 2.5f,
                modelDiameterM = 2.42f,
                modelExtentPoints = beamExtents,
            ),
        )
        assertEquals(1, solution.stationCount)
        assertFalse(solution.multiView)
        assertTrue(CorrespondenceAdvice.ADD_VIEW in solution.advice)
        // The rays still hold the marks; only distance and tilt are open from one station.
        assertTrue("residuo ${solution.worstLateralMm} mm", solution.worstLateralMm < 12f)
    }

    @Test
    fun `a small single-view residual is never presented as a confirmed alignment`() {
        val trueRotation = rotation(floatArrayOf(0.2f, 1f, 0.1f), 25f)
        val translation = floatArrayOf(0.1f, -0.2f, -2.6f)
        val sightings = beamFlange.map { pick ->
            sighting(pick.label, floatArrayOf(0f, 0f, 0f),
                apply(trueRotation, translation, pick.modelPoint), depthErrorM = 0f)
        }
        val solution = solved(ManualCorrespondenceSolver.solve(beamFlange, sightings,
            quaternion(trueRotation), 2.5f, 2.42f, beamExtents))
        assertTrue(solution.worstLateralMm < 1f)
        assertEquals(1, solution.stationCount)
        assertEquals(CorrespondenceQuality.DOUBTFUL, solution.quality)
        assertTrue(CorrespondenceAdvice.ADD_VIEW in solution.advice)
    }

    @Test
    fun `two nearly identical photos still ask for lateral separation`() {
        val trueRotation = rotation(floatArrayOf(0.2f, 1f, 0.1f), 25f)
        val translation = floatArrayOf(0.1f, -0.2f, -2.6f)
        val cameras = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(0.04f, 0f, 0f))
        val sightings = cameras.flatMapIndexed { station, camera ->
            beamFlange.map { pick -> sighting(pick.label, camera,
                apply(trueRotation, translation, pick.modelPoint), depthErrorM = null, station = station + 1) }
        }
        val solution = solved(ManualCorrespondenceSolver.solve(beamFlange, sightings,
            quaternion(trueRotation), 2.5f, 2.42f, beamExtents))
        assertEquals(2, solution.stationCount)
        assertTrue(solution.parallaxDegrees < 5f)
        assertEquals(CorrespondenceQuality.DOUBTFUL, solution.quality)
        assertTrue(CorrespondenceAdvice.ADD_VIEW in solution.advice)
    }

    @Test
    fun `two stations recover the pose without any depth`() {
        val trueRotation = rotation(floatArrayOf(0.2f, 1f, 0.1f), 25f)
        val trueTranslation = floatArrayOf(0.1f, -0.2f, -2.6f)
        val stations = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(1.3f, 0.2f, -0.4f))
        val sightings = stations.flatMapIndexed { station, camera ->
            beamFlange.map { pick ->
                sighting(
                    label = pick.label,
                    cameraOrigin = camera,
                    worldPoint = apply(trueRotation, trueTranslation, pick.modelPoint),
                    depthErrorM = null,
                    station = station + 1,
                )
            }
        }
        val solution = solved(
            ManualCorrespondenceSolver.solve(
                picks = beamFlange,
                sightings = sightings,
                // A deliberately poor seed: the operator stands on another side than in 3D.
                seedQuaternionXyzw = quaternion(rotation(floatArrayOf(1f, 0f, 0f), 140f)),
                fallbackDistanceM = 1.2f,
                modelDiameterM = 2.42f,
                modelExtentPoints = beamExtents,
            ),
        )
        assertEquals(2, solution.stationCount)
        assertTrue(solution.multiView)
        assertTrue(solution.parallaxDegrees > 15f)
        assertEquals(CorrespondenceQuality.ACCEPTABLE, solution.quality)
        assertTrue(solution.advice.isEmpty())
        assertNull(solution.alternative)
        assertTrue(worstErrorMm(solution, trueRotation, trueTranslation, beamExtents) < 1.5f)
        assertEquals(solution.modelSpanMm, requireNotNull(solution.observedSpanMm), 2f)
    }

    @Test
    fun `two stations stay accurate with realistic mark noise and ignore bad depth`() {
        val trueRotation = rotation(floatArrayOf(0.2f, 1f, 0.1f), 25f)
        val trueTranslation = floatArrayOf(0.1f, -0.2f, -2.6f)
        val stations = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(1.3f, 0.2f, -0.4f))
        val random = Random(20260918L)
        var worst = 0f
        repeat(40) {
            val sightings = stations.flatMapIndexed { station, camera ->
                beamFlange.map { pick ->
                    sighting(
                        label = pick.label,
                        cameraOrigin = camera,
                        worldPoint = apply(trueRotation, trueTranslation, pick.modelPoint),
                        // Every other reading hits the background by more than a metre.
                        depthErrorM = if (random.nextBoolean()) 1.2f + random.nextFloat() else 0.02f,
                        station = station + 1,
                        pixelNoise = floatArrayOf(
                            (random.nextGaussian() * 4.0).toFloat(),
                            (random.nextGaussian() * 4.0).toFloat(),
                        ),
                    )
                }
            }
            val solution = solved(
                ManualCorrespondenceSolver.solve(
                    picks = beamFlange,
                    sightings = sightings,
                    seedQuaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
                    fallbackDistanceM = 2f,
                    modelDiameterM = 2.42f,
                    modelExtentPoints = beamExtents,
                ),
            )
            worst = maxOf(worst, worstErrorMm(solution, trueRotation, trueTranslation, beamExtents))
            assertTrue(
                "la precisión estimada ${solution.expectedErrorMm} mm debe ser realista",
                solution.expectedErrorMm in 3f..60f,
            )
        }
        assertTrue("peor error en los extremos de la pieza: $worst mm", worst < 45f)
    }

    @Test
    fun `expected error shrinks when a second station is added`() {
        val trueRotation = rotation(floatArrayOf(0f, 1f, 0f), 10f)
        val trueTranslation = floatArrayOf(0f, 0f, -3f)
        val first = beamFlange.map { pick ->
            sighting(pick.label, floatArrayOf(0f, 0f, 0f), apply(trueRotation, trueTranslation, pick.modelPoint), null)
        }
        val second = beamFlange.map { pick ->
            sighting(
                pick.label,
                floatArrayOf(1.6f, 0.3f, -0.8f),
                apply(trueRotation, trueTranslation, pick.modelPoint),
                null,
                station = 2,
            )
        }
        fun solve(sightings: List<CorrespondenceSighting>) = solved(
            ManualCorrespondenceSolver.solve(
                picks = beamFlange,
                sightings = sightings,
                seedQuaternionXyzw = quaternion(trueRotation),
                fallbackDistanceM = 3f,
                modelDiameterM = 2.42f,
                modelExtentPoints = beamExtents,
            ),
        )
        val single = solve(first)
        val double = solve(first + second)
        assertTrue(
            "una estación ${single.expectedErrorMm} mm, dos ${double.expectedErrorMm} mm",
            double.expectedErrorMm < single.expectedErrorMm / 3f,
        )
        assertTrue(double.expectedErrorMm < double.expectedErrorLimitMm)
    }

    @Test
    fun `a fronto parallel single station reports the mirrored tilt as an alternative`() {
        // Seen from far away and almost square on, a plate tilted +20° and -20° project alike.
        val trueRotation = rotation(floatArrayOf(0f, 1f, 0f), 20f)
        val trueTranslation = floatArrayOf(0f, 0f, -6f)
        val picks = listOf(
            CorrespondencePick(1, floatArrayOf(-0.3f, -0.2f, 0f)),
            CorrespondencePick(2, floatArrayOf(0.3f, -0.2f, 0f)),
            CorrespondencePick(3, floatArrayOf(0.3f, 0.2f, 0f)),
            CorrespondencePick(4, floatArrayOf(-0.3f, 0.2f, 0f)),
        )
        val sightings = picks.map { pick ->
            sighting(pick.label, floatArrayOf(0f, 0f, 0f), apply(trueRotation, trueTranslation, pick.modelPoint), null)
        }
        val solution = solved(
            ManualCorrespondenceSolver.solve(
                picks = picks,
                sightings = sightings,
                seedQuaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
                fallbackDistanceM = 6f,
                modelDiameterM = 0.75f,
            ),
        )
        assertTrue(CorrespondenceAdvice.TILT_AMBIGUOUS in solution.advice)
        val alternative = requireNotNull(solution.alternative)
        val tilts = listOf(solution.rotation, alternative.rotation)
        assertTrue(tilts.any { degreesBetween(it, trueRotation) < 4f })
        val swapped = requireNotNull(solution.withAlternativePose())
        assertTrue(degreesBetween(swapped.rotation, alternative.rotation) < 0.01f)
        assertNotNull(swapped.alternative)
    }

    @Test
    fun `two marks keep the operator roll and report it as undetermined`() {
        val trueRotation = rotation(floatArrayOf(0f, 1f, 0f), 15f)
        val trueTranslation = floatArrayOf(0f, 0f, -1.7f)
        val picks = plateCorners.take(2)
        val camera = floatArrayOf(0f, 0f, 0f)
        val sightings = picks.map { pick ->
            sighting(
                label = pick.label,
                cameraOrigin = camera,
                worldPoint = apply(trueRotation, trueTranslation, pick.modelPoint),
                depthErrorM = 0.01f,
            )
        }
        val solution = solved(
            ManualCorrespondenceSolver.solve(
                picks = picks,
                sightings = sightings,
                // The orientation of the 3D viewer supplies the unobservable roll.
                seedQuaternionXyzw = quaternion(trueRotation),
                fallbackDistanceM = 1.7f,
                modelDiameterM = 0.9f,
            ),
        )
        assertTrue(solution.rollUndetermined)
        assertTrue(CorrespondenceAdvice.ROLL_FREE in solution.advice)
        assertTrue("residuo ${solution.worstLateralMm} mm", solution.worstLateralMm < 6f)
        assertTrue(degreesBetween(trueRotation, solution.rotation) < 4f)
        assertTrue(solution.axisDirection != null && solution.axisOrigin != null)
    }

    @Test
    fun `rolling around the pick axis preserves every residual`() {
        val trueRotation = rotation(floatArrayOf(0f, 1f, 0f), 15f)
        val trueTranslation = floatArrayOf(0f, 0f, -1.7f)
        val picks = plateCorners.take(2)
        val camera = floatArrayOf(0f, 0f, 0f)
        val sightings = picks.map { pick ->
            sighting(pick.label, camera, apply(trueRotation, trueTranslation, pick.modelPoint), 0f)
        }
        val solution = solved(
            ManualCorrespondenceSolver.solve(
                picks = picks,
                sightings = sightings,
                seedQuaternionXyzw = quaternion(trueRotation),
                fallbackDistanceM = 1.7f,
                modelDiameterM = 0.9f,
            ),
        )
        val rolled = requireNotNull(solution.rolled(90f))
        assertTrue(degreesBetween(solution.rotation, rolled.rotation) > 80f)
        picks.forEachIndexed { index, pick ->
            val before = requireNotNull(
                ManualCorrespondenceSolver.residualOf(solution, pick, sightings[index]),
            )
            val after = requireNotNull(
                ManualCorrespondenceSolver.residualOf(rolled, pick, sightings[index]),
            )
            assertTrue(abs(before - after) < 1e-2f)
        }
    }

    @Test
    fun `three collinear marks report an undetermined roll`() {
        val trueRotation = rotation(floatArrayOf(0f, 0f, 1f), 8f)
        val trueTranslation = floatArrayOf(0f, 0f, -2f)
        val picks = listOf(
            CorrespondencePick(1, floatArrayOf(-0.5f, 0f, 0f)),
            CorrespondencePick(2, floatArrayOf(0f, 0f, 0f)),
            CorrespondencePick(3, floatArrayOf(0.5f, 0f, 0f)),
        )
        val camera = floatArrayOf(0f, 0f, 0f)
        val sightings = picks.map { pick ->
            sighting(pick.label, camera, apply(trueRotation, trueTranslation, pick.modelPoint), 0f)
        }
        val solution = solved(
            ManualCorrespondenceSolver.solve(
                picks = picks,
                sightings = sightings,
                seedQuaternionXyzw = quaternion(trueRotation),
                fallbackDistanceM = 2f,
                modelDiameterM = 1f,
            ),
        )
        assertTrue(solution.rollUndetermined)
    }

    @Test
    fun `end plate corners of a long beam resolve the roll from the marks`() {
        // 8 m beam, four corners of the 30 cm end plates: 3.7 % of the span off the diagonal, which
        // the relative rule alone called collinear, so the roll came from the viewer seed instead.
        val picks = listOf(
            CorrespondencePick(1, floatArrayOf(-4f, -0.15f, 0.15f)),
            CorrespondencePick(2, floatArrayOf(-4f, 0.15f, 0.15f)),
            CorrespondencePick(3, floatArrayOf(4f, 0.15f, 0.15f)),
            CorrespondencePick(4, floatArrayOf(4f, -0.15f, 0.15f)),
        )
        val extents = listOf(-4f, 4f).flatMap { x ->
            listOf(-0.15f, 0.15f).flatMap { y -> listOf(-0.15f, 0.15f).map { z -> floatArrayOf(x, y, z) } }
        }
        val trueRotation = rotation(floatArrayOf(0.2f, 1f, 0.1f), 20f)
        val trueTranslation = floatArrayOf(0f, -0.3f, -6f)
        val stations = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(3.5f, 0.3f, -0.3f))
        val sightings = stations.flatMapIndexed { station, camera ->
            picks.map { pick ->
                sighting(pick.label, camera, apply(trueRotation, trueTranslation, pick.modelPoint), null, station + 1)
            }
        }
        val solution = solved(
            ManualCorrespondenceSolver.solve(
                picks = picks,
                sightings = sightings,
                // The viewer showed the beam rolled by a right angle: the marks must win.
                seedQuaternionXyzw = quaternion(rotation(floatArrayOf(1f, 0f, 0f), 90f)),
                fallbackDistanceM = 6f,
                modelDiameterM = 8.03f,
                modelExtentPoints = extents,
            ),
        )
        assertFalse(solution.rollUndetermined)
        assertFalse(CorrespondenceAdvice.ROLL_FREE in solution.advice)
        assertTrue(solution.multiView)
        assertEquals(CorrespondenceQuality.ACCEPTABLE, solution.quality)
        assertTrue(worstErrorMm(solution, trueRotation, trueTranslation, extents) < 3f)
    }

    @Test
    fun `cad points on a long part are judged by an absolute distance`() {
        // Two corners of one end plate, 60 mm apart, on a 10 m part: 2 % of its diagonal would
        // have demanded 200 mm.
        val picks = listOf(
            CorrespondencePick(1, floatArrayOf(-5f, 0f, 0f)),
            CorrespondencePick(2, floatArrayOf(-5f, 0.06f, 0f)),
        )
        val camera = floatArrayOf(0f, 0f, 0f)
        val sightings = picks.map { pick ->
            sighting(pick.label, camera, floatArrayOf(pick.modelPoint[0] + 5f, pick.modelPoint[1], -3f), 0f)
        }
        val outcome = ManualCorrespondenceSolver.solve(
            picks = picks,
            sightings = sightings,
            seedQuaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
            fallbackDistanceM = 3f,
            modelDiameterM = 10f,
        )
        assertTrue(outcome is CorrespondenceOutcome.Solved)
    }

    @Test
    fun `marks in the wrong order are reported as a bad fit`() {
        val trueRotation = rotation(floatArrayOf(0f, 1f, 0f), 12f)
        val trueTranslation = floatArrayOf(0f, 0f, -2f)
        val worldPoints = plateCorners.map { apply(trueRotation, trueTranslation, it.modelPoint) }
        // P1 and P2 marked the other way round, the classic workshop mistake, from two stations.
        val swapped = listOf(worldPoints[1], worldPoints[0], worldPoints[2], worldPoints[3])
        val stations = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(0.9f, 0.1f, -0.2f))
        val sightings = stations.flatMapIndexed { station, camera ->
            plateCorners.mapIndexed { index, pick ->
                sighting(pick.label, camera, swapped[index], 0f, station = station + 1)
            }
        }
        val solution = solved(
            ManualCorrespondenceSolver.solve(
                picks = plateCorners,
                sightings = sightings,
                seedQuaternionXyzw = quaternion(trueRotation),
                fallbackDistanceM = 2f,
                modelDiameterM = 0.9f,
            ),
        )
        assertEquals(CorrespondenceQuality.BAD, solution.quality)
        assertTrue(CorrespondenceAdvice.CHECK_MARKS in solution.advice)
        assertTrue(solution.worstLateralMm > 50f)
    }

    @Test
    fun `a landmark skipped at the second station still solves`() {
        val trueRotation = rotation(floatArrayOf(0.1f, 1f, 0f), -30f)
        val trueTranslation = floatArrayOf(0.2f, 0f, -2.2f)
        val first = plateCorners.map { pick ->
            sighting(pick.label, floatArrayOf(0f, 0f, 0f), apply(trueRotation, trueTranslation, pick.modelPoint), null)
        }
        val second = plateCorners.filter { it.label != 3 }.map { pick ->
            sighting(
                pick.label,
                floatArrayOf(-1.1f, 0.2f, -0.5f),
                apply(trueRotation, trueTranslation, pick.modelPoint),
                null,
                station = 2,
            )
        }
        val solution = solved(
            ManualCorrespondenceSolver.solve(
                picks = plateCorners,
                sightings = first + second,
                seedQuaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
                fallbackDistanceM = 2f,
                modelDiameterM = 0.9f,
            ),
        )
        assertTrue(solution.multiView)
        assertEquals(7, solution.residuals.size)
        assertTrue(worstErrorMm(solution, trueRotation, trueTranslation, plateCorners.map { it.modelPoint }) < 1.5f)
    }

    @Test
    fun `marks on the same pixel of one station are refused but not across stations`() {
        val picks = plateCorners.take(2)
        val samePixel = picks.map { pick ->
            sighting(pick.label, floatArrayOf(0f, 0f, 0f), floatArrayOf(0.1f, 0f, -2f), 0f)
        }
        assertTrue(
            ManualCorrespondenceSolver.solve(
                picks = picks,
                sightings = samePixel,
                seedQuaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
                fallbackDistanceM = 2f,
                modelDiameterM = 0.9f,
            ) is CorrespondenceOutcome.Rejected,
        )
    }

    @Test
    fun `cad points too close together are refused`() {
        val picks = listOf(
            CorrespondencePick(1, floatArrayOf(0f, 0f, 0f)),
            CorrespondencePick(2, floatArrayOf(0.004f, 0f, 0f)),
        )
        val camera = floatArrayOf(0f, 0f, 0f)
        val sightings = picks.map { pick ->
            sighting(pick.label, camera, floatArrayOf(pick.modelPoint[0], 0f, -1.5f), 0f)
        }
        val outcome = ManualCorrespondenceSolver.solve(
            picks = picks,
            sightings = sightings,
            seedQuaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
            fallbackDistanceM = 1.5f,
            modelDiameterM = 0.9f,
        )
        assertTrue(outcome is CorrespondenceOutcome.Rejected)
    }

    @Test
    fun `a single mark is not enough`() {
        val picks = plateCorners.take(1)
        val sightings = listOf(
            sighting(1, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, -1.5f), 0f),
        )
        val outcome = ManualCorrespondenceSolver.solve(
            picks = picks,
            sightings = sightings,
            seedQuaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
            fallbackDistanceM = 1.5f,
            modelDiameterM = 0.9f,
        )
        assertTrue(outcome is CorrespondenceOutcome.Rejected)
    }

    @Test
    fun `parallax is the angle between two standing positions seen from the landmark`() {
        val degrees = ManualCorrespondenceSolver.parallaxDegrees(
            point = floatArrayOf(0f, 0f, -2f),
            markedFrom = floatArrayOf(0f, 0f, 0f),
            cameraNow = floatArrayOf(2f, 0f, -2f),
        )
        assertEquals(90f, degrees, 0.01f)
    }

    @Test
    fun `the viewer seed shows the model to the AR camera as the 3D viewer showed it`() {
        val viewer = quaternion(rotation(floatArrayOf(0f, 1f, 0f), 40f))
        val arCamera = quaternion(rotation(floatArrayOf(1f, 0f, 0f), -25f))
        val seed = AlignmentSeed.modelRotationFromViewerCamera(arCamera, viewer)
        // Identical cameras leave the model untouched.
        val same = AlignmentSeed.modelRotationFromViewerCamera(viewer, viewer)
        assertEquals(1f, abs(same[3]), 1e-5f)
        assertEquals(1f, sqrt(seed.fold(0f) { sum, value -> sum + value * value }), 1e-5f)
    }

    // The two long flange edges, drawn by dragging P2 out of P1 and P3 out of P4.
    private val flangeEdges = listOf(CorrespondenceLink(1, 2), CorrespondenceLink(3, 4))
    private val lineRotation = rotation(floatArrayOf(0.2f, 1f, 0.1f), 25f)
    private val lineTranslation = floatArrayOf(0.1f, -0.2f, -2.6f)
    private val lineStations = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(1.3f, 0.2f, -0.4f))

    private fun solveBeam(sightings: List<CorrespondenceSighting>, lines: List<CorrespondenceLink>) = solved(
        ManualCorrespondenceSolver.solve(
            picks = beamFlange,
            sightings = sightings,
            seedQuaternionXyzw = floatArrayOf(0f, 0f, 0f, 1f),
            fallbackDistanceM = 2f,
            modelDiameterM = 2.42f,
            modelExtentPoints = beamExtents,
            lines = lines,
        ),
    )

    @Test
    fun `lines leave an exact fit and its accuracy estimate unchanged`() {
        val sightings = lineStations.flatMapIndexed { station, camera ->
            beamFlange.map { pick ->
                sighting(pick.label, camera, apply(lineRotation, lineTranslation, pick.modelPoint), null, station + 1)
            }
        }
        val points = solveBeam(sightings, emptyList())
        val lines = solveBeam(sightings, flangeEdges)
        assertTrue(worstErrorMm(lines, lineRotation, lineTranslation, beamExtents) < 1.5f)
        assertTrue(degreesBetween(points.rotation, lines.rotation) < 0.01f)
        // The same marks are not counted twice as accuracy.
        assertEquals(points.expectedErrorMm, lines.expectedErrorMm, 0.01f)
        assertEquals(points.quality, lines.quality)
        assertEquals(4, lines.lineResiduals.size)
        assertTrue(lines.lineResiduals.all { it.acrossMm < 0.1f && it.acrossPx < 0.1f })
        assertTrue(points.lineResiduals.isEmpty())
    }

    @Test
    fun `a traced edge keeps its direction when a mark lands further along it`() {
        // The real beam end lies 20 mm beyond the CAD corner, or the operator marked that far
        // along the edge: the error runs along the traced edge P1-P2 in both views.
        val along = apply(lineRotation, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f))
        val sightings = lineStations.flatMapIndexed { station, camera ->
            beamFlange.map { pick ->
                val world = apply(lineRotation, lineTranslation, pick.modelPoint)
                val marked = if (pick.label == 2) FloatArray(3) { world[it] + along[it] * 0.02f } else world
                sighting(pick.label, camera, marked, null, station + 1)
            }
        }
        val points = solveBeam(sightings, emptyList())
        val lines = solveBeam(sightings, flangeEdges)
        val withoutLines = degreesBetween(lineRotation, points.rotation)
        val withLines = degreesBetween(lineRotation, lines.rotation)
        assertTrue("orientación con líneas $withLines° frente a $withoutLines°", withLines < withoutLines * 0.8f)
        // The drawn line itself stays on the edge: the offset lies along it, not across.
        assertTrue(lines.lineResiduals.all { it.acrossMm < 3f })
    }

    @Test
    fun `a line with an end skipped from a view is not used in that view`() {
        val sightings = lineStations.flatMapIndexed { station, camera ->
            beamFlange.filter { station == 0 || it.label != 2 }.map { pick ->
                sighting(pick.label, camera, apply(lineRotation, lineTranslation, pick.modelPoint), null, station + 1)
            }
        }
        val solution = solveBeam(sightings, flangeEdges + CorrespondenceLink(1, 2))
        assertEquals(
            listOf(CorrespondenceLink(1, 2) to 1, CorrespondenceLink(3, 4) to 1, CorrespondenceLink(3, 4) to 2),
            solution.lineResiduals.map { it.link to it.station },
        )
    }

    @Test
    fun `lines between points outside the fit are ignored`() {
        val sightings = beamFlange.take(3).map { pick ->
            sighting(pick.label, floatArrayOf(0f, 0f, 0f), apply(lineRotation, lineTranslation, pick.modelPoint), 0f)
        }
        // P4 was chosen and joined in 3D but never marked.
        val solution = solveBeam(sightings, listOf(CorrespondenceLink(3, 4), CorrespondenceLink(1, 3)))
        assertEquals(listOf(CorrespondenceLink(1, 3)), solution.lineResiduals.map { it.link })
    }
}
