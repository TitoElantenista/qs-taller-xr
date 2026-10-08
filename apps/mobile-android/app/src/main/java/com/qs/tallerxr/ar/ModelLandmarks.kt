package com.qs.tallerxr.ar

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Snap targets of one part: [holeCentre] marks the entries that are the centre of a bolt hole,
 * and [facing] holds, for those, the outward normal of the plate face the centre lies on (zero
 * for corners).  A hole centre is only offered to a camera on that side: the point itself sits in
 * the air of the hole, so a ray test alone would show the far face's centre through the hole.
 */
class ModelLandmarkSet(val points: FloatArray, val holeCentre: BooleanArray, val facing: FloatArray = FloatArray(points.size)) {
    init { require(points.size == holeCentre.size * 3 && facing.size == points.size) }
    val count: Int get() = holeCentre.size
    fun point(index: Int): FloatArray = floatArrayOf(points[index * 3], points[index * 3 + 1], points[index * 3 + 2])

    /** True when [camera] is on the side this landmark faces; corners face every side. */
    fun faces(index: Int, camera: FloatArray): Boolean {
        if (!holeCentre[index]) return true
        val base = index * 3
        return (camera[0] - points[base]) * facing[base] + (camera[1] - points[base + 1]) * facing[base + 1] +
            (camera[2] - points[base + 2]) * facing[base + 2] > 0f
    }

    companion object {
        val EMPTY = ModelLandmarkSet(FloatArray(0), BooleanArray(0))
    }
}

/**
 * Snap targets for the operator's CAD marks.
 *
 * A point in the middle of a flat web is useless in the workshop: nobody can find it again on the
 * real part.  Only landmarks an operator can recognise are offered: the corners where feature
 * edges of the QSRG meet, the centres of round bolt holes, and the extreme points of the sampled
 * surface for packages whose tracking target carries no edges.
 *
 * Nothing here scales with the size of the part.  A long beam has end plates whose outer corners
 * sit one plate thickness away from the flange ends; a merge distance that grew with the length
 * (0.33.0 and before: 1.2 % of the diagonal, 12 cm on a 10 m beam) folded them into the flange
 * ends behind the plate, and the corner the operator could see was never offered.
 *
 * Bolt holes are the other extreme: the phone's IFC tessellation leaves a ring of short wall edges
 * around every hole, a dozen endpoints a few millimetres apart.  None of them can be aimed at in
 * the camera, but the centre of the hole can.  The wall edges of one hole are parallel and their
 * endpoints lie on a circle, so each round hole becomes one target per plate face at its centre;
 * every other vertex that only short edges reach (tessellated arcs, slots, fillets) is dropped.
 */
object ModelLandmarks {
    private const val EDGE_WEIGHT = 2f
    private const val SUPPORT_WEIGHT = 1f
    private const val HOLE_WEIGHT = 3f

    /** Per further edge direction meeting at a vertex: a plate corner outranks a tessellation vertex. */
    private const val CORNER_BONUS = 0.5f
    private const val MAXIMUM_LINES = 3

    /** Nearer than this, two corners are one to an operator: weld seam, rounding, chamfer. */
    const val SEPARATION_M = 0.006f

    /** Edge directions within 25° of each other (or opposite) continue the same line. */
    private const val SAME_LINE_COSINE = 0.906f
    private const val VERTEX_QUANTUM_M = 1.0e-4

    /** Hole walls, rim segments and tessellated arcs are shorter than this; plate sides are longer. */
    private const val SHORT_EDGE_M = 0.03f

    /** Wall edges of one hole lie nearer to each other than this; neighbouring holes further apart. */
    private const val HOLE_LINK_M = 0.02f
    private const val HOLE_MINIMUM_WALLS = 6
    private const val HOLE_RADIUS_MINIMUM_M = 0.004f
    private const val HOLE_RADIUS_MAXIMUM_M = 0.06f
    private const val HOLE_MAXIMUM_GAP_DEGREES = 100.0
    private const val PARALLEL_COSINE = 0.996f
    private const val HOLE_RIM_TOLERANCE_M = 0.002f

