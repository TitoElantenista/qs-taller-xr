package com.qs.tallerxr.importer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Builds the QSRG 1.0 reference of a part on the phone: bounds, a uniform surface cloud and the
 * geometric feature edges.  The AR alignment takes the corners the operator can pick and the
 * marked edges from it.
 *
 * It is a port of `registration_model.py` of the PC exporter and writes the same binary, so a
 * part prepared from a folder and one imported from a `.qsproject` behave identically.  One
 * difference is deliberate: meshes tessellated on the phone contain T-junctions, where the PC
 * rule "an edge used by a single triangle is a boundary" would draw false edges across flat
 * faces.  A single triangle edge only counts when no coplanar neighbour overlaps it.
 */
object RegistrationModelBuilder {
    private const val MINIMUM_POINT_COUNT = 1_024
    private const val MAXIMUM_POINT_COUNT = 12_000
    private const val MAXIMUM_EDGE_COUNT = 6_000
    private const val MINIMUM_SPACING_M = 0.004
    private const val MAXIMUM_SPACING_M = 0.025
    private const val FEATURE_EDGE_DEGREES = 32.0
    private const val FLAG_NORMALS = 1
    private const val FLAG_FEATURE_EDGES = 2

    class Result(
        val payload: ByteArray,
        val pointCount: Int,
        val edgeCount: Int,
        val boundsMin: FloatArray,
        val boundsMax: FloatArray,
        val sampleSpacingM: Float,
    ) {
        val diameterM: Float
            get() = sqrt((0..2).fold(0f) { sum, axis -> sum + (boundsMax[axis] - boundsMin[axis]).let { it * it } })
    }

    /** [triangles] holds nine floats per triangle in the assembly frame, in metres. */
    fun build(triangles: FloatArray): Result {
        val faces = validFaces(triangles)
        if (faces.count == 0) throw GlbFormatException("Das Modell enthält keine Flächen, an denen ausgerichtet werden kann.")
        val lower = DoubleArray(3) { Double.POSITIVE_INFINITY }
        val upper = DoubleArray(3) { Double.NEGATIVE_INFINITY }
        for (face in 0 until faces.count) {
            for (corner in 0 until 9) {
                val value = faces.vertices[face * 9 + corner]
                val axis = corner % 3
                if (value < lower[axis]) lower[axis] = value
                if (value > upper[axis]) upper[axis] = value
            }
        }
        val diameter = sqrt((0..2).sumOf { (upper[it] - lower[it]) * (upper[it] - lower[it]) })
        if (!diameter.isFinite() || diameter <= 1.0e-6) {
            throw GlbFormatException("Das Modell hat keine gültigen physischen Abmessungen.")
        }
        val spacing = (diameter / 120.0).coerceIn(MINIMUM_SPACING_M, MAXIMUM_SPACING_M)
        val samples = sampleSurface(faces, spacing)
        val edges = featureEdges(faces, spacing, diameter)

        val pointCount = samples.size / 6
        val edgeCount = edges.size / 6
        val buffer = ByteBuffer.allocate(48 + (pointCount + edgeCount) * 24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf('Q'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte(), 'G'.code.toByte()))
        buffer.putShort(1)
        buffer.putShort(0)
        buffer.putInt(pointCount)
        buffer.putInt(edgeCount)
        lower.forEach { buffer.putFloat(it.toFloat()) }
        upper.forEach { buffer.putFloat(it.toFloat()) }
        buffer.putFloat(spacing.toFloat())
        buffer.putInt(FLAG_NORMALS or if (edgeCount > 0) FLAG_FEATURE_EDGES else 0)
        samples.forEach { buffer.putFloat(it.toFloat()) }
        edges.forEach { buffer.putFloat(it.toFloat()) }
        return Result(
            payload = buffer.array(),
            pointCount = pointCount,
            edgeCount = edgeCount,
            boundsMin = FloatArray(3) { lower[it].toFloat() },
            boundsMax = FloatArray(3) { upper[it].toFloat() },
            sampleSpacingM = spacing.toFloat(),
        )
    }

    private class Faces(val vertices: DoubleArray, val normals: DoubleArray, val doubleAreas: DoubleArray) {
        val count: Int get() = doubleAreas.size
    }

