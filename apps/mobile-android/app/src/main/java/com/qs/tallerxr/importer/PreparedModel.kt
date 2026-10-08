package com.qs.tallerxr.importer

import com.qs.tallerxr.data.AssemblyRef
import com.qs.tallerxr.data.Bounds
import com.qs.tallerxr.data.ChecksumDocument
import com.qs.tallerxr.data.CoordinateSystem
import com.qs.tallerxr.data.InstalledPackage
import com.qs.tallerxr.data.PackageFiles
import com.qs.tallerxr.data.PackageManifest
import com.qs.tallerxr.data.Part
import com.qs.tallerxr.data.PartDocument
import com.qs.tallerxr.data.Producer
import com.qs.tallerxr.data.ProjectRef
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Part metadata delivered by the IFC conversion next to its GLB. */
@Serializable
data class IfcPartsDocument(
    val parts: List<Part> = emptyList(),
    val assemblies: List<IfcAssemblyInfo> = emptyList(),
)

@Serializable
data class IfcAssemblyInfo(
    val guid: String? = null,
    val name: String? = null,
    val positionNumber: String? = null,
)

/** Records which source file a prepared model was made from, so a changed file is prepared again. */
@Serializable
data class PreparedStamp(
    val preparerVersion: Int,
    val sourceSizeBytes: Long,
    val sourceLastModified: Long,
    val format: String,
    val unitScale: Float = 1f,
    val triangleCount: Int = 0,
    val partCount: Int = 0,
    val preparedAt: String = "",
    /** The linked GLB is rendered from where it lies; no copy of it was stored. */
    val modelUri: String? = null,
) {
    fun matches(sizeBytes: Long, lastModified: Long): Boolean =
        preparerVersion == PreparedModelWriter.PREPARER_VERSION &&
            sourceSizeBytes == sizeBytes && sourceLastModified == lastModified
}

class ModelIdentity(
    val projectId: String,
    val projectName: String,
    val modelKey: String,
    val displayName: String,
)

/**
 * Turns one GLB (given by the operator or converted from IFC on the phone) into the on-disk form
 * the viewer opens: the GLB itself, the part list and the QSRG reference.  It is the same layout a
 * `.qsproject` package extracts to, so both project sources share the 3D and AR viewers.
 */
object PreparedModelWriter {
    /** Bump to invalidate every prepared model after a change in how they are derived. */
    const val PREPARER_VERSION = 2

    private const val MODEL_FILE = "assembly.glb"
    private const val REGISTRATION_FILE = "registration_model.bin"
    private const val PARTS_FILE = "parts.json"
    private const val MANIFEST_FILE = "manifest.json"
    private const val CHECKSUMS_FILE = "checksums.json"
    private const val STAMP_FILE = "prepared.json"

    // No steel assembly is a hundred metres across: such a GLB was exported in millimetres.
    private const val MILLIMETRE_DIAMETER_THRESHOLD_M = 100f

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun stamp(directory: File): PreparedStamp? = runCatching {
        json.decodeFromString<PreparedStamp>(File(directory, STAMP_FILE).readText())
    }.getOrNull()

    fun load(directory: File): InstalledPackage? = runCatching {
        val manifest = json.decodeFromString<PackageManifest>(File(directory, MANIFEST_FILE).readText())
        val parts = json.decodeFromString<PartDocument>(File(directory, PARTS_FILE).readText())
        val checksums = json.decodeFromString<ChecksumDocument>(File(directory, CHECKSUMS_FILE).readText())
        InstalledPackage(directory, manifest, parts, checksums, stamp(directory)?.modelUri).takeIf {
            (it.modelUri != null || it.modelFile.isFile) && it.registrationModelFile.isFile
        }
    }.getOrNull()