    fun corners(model: MarkerlessRegistrationModel, maximumCount: Int = 400): FloatArray =
        landmarks(model, maximumCount).points

    fun landmarks(
        model: MarkerlessRegistrationModel,
        maximumCount: Int = 400,
    ): ModelLandmarkSet {
        require(maximumCount > 0)
        val separation = SEPARATION_M
        val centre = FloatArray(3) { axis -> (model.boundsMin[axis] + model.boundsMax[axis]) / 2f }
        val candidates = HashMap<Long, Candidate>()
        fun offer(x: Float, y: Float, z: Float, weight: Float, hole: Boolean, facing: FloatArray? = null) {
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
            val key = cellKey(x, y, z, separation)
            val existing = candidates[key]
            if (existing == null || weight > existing.weight) {
                candidates[key] = Candidate(x, y, z, weight, hole, facing)
            }
        }

        val holes = detectHoles(model.featureEdges)
        val vertices = verticesOf(model.featureEdges)
        var index = 0
        while (index + 5 < model.featureEdges.size) {
            for (end in 0..1) {
                val x = model.featureEdges[index + end * 3]
                val y = model.featureEdges[index + end * 3 + 1]
                val z = model.featureEdges[index + end * 3 + 2]
                val vertex = vertices[vertexKey(x, y, z)] ?: continue
                // Only short edges reach this vertex along one line: a rim or arc point, not a corner.
                if (vertex.longestEdge < SHORT_EDGE_M && vertex.lines.size <= 1) continue
                if (holes.any { it.contains(x, y, z) }) continue
                offer(x, y, z, EDGE_WEIGHT + min(vertex.lines.size, MAXIMUM_LINES) * CORNER_BONUS, hole = false)
            }
            index += 6
        }
        holes.forEach { hole ->
            hole.faceCentres().forEachIndexed { face, centre ->
                val outward = FloatArray(3) { if (face == 0) -hole.axis[it] else hole.axis[it] }
                offer(centre[0], centre[1], centre[2], HOLE_WEIGHT, hole = true, facing = outward)
            }
        }
        supportDirections().forEach { direction ->
            var bestIndex = -1
            var bestProjection = Float.NEGATIVE_INFINITY
            var point = 0
            while (point + 2 < model.points.size) {
                val projection = model.points[point] * direction[0] +
                    model.points[point + 1] * direction[1] +
                    model.points[point + 2] * direction[2]
                if (projection > bestProjection) {
                    bestProjection = projection
                    bestIndex = point
                }
                point += 3
            }
            if (bestIndex >= 0) {
                offer(
                    model.points[bestIndex],
                    model.points[bestIndex + 1],
                    model.points[bestIndex + 2],
                    SUPPORT_WEIGHT,
                    hole = false,
                )
            }
        }
        if (candidates.isEmpty()) return ModelLandmarkSet.EMPTY

        // Real corners and hole centres first, then the ones far from the centre: those are the
        // targets an operator can name on the real part, so they survive when the cap trims the list.
        val ordered = candidates.values.sortedWith(
            compareByDescending<Candidate> { it.weight }
                .thenByDescending { it.squaredDistanceTo(centre) },
        )
        val accepted = ArrayList<Candidate>(minOf(maximumCount, ordered.size))
        val minimumSquared = separation * separation
        ordered.forEach { candidate ->
            if (accepted.size >= maximumCount) return@forEach
            if (accepted.none { it.squaredDistanceTo(candidate) < minimumSquared }) {
                accepted += candidate
            }
        }
        val points = FloatArray(accepted.size * 3)
        val holeCentre = BooleanArray(accepted.size)
        val facing = FloatArray(accepted.size * 3)
        accepted.forEachIndexed { position, candidate ->
            points[position * 3] = candidate.x
            points[position * 3 + 1] = candidate.y
            points[position * 3 + 2] = candidate.z
            holeCentre[position] = candidate.hole
            candidate.facing?.let { it.copyInto(facing, position * 3) }
        }
        return ModelLandmarkSet(points, holeCentre, facing)
    }