    private fun validFaces(triangles: FloatArray): Faces {
        val total = triangles.size / 9
        val vertices = DoubleArray(total * 9)
        val normals = DoubleArray(total * 3)
        val areas = DoubleArray(total)
        var kept = 0
        for (face in 0 until total) {
            val base = face * 9
            val ux = (triangles[base + 3] - triangles[base]).toDouble()
            val uy = (triangles[base + 4] - triangles[base + 1]).toDouble()
            val uz = (triangles[base + 5] - triangles[base + 2]).toDouble()
            val vx = (triangles[base + 6] - triangles[base]).toDouble()
            val vy = (triangles[base + 7] - triangles[base + 1]).toDouble()
            val vz = (triangles[base + 8] - triangles[base + 2]).toDouble()
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (!length.isFinite() || length <= 1.0e-12) continue
            for (corner in 0 until 9) vertices[kept * 9 + corner] = triangles[base + corner].toDouble()
            normals[kept * 3] = nx / length
            normals[kept * 3 + 1] = ny / length
            normals[kept * 3 + 2] = nz / length
            areas[kept] = length
            kept += 1
        }
        return Faces(vertices.copyOf(kept * 9), normals.copyOf(kept * 3), areas.copyOf(kept))
    }

    private fun radicalInverse(value: Int, base: Int): Double {
        var source = value
        var result = 0.0
        var factor = 1.0 / base
        while (source > 0) {
            result += (source % base) * factor
            source /= base
            factor /= base
        }
        return result
    }

    /** Area uniform, deterministic samples: position and face normal, six doubles each. */
    private fun sampleSurface(faces: Faces, spacing: Double): DoubleArray {
        val cumulative = DoubleArray(faces.count)
        var sum = 0.0
        for (face in 0 until faces.count) {
            sum += faces.doubleAreas[face]
            cumulative[face] = sum
        }
        val totalArea = sum * 0.5
        val wanted = (totalArea * 0.62 / (spacing * spacing)).roundToLong()
        val count = wanted.coerceIn(MINIMUM_POINT_COUNT.toLong(), MAXIMUM_POINT_COUNT.toLong()).toInt()
        val result = DoubleArray(count * 6)
        var face = 0
        for (sample in 0 until count) {
            val target = (sample + 0.5) * sum / count
            while (face < faces.count - 1 && cumulative[face] < target) face += 1
            val first = sqrt(radicalInverse(sample + 1, 2))
            val second = radicalInverse(sample + 1, 3)
            val weights = doubleArrayOf(1.0 - first, first * (1.0 - second), first * second)
            for (axis in 0..2) {
                result[sample * 6 + axis] = (0..2).sumOf { corner ->
                    weights[corner] * faces.vertices[face * 9 + corner * 3 + axis]
                }
                result[sample * 6 + 3 + axis] = faces.normals[face * 3 + axis]
            }
        }
        return result
    }

    private data class EdgeKey(
        val ax: Long, val ay: Long, val az: Long,
        val bx: Long, val by: Long, val bz: Long,
    ) : Comparable<EdgeKey> {
        override fun compareTo(other: EdgeKey): Int =
            compareValuesBy(this, other, EdgeKey::ax, EdgeKey::ay, EdgeKey::az, EdgeKey::bx, EdgeKey::by, EdgeKey::bz)
    }

    private class EdgeRecord(val start: DoubleArray, val end: DoubleArray) {
        val faces = ArrayList<Int>(2)
    }

    private fun featureEdges(faces: Faces, spacing: Double, diameter: Double): DoubleArray {
        val quantization = maxOf(diameter * 1.0e-7, 1.0e-7)
        val records = HashMap<EdgeKey, EdgeRecord>(faces.count * 2)
        for (face in 0 until faces.count) {
            for (edge in 0..2) {
                val first = face * 9 + edge * 3
                val second = face * 9 + ((edge + 1) % 3) * 3
                val a = LongArray(3) { (faces.vertices[first + it] / quantization).roundToLong() }
                val b = LongArray(3) { (faces.vertices[second + it] / quantization).roundToLong() }
                val forward = compareValuesBy(a, b, { it[0] }, { it[1] }, { it[2] }) <= 0
                val key = if (forward) {
                    EdgeKey(a[0], a[1], a[2], b[0], b[1], b[2])
                } else {
                    EdgeKey(b[0], b[1], b[2], a[0], a[1], a[2])
                }
                val record = records.getOrPut(key) {
                    val start = if (forward) first else second
                    val end = if (forward) second else first
                    EdgeRecord(
                        DoubleArray(3) { faces.vertices[start + it] },
                        DoubleArray(3) { faces.vertices[end + it] },
                    )
                }
                record.faces += face
            }
        }

        val cosineLimit = cos(Math.toRadians(FEATURE_EDGE_DEGREES))
        val open = records.values.filter { it.faces.size == 1 }
        val coplanarOverlap = CoplanarOverlapIndex(faces, open, spacing)
        class Candidate(val length: Double, val key: EdgeKey, val record: EdgeRecord)
        val candidates = ArrayList<Candidate>()
        records.forEach { (key, record) ->
            val length = sqrt((0..2).sumOf { (record.end[it] - record.start[it]).let { d -> d * d } })
            if (length < spacing * 0.45) return@forEach
            val feature = if (record.faces.size == 1) {
                !coplanarOverlap.isInsideFlatFace(record)
            } else {
                var sharp = false
                for (first in record.faces.indices) {
                    for (second in first + 1 until record.faces.size) {
                        val a = record.faces[first] * 3
                        val b = record.faces[second] * 3
                        val dot = faces.normals[a] * faces.normals[b] +
                            faces.normals[a + 1] * faces.normals[b + 1] +
                            faces.normals[a + 2] * faces.normals[b + 2]
                        if (abs(dot) < cosineLimit) sharp = true
                    }
                }
                sharp
            }
            if (feature) candidates += Candidate(length, key, record)
        }
        candidates.sortWith(compareByDescending<Candidate> { it.length }.thenBy { it.key })
        val kept = candidates.take(MAXIMUM_EDGE_COUNT)
        val result = DoubleArray(kept.size * 6)
        kept.forEachIndexed { index, candidate ->
            for (axis in 0..2) {
                result[index * 6 + axis] = candidate.record.start[axis]
                result[index * 6 + 3 + axis] = candidate.record.end[axis]
            }
        }
        return result
    }

