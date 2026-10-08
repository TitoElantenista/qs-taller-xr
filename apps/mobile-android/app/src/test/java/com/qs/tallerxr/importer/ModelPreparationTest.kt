package com.qs.tallerxr.importer

import com.qs.tallerxr.ar.MarkerlessRegistrationModelReader
import com.qs.tallerxr.ar.ModelLandmarks
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelPreparationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/importer/$name")) { name }.use { it.readBytes() }

    private fun resourceFile(name: String): File = temporary.newFile(name).apply { writeBytes(resource(name)) }

    // The Tekla beam fixture comes from a customer model and is not published; tests on it skip
    // in a public clone and run where the file exists.
    private fun hasResource(name: String): Boolean = javaClass.getResource("/importer/$name") != null

    private val identity = ModelIdentity("project-1", "Nave 3", "model-key", "TRAEGER 1")

    @Test
    fun `the phone derives the same reference the PC exporter shipped for the HEB sample`() {
        val scene = GlbReader.parse(resource("heb-assembly.glb"))
        val triangles = scene.meshes.flatMap { it.triangles.asList() }.toFloatArray()
        val built = RegistrationModelBuilder.build(triangles)
        val reference = MarkerlessRegistrationModelReader.decode(resource("heb-registration_model.bin"))
        val decoded = MarkerlessRegistrationModelReader.decode(built.payload)

        assertEquals(reference.pointCount, decoded.pointCount)
        for (axis in 0..2) {
            assertEquals(reference.boundsMin[axis], decoded.boundsMin[axis], 1e-6f)
            assertEquals(reference.boundsMax[axis], decoded.boundsMax[axis], 1e-6f)
        }
        assertEquals(reference.sampleSpacingM, decoded.sampleSpacingM, 1e-7f)
        // Same deterministic sampling: the clouds coincide point by point.
        var worst = 0f
        for (index in reference.points.indices) worst = maxOf(worst, abs(reference.points[index] - decoded.points[index]))
        assertTrue("desviación de la nube: $worst m", worst < 1e-5f)
        // The corners an operator can pick are the same set.
        assertEquals(
            ModelLandmarks.corners(reference).size,
            ModelLandmarks.corners(decoded).size,
        )
        assertTrue(decoded.edgeCount in 1..reference.edgeCount)
    }

    @Test
    fun `a Tekla beam converted on the phone becomes an openable model with corners and parts`() {
        assumeTrue("traeger.* liegt nur lokal vor (aus einem Kundenmodell)", hasResource("traeger.glb"))
        val parts = Json { ignoreUnknownKeys = true }
            .decodeFromString<IfcPartsDocument>(resource("traeger.parts.json").toString(Charsets.UTF_8))
        val target = File(temporary.root, "models/model-key")
        target.parentFile.mkdirs()
        val installed = PreparedModelWriter.write(
            glb = resourceFile("traeger.glb"),
            sourceUri = null,
            ifcParts = parts,
            identity = identity,
            sourceSizeBytes = 39_858,
            sourceLastModified = 1_700_000_000_000,
            format = "IFC",
            target = target,
        )
        assertEquals("TRAEGER 1", installed.manifest.assembly.positionNumber)
        assertEquals("Nave 3", installed.manifest.project.name)
        assertEquals(22, installed.parts.parts.size)
        assertEquals(1, installed.parts.parts.count { it.kind == "MAIN_PART" })
        assertEquals(2, installed.parts.parts.count { it.kind == "BOLT_GROUP" })
        // The main part keeps the drawing number the IFC gives it.
        val drawing = parts.parts.first { it.kind == "MAIN_PART" }.positionNumber.substringBefore('.')
        assertTrue(installed.parts.parts.first { it.kind == "MAIN_PART" }.positionNumber.startsWith("$drawing."))

        val registration = MarkerlessRegistrationModelReader.read(installed.registrationModelFile)
        // HEA220 beam with end plates, 6.365 m of steel: bolts are 6.45 m apart end to end but
        // never steer the alignment.  The reference spans the real part, in metres.
        assertEquals(6.365f, registration.boundsMax[0] - registration.boundsMin[0], 0.005f)
        assertTrue(registration.diameterM in 6.3f..6.5f)
        assertTrue(registration.edgeCount > 100)
        assertTrue(ModelLandmarks.corners(registration).size > 40)

        // Every part is bound to a node the renderer can find by name.
        val nodes = GlbReader.read(installed.modelFile).meshes.mapNotNull(GlbNodeMesh::name).toSet()
        assertTrue(installed.parts.parts.all { it.gltfNodeName in nodes })

        val stamp = requireNotNull(PreparedModelWriter.stamp(target))
        assertTrue(stamp.matches(39_858, 1_700_000_000_000))
        assertTrue(!stamp.matches(39_859, 1_700_000_000_000))
        assertNotNull(PreparedModelWriter.load(target))
        assertTrue(target.parentFile.listFiles().orEmpty().none { ".tmp-" in it.name })
    }

    @Test
    fun `a linked GLB is rendered from the operator's folder and never duplicated`() {
        val target = File(temporary.root, "models/linked")
        target.parentFile!!.mkdirs()
        val source = temporary.newFile("viga.glb").apply { writeBytes(glb("""[{"mesh":0,"name":"viga"}]""", "[0]", size = 2f)) }
        val linked = PreparedModelWriter.write(
            glb = source,
            sourceUri = "content://folder/viga.glb",
            ifcParts = null,
            identity = identity,
            sourceSizeBytes = source.length(),
            sourceLastModified = 1L,
            format = "GLB",
            target = target,
        )
        assertEquals("content://folder/viga.glb", linked.modelLocation)
        assertTrue("el GLB no debe copiarse", !File(target, "assembly.glb").exists())
        assertTrue(linked.registrationModelFile.isFile)
        assertEquals("content://folder/viga.glb", requireNotNull(PreparedModelWriter.load(target)).modelUri)

        // A GLB that has to be rewritten (no node names here) is derived data and is stored.
        val unnamed = temporary.newFile("sin-nombre.glb").apply { writeBytes(glb("""[{"mesh":0}]""", "[0]", size = 2f)) }
        val derived = PreparedModelWriter.write(
            glb = unnamed,
            sourceUri = "content://folder/sin-nombre.glb",
            ifcParts = null,
            identity = identity,
            sourceSizeBytes = unnamed.length(),
            sourceLastModified = 1L,
            format = "GLB",
            target = File(temporary.root, "models/derived"),
        )
        assertNull(derived.modelUri)
        assertTrue(derived.modelFile.isFile)
    }

    @Test
    fun `flat faces tessellated with T-junctions do not grow false edges`() {
        // A 1 x 1 m plate, 10 mm thick.  Its top face is split unevenly on purpose: one triangle
        // spans the whole diagonal side while its neighbours stop halfway.
        val registration = MarkerlessRegistrationModelReader.decode(
            RegistrationModelBuilder.build(plateWithTJunction()).payload,
        )
        var index = 0
        while (index + 5 < registration.featureEdges.size) {
            val edge = registration.featureEdges.copyOfRange(index, index + 6)
            val onTop = abs(edge[1] - 0.01f) < 1e-6f && abs(edge[4] - 0.01f) < 1e-6f
            val onBorder = listOf(0, 2).any { axis ->
                (abs(edge[axis]) < 1e-6f && abs(edge[axis + 3]) < 1e-6f) ||
                    (abs(edge[axis] - 1f) < 1e-6f && abs(edge[axis + 3] - 1f) < 1e-6f)
            }
            if (onTop && !onBorder) fail("arista falsa dentro de la cara: ${edge.toList()}")
            index += 6
        }
        assertTrue(registration.edgeCount >= 12)
    }

    @Test
    fun `a foreign GLB gets unique node names and millimetre exports are scaled to metres`() {
        val bytes = glb(
            nodesJson = """[{"mesh":0},{"mesh":0,"name":"placa","translation":[2000,0,0]},""" +
                """{"mesh":0,"name":"placa","translation":[4000,0,0]}]""",
            sceneNodes = "[0,1,2]",
            size = 1_000f,
        )
        val normalised = PreparedModelWriter.normalise(bytes)
        assertEquals(0.001f, normalised.unitScale, 0f)
        val names = normalised.scene.meshes.map(GlbNodeMesh::name)
        assertEquals(3, names.filterNotNull().toSet().size)
        assertTrue("placa" in names)
        val width = normalised.scene.meshes.maxOf { it.boundsMax[0] } - normalised.scene.meshes.minOf { it.boundsMin[0] }
        assertEquals(5f, width, 1e-3f)

        val parts = PreparedModelWriter.partsFromNodes(normalised.scene)
        assertEquals(3, parts.size)
        assertEquals(parts.map { it.gltfNodeName }.toSet().size, parts.size)
    }

    @Test
    fun `a metre GLB with good names is kept byte for byte`() {
        val bytes = glb("""[{"mesh":0,"name":"viga"}]""", "[0]", size = 2f)
        val normalised = PreparedModelWriter.normalise(bytes)
        assertEquals(1f, normalised.unitScale, 0f)
        assertTrue(bytes.contentEquals(normalised.bytes))
    }

    @Test
    fun `unsupported or broken files are refused with a readable reason`() {
        listOf(
            "texto".toByteArray(),
            ByteArray(64),
            glb("""[{"mesh":0}]""", "[0]", 1f).copyOf(60),
        ).forEach { bytes ->
            try {
                GlbReader.parse(bytes)
                fail("debía rechazarse")
            } catch (error: GlbFormatException) {
                assertTrue(error.message!!.isNotBlank())
            }
        }
        assertNull(PreparedModelWriter.load(temporary.newFolder("vacía")))
    }

    /** One triangle mesh (a right triangle of the given size) used by several nodes. */
    private fun glb(nodesJson: String, sceneNodes: String, size: Float): ByteArray {
        val binary = ByteBuffer.allocate(36 + 8).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(0f, 0f, 0f, size, 0f, 0f, 0f, size, 0f).forEach(binary::putFloat)
        shortArrayOf(0, 1, 2).forEach(binary::putShort)
        val bin = binary.array()
        val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":$sceneNodes}],"nodes":$nodesJson,""" +
            """"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],""" +
            """"accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[$size,$size,0]},""" +
            """{"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],""" +
            """"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],""" +
            """"buffers":[{"byteLength":${bin.size}}]}"""
        val text = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { 0x20 } }
        val total = 12 + 8 + text.size + 8 + bin.size
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46546C67).putInt(2).putInt(total)
            .putInt(text.size).putInt(0x4E4F534A).put(text)
            .putInt(bin.size).putInt(0x004E4942).put(bin)
            .array()
    }

    private fun plateWithTJunction(): FloatArray {
        val triangles = ArrayList<Float>()
        fun triangle(vararg values: Float) = values.forEach(triangles::add)
        fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) {
            triangle(*a, *b, *c)
            triangle(*a, *c, *d)
        }
        val t = 0.01f
        // Top face: fan around (0.5, 1) with a T-junction at (0.5, 0) on the long front side.
        triangle(0f, t, 0f, 1f, t, 0f, 0.5f, t, 1f)
        triangle(0f, t, 0f, 0.5f, t, 1f, 0f, t, 1f)
        triangle(1f, t, 0f, 1f, t, 1f, 0.5f, t, 1f)
        // Bottom face.
        quad(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 1f), floatArrayOf(1f, 0f, 0f))
        // Sides; the front one is split at x = 0.5, the top face is not: a T-junction.
        quad(floatArrayOf(0f, 0f, 0f), floatArrayOf(0.5f, 0f, 0f), floatArrayOf(0.5f, t, 0f), floatArrayOf(0f, t, 0f))
        quad(floatArrayOf(0.5f, 0f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, t, 0f), floatArrayOf(0.5f, t, 0f))
        quad(floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f, 1f), floatArrayOf(1f, t, 1f), floatArrayOf(1f, t, 0f))
        quad(floatArrayOf(1f, 0f, 1f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, t, 1f), floatArrayOf(1f, t, 1f))
        quad(floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, t, 0f), floatArrayOf(0f, t, 1f))
        return triangles.toFloatArray()
    }
}
