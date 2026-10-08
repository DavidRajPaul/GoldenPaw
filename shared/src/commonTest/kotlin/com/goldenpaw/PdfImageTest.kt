package com.goldenpaw

import com.goldenpaw.report.JpegInfo
import com.goldenpaw.report.SimplePdfCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfImageTest {

    /** SOI, a JFIF APP0 segment, SOF0 for a 300×200 RGB image, EOI. Enough for header parsing. */
    private val jpeg: ByteArray = intArrayOf(
        0xFF, 0xD8,
        0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        0xFF, 0xC0, 0x00, 0x11, 0x08, 0x00, 0xC8, 0x01, 0x2C, 0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
        0xFF, 0xD9,
    ).map { it.toByte() }.toByteArray()

    @Test
    fun readsJpegSize() {
        val info = assertNotNull(JpegInfo.read(jpeg))
        assertEquals(300, info.width)
        assertEquals(200, info.height)
        assertEquals(3, info.components)
        assertNull(JpegInfo.read(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun embedsJpegWithValidXref() {
        val canvas = SimplePdfCanvas()
        canvas.beginPage()
        canvas.text("Rabies", 40f, 40f, com.goldenpaw.report.ReportTextStyle(10f, 0))
        canvas.endPage()
        canvas.beginPage()
        canvas.image(jpeg, 40f, 60f, 340f, 260f)
        canvas.endPage()
        val pdf = canvas.finish()
        val latin = pdf.map { (it.toInt() and 0xFF).toChar() }.joinToString("")

        assertTrue(latin.startsWith("%PDF-1.4"))
        assertTrue("/Subtype /Image /Width 300 /Height 200" in latin)
        assertTrue("/Filter /DCTDecode /Length ${jpeg.size}" in latin)
        assertTrue("/XObject << /Im0 6 0 R >>" in latin)
        assertTrue("/Im0 Do" in latin)

        // Every xref offset must point at "N 0 obj".
        val xrefAt = latin.substring(latin.lastIndexOf("startxref") + 10).trim().lines().first().toInt()
        val entries = latin.substring(xrefAt).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(10, entries.size) // catalog, pages, 3 fonts, 1 image, 2 × (page, content)
        entries.forEachIndexed { i, line ->
            val offset = line.take(10).toInt()
            assertTrue(latin.startsWith("${i + 1} 0 obj", offset), "object ${i + 1} at $offset")
        }
    }
}
