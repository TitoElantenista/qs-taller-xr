package com.qs.tallerxr.ar

import com.google.ar.core.Pose
import com.qs.tallerxr.ar.SnapTestScene.degrees
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The floor snap against rendered camera images of the TV bench demo on a plank floor, and, when
 * `QS_SNAP_PHOTO` names it, against a real photo of such a bench (not in the repository).
 */
class FloorSnapTest {
    private val bench get() = SnapTestScene.bench
    private val truth = FloorPose(0.08f, -0.05f, degrees(10f))
    private val target = floatArrayOf(0f, 0.3f, 0f)

    // Standing like the operator did: a little left of the bench, eyes 1.35 m up, 1.9 m in front.
    private val front = SnapTestScene.lookAt(floatArrayOf(-0.35f, 1.35f, 1.9f), target)
    private val side = SnapTestScene.lookAt(floatArrayOf(0.35f, 1.3f, 1.95f), target)

    private fun session(aim: FloorPose) = FloorSnapSession(bench.model, LandmarkVisibility(bench.triangles), 0f, aim)

    private fun view(camera: Pose, seed: Long, pose: FloorPose? = truth) =
        SnapView(SnapTestScene.render(camera, pose, seed), SnapTestScene.geometry(camera))

    /** Largest horizontal miss of a bottom corner plus the height error. */
    private fun errorM(pose: FloorPose) = bench.model.separationM(pose, truth) + abs(pose.lift)

    private fun aim(dx: Float, dz: Float, degrees: Float) =
        FloorPose(truth.x + dx, truth.z + dz, truth.yawRadians + degrees(degrees))

    @Test
    fun `the bench is found on a plank floor from aims far off`() {
        val view = view(front, seed = 1)
        for ((offset, turn) in listOf(
            floatArrayOf(0.25f, 0.1f) to -15f,
            floatArrayOf(-0.3f, -0.2f) to 18f,
            floatArrayOf(0.05f, 0.28f) to 8f,
            floatArrayOf(-0.12f, 0f) to -20f,
        )) {
            val snap = session(aim(offset[0], offset[1], turn))
            val state = snap.process(view, 0L)
            val best = state.candidates.first()
            println("render aim ${offset.toList()} $turn°: ${state.candidates.joinToString { "${it.pose} ${it.support}" }} " +
                "(ms: ${snap.lastTimings.toList()})")
            assertTrue("aim ${offset.toList()} $turn°: best ${best.pose} (${best.support}), error ${errorM(best.pose)} m",
                errorM(best.pose) < 0.015f)
            val quality = requireNotNull(snapQuality(state, null))
            assertTrue("$quality", quality.support >= SnapCriteria.GOOD_SUPPORT)
            // One view is never «good», however well it fits.
            assertEquals(SnapLevel.WEAK, quality.level)
        }
    }

    @Test
    fun `a second view from the side makes the pose good`() {
        val snap = session(aim(0.2f, -0.15f, 12f))
        val first = snap.process(view(front, seed = 2), 0L)
        assertTrue(errorM(first.candidates.first().pose) < 0.015f)
        assertTrue(snap.wantsView(SnapTestScene.geometry(side), 200L))
        // The same direction again adds nothing.
        assertTrue(!snap.wantsView(SnapTestScene.geometry(front), 200L))
        val second = snap.process(view(side, seed = 3), 400L)
        val quality = requireNotNull(snapQuality(second, null))
        assertTrue("${second.candidates}", errorM(second.candidates.first().pose) < 0.015f)
        assertEquals("$quality", SnapLevel.GOOD, quality.level)
        assertEquals(2, quality.views)
    }

    @Test
    fun `an empty floor gives no hold`() {
        val snap = session(aim(0.1f, 0.1f, 5f))
        val state = snap.process(view(front, seed = 4, pose = null), 0L)
        val quality = requireNotNull(snapQuality(state, null))
        assertEquals("$quality", SnapLevel.NO_HOLD, quality.level)
        // Without a hold a fresh frame is tried again after a second, whatever the direction.
        assertTrue(!snap.wantsView(SnapTestScene.geometry(front), 500L))
        assertTrue(snap.wantsView(SnapTestScene.geometry(front), 1_100L))
    }

