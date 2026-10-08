package com.qs.tallerxr.ui

import com.google.ar.core.Pose
import com.qs.tallerxr.ar.AlignmentMarking
import com.qs.tallerxr.ar.CorrespondenceOutcome
import com.qs.tallerxr.ar.CorrespondencePick
import com.qs.tallerxr.ar.CorrespondenceSighting
import com.qs.tallerxr.ar.ManualCorrespondenceSolver
import com.qs.tallerxr.ar.SnapLevel
import com.qs.tallerxr.ar.SnapQuality
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArMarkingControlsTest {
    private fun sighting(label: Int, x: Float) = CorrespondenceSighting(
        label, 1, floatArrayOf(0f, 0f, 0f), floatArrayOf(x, 0f, -1f), null, 1_500f,
    )

    @Test
    fun `chips follow the state of each point in the round`() {
        val marking = AlignmentMarking(listOf(1, 2, 3, 4))
            .marked(sighting(1, 0f))
            .skipped()
            .selected(4)
        assertEquals(ChipTone.MARKED, chipTone(marking.stateOf(1)))
        assertEquals(ChipTone.SKIPPED, chipTone(marking.stateOf(2)))
        assertEquals(ChipTone.PENDING, chipTone(marking.stateOf(3)))
        assertEquals(ChipTone.CURRENT, chipTone(marking.stateOf(4)))
        assertTrue(pointChipDescription(marking, 4).startsWith("P4 als Nächstes zu markieren"))
        assertTrue(pointChipDescription(marking.selected(1), 1).startsWith("P1 wird neu gesetzt"))
    }

    @Test
    fun `review chips colour each point by its deviation against the tolerance`() {
        val picks = listOf(
            CorrespondencePick(1, floatArrayOf(-0.4f, -0.12f, 0f)),
            CorrespondencePick(2, floatArrayOf(0.4f, -0.12f, 0f)),
            CorrespondencePick(3, floatArrayOf(0.4f, 0.12f, 0.06f)),
            CorrespondencePick(4, floatArrayOf(-0.4f, 0.1f, -0.05f)),
        )
        val camera = floatArrayOf(0f, 0f, 0f)
        val sightings = picks.map { pick ->
            val world = floatArrayOf(pick.modelPoint[0], pick.modelPoint[1], pick.modelPoint[2] - 2f)
            // P3 is marked 60 px off; the others exactly.
            val shift = if (pick.label == 3) 60f / 1500f * 2f else 0f
            val direction = floatArrayOf(world[0] + shift, world[1], world[2])
            val length = sqrt(direction.fold(0f) { sum, value -> sum + value * value })
            CorrespondenceSighting(pick.label, 1, camera, FloatArray(3) { direction[it] / length }, 2f, 1_500f)
        }
        val solution = (ManualCorrespondenceSolver.solve(picks, sightings, floatArrayOf(0f, 0f, 0f, 1f), 2f, 0.9f)
            as CorrespondenceOutcome.Solved).solution
        assertEquals(ChipTone.BAD, residualTone(solution, 3, 1))
        assertEquals(ChipTone.MISSING, residualTone(solution, 3, 2))
        assertEquals(ChipTone.MISSING, residualTone(null, 1, 1))
        assertTrue(solution.residuals.first { it.label == 3 }.lateralMm > solution.toleranceMm)
    }

    @Test
    fun `the snap aim shows the model as large and centred as the 3D viewer did`() {
        // Viewer 3 m in front of the model, AR camera with half the field of view (twice the scale).
        val viewer = SnapViewerCamera(floatArrayOf(0f, 0f, 3f), floatArrayOf(0f, 0f, 0f, 1f), projectionY = 1.5f)
        val aim = viewer.cameraFromModel(arProjectionY = 3f, distanceFactor = 1f)
        assertEquals(-6f, aim.translation[2], 1e-5f)
        // Same size on screen: projection scale divided by distance stays equal.
        assertEquals(1.5f / 3f, 3f / 6f, 1e-6f)
        // A pinch that doubles the distance halves the model on screen.
        assertEquals(-12f, viewer.cameraFromModel(3f, 2f).translation[2], 1e-5f)
        // The turn is the viewer's own: a model point to its right stays to the right.
        val turned = SnapViewerCamera(floatArrayOf(3f, 0f, 0f), floatArrayOf(0f, 0.7071068f, 0f, 0.7071068f), 1.5f)
        val point = turned.cameraFromModel(1.5f, 1f).transformPoint(floatArrayOf(0f, 0f, -1f))
        assertEquals(-3f, point[2], 1e-4f)
        assertEquals(1f, point[0], 1e-4f)
        assertTrue(Pose.IDENTITY.translation.all { it == 0f })
    }

    @Test
    fun `the snap status names support, ambiguity and views`() {
        assertEquals("Magnet sucht die Lage…", snapQualityText(null))
        assertEquals("Kein Halt · beste Lage 31 % Kanten",
            snapQualityText(SnapQuality(0.31f, 0.31f, 0.2f, 0, 0, 1, 1, 0f, SnapLevel.NO_HOLD)))
        assertEquals("64 % Kanten · eindeutig · 2 Ansichten · 14°",
            snapQualityText(SnapQuality(0.64f, 0.6f, 0.1f, 0, 2, 1, 2, 14.2f, SnapLevel.GOOD)))
        assertEquals("58 % Kanten · 2 ähnliche Lagen · 1 Ansicht",
            snapQualityText(SnapQuality(0.58f, 0.58f, 0.02f, 1, 2, 1, 1, 0f, SnapLevel.WEAK)))
        assertEquals("Lage 2 von 2 · 55 % Kanten · 1 Ansicht",
            snapQualityText(SnapQuality(0.55f, 0.55f, -0.03f, 1, 2, 2, 1, 0f, SnapLevel.WEAK)))
    }
}