    /**
     * Writes the prepared model into [target].  Everything is built in a sibling directory first
     * and swapped in at the end, so an interrupted preparation never leaves a half written model
     * that a later open would trust.
     *
     * With [sourceUri] the GLB is a file of a linked folder: it is not duplicated.  Only what has
     * to be derived from it is stored (parts and the QSRG reference), and the renderer reads the
     * operator's own file.  A GLB that needs node names or a unit scale, and the mesh evaluated
     * from an IFC, are derived data and are stored.
     */
    fun write(
        glb: File,
        sourceUri: String?,
        ifcParts: IfcPartsDocument?,
        identity: ModelIdentity,
        sourceSizeBytes: Long,
        sourceLastModified: Long,
        format: String,
        target: File,
    ): InstalledPackage {
        val original = glb.readBytes()
        val normalised = normalise(original)
        val linkedModelUri = sourceUri?.takeIf { normalised.bytes === original }
        val scene = normalised.scene
        val parts = ifcParts?.parts?.takeIf { it.isNotEmpty() } ?: partsFromNodes(scene)
        val decorative = parts.filter { it.kind == "BOLT_GROUP" || it.kind == "FABRICATION_MARK" }
            .mapTo(HashSet()) { it.gltfNodeName }
        val structural = scene.meshes.filter { it.name !in decorative }.ifEmpty { scene.meshes }
        val triangles = FloatArray(structural.sumOf { it.triangles.size })
        var offset = 0
        structural.forEach { mesh ->
            mesh.triangles.copyInto(triangles, offset)
            offset += mesh.triangles.size
        }
        val registration = RegistrationModelBuilder.build(triangles)

        val staging = File(target.parentFile, "${target.name}.tmp-${UUID.randomUUID()}")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Der Vorbereitungsordner konnte nicht angelegt werden." }
        try {
            if (linkedModelUri == null) File(staging, MODEL_FILE).writeBytes(normalised.bytes)
            File(staging, REGISTRATION_FILE).writeBytes(registration.payload)
            val manifest = PackageManifest(
                schemaVersion = "2.0",
                packageId = identity.modelKey,
                createdAt = Instant.now().toString(),
                producer = Producer("QS Taller XR", "folder-import-$PREPARER_VERSION"),
                project = ProjectRef(identity.projectId, identity.projectName),
                assembly = AssemblyRef(identity.modelKey, identity.displayName, 1),
                coordinateSystem = CoordinateSystem(
                    "RIGHT_HANDED", "Y", "m", "COLUMN_MAJOR", "COLUMN_VECTOR", "PARENT_FROM_CHILD",
                ),
                files = PackageFiles(MODEL_FILE, REGISTRATION_FILE, PARTS_FILE, "", CHECKSUMS_FILE),
            )
            File(staging, MANIFEST_FILE).writeText(json.encodeToString(manifest))
            File(staging, PARTS_FILE).writeText(json.encodeToString(PartDocument("1.0", identity.modelKey, parts)))
            File(staging, CHECKSUMS_FILE).writeText(json.encodeToString(ChecksumDocument("1.0", "SHA-256", emptyList())))
            File(staging, STAMP_FILE).writeText(
                json.encodeToString(
                    PreparedStamp(
                        preparerVersion = PREPARER_VERSION,
                        sourceSizeBytes = sourceSizeBytes,
                        sourceLastModified = sourceLastModified,
                        format = format,
                        unitScale = normalised.unitScale,
                        triangleCount = scene.triangleCount,
                        partCount = parts.size,
                        preparedAt = Instant.now().toString(),
                        modelUri = linkedModelUri,
                    ),
                ),
            )
            target.deleteRecursively()
            check(staging.renameTo(target)) { "Das vorbereitete Modell konnte nicht veröffentlicht werden." }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
        return checkNotNull(load(target)) { "Das vorbereitete Modell konnte nicht wieder gelesen werden." }
    }

    internal class Normalised(val bytes: ByteArray, val scene: GlbScene, val unitScale: Float)

    /**
     * Makes a foreign GLB usable without touching its geometry data: every mesh node gets a
     * unique name, because styles, selection and transparency bind to parts by node name, and a
     * model exported in millimetres is wrapped in a 0.001 scale so it overlays the real part 1:1.
     */
    internal fun normalise(bytes: ByteArray): Normalised {
        val scene = GlbReader.parse(bytes)
        val min = FloatArray(3) { axis -> scene.meshes.minOf { it.boundsMin[axis] } }
        val max = FloatArray(3) { axis -> scene.meshes.maxOf { it.boundsMax[axis] } }
        val diameter = sqrt((0..2).fold(0f) { sum, axis -> sum + (max[axis] - min[axis]).let { it * it } })
        val unitScale = if (diameter > MILLIMETRE_DIAMETER_THRESHOLD_M) 0.001f else 1f

        val names = HashSet<String>()
        val renames = HashMap<Int, String>()
        scene.meshes.forEach { mesh ->
            val current = mesh.name?.trim().orEmpty()
            if (current.isNotEmpty() && names.add(current)) return@forEach
            var candidate = if (current.isEmpty()) "bauteil-${mesh.nodeIndex + 1}" else "$current-${mesh.nodeIndex + 1}"
            while (!names.add(candidate)) candidate += "_"
            renames[mesh.nodeIndex] = candidate
        }
        if (renames.isEmpty() && unitScale == 1f) return Normalised(bytes, scene, 1f)

        var nodes = scene.document.array("nodes").mapIndexed { index, node ->
            renames[index]?.let { JsonObject(node.jsonObject + ("name" to JsonPrimitive(it))) } ?: node
        }
        var document = scene.document
        if (unitScale != 1f) {
            val scenes = document.array("scenes")
            val active = document.int("scene") ?: 0
            val roots = (scenes.getOrNull(active)?.jsonObject?.get("nodes") as? JsonArray)
                ?: throw GlbFormatException("Die GLB-Datei deklariert keine Szene, die skaliert werden kann.")
            nodes = nodes + JsonObject(
                mapOf(
                    "name" to JsonPrimitive("qs-einheiten-mm"),
                    "scale" to JsonArray(List(3) { JsonPrimitive(unitScale) }),
                    "children" to roots,
                ),
            )
            val rewrittenScenes = scenes.mapIndexed { index, element ->
                if (index != active) element else {
                    JsonObject(element.jsonObject + ("nodes" to JsonArray(listOf(JsonPrimitive(nodes.size - 1)))))
                }
            }
            document = JsonObject(document + ("scenes" to JsonArray(rewrittenScenes)))
        }
        document = JsonObject(document + ("nodes" to JsonArray(nodes)))
        val rewritten = assemble(document, scene.binary)
        return Normalised(rewritten, GlbReader.parse(rewritten), unitScale)
    }

    private fun assemble(document: JsonObject, binary: ByteArray): ByteArray {
        val text = document.toString().toByteArray(Charsets.UTF_8)
        val jsonPadding = (4 - text.size % 4) % 4
        val binaryPadding = (4 - binary.size % 4) % 4
        val hasBinary = binary.isNotEmpty()
        val total = 12 + 8 + text.size + jsonPadding + if (hasBinary) 8 + binary.size + binaryPadding else 0
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x46546C67).putInt(2).putInt(total)
        buffer.putInt(text.size + jsonPadding).putInt(0x4E4F534A).put(text)
        repeat(jsonPadding) { buffer.put(0x20) }
        if (hasBinary) {
            buffer.putInt(binary.size + binaryPadding).putInt(0x004E4942).put(binary)
            repeat(binaryPadding) { buffer.put(0) }
        }
        return buffer.array()
    }

    /** A plain GLB carries no IFC data: every mesh node is one selectable part. */
    internal fun partsFromNodes(scene: GlbScene): List<Part> {
        val largest = scene.meshes.maxByOrNull { mesh ->
            (0..2).maxOf { mesh.boundsMax[it] - mesh.boundsMin[it] }
        }
        return scene.meshes.map { mesh ->
            val name = checkNotNull(mesh.name) { "normalise() names every mesh node." }
            Part(
                guid = name,
                positionNumber = name.takeIf { it.length <= 24 && !it.startsWith("bauteil-") }.orEmpty(),
                kind = if (mesh === largest && scene.meshes.size > 1) "MAIN_PART" else "PART",
                gltfNodeName = name,
                boundsAssemblyM = Bounds(mesh.boundsMin.toList(), mesh.boundsMax.toList()),
                inspectable = true,
                properties = JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(name),
                        "triangles" to JsonPrimitive(mesh.triangleCount),
                    ),
                ),
            )
        }
    }
}
