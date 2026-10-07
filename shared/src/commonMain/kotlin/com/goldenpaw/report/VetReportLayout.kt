package com.goldenpaw.report

import com.goldenpaw.core.Fmt
import com.goldenpaw.core.epochDay
import com.goldenpaw.core.toFixed
import com.goldenpaw.domain.logic.QualityOfLifeCalculator
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.logic.SymptomPatternDetector
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.domain.model.DayQuality
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.PetSex
import com.goldenpaw.domain.model.SlotState
import kotlin.math.roundToInt

/**
 * Lays out the A4 vet-ready report on any [ReportCanvas]. Same layout on every platform.
 */
class VetReportLayout(private val canvas: ReportCanvas) {

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

    private val title = ReportTextStyle(22f, ink, ReportFont.SERIF_BOLD)
    private val h2 = ReportTextStyle(13f, honey, ReportFont.SANS_BOLD, letterSpacing = 0.06f)
    private val body = ReportTextStyle(10.5f, ink)
    private val bodyBold = ReportTextStyle(10.5f, ink, ReportFont.SANS_BOLD)
    private val small = ReportTextStyle(9f, muted)

    private var y = 0f
    private var pageNumber = 0

    fun render(data: VetReportData): ByteArray {
        pageNumber = 0
        newPage()
        header(data)
        if (data.options.includeMeds) medications(data)
        if (data.options.includeQol) qualityOfLife(data)
        if (data.options.includeWeight) weight(data)
        if (data.options.includeSymptoms) symptoms(data)
        if (data.options.includeNotes) notes(data)
        finishPage()
        return canvas.finish()
    }

    // ---------------------------------------------------------------- layout helpers

    private fun newPage() {
        pageNumber += 1
        canvas.beginPage()
        y = margin
    }

    private fun finishPage() {
        val footer = "GoldenPaw · informational record kept by the owner, not veterinary advice · page $pageNumber"
        canvas.text(footer, margin, pageHeight - 20f, small)
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
        y += 14f
        canvas.text(name.uppercase(), margin, y, h2)
        y += 6f
        canvas.line(margin, y, pageWidth - margin, y, lineColor)
        y += 16f
    }