    /** A round hole: axis through [base] along [axis], walls between [startAlong] and [endAlong]. */
    internal class Hole(
        val base: FloatArray,
        val axis: FloatArray,
        val radius: Float,
        val startAlong: Float,
        val endAlong: Float,
    ) {
        fun faceCentres(): List<FloatArray> = listOf(startAlong, endAlong).map { along ->
            FloatArray(3) { base[it] + axis[it] * along }
        }

        /** True for the rim and wall vertices of this hole. */
        fun contains(x: Float, y: Float, z: Float): Boolean {
            val dx = x - base[0]
            val dy = y - base[1]
            val dz = z - base[2]
            val along = dx * axis[0] + dy * axis[1] + dz * axis[2]
            if (along < startAlong - HOLE_RIM_TOLERANCE_M || along > endAlong + HOLE_RIM_TOLERANCE_M) return false
            val radial = sqrt(max(0f, dx * dx + dy * dy + dz * dz - along * along))
            return radial <= radius + HOLE_RIM_TOLERANCE_M
        }
    }

    private class Wall(val start: FloatArray, val end: FloatArray, val direction: FloatArray, val length: Float)

    /**
     * Finds round holes from their wall edges: short, parallel, their midpoints on one circle
     * (least squares fit), spread around most of it, with a radius a bolt hole can have.
     */
    internal fun detectHoles(featureEdges: FloatArray): List<Hole> {
        val walls = ArrayList<Wall>()
        var index = 0
        while (index + 5 < featureEdges.size) {
            val start = floatArrayOf(featureEdges[index], featureEdges[index + 1], featureEdges[index + 2])
            val end = floatArrayOf(featureEdges[index + 3], featureEdges[index + 4], featureEdges[index + 5])
            index += 6
            val dx = end[0] - start[0]
            val dy = end[1] - start[1]
            val dz = end[2] - start[2]
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (!length.isFinite() || length < 1.0e-6f || length >= SHORT_EDGE_M) continue
            val direction = floatArrayOf(dx / length, dy / length, dz / length)
            // One sign per line, so opposite edges of the same wall ring group together.
            val lead = (0..2).maxBy { abs(direction[it]) }
            if (direction[lead] < 0f) for (axis in 0..2) direction[axis] = -direction[axis]
            walls += Wall(start, end, direction, length)
        }
        val groups = ArrayList<MutableList<Wall>>()
        walls.forEach { wall ->
            val group = groups.firstOrNull { abs(dot(it[0].direction, wall.direction)) >= PARALLEL_COSINE }
            if (group != null) group += wall else groups += mutableListOf(wall)
        }
        val holes = ArrayList<Hole>()
        groups.forEach { group ->
            if (group.size < HOLE_MINIMUM_WALLS) return@forEach
            val axis = group[0].direction
            val (u, v) = perpendicularBasis(axis)
            val mid = group.map { wall ->
                floatArrayOf(
                    (wall.start[0] + wall.end[0]) / 2f, (wall.start[1] + wall.end[1]) / 2f, (wall.start[2] + wall.end[2]) / 2f,
                )
            }
            val planar = mid.map { floatArrayOf(dot(it, u), dot(it, v)) }
            // Single linkage in 3D: neighbouring wall edges of one hole are a few millimetres apart,
            // the same hole in a plate further along the axis (a Futterblech behind an end plate)
            // is a plate pitch away and must not join, or the circle fits neither ring.
            val parent = IntArray(group.size) { it }
            fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
            val linkSquared = HOLE_LINK_M * HOLE_LINK_M
            for (i in group.indices) for (j in i + 1 until group.size) {
                val ddx = mid[i][0] - mid[j][0]
                val ddy = mid[i][1] - mid[j][1]
                val ddz = mid[i][2] - mid[j][2]
                if (ddx * ddx + ddy * ddy + ddz * ddz <= linkSquared) {
                    val ri = root(i); val rj = root(j)
                    if (ri != rj) parent[ri] = rj
                }
            }
            group.indices.groupBy { root(it) }.values.forEach cluster@{ members ->
                if (members.size < HOLE_MINIMUM_WALLS) return@cluster
                val lengths = members.map { group[it].length }
                if (lengths.max() > lengths.min() * 1.5f) return@cluster
                val fit = fitCircle(members.map { planar[it] }) ?: return@cluster
                val (cx, cy, radius) = fit
                if (radius < HOLE_RADIUS_MINIMUM_M || radius > HOLE_RADIUS_MAXIMUM_M) return@cluster
                val tolerance = max(0.0005f, radius * 0.03f)
                var residual = 0f
                members.forEach { member ->
                    val p = planar[member]
                    val d = sqrt((p[0] - cx) * (p[0] - cx) + (p[1] - cy) * (p[1] - cy)) - radius
                    residual += d * d
                }
                if (sqrt(residual / members.size) > tolerance) return@cluster
                val angles = members.map { member ->
                    val p = planar[member]
                    Math.toDegrees(atan2((p[1] - cy).toDouble(), (p[0] - cx).toDouble()))
                }.sorted()
                var gap = angles.first() + 360.0 - angles.last()
                for (i in 1 until angles.size) gap = max(gap, angles[i] - angles[i - 1])
                if (gap > HOLE_MAXIMUM_GAP_DEGREES) return@cluster
                val base = FloatArray(3) { u[it] * cx + v[it] * cy }
                var startAlong = Float.POSITIVE_INFINITY
                var endAlong = Float.NEGATIVE_INFINITY
                members.forEach { member ->
                    val wall = group[member]
                    listOf(wall.start, wall.end).forEach { p ->
                        val along = dot(p, axis) - dot(base, axis)
                        startAlong = min(startAlong, along)
                        endAlong = max(endAlong, along)
                    }
                }
                holes += Hole(base, axis, radius, startAlong, endAlong)
            }
        }
        return holes
    }

