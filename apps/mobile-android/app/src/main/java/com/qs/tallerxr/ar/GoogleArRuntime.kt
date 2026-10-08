package com.qs.tallerxr.ar

import com.google.ar.core.Anchor
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.Coordinates2d
import com.google.ar.core.exceptions.NotYetAvailableException

internal class GoogleArSession(private val session: Session) : ArSessionHandle {
    override fun createAnchor(pose: Pose): ArAnchorHandle = GoogleArAnchor(session.createAnchor(pose))

    override fun horizontalPlanes(): List<SupportPlane> = runCatching {
        session.getAllTrackables(Plane::class.java)
            .filter {
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING &&
                    it.subsumedBy == null
            }
            .map { plane ->
                val centre = plane.centerPose
                val polygon = plane.polygon.duplicate().also { it.rewind() }
                val local = FloatArray(polygon.remaining()).also { polygon.get(it) }
                val outline = FloatArray(local.size)
                for (vertex in 0 until local.size / 2) {
                    val world = centre.transformPoint(floatArrayOf(local[vertex * 2], 0f, local[vertex * 2 + 1]))
                    outline[vertex * 2] = world[0]
                    outline[vertex * 2 + 1] = world[2]
                }
                SupportPlane(centre.ty(), outline)
            }
    }.getOrDefault(emptyList())
}

private class GoogleArAnchor(private val anchor: Anchor) : ArAnchorHandle {
    override val pose get() = anchor.pose
    override val trackingState get() = ArTracking.valueOf(anchor.trackingState.name)
    override fun detach() { anchor.detach() }
}

internal class GoogleArFrame(private val frame: Frame) : ArFrameData {
    override fun copyCameraImage(width: Int, height: Int): ArCameraImage? {
        val image = try { frame.acquireCameraImage() } catch (_: NotYetAvailableException) { return null }
        image.use {
            val corners = FloatArray(6)
            frame.transformCoordinates2d(Coordinates2d.VIEW,
                floatArrayOf(0f, 0f, width.toFloat(), 0f, 0f, height.toFloat()),
                Coordinates2d.IMAGE_PIXELS, corners)
            val planes = image.planes.map { plane ->
                val buffer = plane.buffer.duplicate()
                CameraPlane(ByteArray(buffer.remaining()).also { buffer.get(it) }, plane.rowStride, plane.pixelStride)
            }
            return ArCameraImage(image.width, image.height, planes, corners, width, height)
        }
    }
    override fun copyLuminance(width: Int, height: Int): LuminanceImage? {
        val image = try { frame.acquireCameraImage() } catch (_: NotYetAvailableException) { return null }
        image.use {
            val corners = FloatArray(6)
            frame.transformCoordinates2d(Coordinates2d.VIEW,
                floatArrayOf(0f, 0f, width.toFloat(), 0f, 0f, height.toFloat()),
                Coordinates2d.IMAGE_PIXELS, corners)
            // Only the Y plane: the snap works on grey levels.  Copied at once, image closed at once.
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate()
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            return LuminanceImage(image.width, image.height, bytes, plane.rowStride, plane.pixelStride,
                corners, width, height)
        }
    }
    override val trackingState get() = ArTracking.valueOf(frame.camera.trackingState.name)
    override val cameraPose get() = frame.camera.pose
    override val displayOrientedPose get() = frame.camera.displayOrientedPose
    override fun projection(near: Float, far: Float) = FloatArray(16).also {
        frame.camera.getProjectionMatrix(it, 0, near, far)
    }
    override fun viewMatrix() = FloatArray(16).also { frame.camera.getViewMatrix(it, 0) }
    override fun measuredDistance(origin: FloatArray, direction: FloatArray): Float? = runCatching {
        val hits = frame.hitTest(origin, 0, direction, 0).filter { it.distance in 0.2f..12f }
        (hits.firstOrNull { it.trackable is DepthPoint } ?: hits.firstOrNull { it.trackable is Point })?.distance
    }.getOrNull()
}