    private fun textLine(text: String, style: ReportTextStyle = body, indent: Float = 0f, gap: Float = 15f) {
        val maxWidth = pageWidth - margin * 2 - indent
        wrap(text, style, maxWidth).forEach { lineText ->
            ensureSpace(gap)
            canvas.text(lineText, margin + indent, y, style)
            y += gap
        }
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

    // ---------------------------------------------------------------- sections

    private fun header(data: VetReportData) {
        val pet = data.pet
        canvas.fillRect(0f, 0f, pageWidth, 8f, band)
        y = margin + 10f
        canvas.text("${pet.name} · health summary", margin, y, title)
        y += 18f
        canvas.text(
            "${Fmt.dayMonthYear(data.options.from)} – ${Fmt.dayMonthYear(data.options.to)}   ·   generated ${Fmt.dayMonthYear(data.generatedOn)}",
            margin, y, small,
        )
        y += 22f

        val facts = buildList {
            add("Species" to pet.species.label)
            if (pet.breed.isNotBlank()) add("Breed" to pet.breed)
            pet.ageLabel(data.generatedOn)?.let { add("Age" to it) }
            pet.birthDate?.let { add("Born" to Fmt.dayMonthYear(it)) }
            if (pet.sex != PetSex.UNKNOWN) add("Sex" to pet.sex.label)
            data.weights.maxByOrNull { it.date }?.let {
                add("Latest weight" to "${UnitConversion.format(it.weightKg, data.unit)} (${Fmt.dayMonth(it.date)})")
            }
            if (pet.conditions.isNotEmpty()) add("Conditions" to pet.conditions.joinToString(", "))
            if (pet.vetName.isNotBlank()) add("Vet" to listOf(pet.vetName, pet.vetPhone).filter { it.isNotBlank() }.joinToString(" · "))
            if (data.ownerName.isNotBlank()) add("Owner" to data.ownerName)
            if (data.caregivers.size > 1) add("Care team" to data.caregivers.joinToString(", "))
        }
        facts.forEach { (k, v) ->
            ensureSpace(15f)
            canvas.text(k, margin, y, small)
            textLine(v, bodyBold, indent = 90f)
        }
    }

    private fun medications(data: VetReportData) {
        section("Medications")
        if (data.medications.isEmpty()) {
            textLine("No medications recorded.", small)
            return
        }
        data.medications.sortedByDescending { it.isActive }.forEach { med ->
            val medSlots = data.slots.filter { it.medication.id == med.id }
            val given = medSlots.count { it.state == SlotState.GIVEN }
            val skipped = medSlots.count { it.state == SlotState.SKIPPED }
            val missed = medSlots.count { it.state == SlotState.MISSED }
            val adherence = ScheduleEngine.adherence(medSlots)
            ensureSpace(48f)
            textLine("${med.name}  ${med.doseLabel}${if (med.isActive) "" else "  (stopped/paused)"}", bodyBold)
            val detail = listOfNotNull(
                med.scheduleSummary(),
                med.route.takeIf { it.isNotBlank() },
                "with food".takeIf { med.withFood },
                med.reason.takeIf { it.isNotBlank() }?.let { "for $it" },
                med.prescribedBy.takeIf { it.isNotBlank() }?.let { "prescribed by $it" },
                "since ${Fmt.dayMonth(med.schedule.startDate)}",
            ).joinToString(" · ")
            textLine(detail, small, indent = 10f, gap = 13f)
            if (medSlots.isNotEmpty()) {
                val pct = adherence?.let { "${(it * 100).roundToInt()}% given" } ?: "–"
                textLine("Doses in period: $given given · $skipped skipped · $missed missed  ($pct)", body, indent = 10f)
                adherenceBar(medSlots)
                val doubles = medSlots.count { it.isPossibleDoubleDose }
                if (doubles > 0) textLine("Logged twice by different caregivers on $doubles occasion(s).", small, indent = 10f)
            }
            y += 4f
        }
    }

    private fun adherenceBar(slots: List<DoseSlot>) {
        ensureSpace(16f)
        val left = margin + 10f
        val width = pageWidth - margin * 2 - 10f
        val cell = (width / slots.size).coerceAtMost(14f)
        slots.forEachIndexed { i, s ->
            val color = when (s.state) {
                SlotState.GIVEN -> sage
                SlotState.SKIPPED -> honey
                SlotState.MISSED -> clay
                else -> lineColor
            }
            val x = left + i * cell
            if (x + cell <= left + width) canvas.fillRect(x, y - 8f, x + cell - 1f, y, color)
        }
        y += 10f
    }

    private fun qualityOfLife(data: VetReportData) {
        section("Quality of life (HHHHHMM scale, 0–70)")
        val inRange = data.checkIns.filter { it.date >= data.options.from }
        if (inRange.isEmpty()) {
            textLine("No daily check-ins in this period.", small)
            return
        }
        val totals = QualityOfLifeCalculator.dailyTotals(data.checkIns).filter { it.first >= data.options.from }
        val avg = totals.map { it.second }.average()
        val good = inRange.count { it.dayQuality == DayQuality.GOOD }
        val okay = inRange.count { it.dayQuality == DayQuality.OKAY }
        val hard = inRange.count { it.dayQuality == DayQuality.HARD }
        textLine("Average score ${avg.roundToInt()} / 70 across ${inRange.size} check-ins.  Good days: $good · Okay: $okay · Hard: $hard", body)

        val cats = listOf(
            "Comfort (pain)" to inRange.map { it.pain }, "Appetite" to inRange.map { it.appetite },
            "Water" to inRange.map { it.water }, "Hygiene" to inRange.map { it.hygiene },
            "Mood" to inRange.map { it.mood }, "Mobility" to inRange.map { it.mobility },
            "Sleep" to inRange.map { it.sleep },
        )
        textLine(cats.joinToString("   ") { (n, v) -> "$n ${v.average().toFixed(1)}/5" }, small, gap = 13f)

        val chartH = 90f
        ensureSpace(chartH + 26f)
        val left = margin
        val right = pageWidth - margin
        val top = y
        val bottom = y + chartH
        canvas.strokeRect(left, top, right, bottom, lineColor)
        val thresholdY = bottom - (35f / 70f) * chartH
        canvas.line(left, thresholdY, right, thresholdY, muted, dashed = true)
        canvas.text("35", right + 3f, thresholdY + 3f, small)
        val days = (data.options.to.epochDay() - data.options.from.epochDay() + 1).coerceAtLeast(1)
        val barW = ((right - left) / days).coerceAtLeast(1.5f)
        totals.forEach { (date, total) ->
            val idx = date.epochDay() - data.options.from.epochDay()
            val x = left + idx * barW
            val h = (total / 70.0 * chartH).toFloat()
            val color = when {
                total >= 49 -> sage
                total >= 35 -> honey
                else -> clay
            }
            canvas.fillRect(x + 0.5f, bottom - h, x + barW - 0.5f, bottom, color)
        }
        y = bottom + 12f
        canvas.text(Fmt.dayMonth(data.options.from), left, y, small)
        val endLabel = Fmt.dayMonth(data.options.to)
        canvas.text(endLabel, right - canvas.measure(endLabel, small), y, small)
        y += 14f
    }

    private fun weight(data: VetReportData) {
        section("Weight (${data.unit.label})")
        val entries = data.weights.sortedBy { it.date }
        if (entries.isEmpty()) {
            textLine("No weights recorded.", small)
            return
        }
        val first = entries.first()
        val last = entries.last()
        val change = if (first.weightKg > 0) (last.weightKg - first.weightKg) / first.weightKg * 100 else 0.0
        textLine(
            "${UnitConversion.format(first.weightKg, data.unit)} (${Fmt.dayMonth(first.date)}) → " +
                "${UnitConversion.format(last.weightKg, data.unit)} (${Fmt.dayMonth(last.date)})  ·  " +
                "${if (change >= 0) "+" else ""}${change.toFixed(1)}%",
            body,
        )
        if (entries.size < 2) return

        val chartH = 100f
        ensureSpace(chartH + 26f)
        val left = margin + 24f
        val right = pageWidth - margin
        val top = y
        val bottom = y + chartH
        canvas.strokeRect(left, top, right, bottom, lineColor)
        val values = entries.map { UnitConversion.fromKg(it.weightKg, data.unit) }
        val min = values.min()
        val max = values.max()
        val pad = ((max - min) * 0.15).coerceAtLeast(0.2)
        val lo = min - pad
        val hi = max + pad
        val startDay = entries.first().date.epochDay()
        val span = (entries.last().date.epochDay() - startDay).coerceAtLeast(1)
        val points = entries.mapIndexed { i, e ->
            val x = left + (e.date.epochDay() - startDay).toFloat() / span * (right - left)
            val yy = bottom - ((values[i] - lo) / (hi - lo)).toFloat() * chartH
            x to yy
        }
        canvas.polyline(points, honey, 2f)
        points.forEach { (x, yy) -> canvas.fillCircle(x, yy, 2.5f, honey) }
        canvas.text(hi.toFixed(1), margin, top + 9f, small)
        canvas.text(lo.toFixed(1), margin, bottom, small)
        y = bottom + 12f
        canvas.text(Fmt.dayMonth(entries.first().date), left, y, small)
        val endLabel = Fmt.dayMonth(entries.last().date)
        canvas.text(endLabel, right - canvas.measure(endLabel, small), y, small)
        y += 14f
    }

    private fun symptoms(data: VetReportData) {
        section("Symptom journal")
        if (data.symptoms.isEmpty()) {
            textLine("No symptoms logged in this period.", small)
            return
        }
        val patterns = SymptomPatternDetector.detect(data.symptoms, data.options.to, windowDays = 7)
        val counts = data.symptoms.groupBy { it.type }.entries.sortedByDescending { it.value.size }
        textLine(
            "Summary: " + counts.joinToString(" · ") { (type, list) ->
                "${type.label} ×${list.size} (${list.map { it.date }.distinct().size} days)"
            },
            body,
        )
        patterns.forEach { textLine("Pattern: ${it.message}", bodyBold) }
        y += 4f
        data.symptoms.sortedByDescending { it.loggedAt }.forEach { s ->
            val head = "${Fmt.dayMonth(s.date)}  ${s.type.label}  · severity ${s.severity}/5" +
                (if (s.tags.isNotEmpty()) "  · ${s.tags.joinToString(", ")}" else "") +
                (if (s.loggedBy.name.isNotBlank() && data.caregivers.size > 1) "  · by ${s.loggedBy.name}" else "")
            textLine(head, bodyBold, gap = 13f)
            if (s.notes.isNotBlank()) textLine(s.notes, small, indent = 10f, gap = 12f)
        }
    }

    private fun notes(data: VetReportData) {
        val withNotes = data.checkIns.filter { it.notes.isNotBlank() && it.date >= data.options.from }
        if (withNotes.isEmpty()) return
        section("Owner notes from check-ins")
        withNotes.sortedByDescending { it.date }.forEach {
            textLine("${Fmt.dayMonth(it.date)}: ${it.notes}", body, gap = 13f)
        }
    }
}
