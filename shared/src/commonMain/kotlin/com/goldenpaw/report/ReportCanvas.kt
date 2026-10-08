package com.goldenpaw.report

import com.goldenpaw.core.toFixed

enum class ReportFont { SERIF_BOLD, SANS, SANS_BOLD }

/** [color] is 0xRRGGBB. */
data class ReportTextStyle(
    val size: Float,
    val color: Int,
    val font: ReportFont = ReportFont.SANS,
    val letterSpacing: Float = 0f,
)

/**
 * Minimal drawing surface for the vet report. Coordinates are in PDF points with the origin at the
 * top-left (y grows downward); text is positioned by its baseline.
 *
 * Android renders through `PdfDocument` (full Unicode); iOS and Desktop use [SimplePdfCanvas], a
 * small pure-Kotlin PDF writer with the standard Helvetica/Times fonts.
 */
interface ReportCanvas {
    val pageWidth: Float
    val pageHeight: Float
    fun beginPage()
    fun endPage()
    fun text(text: String, x: Float, y: Float, style: ReportTextStyle)
    fun measure(text: String, style: ReportTextStyle): Float
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float = 1f, dashed: Boolean = false)
    fun fillRect(left: Float, top: Float, right: Float, bottom: Float, color: Int)
    fun strokeRect(left: Float, top: Float, right: Float, bottom: Float, color: Int, width: Float = 1f)
    fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int)
    fun polyline(points: List<Pair<Float, Float>>, color: Int, width: Float)
    /**
     * Draws a JPEG scaled into the given box (callers keep the aspect ratio, see [JpegInfo]).
     * JPEGs are embedded as-is where the writer can (DCTDecode), so scanned pages stay small.
     */
    fun image(jpeg: ByteArray, left: Float, top: Float, right: Float, bottom: Float)
    /** Finishes the document and returns the PDF bytes. */
    fun finish(): ByteArray
}

