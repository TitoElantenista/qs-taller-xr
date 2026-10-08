package com.qs.tallerxr.ar

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLandmarksTest {

    private fun boxModel(featureEdges: FloatArray): MarkerlessRegistrationModel {
        val points = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val steps = 6
        for (x in 0..steps) {
            for (y in 0..steps) {
                for (z in 0..steps) {
                    val onSurface = x == 0 || y == 0 || z == 0 ||
                        x == steps || y == steps || z == steps
                    if (!onSurface) continue
                    points += floatArrayOf(
                        -0.3f + 0.6f * x / steps,
                        -0.1f + 0.2f * y / steps,
                        -0.05f + 0.1f * z / steps,
                    ).toList()
                    normals += listOf(0f, 1f, 0f)
                }
            }
        }
        return MarkerlessRegistrationModel(
            points = points.toFloatArray(),
            normals = normals.toFloatArray(),
            featureEdges = featureEdges,
            boundsMin = floatArrayOf(-0.3f, -0.1f, -0.05f),
            boundsMax = floatArrayOf(0.3f, 0.1f, 0.05f),
            sampleSpacingM = 0.01f,
        )
    }

    @Test
    fun `edge endpoints become snap targets`() {
        val edge = floatArrayOf(-0.3f, -0.1f, -0.05f, 0.3f, -0.1f, -0.05f)
        val corners = ModelLandmarks.corners(boxModel(edge))
        assertTrue(corners.size % 3 == 0)
        val extracted = (0 until corners.size / 3).map {
            Triple(corners[it * 3], corners[it * 3 + 1], corners[it * 3 + 2])
        }
        assertTrue(extracted.any { (x, y, z) -> x == -0.3f && y == -0.1f && z == -0.05f })
        assertTrue(extracted.any { (x, y, z) -> x == 0.3f && y == -0.1f && z == -0.05f })
    }

    @Test
    fun `a target without feature edges still offers extreme points`() {
        val model = boxModel(FloatArray(0))
        val corners = ModelLandmarks.corners(model)
        assertTrue("se esperaban esquinas de soporte", corners.size / 3 >= 8)
        val modelPoints = (0 until model.pointCount).map {
            Triple(model.points[it * 3], model.points[it * 3 + 1], model.points[it * 3 + 2])
        }.toSet()
        for (index in 0 until corners.size / 3) {
            val candidate = Triple(corners[index * 3], corners[index * 3 + 1], corners[index * 3 + 2])
            assertTrue("$candidate no pertenece al modelo", candidate in modelPoints)
        }
    }

    @Test
    fun `the bundled HEB target offers snap corners at both ends`() {
        val bundled = File("src/debug/assets/bundled/heb140-300.qsxr")
        assertTrue("falta el paquete de ejemplo debug", bundled.isFile)
        val payload = ZipFile(bundled).use { archive ->
            archive.getInputStream(archive.getEntry("registration_model.bin")).readBytes()
        }
        val model = MarkerlessRegistrationModelReader.decode(payload)
        val corners = ModelLandmarks.corners(model)
        val count = corners.size / 3
        assertTrue("sólo ${'$'}count esquinas", count >= 16)
        // A beam is marked by its ends, so both extremes of the longest axis must be offered.
        val longest = (0..2).maxBy { model.boundsMax[it] - model.boundsMin[it] }
        val span = model.boundsMax[longest] - model.boundsMin[longest]
        val nearEnd = (0 until count).any {
            corners[it * 3 + longest] < model.boundsMin[longest] + span * 0.2f
        }
        val farEnd = (0 until count).any {
            corners[it * 3 + longest] > model.boundsMax[longest] - span * 0.2f
        }
        assertTrue("faltan esquinas en un extremo", nearEnd && farEnd)
    }

    /** 8 m beam along X with flange edges and a 20 mm end plate at x = -4, as the builder orders them. */
    private fun beamModel(): MarkerlessRegistrationModel {
        val edges = ArrayList<Float>()
        fun edge(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
            edges += listOf(ax, ay, az, bx, by, bz)
        }
        for (y in listOf(-0.1f, 0.1f)) for (z in listOf(-0.1f, 0.1f)) edge(-4f, y, z, 4f, y, z)
        for (x in listOf(-4.02f, -4f)) {
            edge(x, -0.15f, -0.15f, x, 0.15f, -0.15f)
            edge(x, -0.15f, 0.15f, x, 0.15f, 0.15f)
            edge(x, -0.15f, -0.15f, x, -0.15f, 0.15f)
            edge(x, 0.15f, -0.15f, x, 0.15f, 0.15f)
        }
        for (y in listOf(-0.15f, 0.15f)) for (z in listOf(-0.15f, 0.15f)) edge(-4.02f, y, z, -4f, y, z)
        val points = floatArrayOf(-4.02f, -0.15f, -0.15f, 4f, 0.1f, 0.1f, 0f, -0.1f, 0.1f)
        return MarkerlessRegistrationModel(
            points = points,
            normals = FloatArray(points.size) { if (it % 3 == 1) 1f else 0f },
            featureEdges = edges.toFloatArray(),
            boundsMin = floatArrayOf(-4.02f, -0.15f, -0.15f),
            boundsMax = floatArrayOf(4f, 0.15f, 0.15f),
            sampleSpacingM = 0.025f,
        )
    }

    private fun FloatArray.has(x: Float, y: Float, z: Float): Boolean =
        (0 until size / 3).any { this[it * 3] == x && this[it * 3 + 1] == y && this[it * 3 + 2] == z }

    @Test
    fun `a long beam keeps the outer corners of its end plate`() {
        // The outer corners sit 20 mm from the inner ones and 70 mm from the flange ends. A merge
        // distance growing with the 8 m length (96 mm before 0.33.1) kept only one of each group.
        val corners = ModelLandmarks.corners(beamModel())
        for (y in listOf(-0.15f, 0.15f)) for (z in listOf(-0.15f, 0.15f)) {
            assertTrue("falta la esquina exterior ($y, $z)", corners.has(-4.02f, y, z))
            assertTrue("falta la esquina interior ($y, $z)", corners.has(-4f, y, z))
        }
        for (y in listOf(-0.1f, 0.1f)) for (z in listOf(-0.1f, 0.1f)) {
            assertTrue("falta el extremo del ala ($y, $z)", corners.has(-4f, y, z))
            assertTrue("falta el extremo del ala ($y, $z)", corners.has(4f, y, z))
        }
    }

    @Test
    fun `a real corner outranks the vertices of a split straight edge when the cap trims`() {
        // Ten collinear segments far from the centre offer eleven vertices on one line; the corner
        // where three edges meet lies at the centre and would lose on distance alone.
        val edges = ArrayList<Float>()
        edges += listOf(0f, 0f, 0f, 0.05f, 0f, 0f, 0f, 0f, 0f, 0f, 0.05f, 0f, 0f, 0f, 0f, 0f, 0f, 0.04f)
        for (step in 0 until 10) {
            edges += listOf(0.1f + 0.02f * step, 0.09f, 0.04f, 0.1f + 0.02f * (step + 1), 0.09f, 0.04f)
        }
        val corners = ModelLandmarks.corners(boxModel(edges.toFloatArray()), maximumCount = 2)
        assertEquals(2, corners.size / 3)
        assertTrue("la esquina real debe sobrevivir al recorte", corners.has(0f, 0f, 0f))
    }

    /** A 300 × 300 × 15 plate at x = 0 with [walls] edges of 15 mm along x for each hole given as (y, z, radius). */
    private fun plateWithHoles(walls: Int, vararg holes: Triple<Float, Float, Float>): MarkerlessRegistrationModel {
        val edges = ArrayList<Float>()
        fun edge(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) { edges += listOf(ax, ay, az, bx, by, bz) }
        for (x in listOf(0f, 0.015f)) {
            edge(x, -0.15f, -0.15f, x, 0.15f, -0.15f); edge(x, -0.15f, 0.15f, x, 0.15f, 0.15f)
            edge(x, -0.15f, -0.15f, x, -0.15f, 0.15f); edge(x, 0.15f, -0.15f, x, 0.15f, 0.15f)
        }
        for (y in listOf(-0.15f, 0.15f)) for (z in listOf(-0.15f, 0.15f)) edge(0f, y, z, 0.015f, y, z)
        holes.forEach { (cy, cz, radius) ->
            for (step in 0 until walls) {
                val angle = Math.toRadians(360.0 / walls * step)
                val y = (cy + radius * kotlin.math.cos(angle)).toFloat()
                val z = (cz + radius * kotlin.math.sin(angle)).toFloat()
                edge(0f, y, z, 0.015f, y, z)
            }
        }
        return boxModel(edges.toFloatArray())
    }

    private fun ModelLandmarkSet.near(x: Float, y: Float, z: Float, within: Float): List<Int> =
        (0 until count).filter { index ->
            val p = point(index)
            val dx = p[0] - x; val dy = p[1] - y; val dz = p[2] - z
            dx * dx + dy * dy + dz * dz <= within * within
        }

    @Test
    fun `a round hole becomes its centre on each plate face and loses its rim vertices`() {
        // The phone's IFC tessellation leaves twelve wall edges of 15 mm around a Ø26 bolt hole,
        // the only feature edges a hole has.  Nobody can aim at one of them; the centre they can.
        val set = ModelLandmarks.landmarks(plateWithHoles(12, Triple(0.1f, 0.1f, 0.013f), Triple(-0.08f, 0.05f, 0.009f)))
        for (x in listOf(0f, 0.015f)) for (y in listOf(-0.15f, 0.15f)) for (z in listOf(-0.15f, 0.15f)) {
            assertTrue("falta la esquina ($x, $y, $z)", set.points.has(x, y, z))
        }
        for ((cy, cz, radius) in listOf(Triple(0.1f, 0.1f, 0.013f), Triple(-0.08f, 0.05f, 0.009f))) {
            for (x in listOf(0f, 0.015f)) {
                val centres = set.near(x, cy, cz, 0.0005f)
                assertEquals("centro en ($x, $cy, $cz)", 1, centres.size)
                assertTrue(set.holeCentre[centres.single()])
                // Each face centre faces away from the plate: seen from its own side only.
                val outside = floatArrayOf(if (x == 0f) -1f else 1.015f, cy, cz)
                val behind = floatArrayOf(if (x == 0f) 1f else -1f, cy, cz)
                assertTrue(set.faces(centres.single(), outside))
                assertFalse(set.faces(centres.single(), behind))
            }
            // Nothing else around the hole: its rim vertices are gone.
            assertEquals(2, set.near(0.0075f, cy, cz, radius + 0.01f).size)
        }
        assertEquals(4, set.holeCentre.count { it })
    }

    @Test
    fun `a slotted hole or a tessellated arc offers nothing`() {
        // Twelve wall edges along a stadium: no circle fits them, so no centre, and the vertices
        // are not corners either.  A quarter arc of a fillet is treated the same way.
        val edges = ArrayList<Float>()
        fun edge(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) { edges += listOf(ax, ay, az, bx, by, bz) }
        edge(0f, -0.15f, -0.15f, 0f, 0.15f, -0.15f); edge(0f, -0.15f, 0.15f, 0f, 0.15f, 0.15f)
        edge(0f, -0.15f, -0.15f, 0f, -0.15f, 0.15f); edge(0f, 0.15f, -0.15f, 0f, 0.15f, 0.15f)
        for (step in 0 until 12) {
            val t = step / 12.0
            val (y, z) = if (t < 0.5) (0.1 + 0.03 * (t * 2 - 0.5)) to 0.1 + 0.012
            else (0.1 + 0.03 * ((1 - t) * 2 - 0.5)) to 0.1 - 0.012
            edge(0f, y.toFloat(), z.toFloat(), 0.015f, y.toFloat(), z.toFloat())
        }
        for (step in 0..6) {
            val angle = Math.toRadians(15.0 * step)
            val y = (-0.1 + 0.02 * kotlin.math.cos(angle)).toFloat()
            val z = (-0.1 + 0.02 * kotlin.math.sin(angle)).toFloat()
            if (step > 0) {
                val previous = Math.toRadians(15.0 * (step - 1))
                edge((-0.1 + 0.02 * kotlin.math.cos(previous)).toFloat(), 0f, (-0.1 + 0.02 * kotlin.math.sin(previous)).toFloat(), y, 0f, z)
            }
        }
        val set = ModelLandmarks.landmarks(boxModel(edges.toFloatArray()))
        assertEquals(0, set.holeCentre.count { it })
        assertTrue(set.near(0.0075f, 0.1f, 0.1f, 0.04f).isEmpty())
        assertTrue(set.near(-0.1f, 0f, -0.1f, 0.03f).isEmpty())
        for (y in listOf(-0.15f, 0.15f)) for (z in listOf(-0.15f, 0.15f)) assertTrue(set.points.has(0f, y, z))
    }

    @Test
    fun `snap targets keep a minimum separation`() {
        val edges = FloatArray(600) { index -> (index % 7) * 0.001f }
        val corners = ModelLandmarks.corners(boxModel(edges), maximumCount = 50)
        assertTrue(corners.size / 3 <= 50)
        val separation = ModelLandmarks.SEPARATION_M
        for (first in 0 until corners.size / 3) {
            for (second in first + 1 until corners.size / 3) {
                val dx = corners[first * 3] - corners[second * 3]
                val dy = corners[first * 3 + 1] - corners[second * 3 + 1]
                val dz = corners[first * 3 + 2] - corners[second * 3 + 2]
                assertTrue(dx * dx + dy * dy + dz * dz >= separation * separation * 0.999f)
            }
        }
        assertEquals(0, corners.size % 3)
    }
}