    @Test
    fun `an aim one drawer bay aside still ranks the bench first`() {
        val view = view(front, seed = 5)
        for (bay in listOf(0.45f, -0.45f)) {
            val state = session(aim(bay, 0f, 0f)).process(view, 0L)
            val best = state.candidates.first()
            assertTrue("aim $bay m aside: best ${best.pose} (${best.support}), others " +
                state.candidates.drop(1).joinToString { "${it.pose} ${it.support}" }, errorM(best.pose) < 0.015f)
        }
    }

    @Test
    fun `quality names the lead and the other candidates can be chosen`() {
        fun candidate(id: Int, support: Float) = SnapCandidate(id, FloorPose(id * 0.5f, 0f, 0f), support, listOf(support, support))
        val clear = SnapState(listOf(candidate(1, 0.66f), candidate(2, 0.56f), candidate(3, 0.5f)), 2, 15f)
        val quality = requireNotNull(snapQuality(clear, null))
        assertEquals(SnapLevel.GOOD, quality.level)
        assertEquals(0, quality.similar)
        // The third is more than 0.12 behind: not worth offering.
        assertEquals(2, quality.plausible)
        // The second one may be looked at, but it is never «good».
        val second = requireNotNull(snapQuality(clear, 2))
        assertEquals(2, second.rank)
        assertEquals(SnapLevel.WEAK, second.level)
        assertEquals(2, nextSnapCandidate(clear, 1)?.id)
        assertEquals(1, nextSnapCandidate(clear, 2)?.id)
        // Two poses nearly alike: not good even from two views.
        val alike = SnapState(listOf(candidate(1, 0.64f), candidate(2, 0.61f)), 2, 15f)
        val ambiguous = requireNotNull(snapQuality(alike, null))
        assertEquals(SnapLevel.WEAK, ambiguous.level)
        assertEquals(1, ambiguous.similar)
        assertNull(nextSnapCandidate(SnapState(listOf(candidate(1, 0.6f), candidate(2, 0.2f)), 1, 0f), 1))
        assertNull(snapQuality(SnapState(emptyList(), 0, 0f), null))
    }

    @Test
    fun `edges are kept per direction`() {
        // A vertical step: dark left, light right.  Its gradient points along +x.
        val width = 40
        val height = 30
        val grey = FloatArray(width * height) { if (it % width < 20) 60f else 180f }
        val level = SnapEdgeLevel.build(grey, width, height, 1f)
        val across = SnapEdgeLevel.bin(0f)
        val along = SnapEdgeLevel.bin((Math.PI / 2).toFloat())
        assertTrue(level.distance(across, 19, 15) <= 1 || level.distance(across, 20, 15) <= 1)
        assertTrue(level.distance(across, 30, 15) in 9..11)
        // A CAD edge running across the step finds nothing to lean on.
        assertEquals(255, level.distance(along, 20, 15))
        assertEquals(level.distance(across, 5, 15), level.distance(SnapEdgeLevel.bin(Math.PI.toFloat()), 5, 15))
    }

    @Test
    fun `the luminance image follows the rotation and crop of the viewport`() {
        // A 4x3 camera image shown rotated by 90 degrees in a 3x4 portrait viewport.
        val grey = ByteArray(12) { (it * 10).toByte() }
        val image = LuminanceImage(4, 3, grey, 4, 1, floatArrayOf(0f, 3f, 0f, 0f, 4f, 3f), 3, 4)
        // Viewport (x, y) is image column y, row 3 - x.
        assertEquals(50f, image.at(2f, 1f), 0.01f)
        assertEquals(65f, image.at(1.5f, 0.5f), 0.01f)
        assertTrue(image.at(-1f, 0f).isNaN())
    }