    /** Kåsa algebraic circle fit: centre x, centre y, radius; null when the points are degenerate. */
    private fun fitCircle(points: List<FloatArray>): Triple<Float, Float, Float>? {
        val meanX = points.map { it[0] }.average()
        val meanY = points.map { it[1] }.average()
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        points.forEach { p ->
            val x = p[0] - meanX
            val y = p[1] - meanY
            val z = x * x + y * y
            sxx += x * x; sxy += x * y; syy += y * y; sx += x; sy += y
            sxz += x * z; syz += y * z; sz += z
        }
        val n = points.size.toDouble()
        // Rows of the normal equations for a, b, c in x² + y² + a x + b y + c = 0.
        val m = arrayOf(
            doubleArrayOf(sxx, sxy, sx, -sxz),
            doubleArrayOf(sxy, syy, sy, -syz),
            doubleArrayOf(sx, sy, n, -sz),
        )
        for (column in 0..2) {
            var pivot = column
            for (row in column + 1..2) if (abs(m[row][column]) > abs(m[pivot][column])) pivot = row
            if (abs(m[pivot][column]) < 1.0e-18) return null
            val swap = m[column]; m[column] = m[pivot]; m[pivot] = swap
            for (row in 0..2) {
                if (row == column) continue
                val factor = m[row][column] / m[column][column]
                for (k in column..3) m[row][k] -= factor * m[column][k]
            }
        }
        val a = m[0][3] / m[0][0]
        val b = m[1][3] / m[1][1]
        val c = m[2][3] / m[2][2]
        val cx = -a / 2.0
        val cy = -b / 2.0
        val radiusSquared = cx * cx + cy * cy - c
        if (!(radiusSquared > 0.0) || !radiusSquared.isFinite()) return null
        return Triple((cx + meanX).toFloat(), (cy + meanY).toFloat(), sqrt(radiusSquared).toFloat())
    }