    /**
     * Finds open edges that are only open because the tessellation left a T-junction: another
     * open edge of a coplanar triangle lies on the same line and overlaps it.  Such an edge runs
     * through the inside of a flat face and is not a corner the operator could find.
     */
    private class CoplanarOverlapIndex(
        private val faces: Faces,
        open: List<EdgeRecord>,
        spacing: Double,
    ) {
        private val tolerance = maxOf(spacing * 0.02, 2.0e-5)
        private val cell = maxOf(spacing * 4.0, 0.02)
        private val buckets = HashMap<Long, ArrayList<EdgeRecord>>()

        init {
            open.forEach { record -> cellsOf(record).forEach { buckets.getOrPut(it) { ArrayList() } += record } }
        }

        private fun cellsOf(record: EdgeRecord): Set<Long> {
            val length = sqrt((0..2).sumOf { (record.end[it] - record.start[it]).let { d -> d * d } })
            val steps = maxOf(1, (length / cell).roundToInt() + 1)
            return (0..steps).mapTo(HashSet()) { step ->
                val t = step.toDouble() / steps
                val x = (record.start[0] + (record.end[0] - record.start[0]) * t).toCellIndex()
                val y = (record.start[1] + (record.end[1] - record.start[1]) * t).toCellIndex()
                val z = (record.start[2] + (record.end[2] - record.start[2]) * t).toCellIndex()
                (x * 73_856_093L) xor (y * 19_349_663L) xor (z * 83_492_791L)
            }
        }

        private fun Double.toCellIndex(): Long = kotlin.math.floor(this / cell).toLong()

        fun isInsideFlatFace(record: EdgeRecord): Boolean {
            val face = record.faces[0]
            val direction = DoubleArray(3) { record.end[it] - record.start[it] }
            val length = sqrt(direction.sumOf { it * it })
            if (length <= 0.0) return false
            for (axis in 0..2) direction[axis] /= length
            var covered = 0.0
            val seen = HashSet<EdgeRecord>()
            cellsOf(record).forEach { key ->
                buckets[key]?.forEach candidate@{ other ->
                    if (other === record || !seen.add(other)) return@candidate
                    val otherFace = other.faces[0]
                    val dot = (0..2).sumOf { faces.normals[face * 3 + it] * faces.normals[otherFace * 3 + it] }
                    if (abs(dot) < 0.9995) return@candidate
                    val startOffset = DoubleArray(3) { other.start[it] - record.start[it] }
                    val endOffset = DoubleArray(3) { other.end[it] - record.start[it] }
                    val startAlong = (0..2).sumOf { startOffset[it] * direction[it] }
                    val endAlong = (0..2).sumOf { endOffset[it] * direction[it] }
                    val startAcross = sqrt(maxOf(0.0, startOffset.sumOf { it * it } - startAlong * startAlong))
                    val endAcross = sqrt(maxOf(0.0, endOffset.sumOf { it * it } - endAlong * endAlong))
                    if (startAcross > tolerance || endAcross > tolerance) return@candidate
                    val from = maxOf(0.0, minOf(startAlong, endAlong))
                    val to = minOf(length, maxOf(startAlong, endAlong))
                    if (to > from) covered += to - from
                }
            }
            return covered >= length * 0.9
        }
    }
}
