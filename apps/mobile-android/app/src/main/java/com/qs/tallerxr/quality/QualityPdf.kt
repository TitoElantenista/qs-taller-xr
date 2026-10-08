package com.qs.tallerxr.quality

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

object QualityPdf {
    /** One position: native A4 PDF measured with the exact font metrics of this device. [logo] from the settings. */
    fun write(report: IssuedQualityReport, photoFile: (QualityPhoto) -> File, output: OutputStream, logo: File? = null) =
        write(listOf(report.record), photoFile, output, logo) { measure, sizes, logoSize ->
            QualityReportLayout(measure, GENERATOR).build(report, sizes, logoSize)
        }

    /** Several positions in one file: overview first, then every position's report. */
    fun write(collection: QualityReportCollection, photoFile: (QualityPhoto) -> File, output: OutputStream, logo: File? = null) =
        write(collection.reports.map { it.record }, photoFile, output, logo) { measure, sizes, logoSize ->
            QualityReportLayout(measure, GENERATOR).build(collection, sizes, logoSize)
        }

    private fun write(
        records: List<QualityRecord>,
        photoFile: (QualityPhoto) -> File,
        output: OutputStream,
        logo: File?,
        layout: ((String, Float, Boolean) -> Float, Map<String, ReportImageSize>, ReportImageSize?) -> QualityReportPages,
    ) {
        // A missing or unreadable logo never stops a report; it is simply left out.
        val logoSize = logo?.takeIf { it.isFile }?.let { file ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            ReportImageSize(bounds.outWidth, bounds.outHeight).takeIf { it.width > 0 && it.height > 0 }
        }
        val photos = records.flatMap { it.photos }.associateBy { it.id }
        val sizes = photos.mapValues { (_, event) ->
            val photo = checkNotNull(event.photo)
            val file = photoFile(photo)
            check(file.isFile) { "Foto fehlt: ${photo.displayName}. Das Protokoll wurde nicht erstellt." }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Foto nicht lesbar: ${photo.displayName}." }
            ReportImageSize(bounds.outWidth, bounds.outHeight)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun configure(size: Float, bold: Boolean) {
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val pages = layout({ text, size, bold -> configure(size, bold); paint.measureText(text) }, sizes, logoSize)
        val pdf = PdfDocument()
        var active: PdfDocument.Page? = null
        try {
            pages.pages.forEachIndexed { index, page ->
                val target = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                active = target
                val canvas = target.canvas
                page.drawings.forEach { drawing ->
                    paint.color = drawing.color.toColorInt()
                    paint.style = Paint.Style.FILL
                    when (drawing.kind) {
                        "text" -> {
                            configure(drawing.size, drawing.bold)
                            canvas.drawText(drawing.text, drawing.x, drawing.y - paint.fontMetrics.ascent, paint)
                        }
                        "rect" -> canvas.drawRoundRect(RectF(drawing.x, drawing.y, drawing.x + drawing.width,
                            drawing.y + drawing.height), drawing.radius, drawing.radius, paint)
                        "line" -> {
                            paint.strokeWidth = 0.6f
                            canvas.drawLine(drawing.x, drawing.y, drawing.x + drawing.width, drawing.y, paint)
                        }
                        "logo" -> if (logo != null && logoSize != null) {
                            val bitmap = reportBitmap(logo, logoSize, drawing.width, drawing.height, LOGO_PIXELS_PER_POINT)
                            try {
                                paint.isFilterBitmap = true
                                canvas.drawBitmap(bitmap, null, RectF(drawing.x, drawing.y, drawing.x + drawing.width,
                                    drawing.y + drawing.height), paint)
                            } finally { bitmap.recycle() }
                        }
                        "photo" -> {
                            val event = checkNotNull(photos[drawing.imageId])
                            val bitmap = reportBitmap(photoFile(checkNotNull(event.photo)), checkNotNull(sizes[drawing.imageId]),
                                drawing.width, drawing.height)
                            try {
                                val scale = minOf(drawing.width / bitmap.width, drawing.height / bitmap.height)
                                val width = bitmap.width * scale
                                val height = bitmap.height * scale
                                val x = drawing.x + (drawing.width - width) / 2
                                val y = drawing.y + (drawing.height - height) / 2
                                paint.isFilterBitmap = true
                                canvas.drawBitmap(bitmap, null, RectF(x, y, x + width, y + height), paint)
                            } finally { bitmap.recycle() }
                        }
                    }
                }
                pdf.finishPage(target)
                active = null
            }
            pdf.writeTo(output)
        } finally {
            active?.let(pdf::finishPage)
            pdf.close()
        }
    }

    /**
     * The photo at about 190 dpi of its printed size: sharp on paper and when zooming in, without
     * embedding the full camera resolution for every thumbnail.
     */
    private fun reportBitmap(file: File, size: ReportImageSize, width: Float, height: Float,
        pixelsPerPoint: Float = PIXELS_PER_POINT): Bitmap {
        val targetWidth = (width * pixelsPerPoint).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * pixelsPerPoint).roundToInt().coerceAtLeast(1)
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (size.width / (inSampleSize * 2) >= targetWidth && size.height / (inSampleSize * 2) >= targetHeight) inSampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: error("Das AR-Foto konnte nicht gelesen werden.")
        if (decoded.width <= targetWidth * 1.25f) return decoded
        val scaled = decoded.scale(targetWidth, (decoded.height * targetWidth.toFloat() / decoded.width).roundToInt().coerceAtLeast(1))
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private const val PIXELS_PER_POINT = 2.6f
    /** Logos are small and sharp-edged: drawn at about 360 dpi. */
    private const val LOGO_PIXELS_PER_POINT = 5f
    private const val GENERATOR = "QS Taller XR " + com.qs.tallerxr.BuildConfig.VERSION_NAME
}
