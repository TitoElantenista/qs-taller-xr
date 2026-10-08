package com.qs.tallerxr.ar

import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Diagnostic over a real QSRG (`QS_LONG_PART_QSRG=/path/to/registration_model.bin`): which corners
 * the viewer offers at both ends of the longest axis, before (0.33.0 rule) and after 0.33.1, and
 * which feature-edge endpoints near the ends are not offered.  Writes `QS_LONG_PART_REPORT` when
 * set, otherwise prints.  Skipped unless the variable is set, so the normal suite stays unchanged.
 */
class LongPartLandmarksProbe {

    @Test
    fun `report the corners offered at both ends of a long part`() {
        val path = System.getenv("QS_LONG_PART_QSRG")
        assumeTrue("QS_LONG_PART_QSRG nicht gesetzt", !path.isNullOrBlank() && File(path).isFile)
        val model = MarkerlessRegistrationModelReader.read(File(path!!))
        val axis = (0..2).maxBy { model.boundsMax[it] - model.boundsMin[it] }
        val endZone = 0.35f
        val set = ModelLandmarks.landmarks(model)
        val current = set.points
        val holeCentres = (0 until set.count).filter { set.holeCentre[it] }.map { set.point(it) }
        val previous = previousCorners(model)
        val endpoints = edgeEndpoints(model)
        val lines = StringBuilder()
        lines.appendLine("QSRG: ${model.pointCount} Punkte, ${model.edgeCount} Kanten, Abstand ${model.sampleSpacingM} m, " +
            "Diagonale ${"%.3f".format(model.diameterM)} m, Achse ${"xyz"[axis]} " +
            "${"%.3f".format(model.boundsMin[axis])}..${"%.3f".format(model.boundsMax[axis])}")
        lines.appendLine("Ecken gesamt: 0.33.0 = ${previous.size / 3}, jetzt = ${current.size / 3} " +
            "(davon ${holeCentres.size} Bohrungsmitten); Kantenendpunkte (eindeutig) = ${endpoints.size / 3}")
        for (end in 0..1) {
            val limit = if (end == 0) model.boundsMin[axis] + endZone else model.boundsMax[axis] - endZone
            fun near(x: Float) = if (end == 0) x <= limit else x >= limit
            fun list(points: FloatArray) = (0 until points.size / 3).filter { near(points[it * 3 + axis]) }
                .map { floatArrayOf(points[it * 3], points[it * 3 + 1], points[it * 3 + 2]) }
                .sortedWith(compareBy({ it[axis] }, { it[(axis + 1) % 3] }, { it[(axis + 2) % 3] }))
            val before = list(previous)
            val after = list(current)
            val vertices = list(endpoints)
            lines.appendLine()
            val centresHere = holeCentres.filter { near(it[axis]) }
            lines.appendLine("== Ende ${if (end == 0) "min" else "max"} (${endZone} m): Kantenendpunkte ${vertices.size}, " +
                "angeboten 0.33.0 = ${before.size}, jetzt = ${after.size}, Bohrungsmitten ${centresHere.size}")
            centresHere.forEach { lines.appendLine("  Bohrung " + it.joinToString(" ") { v -> "%8.4f".format(v) }) }
            vertices.forEach { vertex ->
                val wasOffered = before.any { same(it, vertex) }
                val isOffered = after.any { same(it, vertex) }
                val mark = when {
                    wasOffered && isOffered -> "  beide "
                    isOffered -> "  NEU   "
                    wasOffered -> "  WEG   "
                    else -> "  fehlt "
                }
                lines.appendLine(mark + vertex.joinToString(" ") { "%8.4f".format(it) })
            }
        }
        val report = System.getenv("QS_LONG_PART_REPORT")
        if (!report.isNullOrBlank()) File(report).writeText(lines.toString()) else println(lines)
    }

    private fun same(a: FloatArray, b: FloatArray): Boolean =
        (0..2).all { kotlin.math.abs(a[it] - b[it]) < 1e-5f }

    private fun edgeEndpoints(model: MarkerlessRegistrationModel): FloatArray {
        val seen = LinkedHashMap<Triple<Float, Float, Float>, Unit>()
        var index = 0
        while (index + 5 < model.featureEdges.size) {
            for (end in 0..1) {
                seen[Triple(model.featureEdges[index + end * 3], model.featureEdges[index + end * 3 + 1],
                    model.featureEdges[index + end * 3 + 2])] = Unit
            }
            index += 6
        }
        val result = FloatArray(seen.size * 3)
        seen.keys.forEachIndexed { i, (x, y, z) -> result[i * 3] = x; result[i * 3 + 1] = y; result[i * 3 + 2] = z }
        return result
    }

    /** The 0.33.0 rule: merge cell and minimum separation of max(2.5 × spacing, 1.2 % of the diagonal). */
    private fun previousCorners(model: MarkerlessRegistrationModel, maximumCount: Int = 240): FloatArray {
        val separation = max(model.sampleSpacingM * 2.5f, model.diameterM * 0.012f).coerceAtLeast(0.002f)
        val centre = FloatArray(3) { axis -> (model.boundsMin[axis] + model.boundsMax[axis]) / 2f }
        class Candidate(val x: Float, val y: Float, val z: Float, val weight: Float) {
            fun d2(o: Candidate) = (x - o.x) * (x - o.x) + (y - o.y) * (y - o.y) + (z - o.z) * (z - o.z)
            fun d2(p: FloatArray) = (x - p[0]) * (x - p[0]) + (y - p[1]) * (y - p[1]) + (z - p[2]) * (z - p[2])
        }
        val candidates = HashMap<Long, Candidate>()
        fun offer(x: Float, y: Float, z: Float, weight: Float) {
            val key = (floor(x / separation).toLong() * 73_856_093L) xor (floor(y / separation).toLong() * 19_349_663L) xor
                (floor(z / separation).toLong() * 83_492_791L)
            val existing = candidates[key]
            if (existing == null || weight > existing.weight) candidates[key] = Candidate(x, y, z, weight)
        }
        var index = 0
        while (index + 5 < model.featureEdges.size) {
            offer(model.featureEdges[index], model.featureEdges[index + 1], model.featureEdges[index + 2], 2f)
            offer(model.featureEdges[index + 3], model.featureEdges[index + 4], model.featureEdges[index + 5], 2f)
            index += 6
        }
        val ordered = candidates.values.sortedWith(compareByDescending<Candidate> { it.weight }.thenByDescending { it.d2(centre) })
        val accepted = ArrayList<Candidate>()
        ordered.forEach { c -> if (accepted.size < maximumCount && accepted.none { it.d2(c) < separation * separation }) accepted += c }
        return FloatArray(accepted.size * 3).also { out ->
            accepted.forEachIndexed { i, c -> out[i * 3] = c.x; out[i * 3 + 1] = c.y; out[i * 3 + 2] = c.z }
        }
    }

    @Suppress("unused")
    private fun length(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
}
