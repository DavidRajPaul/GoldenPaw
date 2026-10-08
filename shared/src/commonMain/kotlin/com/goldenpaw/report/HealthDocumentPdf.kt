package com.goldenpaw.report

import com.goldenpaw.core.Fmt
import com.goldenpaw.domain.model.DocumentType
import com.goldenpaw.domain.model.HealthDocument
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.PetSex
import com.goldenpaw.domain.model.VaccineStatus
import kotlinx.datetime.LocalDate

/** Creates the canvas for a scanned-record PDF. [needsUnicode]: text outside WinAnsi (e.g. Tamil names). */
fun interface DocumentCanvasFactory {
    fun create(needsUnicode: Boolean): ReportCanvas
}

/** One scanned page, already upright and JPEG-encoded. */
class ScannedPage(val jpeg: ByteArray, val info: JpegInfo)

/**
 * Lays out a scanned record as a tidy A4 PDF:
 *  page 1  a summary the vet can read at a glance (pet, record type, clinic, a vaccine table with
 *          given / next-due dates and batch numbers, notes),
 *  then    every scanned page, fitted to the page with a small caption.
 */
class HealthDocumentPdfLayout(private val canvas: ReportCanvas) {
    private val pageWidth = canvas.pageWidth
    private val pageHeight = canvas.pageHeight
    private val margin = 40f

    private val honey = 0xC8862A
    private val sage = 0x6F8F6A
    private val clay = 0xB5654A
    private val ink = 0x28221C
    private val muted = 0x786E64
    private val lineColor = 0xE6DCCD
    private val band = 0xFCE3B6
    private val zebra = 0xFFF8EE

    private val title = ReportTextStyle(22f, ink, ReportFont.SERIF_BOLD)
    private val h2 = ReportTextStyle(12f, honey, ReportFont.SANS_BOLD, letterSpacing = 0.06f)
    private val body = ReportTextStyle(10.5f, ink)
    private val bodyBold = ReportTextStyle(10.5f, ink, ReportFont.SANS_BOLD)
    private val small = ReportTextStyle(9f, muted)
    private val smallBold = ReportTextStyle(9f, muted, ReportFont.SANS_BOLD)

    private var y = 0f
    private var pageNumber = 0

    fun render(document: HealthDocument, pet: Pet, pages: List<ScannedPage>, ownerName: String, today: LocalDate): ByteArray {
        pageNumber = 0
        summaryPage(document, pet, ownerName, today, pages.size)
        pages.forEachIndexed { i, page -> scanPage(document, pet, page, i + 1, pages.size) }
        return canvas.finish()
    }

    // ---------------------------------------------------------------- pages

    private fun summaryPage(doc: HealthDocument, pet: Pet, ownerName: String, today: LocalDate, scanCount: Int) {
        newPage()
        canvas.fillRect(0f, 0f, pageWidth, 8f, band)
        y = margin + 10f
        canvas.text(doc.displayTitle, margin, y, title)
        y += 18f
        val subtitle = buildList {
            add(doc.type.label)
            doc.issuedOn?.let { add("dated ${Fmt.dayMonthYear(it)}") }
            add("$scanCount scanned ${if (scanCount == 1) "page" else "pages"}")
        }.joinToString("   ·   ")
        canvas.text(subtitle, margin, y, small)
        y += 26f

        section("Pet")
        facts(
            buildList {
                add("Name" to pet.name)
                add("Species" to pet.species.label)
                if (pet.breed.isNotBlank()) add("Breed" to pet.breed)
                if (pet.sex != PetSex.UNKNOWN) add("Sex" to pet.sex.label)
                pet.birthDate?.let { add("Born" to Fmt.dayMonthYear(it)) }
                if (ownerName.isNotBlank()) add("Owner" to ownerName)
            },
        )

        if (doc.clinic.isNotBlank() || doc.vetName.isNotBlank()) {
            section("Issued by")
            facts(
                buildList {
                    if (doc.clinic.isNotBlank()) add("Clinic" to doc.clinic)
                    if (doc.vetName.isNotBlank()) add("Vet" to doc.vetName)
                    doc.issuedOn?.let { add("Date" to Fmt.dayMonthYear(it)) }
                },
            )
        }

        val vaccines = doc.vaccines.filter { it.name.isNotBlank() }
        if (vaccines.isNotEmpty()) {
            section(if (doc.type == DocumentType.VACCINE_CARD) "Vaccinations" else "Treatments")
            val cols = floatArrayOf(margin, margin + 190f, margin + 290f, margin + 390f)
            val headers = listOf("Vaccine / treatment", "Given", "Next due", "Batch")
            headers.forEachIndexed { i, h -> canvas.text(h.uppercase(), cols[i], y, smallBold) }
            y += 6f
            canvas.line(margin, y, pageWidth - margin, y, lineColor)
            y += 14f
            vaccines.forEachIndexed { index, v ->
                ensureSpace(20f)
                if (index % 2 == 0) canvas.fillRect(margin - 4f, y - 11f, pageWidth - margin + 4f, y + 6f, zebra)
                canvas.text(fit(v.name, body, 184f), cols[0], y, bodyBold)
                canvas.text(v.givenOn?.let { Fmt.dayMonthYear(it) } ?: "–", cols[1], y, body)
                val due = v.nextDue
                if (due != null) {
                    val status = v.status(today)
                    val color = when (status) {
                        VaccineStatus.OVERDUE -> clay
                        VaccineStatus.DUE_SOON -> honey
                        else -> sage
                    }
                    canvas.fillCircle(cols[2] + 3f, y - 3.5f, 3f, color)
                    canvas.text(Fmt.dayMonthYear(due), cols[2] + 10f, y, body)
                } else {
                    canvas.text("–", cols[2], y, body)
                }
                canvas.text(fit(v.batch.ifBlank { "–" }, body, pageWidth - margin - cols[3]), cols[3], y, body)
                y += 18f
            }
            y += 2f
            canvas.fillCircle(margin + 3f, y - 3f, 3f, sage)
            canvas.text("up to date", margin + 10f, y, small)
            canvas.fillCircle(margin + 80f, y - 3f, 3f, honey)
            canvas.text("due within 30 days", margin + 87f, y, small)
            canvas.fillCircle(margin + 185f, y - 3f, 3f, clay)
            canvas.text("overdue (as of ${Fmt.dayMonthYear(today)})", margin + 192f, y, small)
            y += 14f
        }

        if (doc.notes.isNotBlank()) {
            section("Notes")
            textLine(doc.notes)
        }

        y += 10f
        textLine(
            "Details above were entered by the owner from the scanned card. The scanned pages that follow are the original record.",
            small,
            gap = 12f,
        )
        finishPage()
    }

