package com.qs.tallerxr.ar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/** Plain copied bytes and the same frame's rotation/crop. Conversion runs off the render thread. */
internal class ArCameraImage(
    val width: Int,
    val height: Int,
    val planes: List<CameraPlane>,
    val viewportCornersInImage: FloatArray,
    val viewportWidth: Int,
    val viewportHeight: Int,
) {
    fun bitmap(): Bitmap {
        val source = Bitmap.createBitmap(cameraArgb(width, height, planes), width, height, Bitmap.Config.ARGB_8888)
        try {
            // Retain the camera image's actual pixel density, without allocating a screen-sized
            // upsample. The UI and the observation geometry share the original viewport mapping.
            val horizontal = kotlin.math.hypot(
                viewportCornersInImage[2] - viewportCornersInImage[0],
                viewportCornersInImage[3] - viewportCornersInImage[1],
            )
            val ratio = (horizontal / viewportWidth).coerceAtMost(1f)
            val outputWidth = (viewportWidth * ratio).toInt().coerceAtLeast(1)
            val outputHeight = (viewportHeight * ratio).toInt().coerceAtLeast(1)
            val target = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            try {
                val matrix = Matrix()
                check(matrix.setPolyToPoly(viewportCornersInImage, 0,
                    floatArrayOf(0f, 0f, outputWidth.toFloat(), 0f, 0f, outputHeight.toFloat()), 0, 3))
                Canvas(target).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                return target
            } catch (error: Exception) {
                target.recycle()
                throw error
            }
        } finally {
            source.recycle()
        }
    }
}