/** Pure-Kotlin PDF 1.4 writer (no dependencies). Text is encoded as WinAnsi; unsupported glyphs become "?". */
class SimplePdfCanvas(
    override val pageWidth: Float = 595f,
    override val pageHeight: Float = 842f,
) : ReportCanvas {

    private class Page(val content: String, val images: List<Int>)
    private class Image(val bytes: ByteArray, val info: JpegInfo)

    private val pages = mutableListOf<Page>()
    private val images = mutableListOf<Image>()
    private var current: StringBuilder? = null
    private val currentImages = mutableListOf<Int>()

    override fun beginPage() {
        current = StringBuilder()
        currentImages.clear()
    }

    override fun endPage() {
        current?.let { pages += Page(it.toString(), currentImages.toList()) }
        current = null
        currentImages.clear()
    }

    private val out: StringBuilder get() = current ?: StringBuilder().also { current = it }

    private fun n(v: Float) = v.toFixed(2)
    private fun yFlip(y: Float) = pageHeight - y

    private fun rgb(color: Int): String {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return "${r.toFixed(3)} ${g.toFixed(3)} ${b.toFixed(3)}"
    }

    override fun text(text: String, x: Float, y: Float, style: ReportTextStyle) {
        val font = when (style.font) {
            ReportFont.SANS -> "F1"
            ReportFont.SANS_BOLD -> "F2"
            ReportFont.SERIF_BOLD -> "F3"
        }
        out.append("BT\n${rgb(style.color)} rg\n/$font ${n(style.size)} Tf\n")
        if (style.letterSpacing != 0f) out.append("${n(style.letterSpacing * style.size)} Tc\n")
        out.append("${n(x)} ${n(yFlip(y))} Td\n(").append(encode(text)).append(") Tj\n")
        if (style.letterSpacing != 0f) out.append("0 Tc\n")
        out.append("ET\n")
    }

    override fun measure(text: String, style: ReportTextStyle): Float {
        val widths = if (style.font == ReportFont.SANS) PdfFontMetrics.helvetica else PdfFontMetrics.helveticaBold
        val scale = if (style.font == ReportFont.SERIF_BOLD) 0.95f else 1f
        var units = 0
        for (ch in text) {
            val code = ch.code
            units += if (code in 32..126) widths[code - 32] else 556
        }
        val spacing = style.letterSpacing * style.size * (text.length - 1).coerceAtLeast(0)
        return units / 1000f * style.size * scale + spacing
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, dashed: Boolean) {
        out.append("${rgb(color)} RG\n${n(width)} w\n")
        if (dashed) out.append("[4 4] 0 d\n")
        out.append("${n(x1)} ${n(yFlip(y1))} m ${n(x2)} ${n(yFlip(y2))} l S\n")
        if (dashed) out.append("[] 0 d\n")
    }

    override fun fillRect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        out.append("${rgb(color)} rg\n${n(left)} ${n(yFlip(bottom))} ${n(right - left)} ${n(bottom - top)} re f\n")
    }

    override fun strokeRect(left: Float, top: Float, right: Float, bottom: Float, color: Int, width: Float) {
        out.append("${rgb(color)} RG\n${n(width)} w\n${n(left)} ${n(yFlip(bottom))} ${n(right - left)} ${n(bottom - top)} re S\n")
    }

    override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) {
        val k = 0.5523f * radius
        val y = yFlip(cy)
        out.append("${rgb(color)} rg\n")
        out.append("${n(cx + radius)} ${n(y)} m\n")
        out.append("${n(cx + radius)} ${n(y + k)} ${n(cx + k)} ${n(y + radius)} ${n(cx)} ${n(y + radius)} c\n")
        out.append("${n(cx - k)} ${n(y + radius)} ${n(cx - radius)} ${n(y + k)} ${n(cx - radius)} ${n(y)} c\n")
        out.append("${n(cx - radius)} ${n(y - k)} ${n(cx - k)} ${n(y - radius)} ${n(cx)} ${n(y - radius)} c\n")
        out.append("${n(cx + k)} ${n(y - radius)} ${n(cx + radius)} ${n(y - k)} ${n(cx + radius)} ${n(y)} c\nf\n")
    }

    override fun polyline(points: List<Pair<Float, Float>>, color: Int, width: Float) {
        if (points.size < 2) return
        out.append("${rgb(color)} RG\n${n(width)} w\n1 J 1 j\n")
        points.forEachIndexed { i, (x, y) ->
            out.append("${n(x)} ${n(yFlip(y))} ${if (i == 0) "m" else "l"}\n")
        }
        out.append("S\n0 J 0 j\n")
    }

    override fun image(jpeg: ByteArray, left: Float, top: Float, right: Float, bottom: Float) {
        val info = JpegInfo.read(jpeg) ?: return
        val index = images.size
        images += Image(jpeg, info)
        currentImages += index
        // Unit square scaled to the box; PDF's origin is bottom-left.
        out.append("q\n${n(right - left)} 0 0 ${n(bottom - top)} ${n(left)} ${n(yFlip(bottom))} cm\n/Im$index Do\nQ\n")
    }

    override fun finish(): ByteArray {
        if (current != null) endPage()
        if (pages.isEmpty()) pages += Page("", emptyList())
        // Object numbers: 1 catalog, 2 pages, 3-5 fonts, then one per image, then (page, content) pairs.
        val firstImageObj = 6
        val firstPageObj = firstImageObj + images.size
        val kids = pages.indices.joinToString(" ") { "${firstPageObj + it * 2} 0 R" }

        val out = PdfBytes()
        val offsets = mutableListOf<Int>()
        fun obj(body: String) {
            offsets += out.size
            out.append("${offsets.size} 0 obj\n").append(body).append("\nendobj\n")
        }
        out.append("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n".let { header -> header.map { it.code.toByte() }.toByteArray() })
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [ $kids ] /Count ${pages.size} >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >>")
        images.forEach { image ->
            offsets += out.size
            val info = image.info
            val colorSpace = when (info.components) {
                1 -> "/DeviceGray"
                4 -> "/DeviceCMYK /Decode [1 0 1 0 1 0 1 0]" // Adobe CMYK JPEGs are stored inverted
                else -> "/DeviceRGB"
            }
            out.append("${offsets.size} 0 obj\n")
            out.append(
                "<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                    "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${image.bytes.size} >>\nstream\n",
            )
            out.append(image.bytes)
            out.append("\nendstream\nendobj\n")
        }
        pages.forEachIndexed { i, page ->
            val contentObj = firstPageObj + i * 2 + 1
            val xobjects = if (page.images.isEmpty()) "" else
                " /XObject << " + page.images.joinToString(" ") { "/Im$it ${firstImageObj + it} 0 R" } + " >>"
            obj(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(pageWidth)} ${n(pageHeight)}] " +
                    "/Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >>$xobjects >> /Contents $contentObj 0 R >>",
            )
            // Content streams are ASCII (text is octal-escaped), so string length == byte length.
            obj("<< /Length ${page.content.length} >>\nstream\n${page.content}\nendstream")
        }
        val xrefOffset = out.size
        out.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        out.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        return out.toByteArray()
    }

    /** WinAnsi literal string with escapes; characters outside the encoding become "?". */
    private fun encode(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val code: Int = when {
                ch == '(' || ch == ')' || ch == '\\' -> { sb.append('\\').append(ch); continue }
                ch.code in 32..126 -> { sb.append(ch); continue }
                ch.code in 0xA0..0xFF -> ch.code
                else -> WIN_ANSI_EXTRAS[ch] ?: when (ch) {
                    '→' -> { sb.append("->"); continue }
                    '≈' -> { sb.append("~"); continue }
                    '✓' -> { sb.append("v"); continue }
                    else -> '?'.code
                }
            }
            if (code < 128) sb.append(code.toChar()) else sb.append('\\').append(code.toString(8).padStart(3, '0'))
        }
        return sb.toString()
    }

    private companion object {
        val WIN_ANSI_EXTRAS = mapOf(
            '€' to 0x80, '‚' to 0x82, '„' to 0x84, '…' to 0x85, '†' to 0x86, '‡' to 0x87, '‰' to 0x89,
            '‘' to 0x91, '’' to 0x92, '“' to 0x93, '”' to 0x94, '•' to 0x95, '–' to 0x96, '—' to 0x97,
            '™' to 0x99,
        )
    }
}

/** Growable byte buffer for the PDF writer (binary JPEG streams sit next to ASCII objects). */
internal class PdfBytes {
    private var buffer = ByteArray(64 * 1024)
    var size: Int = 0
        private set

    fun append(text: String): PdfBytes = append(text.encodeToByteArray())

    fun append(bytes: ByteArray): PdfBytes {
        if (size + bytes.size > buffer.size) {
            var capacity = buffer.size * 2
            while (capacity < size + bytes.size) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
        bytes.copyInto(buffer, size)
        size += bytes.size
        return this
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

/** Helvetica advance widths (1/1000 em) for ASCII 32..126, from the standard Adobe AFM files. */
internal object PdfFontMetrics {
    val helvetica = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    )
    val helveticaBold = intArrayOf(
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
        975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
        333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
        611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584,
    )
}
