package com.qs.tallerxr.data

import com.qs.tallerxr.ar.MarkerlessRegistrationModelReader
import com.qs.tallerxr.ar.ModelLandmarks
import com.qs.tallerxr.importer.GlbNodeMesh
import com.qs.tallerxr.importer.GlbReader
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only debug ships the demo; it must open and offer corners without any file of the user. */
class BundledDemoSamplesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun asset(name: String): File =
        listOf("src/debug/assets/bundled/$name", "app/src/debug/assets/bundled/$name").map(::File).first(File::isFile)

    private class Sample(val manifest: PackageManifest, val parts: PartDocument, val sizeMm: List<Int>, val corners: Int, val nodes: Set<String>)

    private fun read(name: String): Sample = ZipFile(asset(name)).use { zip ->
        fun bytes(entry: String) = zip.getInputStream(zip.getEntry(entry)).use { it.readBytes() }
        val registration = MarkerlessRegistrationModelReader.decode(bytes("registration_model.bin"))
        Sample(
            manifest = json.decodeFromString(bytes("manifest.json").toString(Charsets.UTF_8)),
            parts = json.decodeFromString(bytes("parts.json").toString(Charsets.UTF_8)),
            sizeMm = (0..2).map { Math.round((registration.boundsMax[it] - registration.boundsMin[it]) * 1000f) },
            corners = ModelLandmarks.corners(registration).size / 3,
            nodes = GlbReader.parse(bytes("assembly.glb")).meshes.mapNotNull(GlbNodeMesh::name).toSet(),
        )
    }

    @Test
    fun `the three demo models share one project and carry their real dimensions`() {
        val heb = read("heb140-300.qsxr")
        val ipe = read("ipe270-200.qsxr")
        val bench = read("tv-bank-148x47x57.qsxr")
        assertEquals(1, setOf(heb, ipe, bench).map { it.manifest.project.projectId }.toSet().size)
        assertEquals(setOf("Demo · Testmodelle"), setOf(heb, ipe, bench).map { it.manifest.project.name }.toSet())

        assertEquals(listOf(140, 140, 300), heb.sizeMm)
        // EN 10365 IPE 270: 135 wide, 270 high; the offcut is 200 mm long.
        assertEquals(listOf(135, 270, 200), ipe.sizeMm)
        // The bench top defines all three exterior dimensions, including the knobs.
        assertEquals(listOf(1477, 567, 465), bench.sizeMm)
    }

    @Test
    fun `every demo model offers corners and binds each part to a node of its GLB`() {
        listOf("heb140-300.qsxr", "ipe270-200.qsxr", "tv-bank-148x47x57.qsxr").forEach { name ->
            val sample = read(name)
            assertTrue("$name: ${sample.corners} Ecken", sample.corners >= 16)
            assertTrue(name, sample.parts.parts.isNotEmpty())
            assertTrue(name, sample.parts.parts.all { it.gltfNodeName in sample.nodes })
            assertEquals(name, 1, sample.parts.parts.count { it.kind == "MAIN_PART" })
        }
        assertEquals(32, read("tv-bank-148x47x57.qsxr").parts.parts.size)
    }

    @Test
    fun `the TV bench has three separate drawers below three open bays and one knob per drawer`() {
        val parts = read("tv-bank-148x47x57.qsxr").parts.parts
        fun named(prefix: String) = parts.filter {
            (it.properties["name"] as? JsonPrimitive)?.content?.startsWith(prefix) == true
        }.sortedBy { it.boundsAssemblyM.min[0] }
        val drawers = named("Schubladenfront")
        val dividers = named("Trennwand")
        val knobs = named("Knopf")
        assertEquals(3, drawers.size)
        assertEquals(2, dividers.size)
        assertEquals(3, knobs.size)
        assertTrue(named("Einlegeboden").isEmpty())
        val shelf = named("Zwischenboden").single().boundsAssemblyM
        val topRail = named("Frontleiste oben").single().boundsAssemblyM
        val bottomRail = named("Frontleiste unten").single().boundsAssemblyM
        val openingHeight = topRail.min[1] - shelf.max[1]
        assertTrue("Upper opening must match the short bay of the product reference", openingHeight in 0.110f..0.135f)
        drawers.zip(knobs).forEach { (drawer, knob) ->
            val front = drawer.boundsAssemblyM
            val handle = knob.boundsAssemblyM
            val drawerHeight = front.max[1] - front.min[1]
            assertTrue("The upper bay must be clearly shorter than a drawer", openingHeight < drawerHeight * 0.7f)
            assertTrue(front.min[1] > bottomRail.max[1])
            assertTrue(front.max[1] < shelf.min[1])
            assertTrue(handle.min[0] > front.min[0] && handle.max[0] < front.max[0])
        }
        dividers.forEach { assertTrue(it.boundsAssemblyM.max[1] > shelf.max[1]) }
        assertTrue(named("Deckplatte").single().properties["product"].toString().contains("504.135.26"))
    }
}
