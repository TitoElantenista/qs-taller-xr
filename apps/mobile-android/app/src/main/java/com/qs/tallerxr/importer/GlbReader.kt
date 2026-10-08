package com.qs.tallerxr.importer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** A GLB the phone cannot use.  The message is shown to the operator as is. */
class GlbFormatException(message: String) : Exception(message)

/** Triangles of one glTF node with a mesh, already in the scene (assembly) frame, in metres. */
class GlbNodeMesh(
    val nodeIndex: Int,
    val name: String?,
    /** Nine floats per triangle. */
    val triangles: FloatArray,
) {
    val triangleCount: Int get() = triangles.size / 9

    val boundsMin: FloatArray
    val boundsMax: FloatArray

    init {
        val min = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val max = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        var index = 0
        while (index < triangles.size) {
            val axis = index % 3
            val value = triangles[index]
            if (value < min[axis]) min[axis] = value
            if (value > max[axis]) max[axis] = value
            index += 1
        }
        boundsMin = min
        boundsMax = max
    }
}

class GlbScene(
    val document: JsonObject,
    val binary: ByteArray,
    val meshes: List<GlbNodeMesh>,
) {
    val triangleCount: Int get() = meshes.sumOf(GlbNodeMesh::triangleCount)
}

/**
 * Minimal, strict GLB 2.0 reader for what the workshop needs: the triangles of every mesh node in
 * the scene frame.  It is what the PC exporter used to do before a package reached the phone; with
 * projects read straight from a folder the phone has to do it for any GLB it is given.
 */
object GlbReader {
    private const val MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942
    const val MAXIMUM_BYTES = 512L * 1024L * 1024L
    const val MAXIMUM_TRIANGLES = 3_000_000
    private val json = Json { ignoreUnknownKeys = true }

    fun read(file: File): GlbScene {
        if (!file.isFile) throw GlbFormatException("Die GLB-Datei wurde nicht gefunden.")
        if (file.length() > MAXIMUM_BYTES) {
            throw GlbFormatException("Die GLB-Datei ist größer als 512 MB und damit zu groß für das Telefon.")
        }
        return parse(file.readBytes())
    }

    fun parse(bytes: ByteArray): GlbScene {
        val (document, binary) = split(bytes)
        return GlbScene(document, binary, extractMeshes(document, binary))
    }

