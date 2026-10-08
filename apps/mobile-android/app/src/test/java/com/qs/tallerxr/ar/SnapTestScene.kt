package com.qs.tallerxr.ar

import com.google.ar.core.Pose
import com.qs.tallerxr.importer.GlbReader
import java.io.File
import java.util.Random
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Camera images for the snap tests: the bundled TV bench demo standing on a laminate floor whose plank joints run parallel to the bench front, in
 * front of a wall with frames and cables.  Rendered with a z-buffer at twice the resolution,
 * shaded per face with the colours of the GLB, plus sensor noise.
 */
internal object SnapTestScene {
    class Part(val triangles: FloatArray, val albedo: Float)

    class Bench(val parts: List<Part>, val registration: MarkerlessRegistrationModel) {
        val triangles: FloatArray = parts.fold(FloatArray(0)) { all, part -> all + part.triangles }
        val model = SnapModel(registration.featureEdges, registration.boundsMin, registration.boundsMax)
    }

    val bench: Bench by lazy {
        val asset = File("src/debug/assets/bundled/tv-bank-148x47x57.qsxr")
        ZipFile(asset).use { zip ->
            val glb = zip.getInputStream(zip.getEntry("assembly.glb")).readBytes()
            val registration = MarkerlessRegistrationModelReader.decode(
                zip.getInputStream(zip.getEntry("registration_model.bin")).readBytes())
            val scene = GlbReader.parse(glb)
            val nodes = scene.document["nodes"]!!.jsonArray
            val meshes = scene.document["meshes"]!!.jsonArray
            val materials = scene.document["materials"]!!.jsonArray
            val parts = scene.meshes.map { mesh ->
                val node = nodes[mesh.nodeIndex].jsonObject
                val primitive = meshes[node["mesh"]!!.jsonPrimitive.content.toInt()].jsonObject["primitives"]!!
                    .jsonArray[0].jsonObject
                val material = materials[primitive["material"]!!.jsonPrimitive.content.toInt()].jsonObject
                val colour = ((material["pbrMetallicRoughness"] as JsonObject)["baseColorFactor"] as JsonArray)
                    .map { it.jsonPrimitive.content.toFloat() }
                Part(mesh.triangles, 255f * (0.299f * colour[0] + 0.587f * colour[1] + 0.114f * colour[2]))
            }
            Bench(parts, registration)
        }
    }

    const val WIDTH = 600
    const val HEIGHT = 1000
    const val FOCAL = 850f

    fun projection(width: Int = WIDTH, height: Int = HEIGHT, focal: Float = FOCAL) = FloatArray(16).also {
        val near = 0.05f
        val far = 60f
        it[0] = 2f * focal / width
        it[5] = 2f * focal / height
        it[10] = -(far + near) / (far - near)
        it[11] = -1f
        it[14] = -2f * far * near / (far - near)
    }

    /** Camera at [eye] looking at [target], +Y up; it looks along its own -Z like ARCore. */
    fun lookAt(eye: FloatArray, target: FloatArray): Pose {
        val back = normalized(FloatArray(3) { eye[it] - target[it] })
        val right = normalized(cross(floatArrayOf(0f, 1f, 0f), back))
        val up = cross(back, right)
        return Pose(eye, quaternion(floatArrayOf(
            right[0], up[0], back[0],
            right[1], up[1], back[1],
            right[2], up[2], back[2],
        )))
    }

    /** Geometry of a camera in the world, which is also the reference frame of these tests. */
    fun geometry(camera: Pose, width: Int = WIDTH, height: Int = HEIGHT, focal: Float = FOCAL) = CapturedAlignmentView(
        width, height, projection(width, height, focal),
        FloatArray(16).also { camera.inverse().toMatrix(it, 0) },
        camera, Pose.IDENTITY, camera, focal,
    )

