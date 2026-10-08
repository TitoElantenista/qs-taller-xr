package com.qs.tallerxr.ar

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.unit.IntRect
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SavedArPhoto(val uri: Uri, val displayName: String, val capturedAt: String)

object ArPhotoCapture {
    private const val ALBUM = "QS Taller XR"

    suspend fun captureAndSave(
        activity: Activity,
        rootView: View,
        viewport: Rect,
        positionNumber: String,
        positionLabel: String? = null,
    ): SavedArPhoto {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "AR-Fotos erfordern Android 10 oder neuer."
        }
        require(viewport.width() > 0 && viewport.height() > 0) {
            "Die AR-Ansicht hat noch keine gültige Größe."
        }
        // Both AR backends render the camera and CAD to this SurfaceView. Copying the Window
        // only reads the Compose/UI buffer and can succeed with a completely black AR area.
        val bitmap = try {
            captureSurface(rootView, viewport)
        } catch (_: TimeoutCancellationException) {
            error("Die AR-Aufnahme hat zu lange gedauert. Bitte erneut aufnehmen.")
        }
        val capturedAt = java.time.Instant.now().toString()
        try {
            positionLabel
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { drawPositionLabel(bitmap, it, activity.resources.displayMetrics.density) }
            val displayName = arPhotoDisplayName(positionNumber, LocalDateTime.now())
            val uri = withContext(Dispatchers.IO) {
                saveBitmap(activity, bitmap, displayName)
            }
            return SavedArPhoto(uri, displayName, capturedAt)
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawPositionLabel(bitmap: Bitmap, text: String, density: Float) {
        val safeDensity = density.coerceAtLeast(1f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f * safeDensity
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val horizontalPadding = 12f * safeDensity
        val verticalPadding = 8f * safeDensity
        val margin = 14f * safeDensity
        val textWidth = textPaint.measureText(text)
        val fontMetrics = textPaint.fontMetrics
        val boxWidth = textWidth + horizontalPadding * 2f
        val boxHeight = fontMetrics.descent - fontMetrics.ascent + verticalPadding * 2f
        val right = bitmap.width - margin
        val bottom = bitmap.height - margin
        val bounds = RectF(
            (right - boxWidth).coerceAtLeast(margin),
            (bottom - boxHeight).coerceAtLeast(margin),
            right,
            bottom,
        )
        Canvas(bitmap).apply {
            drawRoundRect(
                bounds,
                8f * safeDensity,
                8f * safeDensity,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 7, 16, 25) },
            )
            drawText(
                text,
                bounds.left + horizontalPadding,
                bounds.top + verticalPadding - fontMetrics.ascent,
                textPaint,
            )
        }
    }

    private data class PhotoSource(val view: SurfaceView, val geometry: ArPhotoSurfaceGeometry, val crop: IntRect)

    private fun geometry(view: SurfaceView): ArPhotoSurfaceGeometry {
        val location = IntArray(2).also(view::getLocationInWindow)
        val frame = view.holder.surfaceFrame
        return ArPhotoSurfaceGeometry(location[0], location[1], view.width, view.height, frame.width(), frame.height())
    }

    private fun findSource(root: View, viewport: IntRect): PhotoSource {
        val matches = mutableListOf<PhotoSource>()
        fun visit(view: View) {
            if (!view.isShown || !view.isAttachedToWindow) return
            if (view is SurfaceView && view.holder.surface.isValid) {
                val geometry = geometry(view)
                arPhotoSourceCrop(viewport, geometry)?.let { matches += PhotoSource(view, geometry, it) }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        // The viewer has one active AR surface. Never silently choose another renderer.
        return matches.singleOrNull() ?: error("Die AR-Bildfläche ist noch nicht eindeutig verfügbar. Bitte erneut aufnehmen.")
    }

    private suspend fun captureSurface(rootView: View, viewport: Rect): Bitmap = withContext(Dispatchers.Main.immediate) {
        withTimeout(3_000) {
            val bounds = IntRect(viewport.left, viewport.top, viewport.right, viewport.bottom)
            var failure = "Die AR-Aufnahme ist leer. Bitte kurz warten und erneut aufnehmen."
            repeat(3) { attempt ->
                val source = findSource(rootView, bounds)
                val bitmap = try {
                    copySurface(source, viewport.width(), viewport.height())
                } catch (error: PhotoCopyFailure) {
                    if (error.result != PixelCopy.ERROR_SOURCE_NO_DATA && error.result != PixelCopy.ERROR_TIMEOUT) throw error
                    failure = "Die AR-Kamera hat noch kein kopierbares Bild geliefert. Bitte erneut aufnehmen."
                    null
                }
                if (bitmap != null) {
                    var accepted = false
                    try {
                        check(source.view.isAttachedToWindow && source.view.isShown && source.view.holder.surface.isValid &&
                            geometry(source.view) == source.geometry) {
                            "Die AR-Ansicht wurde während der Aufnahme verändert. Bitte erneut aufnehmen."
                        }
                        // Check before drawing the optional label or publishing anything to MediaStore/QS.
                        val samples = IntArray(32 * 32) { i ->
                            val x = ((i % 32 + 0.5f) * bitmap.width / 32).toInt().coerceAtMost(bitmap.width - 1)
                            val y = ((i / 32 + 0.5f) * bitmap.height / 32).toInt().coerceAtMost(bitmap.height - 1)
                            bitmap.getPixel(x, y)
                        }
                        if (!isBlankArPhoto(samples)) {
                            accepted = true
                            return@withTimeout bitmap
                        }
                        failure = "Die AR-Aufnahme enthält kein sichtbares Bild. Bitte erneut aufnehmen."
                    } finally {
                        if (!accepted) bitmap.recycle()
                    }
                }
                if (attempt < 2) delay(100)
            }
            error(failure)
        }
    }

    private class PhotoCopyFailure(val result: Int) : IllegalStateException("Die AR-Bildfläche konnte nicht aufgenommen werden ($result).")

    private suspend fun copySurface(source: PhotoSource, width: Int, height: Int): Bitmap =
        suspendCancellableCoroutine { continuation ->
            // The callback owns the target until the copy finishes, including on cancellation.
            val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(
                    source.view,
                    Rect(source.crop.left, source.crop.top, source.crop.right, source.crop.bottom),
                    target,
                    { result ->
                        if (!continuation.isActive) {
                            target.recycle()
                        } else if (result == PixelCopy.SUCCESS) {
                            continuation.resume(target) { _, unconsumed, _ -> unconsumed.recycle() }
                        } else {
                            target.recycle()
                            continuation.resumeWithException(PhotoCopyFailure(result))
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            } catch (error: IllegalArgumentException) {
                target.recycle()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }

    private fun saveBitmap(activity: Activity, bitmap: Bitmap, displayName: String): Uri {
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: error("Android konnte das Foto nicht in der Galerie anlegen.")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)) {
                    "Das AR-Foto konnte nicht kodiert werden."
                }
            } ?: error("Die Fotodatei konnte nicht geöffnet werden.")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) {
                "Das Foto konnte nicht in der Galerie veröffentlicht werden."
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
