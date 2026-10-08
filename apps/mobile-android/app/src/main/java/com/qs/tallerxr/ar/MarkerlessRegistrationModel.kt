package com.qs.tallerxr.ar

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class MarkerlessRegistrationModel(
    val points: FloatArray,
    val normals: FloatArray,
    val featureEdges: FloatArray,
    val boundsMin: FloatArray,
    val boundsMax: FloatArray,
    val sampleSpacingM: Float,
) {
    val pointCount: Int get() = points.size / 3
    val edgeCount: Int get() = featureEdges.size / 6
    val diameterM: Float
        get() {
            val x = boundsMax[0] - boundsMin[0]
            val y = boundsMax[1] - boundsMin[1]
            val z = boundsMax[2] - boundsMin[2]
            return sqrt(x * x + y * y + z * z)
        }
}

object MarkerlessRegistrationModelReader {
    private const val HEADER_BYTES = 48
    private const val MAXIMUM_POINTS = 12_000
    private const val MAXIMUM_EDGES = 6_000
    private const val FLAG_NORMALS = 1
    private val MAGIC = byteArrayOf('Q'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte(), 'G'.code.toByte())

    fun read(file: File): MarkerlessRegistrationModel {
        require(file.isFile) { "Das Referenzmodell für die Ausrichtung wurde nicht gefunden." }
        return decode(file.readBytes())
    }

    internal fun decode(payload: ByteArray): MarkerlessRegistrationModel {
        require(payload.size >= HEADER_BYTES) { "Das Referenzmodell enthält keinen vollständigen Header." }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Das Referenzmodell verwendet nicht das QSRG-Format." }
        val major = buffer.short.toInt() and 0xffff
        val minor = buffer.short.toInt() and 0xffff
        require(major == 1 && minor <= 0) { "Nicht unterstützte QSRG-Version: $major.$minor." }
        val pointCount = buffer.int
        val edgeCount = buffer.int
        require(pointCount in 1..MAXIMUM_POINTS && edgeCount in 0..MAXIMUM_EDGES) {
            "Das Referenzmodell enthält zu viele Datensätze."
        }
        val boundsMin = FloatArray(3) { buffer.float }
        val boundsMax = FloatArray(3) { buffer.float }
        val spacing = buffer.float
        val flags = buffer.int
        require(flags and FLAG_NORMALS != 0) { "Das Referenzmodell enthält keine Normalen." }
        require(spacing.isFinite() && spacing > 0f) { "Die Auflösung des Referenzmodells ist ungültig." }
        require((boundsMin + boundsMax).all(Float::isFinite)) { "Die Grenzen des Referenzmodells sind nicht endlich." }
        require((0..2).all { boundsMax[it] >= boundsMin[it] }) {
            "Die Grenzen des Referenzmodells sind vertauscht."
        }
        val expected = HEADER_BYTES + (pointCount + edgeCount) * 6 * Float.SIZE_BYTES
        require(payload.size == expected) { "Die Größe des Referenzmodells passt nicht zu seinem Header." }

        val points = FloatArray(pointCount * 3)
        val normals = FloatArray(pointCount * 3)
        repeat(pointCount) { point ->
            val offset = point * 3
            points[offset] = buffer.float
            points[offset + 1] = buffer.float
            points[offset + 2] = buffer.float
            normals[offset] = buffer.float
            normals[offset + 1] = buffer.float
            normals[offset + 2] = buffer.float
        }
        val edges = FloatArray(edgeCount * 6) { buffer.float }
        require((points + normals + edges).all(Float::isFinite)) {
            "Das Referenzmodell enthält nicht endliche Werte."
        }
        return MarkerlessRegistrationModel(
            points = points,
            normals = normals,
            featureEdges = edges,
            boundsMin = boundsMin,
            boundsMax = boundsMax,
            sampleSpacingM = spacing,
        )
    }
}