    /** Separates the JSON and BIN chunks of a GLB container. */
    fun split(bytes: ByteArray): Pair<JsonObject, ByteArray> {
        if (bytes.size < 20) throw GlbFormatException("Die Datei ist zu kurz für eine GLB-Datei.")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != MAGIC) throw GlbFormatException("Die Datei ist keine GLB-Datei (binäres glTF).")
        val version = buffer.int
        if (version != 2) throw GlbFormatException("Es wird nur glTF 2.0 unterstützt; diese GLB-Datei hat Version $version.")
        val declared = buffer.int.toLong() and 0xffffffffL
        if (declared > bytes.size) throw GlbFormatException("Die GLB-Datei ist abgeschnitten.")
        var document: JsonObject? = null
        var binary = ByteArray(0)
        while (buffer.remaining() >= 8) {
            val length = buffer.int
            val type = buffer.int
            if (length < 0 || length > buffer.remaining()) throw GlbFormatException("Die GLB-Datei ist beschädigt.")
            val chunk = ByteArray(length).also(buffer::get)
            when (type) {
                CHUNK_JSON -> document = runCatching {
                    json.parseToJsonElement(chunk.toString(Charsets.UTF_8)).jsonObject
                }.getOrElse { throw GlbFormatException("Die glTF-Beschreibung der GLB-Datei ist kein gültiges JSON.") }
                CHUNK_BIN -> if (binary.isEmpty()) binary = chunk
            }
        }
        return (document ?: throw GlbFormatException("Die GLB-Datei enthält keine glTF-Beschreibung.")) to binary
    }

    private fun extractMeshes(document: JsonObject, binary: ByteArray): List<GlbNodeMesh> {
        val nodes = document.array("nodes")
        val meshes = document.array("meshes")
        val used = (document["extensionsRequired"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }
        used.firstOrNull { it == "KHR_draco_mesh_compression" || it == "EXT_meshopt_compression" }?.let {
            throw GlbFormatException(
                "Die GLB-Datei verwendet Netzkomprimierung ($it), die das Telefon nicht lesen kann. " +
                    "Bitte ohne Draco-/meshopt-Komprimierung exportieren.",
            )
        }
        val scenes = document.array("scenes")
        val roots: List<Int> = if (scenes.isNotEmpty()) {
            val active = document.int("scene") ?: 0
            val scene = scenes.getOrNull(active)?.jsonObject
                ?: throw GlbFormatException("Die GLB-Datei verweist auf eine nicht vorhandene Szene.")
            scene.array("nodes").mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        } else {
            val children = nodes.flatMap { node ->
                node.jsonObject.array("children").mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            }.toSet()
            nodes.indices.filter { it !in children }
        }

        val result = ArrayList<GlbNodeMesh>()
        var triangleBudget = MAXIMUM_TRIANGLES
        val visited = HashSet<Int>()

        fun visit(nodeIndex: Int, parent: DoubleArray, depth: Int) {
            if (nodeIndex !in nodes.indices) throw GlbFormatException("Die GLB-Datei verweist auf einen nicht vorhandenen Knoten.")
            if (depth > 64 || !visited.add(nodeIndex)) {
                throw GlbFormatException("Die Knotenhierarchie der GLB-Datei ist kein Baum.")
            }
            val node = nodes[nodeIndex].jsonObject
            val world = multiply(parent, nodeMatrix(node))
            node.int("mesh")?.let { meshIndex ->
                val mesh = meshes.getOrNull(meshIndex)?.jsonObject
                    ?: throw GlbFormatException("Die GLB-Datei verweist auf ein nicht vorhandenes Netz.")
                val triangles = meshTriangles(document, binary, mesh, world)
                if (triangles.isNotEmpty()) {
                    triangleBudget -= triangles.size / 9
                    if (triangleBudget < 0) {
                        throw GlbFormatException(
                            "Die GLB-Datei hat mehr als $MAXIMUM_TRIANGLES Dreiecke. Bitte ein Bauteil oder eine " +
                                "Baugruppe pro Datei verwenden.",
                        )
                    }
                    result += GlbNodeMesh(nodeIndex, node.string("name"), triangles)
                }
            }
            node.array("children").forEach { child ->
                (child as? JsonPrimitive)?.intOrNull?.let { visit(it, world, depth + 1) }
            }
        }
        roots.forEach { visit(it, IDENTITY, 0) }
        if (result.isEmpty()) throw GlbFormatException("Die GLB-Datei enthält keine Dreiecksgeometrie.")
        return result
    }

    private fun meshTriangles(
        document: JsonObject,
        binary: ByteArray,
        mesh: JsonObject,
        world: DoubleArray,
    ): FloatArray {
        val parts = ArrayList<FloatArray>()
        mesh.array("primitives").forEach { element ->
            val primitive = element.jsonObject
            val mode = primitive.int("mode") ?: 4
            if (mode !in 4..6) return@forEach
            if ((primitive["extensions"] as? JsonObject)?.containsKey("KHR_draco_mesh_compression") == true) {
                throw GlbFormatException(
                    "Die GLB-Datei verwendet Draco-Komprimierung, die das Telefon nicht lesen kann. Bitte unkomprimiert exportieren.",
                )
            }
            val positionAccessor = (primitive["attributes"] as? JsonObject)?.int("POSITION") ?: return@forEach
            val positions = readVec3(document, binary, positionAccessor)
            val vertexCount = positions.size / 3
            val raw = primitive.int("indices")?.let { readIndices(document, binary, it) }
                ?: IntArray(vertexCount) { it }
            val indices = when (mode) {
                5 -> IntArray(maxOf(0, raw.size - 2) * 3) { position ->
                    val triangle = position / 3
                    val corner = position % 3
                    // Every second triangle of a strip is wound the other way round.
                    raw[triangle + if (triangle % 2 == 1 && corner > 0) 3 - corner else corner]
                }
                6 -> IntArray(maxOf(0, raw.size - 2) * 3) { position ->
                    val triangle = position / 3
                    when (position % 3) {
                        0 -> raw[0]
                        1 -> raw[triangle + 1]
                        else -> raw[triangle + 2]
                    }
                }
                else -> raw
            }
            val transformed = FloatArray(vertexCount * 3)
            for (vertex in 0 until vertexCount) {
                val x = positions[vertex * 3].toDouble()
                val y = positions[vertex * 3 + 1].toDouble()
                val z = positions[vertex * 3 + 2].toDouble()
                transformed[vertex * 3] = (world[0] * x + world[4] * y + world[8] * z + world[12]).toFloat()
                transformed[vertex * 3 + 1] = (world[1] * x + world[5] * y + world[9] * z + world[13]).toFloat()
                transformed[vertex * 3 + 2] = (world[2] * x + world[6] * y + world[10] * z + world[14]).toFloat()
            }
            val triangles = FloatArray(indices.size / 3 * 9)
            var written = 0
            var index = 0
            while (index + 2 < indices.size) {
                val a = indices[index]
                val b = indices[index + 1]
                val c = indices[index + 2]
                index += 3
                if (a !in 0 until vertexCount || b !in 0 until vertexCount || c !in 0 until vertexCount) continue
                for (corner in intArrayOf(a, b, c)) {
                    triangles[written++] = transformed[corner * 3]
                    triangles[written++] = transformed[corner * 3 + 1]
                    triangles[written++] = transformed[corner * 3 + 2]
                }
            }
            if (written > 0) parts += if (written == triangles.size) triangles else triangles.copyOf(written)
        }
        if (parts.isEmpty()) return FloatArray(0)
        if (parts.size == 1) return parts[0]
        val merged = FloatArray(parts.sumOf(FloatArray::size))
        var offset = 0
        parts.forEach { part ->
            part.copyInto(merged, offset)
            offset += part.size
        }
        return merged
    }

    private class AccessorView(
        val buffer: ByteBuffer,
        val start: Int,
        val stride: Int,
        val count: Int,
        val componentType: Int,
    )

    private fun accessorView(
        document: JsonObject,
        binary: ByteArray,
        accessorIndex: Int,
        components: Int,
    ): AccessorView {
        val accessor = document.array("accessors").getOrNull(accessorIndex)?.jsonObject
            ?: throw GlbFormatException("Die GLB-Datei verweist auf einen nicht vorhandenen Accessor.")
        val componentType = accessor.int("componentType") ?: throw GlbFormatException("Accessor ohne Typ.")
        val count = accessor.int("count") ?: 0
        val componentBytes = when (componentType) {
            5120, 5121 -> 1
            5122, 5123 -> 2
            5125, 5126 -> 4
            else -> throw GlbFormatException("Nicht unterstützter glTF-Datentyp: $componentType.")
        }
        val viewIndex = accessor.int("bufferView")
            ?: throw GlbFormatException("Die GLB-Datei verwendet Sparse-Accessors ohne Basisdaten; das wird nicht unterstützt.")
        val view = document.array("bufferViews").getOrNull(viewIndex)?.jsonObject
            ?: throw GlbFormatException("Die GLB-Datei verweist auf eine nicht vorhandene bufferView.")
        if ((view["extensions"] as? JsonObject)?.containsKey("EXT_meshopt_compression") == true) {
            throw GlbFormatException("Die GLB-Datei verwendet meshopt-Komprimierung, die das Telefon nicht lesen kann.")
        }
        if ((view.int("buffer") ?: 0) != 0) {
            throw GlbFormatException("Die GLB-Datei benötigt externe Dateien; sie muss eine einzelne, eigenständige .glb-Datei sein.")
        }
        val stride = view.int("byteStride")?.takeIf { it > 0 } ?: (componentBytes * components)
        val start = (view.int("byteOffset") ?: 0) + (accessor.int("byteOffset") ?: 0)
        val needed = if (count == 0) 0L else start.toLong() + stride.toLong() * (count - 1) + componentBytes * components
        if (start < 0 || needed > binary.size) throw GlbFormatException("Die Daten der GLB-Datei sind abgeschnitten.")
        return AccessorView(ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN), start, stride, count, componentType)
    }

    private fun readVec3(document: JsonObject, binary: ByteArray, accessorIndex: Int): FloatArray {
        val view = accessorView(document, binary, accessorIndex, 3)
        if (view.componentType != 5126) {
            throw GlbFormatException(
                "Die GLB-Datei speichert quantisierte Positionen (KHR_mesh_quantization); bitte mit " +
                    "Gleitkomma-Positionen exportieren.",
            )
        }
        val result = FloatArray(view.count * 3)
        for (element in 0 until view.count) {
            val base = view.start + element * view.stride
            result[element * 3] = view.buffer.getFloat(base)
            result[element * 3 + 1] = view.buffer.getFloat(base + 4)
            result[element * 3 + 2] = view.buffer.getFloat(base + 8)
        }
        if (!result.all(Float::isFinite)) throw GlbFormatException("Die GLB-Datei enthält nicht endliche Koordinaten.")
        return result
    }

    private fun readIndices(document: JsonObject, binary: ByteArray, accessorIndex: Int): IntArray {
        val view = accessorView(document, binary, accessorIndex, 1)
        return IntArray(view.count) { element ->
            val base = view.start + element * view.stride
            when (view.componentType) {
                5121 -> view.buffer.get(base).toInt() and 0xff
                5123 -> view.buffer.getShort(base).toInt() and 0xffff
                5125 -> view.buffer.getInt(base)
                else -> throw GlbFormatException("Nicht unterstützter glTF-Indextyp: ${view.componentType}.")
            }
        }
    }

    private val IDENTITY = doubleArrayOf(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0,
    )

    /** Column major parentFromNode matrix of a glTF node, from `matrix` or TRS. */
    private fun nodeMatrix(node: JsonObject): DoubleArray {
        (node["matrix"] as? JsonArray)?.let { values ->
            if (values.size == 16) return DoubleArray(16) { (values[it] as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
        }
        val t = node.doubles("translation", 3) ?: doubleArrayOf(0.0, 0.0, 0.0)
        val q = node.doubles("rotation", 4) ?: doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        val s = node.doubles("scale", 3) ?: doubleArrayOf(1.0, 1.0, 1.0)
        val x = q[0]
        val y = q[1]
        val z = q[2]
        val w = q[3]
        return doubleArrayOf(
            (1 - 2 * (y * y + z * z)) * s[0], (2 * (x * y + z * w)) * s[0], (2 * (x * z - y * w)) * s[0], 0.0,
            (2 * (x * y - z * w)) * s[1], (1 - 2 * (x * x + z * z)) * s[1], (2 * (y * z + x * w)) * s[1], 0.0,
            (2 * (x * z + y * w)) * s[2], (2 * (y * z - x * w)) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0.0,
            t[0], t[1], t[2], 1.0,
        )
    }

    private fun multiply(left: DoubleArray, right: DoubleArray): DoubleArray = DoubleArray(16) { index ->
        val column = index / 4
        val row = index % 4
        (0..3).sumOf { inner -> left[inner * 4 + row] * right[column * 4 + inner] }
    }
}

internal fun JsonObject.array(name: String): List<JsonElement> = (this[name] as? JsonArray).orEmpty()

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.doubles(name: String, size: Int): DoubleArray? {
    val values = (this[name] as? JsonArray) ?: return null
    if (values.size != size) return null
    return DoubleArray(size) { (values[it] as? JsonPrimitive)?.doubleOrNull ?: return null }
}
