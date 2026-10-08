package com.goldenpaw.platform

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.goldenpaw.report.ReportCanvas
import com.goldenpaw.report.ReportFont
import com.goldenpaw.report.ReportTextStyle
import java.io.ByteArrayOutputStream
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.Bitmap

/** [ReportCanvas] backed by Android's PdfDocument: real system fonts, full Unicode (any pet name). */
class AndroidPdfCanvas(
    override val pageWidth: Float = 595f,
    override val pageHeight: Float = 842f,
) : ReportCanvas {

    private val document = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private val canvas: Canvas get() = page?.canvas ?: error("beginPage() not called")

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    private fun argb(color: Int) = 0xFF000000.toInt() or color

    private fun applyText(style: ReportTextStyle) {
        textPaint.color = argb(style.color)
        textPaint.textSize = style.size
        textPaint.letterSpacing = style.letterSpacing
        textPaint.typeface = when (style.font) {
            ReportFont.SERIF_BOLD -> serifBold
            ReportFont.SANS_BOLD -> sansBold
            ReportFont.SANS -> sans
        }
    }

    override fun beginPage() {
        pageNumber += 1
        val info = PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), pageNumber).create()
        page = document.startPage(info)
    }

    override fun endPage() {
        page?.let { document.finishPage(it) }
        page = null
    }

    override fun text(text: String, x: Float, y: Float, style: ReportTextStyle) {
        applyText(style)
        canvas.drawText(text, x, y, textPaint)
    }

    override fun measure(text: String, style: ReportTextStyle): Float {
        applyText(style)
        return textPaint.measureText(text)
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, dashed: Boolean) {
        shapePaint.style = Paint.Style.STROKE
        shapePaint.color = argb(color)
        shapePaint.strokeWidth = width
        shapePaint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(4f, 4f), 0f) else null
        canvas.drawLine(x1, y1, x2, y2, shapePaint)
        shapePaint.pathEffect = null
    }

    override fun fillRect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = argb(color)
        canvas.drawRect(left, top, right, bottom, shapePaint)
    }

    override fun strokeRect(left: Float, top: Float, right: Float, bottom: Float, color: Int, width: Float) {
        shapePaint.style = Paint.Style.STROKE
        shapePaint.color = argb(color)
        shapePaint.strokeWidth = width
        canvas.drawRect(left, top, right, bottom, shapePaint)
    }

    override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) {
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = argb(color)
        canvas.drawCircle(cx, cy, radius, shapePaint)
    }

    override fun polyline(points: List<Pair<Float, Float>>, color: Int, width: Float) {
        if (points.size < 2) return
        val path = Path()
        points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        shapePaint.style = Paint.Style.STROKE
        shapePaint.color = argb(color)
        shapePaint.strokeWidth = width
        shapePaint.strokeCap = Paint.Cap.ROUND
        shapePaint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, shapePaint)
    }

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * PdfDocument can't pass JPEG bytes through, so the page is decoded at roughly 150 dpi for the
     * target box (enough to read a vaccine sticker, small enough to share).
     */
    override fun image(jpeg: ByteArray, left: Float, top: Float, right: Float, bottom: Float) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        val targetWidth = ((right - left) / 72f * 150f).toInt().coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return
        canvas.drawBitmap(bitmap, null, RectF(left, top, right, bottom), imagePaint)
        // The page records draw commands; keep the bitmap alive until the document is written.
        bitmaps += bitmap
    }

    private val bitmaps = mutableListOf<Bitmap>()

    override fun finish(): ByteArray {
        if (page != null) endPage()
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        bitmaps.forEach { it.recycle() }
        bitmaps.clear()
        return out.toByteArray()
    }
}