    @Test
    fun `the aim turns the model only about the vertical`() {
        // Model seen from the front and 30° from above: its +Z points back at the camera, tilted.
        val tilted = Pose(floatArrayOf(0f, 0f, -2f), floatArrayOf(0.2588190f, 0f, 0f, 0.9659258f))
        assertEquals(0f, uprightYaw(tilted), 1e-5f)
        val turned = Pose(floatArrayOf(0f, 0f, -2f), floatArrayOf(0f, 0.3826834f, 0f, 0.9238795f))
        assertEquals(45f, Math.toDegrees(uprightYaw(turned).toDouble()).toFloat(), 1e-3f)
        // A ray down to a floor 1.4 m below, and one that never reaches it.
        val hit = requireNotNull(horizontalHit(floatArrayOf(0f, 1.4f, 0f), floatArrayOf(0f, -0.6f, -0.8f), 0f))
        assertEquals(-1.8667f, hit[2], 1e-3f)
        assertNull(horizontalHit(floatArrayOf(0f, 1.4f, 0f), floatArrayOf(0f, 0.6f, -0.8f), 0f))
        // The standing pose keeps the bottom of the box on the floor, whatever the turn.
        val standing = bench.model.referenceFromModel(FloorPose(0.3f, -0.2f, degrees(33f)), 0.1f)
        val bottom = standing.transformPoint(bench.model.foot)
        assertEquals(0.1f, bottom[1], 1e-5f)
        assertEquals(0.3f, bottom[0], 1e-5f)
        assertEquals(-0.2f, bottom[2], 1e-5f)
    }

    @Test
    fun `the part stands on the floor unless a larger table lies under the aim`() {
        fun square(cx: Float, cz: Float, hx: Float, hz: Float) =
            floatArrayOf(cx - hx, cz - hz, cx + hx, cz - hz, cx + hx, cz + hz, cx - hx, cz + hz)
        val eye = floatArrayOf(0f, 1.5f, 0f)
        fun towards(x: Float, y: Float, z: Float) = SnapTestScene.normalized(floatArrayOf(x - eye[0], y - eye[1], z - eye[2]))
        val floor = SupportPlane(0f, square(0f, -2f, 3f, 3f))
        val table = SupportPlane(0.75f, square(0f, -2f, 0.6f, 0.4f))
        // The top of a bench is found as a surface too, but it is no larger than the bench.
        val benchTop = SupportPlane(0.567f, square(1.5f, -2f, 0.74f, 0.23f))
        val planes = listOf(benchTop, floor, table)
        // A 20 x 20 cm part whose centre is 10 cm above its bottom, aimed at on the table.
        val onTable = requireNotNull(supportHit(planes, eye, towards(0f, 0.85f, -2f), 0.1f, 0.04f))
        assertEquals(0.75f, onTable.floorY, 1e-6f)
        assertEquals(-2f, onTable.z, 1e-4f)
        // Aimed beside the table it stands on the floor.
        assertEquals(0f, requireNotNull(supportHit(planes, eye, towards(-1.5f, 0.1f, -2f), 0.1f, 0.04f)).floorY, 1e-6f)
        // The bench itself, aimed at its centre: it stands on the floor, not on its own top.
        val bench = requireNotNull(supportHit(planes, eye, towards(1.5f, 0.2835f, -2f), 0.2835f, 0.687f))
        assertEquals(0f, bench.floorY, 1e-6f)
        assertEquals(1.5f, bench.x, 1e-3f)
        // Nothing found yet, or the camera looking up: no place to stand.
        assertNull(supportHit(emptyList(), eye, towards(0f, 0f, -2f), 0.1f, 0.04f))
        assertNull(supportHit(planes, eye, towards(0f, 2f, -2f), 0.1f, 0.04f))
    }