    private fun perpendicularBasis(axis: FloatArray): Pair<FloatArray, FloatArray> {
        val helper = if (abs(axis[0]) < 0.9f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
        val u = cross(axis, helper).let { normalised(it) }
        val v = cross(axis, u)
        return u to v
    }

    private class Vertex {
        /** Distinct lines of feature edges meeting here: three at a plate corner, one along an arc. */
        val lines = ArrayList<FloatArray>(3)
        var longestEdge = 0f
    }

    private fun verticesOf(featureEdges: FloatArray): Map<Long, Vertex> {
        val vertices = HashMap<Long, Vertex>()
        var index = 0
        while (index + 5 < featureEdges.size) {
            val dx = featureEdges[index + 3] - featureEdges[index]
            val dy = featureEdges[index + 4] - featureEdges[index + 1]
            val dz = featureEdges[index + 5] - featureEdges[index + 2]
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (length.isFinite() && length > 1.0e-6f) {
                val direction = floatArrayOf(dx / length, dy / length, dz / length)
                for (end in 0..1) {
                    val key = vertexKey(featureEdges[index + end * 3], featureEdges[index + end * 3 + 1],
                        featureEdges[index + end * 3 + 2])
                    val vertex = vertices.getOrPut(key) { Vertex() }
                    vertex.longestEdge = max(vertex.longestEdge, length)
                    if (vertex.lines.size < MAXIMUM_LINES && vertex.lines.none { abs(dot(it, direction)) >= SAME_LINE_COSINE }) {
                        vertex.lines += direction
                    }
                }
            }
            index += 6
        }
        return vertices
    }

    private class Candidate(val x: Float, val y: Float, val z: Float, val weight: Float, val hole: Boolean, val facing: FloatArray?) {
        fun squaredDistanceTo(other: Candidate): Float {
            val dx = x - other.x
            val dy = y - other.y
            val dz = z - other.z
            return dx * dx + dy * dy + dz * dz
        }

        fun squaredDistanceTo(point: FloatArray): Float {
            val dx = x - point[0]
            val dy = y - point[1]
            val dz = z - point[2]
            return dx * dx + dy * dy + dz * dz
        }
    }

    /** Exact packing of three cell indices (±1 million cells each): no two cells share a key. */
    private fun cellKey(x: Float, y: Float, z: Float, separation: Float): Long =
        packed(floor(x / separation).toLong(), floor(y / separation).toLong(), floor(z / separation).toLong())

    private fun vertexKey(x: Float, y: Float, z: Float): Long = packed(
        (x / VERTEX_QUANTUM_M).roundToLong(), (y / VERTEX_QUANTUM_M).roundToLong(), (z / VERTEX_QUANTUM_M).roundToLong(),
    )

    private fun packed(cx: Long, cy: Long, cz: Long): Long =
        ((cx and 0x1FFFFF) shl 42) or ((cy and 0x1FFFFF) shl 21) or (cz and 0x1FFFFF)

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun normalised(a: FloatArray): FloatArray {
        val length = sqrt(dot(a, a)).coerceAtLeast(1.0e-9f)
        return floatArrayOf(a[0] / length, a[1] / length, a[2] / length)
    }

    private fun supportDirections(): List<FloatArray> = buildList {
        for (x in -1..1) {
            for (y in -1..1) {
                for (z in -1..1) {
                    if (x == 0 && y == 0 && z == 0) continue
                    val length = sqrt((x * x + y * y + z * z).toFloat())
                    add(floatArrayOf(x / length, y / length, z / length))
                }
            }
        }
    }
}