    private fun scanPage(doc: HealthDocument, pet: Pet, page: ScannedPage, index: Int, count: Int) {
        newPage()
        val captionY = margin - 8f
        canvas.text("${pet.name} · ${doc.displayTitle} · scan $index of $count", margin, captionY, small)
        canvas.line(margin, captionY + 6f, pageWidth - margin, captionY + 6f, lineColor)

        val boxLeft = margin
        val boxTop = captionY + 16f
        val boxWidth = pageWidth - margin * 2
        val boxHeight = pageHeight - boxTop - 40f
        val aspect = page.info.aspect
        val (w, h) = if (boxWidth / boxHeight > aspect) (boxHeight * aspect) to boxHeight else boxWidth to (boxWidth / aspect)
        val left = boxLeft + (boxWidth - w) / 2
        val top = boxTop + (boxHeight - h) / 2
        canvas.image(page.jpeg, left, top, left + w, top + h)
        canvas.strokeRect(left, top, left + w, top + h, lineColor, 0.75f)
        finishPage()
    }

    // ---------------------------------------------------------------- helpers

    private fun newPage() {
        pageNumber += 1
        canvas.beginPage()
        y = margin
    }

    private fun finishPage() {
        canvas.text(
            "GoldenPaw · record kept by the owner, not veterinary advice · page $pageNumber",
            margin, pageHeight - 20f, small,
        )
        canvas.endPage()
    }

    private fun ensureSpace(needed: Float) {
        if (y + needed > pageHeight - 50f) {
            finishPage()
            newPage()
        }
    }

    private fun section(name: String) {
        ensureSpace(60f)
        y += 10f
        canvas.text(name.uppercase(), margin, y, h2)
        y += 6f
        canvas.line(margin, y, pageWidth - margin, y, lineColor)
        y += 16f
    }

    private fun facts(rows: List<Pair<String, String>>) {
        rows.forEach { (k, v) ->
            ensureSpace(16f)
            canvas.text(k, margin, y, small)
            wrap(v, body, pageWidth - margin * 2 - 110f).forEachIndexed { i, line ->
                if (i > 0) {
                    y += 14f
                    ensureSpace(16f)
                }
                canvas.text(line, margin + 110f, y, if (i == 0 && k == "Name") bodyBold else body)
            }
            y += 16f
        }
    }

    private fun textLine(text: String, style: ReportTextStyle = body, gap: Float = 15f) {
        wrap(text, style, pageWidth - margin * 2).forEach { line ->
            ensureSpace(gap)
            canvas.text(line, margin, y, style)
            y += gap
        }
    }

    private fun fit(text: String, style: ReportTextStyle, maxWidth: Float): String {
        if (canvas.measure(text, style) <= maxWidth) return text
        var t = text
        while (t.length > 1 && canvas.measure("$t…", style) > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    private fun wrap(text: String, style: ReportTextStyle, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        text.split("\n").forEach { paragraph ->
            var current = ""
            paragraph.split(" ").forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (canvas.measure(candidate, style) <= maxWidth) {
                    current = candidate
                } else {
                    if (current.isNotEmpty()) lines += current
                    current = word
                }
            }
            lines += current
        }
        return lines
    }

    companion object {
        /** True when [text] has characters the built-in PDF fonts (WinAnsi) can't show. */
        fun needsUnicode(text: String): Boolean = text.any { ch ->
            val c = ch.code
            !(c in 9..13 || c in 32..126 || c in 0xA0..0xFF || ch in "€‚„…†‡‰‘’“”•–—™→≈✓")
        }
    }
}