    /** The floor is the plane y = 0 of the world; [bench] stands on it at [pose] unless null. */
    fun render(
        camera: Pose,
        pose: FloorPose?,
        seed: Long,
        noise: Double = 4.0,
        wallZ: Float = -0.6f,
    ): LuminanceImage {
        val random = Random(seed)
        val scale = 2
        val w = WIDTH * scale
        val h = HEIGHT * scale
        val f = FOCAL * scale
        val depth = FloatArray(w * h) { Float.MAX_VALUE }
        val shade = FloatArray(w * h)
        val toCamera = camera.inverse()
        val light = normalized(floatArrayOf(0.35f, 0.85f, 0.4f))
        if (pose != null) {
            val world = bench.model.referenceFromModel(pose, 0f)
            for (part in bench.parts) {
                val t = part.triangles
                for (o in t.indices step 9) {
                    val a = world.transformPoint(floatArrayOf(t[o], t[o + 1], t[o + 2]))
                    val b = world.transformPoint(floatArrayOf(t[o + 3], t[o + 4], t[o + 5]))
                    val c = world.transformPoint(floatArrayOf(t[o + 6], t[o + 7], t[o + 8]))
                    val n = normalized(cross(FloatArray(3) { b[it] - a[it] }, FloatArray(3) { c[it] - a[it] }))
                    val lambert = abs(n[0] * light[0] + n[1] * light[1] + n[2] * light[2])
                    val value = part.albedo * (0.55f + 0.45f * lambert)
                    rasterize(toCamera.transformPoint(a), toCamera.transformPoint(b), toCamera.transformPoint(c),
                        value, w, h, f, depth, shade)
                }
            }
        }
        // Plank lengths: every row of planks has its end joints at random places.
        val plankWidth = 0.19f
        val joints = HashMap<Int, FloatArray>()
        val brightness = HashMap<Long, Float>()
        val frames = List(6) {
            floatArrayOf(-1.6f + random.nextFloat() * 3.2f, 0.9f + random.nextFloat() * 0.9f,
                0.15f + random.nextFloat() * 0.4f, 0.15f + random.nextFloat() * 0.4f)
        }
        val grey = FloatArray(w * h)
        val origin = camera.translation
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (depth[i] < Float.MAX_VALUE) {
                grey[i] = shade[i]
                continue
            }
            val direction = camera.rotateVector(floatArrayOf((x + 0.5f - w / 2f) / f, (h / 2f - y - 0.5f) / f, -1f))
            val toFloor = if (direction[1] < -1e-6f) -origin[1] / direction[1] else Float.MAX_VALUE
            val toWall = if (direction[2] < -1e-6f) (wallZ - origin[2]) / direction[2] else Float.MAX_VALUE
            grey[i] = if (toFloor < toWall) {
                val px = origin[0] + direction[0] * toFloor
                val pz = origin[2] + direction[2] * toFloor
                val row = floor(pz / plankWidth).toInt()
                val across = pz / plankWidth - row
                val rowJoints = joints.getOrPut(row) { FloatArray(8) { -4f + it * 1.2f + random.nextFloat() * 0.8f } }
                var piece = 0
                var alongJoint = Float.MAX_VALUE
                for (joint in rowJoints) {
                    if (px > joint) piece++
                    alongJoint = min(alongJoint, abs(px - joint))
                }
                val base = brightness.getOrPut(row * 16L + piece) { 115f + random.nextFloat() * 45f }
                val grain = 7f * sin(px * 23f + row * 1.7f) * sin(px * 5.3f + across * 3f)
                when {
                    min(across, 1f - across) * plankWidth < 0.0025f || alongJoint < 0.0025f -> 70f
                    else -> base + grain
                }
            } else if (toWall < Float.MAX_VALUE) {
                val px = origin[0] + direction[0] * toWall
                val py = origin[1] + direction[1] * toWall
                var value = 205f - 10f * py
                for (frame in frames) {
                    val inside = abs(px - frame[0]) < frame[2] / 2f && abs(py - frame[1]) < frame[3] / 2f
                    val border = inside && (abs(px - frame[0]) > frame[2] / 2f - 0.02f || abs(py - frame[1]) > frame[3] / 2f - 0.02f)
                    if (border) value = 45f else if (inside) value = 150f
                }
                // A skirting board where wall and floor meet.
                if (py < 0.06f) value = 225f
                value
            } else 190f
        }
        val bytes = ByteArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            var sum = 0f
            for (dy in 0 until scale) for (dx in 0 until scale) sum += grey[(y * scale + dy) * w + x * scale + dx]
            val value = sum / (scale * scale) + (random.nextGaussian() * noise).toFloat()
            bytes[y * WIDTH + x] = value.coerceIn(0f, 255f).toInt().toByte()
        }
        return LuminanceImage.upright(WIDTH, HEIGHT, bytes)
    }

    private fun rasterize(
        a: FloatArray, b: FloatArray, c: FloatArray, value: Float,
        w: Int, h: Int, f: Float, depth: FloatArray, shade: FloatArray,
    ) {
        if (a[2] > -0.05f || b[2] > -0.05f || c[2] > -0.05f) return
        val ax = w / 2f + f * a[0] / -a[2]; val ay = h / 2f - f * a[1] / -a[2]
        val bx = w / 2f + f * b[0] / -b[2]; val by = h / 2f - f * b[1] / -b[2]
        val cx = w / 2f + f * c[0] / -c[2]; val cy = h / 2f - f * c[1] / -c[2]
        val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if (abs(area) < 1e-6f) return
        val x0 = max(0, floor(min(ax, min(bx, cx))).toInt())
        val x1 = min(w - 1, floor(max(ax, max(bx, cx))).toInt() + 1)
        val y0 = max(0, floor(min(ay, min(by, cy))).toInt())
        val y1 = min(h - 1, floor(max(ay, max(by, cy))).toInt() + 1)
        for (y in y0..y1) for (x in x0..x1) {
            val px = x + 0.5f
            val py = y + 0.5f
            val w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area
            val w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area
            val w2 = 1f - w0 - w1
            if (w0 < 0f || w1 < 0f || w2 < 0f) continue
            val z = 1f / (w0 / -a[2] + w1 / -b[2] + w2 / -c[2])
            val i = y * w + x
            if (z < depth[i]) {
                depth[i] = z
                shade[i] = value
            }
        }
    }

    fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )

    fun normalized(v: FloatArray): FloatArray {
        val length = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return floatArrayOf(v[0] / length, v[1] / length, v[2] / length)
    }

    fun quaternion(m: FloatArray): FloatArray {
        val trace = m[0] + m[4] + m[8]
        return if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            floatArrayOf((m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s, 0.25f * s)
        } else if (m[0] > m[4] && m[0] > m[8]) {
            val s = sqrt(1f + m[0] - m[4] - m[8]) * 2f
            floatArrayOf(0.25f * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s, (m[7] - m[5]) / s)
        } else if (m[4] > m[8]) {
            val s = sqrt(1f + m[4] - m[0] - m[8]) * 2f
            floatArrayOf((m[1] + m[3]) / s, 0.25f * s, (m[5] + m[7]) / s, (m[2] - m[6]) / s)
        } else {
            val s = sqrt(1f + m[8] - m[0] - m[4]) * 2f
            floatArrayOf((m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25f * s, (m[3] - m[1]) / s)
        }
    }

    fun degrees(value: Float) = Math.toRadians(value.toDouble()).toFloat()
}