    @Test
    fun `a support plane found two centimetres too high still gives the pose`() {
        // ARCore's floor may be off by a centimetre or two; the fit may lift or lower the part.
        val snap = FloorSnapSession(bench.model, LandmarkVisibility(bench.triangles), 0.02f, aim(0.15f, -0.1f, 10f))
        val best = snap.process(view(front, seed = 6), 0L).candidates.first()
        val placed = snap.referenceFromModel(best.pose).transformPoint(bench.model.foot)
        assertTrue("$best, bottom at ${placed[1]}", bench.model.separationM(best.pose.copy(lift = 0f), truth) < 0.02f)
        assertEquals("$best", 0f, placed[1], 0.015f)
        assertTrue("$best", best.support >= SnapCriteria.GOOD_SUPPORT)
    }

    /**
     * The operator's photo of the real bench (Galaxy S25 Ultra, 23 mm equivalent), turned upright
     * and halved to 1126 x 2000 like an ARCore frame, as a binary PGM.  The reference pose comes from
     * seven leg corners and knobs marked by hand; it is good to about a centimetre.
     * Run with QS_SNAP_PHOTO=/path/photo.pgm.
     */
    @Test
    fun `the operator's photo of the bench`() {
        val path = System.getenv("QS_SNAP_PHOTO").orEmpty()
        assumeTrue("QS_SNAP_PHOTO not set", path.isNotEmpty() && File(path).isFile)
        val bytes = File(path).readBytes()
        val header = String(bytes, 0, 64, Charsets.US_ASCII).split(Regex("\\s+"))
        require(header[0] == "P5" && header[3] == "255")
        val width = header[1].toInt()
        val height = header[2].toInt()
        val grey = bytes.copyOfRange(bytes.size - width * height, bytes.size)
        val focal = 1300f
        // Camera from model (GL axes) at the hand-marked pose; the model frame is the world here.
        val r = floatArrayOf(
            0.97023239f, -0.00558226f, 0.24211145f,
            0.13681008f, 0.83756011f, -0.52893861f,
            -0.19983022f, 0.54631666f, 0.81339166f,
        )
        val t = floatArrayOf(-0.02438609f, 0.11250976f, -2.14443212f)
        val transposed = floatArrayOf(r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8])
        val centre = FloatArray(3) { row -> -(r[row] * t[0] + r[3 + row] * t[1] + r[6 + row] * t[2]) }
        val camera = Pose(centre, SnapTestScene.quaternion(transposed))
        val geometry = SnapTestScene.geometry(camera, width, height, focal)
        val view = SnapView(LuminanceImage.upright(width, height, grey), geometry)
        val floor = bench.registration.boundsMin[1]
        val photoTruth = FloorPose(0f, 0f, 0f)
        for ((offset, turn) in listOf(
            floatArrayOf(0.2f, 0.1f) to -14f,
            floatArrayOf(-0.33f, -0.21f) to 17f,
            floatArrayOf(0.45f, 0f) to 0f,
        )) {
            val snap = FloorSnapSession(bench.model, LandmarkVisibility(bench.triangles), floor,
                FloorPose(offset[0], offset[1], degrees(turn)))
            val state = snap.process(view, 0L)
            val best = state.candidates.first()
            println("photo aim ${offset.toList()} $turn°: ${state.candidates.joinToString { "${it.pose} ${it.support}" }} " +
                "(ms: ${snap.lastTimings.toList()})")
            // Across the view (x) and in the turn the reference is good; along the view it is not:
            // the focal length is known to about 2 %, i.e. ±4 cm of distance at 2.1 m.
            val message = "aim ${offset.toList()} $turn°: best ${best.pose} (${best.support})"
            assertTrue(message, abs(best.pose.x - photoTruth.x) < 0.015f)
            assertTrue(message, abs(Math.toDegrees(best.pose.yawRadians.toDouble())) < 1.0)
            assertTrue(message, bench.model.separationM(best.pose, photoTruth) + abs(best.pose.lift) < 0.05f)
            // The pose one drawer bay aside is next, clearly behind; from one view it may still be
            // reported as a similar pose (margin under GOOD_MARGIN): a second view has to decide.
            assertTrue(message, requireNotNull(snapQuality(state, null)).margin > 0.05f)
        }
    }
}
