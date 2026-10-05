package com.goldenpaw.platform.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.goldenpaw.domain.logic.QualityOfLifeCalculator
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.logic.SymptomPatternDetector
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.DayQuality
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.SlotState
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.WeightRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class ReportOptions(
    val from: LocalDate,
    val to: LocalDate,
    val includeMeds: Boolean = true,
    val includeWeight: Boolean = true,
    val includeQol: Boolean = true,
    val includeSymptoms: Boolean = true,
    val includeNotes: Boolean = true,
)

data class VetReportData(
    val pet: Pet,
    val options: ReportOptions,
    val medications: List<Medication>,
    val slots: List<DoseSlot>,
    val weights: List<WeightEntry>,
    val checkIns: List<CheckIn>,
    val symptoms: List<SymptomEntry>,
    val unit: WeightUnit,
    val ownerName: String,
)

/** Gathers report data from repositories. */
class VetReportDataSource(
    private val medications: MedicationRepository,
    private val doseEvents: DoseEventRepository,
    private val weights: WeightRepository,
    private val checkIns: CheckInRepository,
    private val symptoms: SymptomRepository,
) {
    suspend fun load(pet: Pet, options: ReportOptions, unit: WeightUnit, ownerName: String): VetReportData {
        val zone = ZoneId.systemDefault()
        val meds = medications.observeForPet(pet.id).first()
        val from = options.from.atStartOfDay(zone).toInstant()
        val to = options.to.plusDays(1).atStartOfDay(zone).toInstant()
        val events = doseEvents.forPetBetween(pet.id, from, to)
        // Include paused meds too so history in the period is represented.
        val slots = ScheduleEngine.slotsFor(
            meds.map { it.copy(isActive = true) }, events, options.from, options.to, Instant.now(), zone,
        ).filter { !it.scheduledAt.isAfter(Instant.now()) }
        return VetReportData(
            pet = pet,
            options = options,
            medications = meds,
            slots = slots,
            weights = weights.forPetBetween(pet.id, options.from.minusDays(60), options.to),
            checkIns = checkIns.forPetBetween(pet.id, options.from.minusDays(7), options.to),
            symptoms = symptoms.forPetBetween(pet.id, options.from, options.to),
            unit = unit,
            ownerName = ownerName,
        )
    }
}

/**
 * Renders an A4 vet-ready PDF on device with [PdfDocument]. No server, no cost.
 */
class VetReportGenerator(private val context: Context) {

    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 40f
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val shortFmt = DateTimeFormatter.ofPattern("d MMM")

    private val honey = Color.rgb(200, 134, 42)
    private val sage = Color.rgb(111, 143, 106)
    private val clay = Color.rgb(181, 101, 74)
    private val ink = Color.rgb(40, 34, 28)
    private val muted = Color.rgb(120, 110, 100)
    private val line = Color.rgb(230, 220, 205)

    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 22f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
    private val h2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = honey; textSize = 13f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); letterSpacing = 0.06f }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 10.5f }
    private val bodyBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 9f }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = line }

    private lateinit var document: PdfDocument
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var y = 0f
    private var pageNumber = 0

    suspend fun generate(data: VetReportData): File = withContext(Dispatchers.Default) {
        document = PdfDocument()
        pageNumber = 0
        newPage()
        header(data)
        if (data.options.includeMeds) medications(data)
        if (data.options.includeQol) qualityOfLife(data)
        if (data.options.includeWeight) weight(data)
        if (data.options.includeSymptoms) symptoms(data)
        if (data.options.includeNotes) notes(data)
        finishPage()

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val safeName = data.pet.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "pet" }
        val file = File(dir, "GoldenPaw_${safeName}_${data.options.to}.pdf")
        file.outputStream().use { document.writeTo(it) }
        document.close()
        file
    }

    /** Renders page 1 as a bitmap for an in-app preview. */
    suspend fun renderPreview(file: File, widthPx: Int = 900): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.openPage(0).use { p ->
                        val height = (widthPx.toFloat() * p.height / p.width).toInt()
                        val bmp = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- layout helpers

    private fun newPage() {
        pageNumber += 1
        val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        val p = document.startPage(info)
        page = p
        canvas = p.canvas
        y = margin
    }

    private fun finishPage() {
        val p = page ?: return
        val footer = "GoldenPaw · informational record kept by the owner, not veterinary advice · page $pageNumber"
        canvas.drawText(footer, margin, pageHeight - 20f, small)
        document.finishPage(p)
        page = null
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
        canvas.drawText(name.uppercase(), margin, y, h2)
        y += 6f
        canvas.drawLine(margin, y, pageWidth - margin, y, stroke)
        y += 16f
    }

    private fun textLine(text: String, paint: Paint = body, indent: Float = 0f, gap: Float = 15f) {
        val maxWidth = pageWidth - margin * 2 - indent
        wrap(text, paint, maxWidth).forEach { lineText ->
            ensureSpace(gap)
            canvas.drawText(lineText, margin + indent, y, paint)
            y += gap
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        text.split("\n").forEach { paragraph ->
            var current = ""
            paragraph.split(" ").forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
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
        val band = Paint().apply { color = Color.rgb(252, 227, 182) }
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), 8f, band)
        y = margin + 10f
        canvas.drawText("${pet.name} · health summary", margin, y, title)
        y += 18f
        canvas.drawText(
            "${data.options.from.format(dateFmt)} – ${data.options.to.format(dateFmt)}   ·   generated ${LocalDate.now().format(dateFmt)}",
            margin, y, small,
        )
        y += 22f

        val facts = buildList {
            add("Species" to pet.species.label)
            if (pet.breed.isNotBlank()) add("Breed" to pet.breed)
            pet.ageLabel()?.let { add("Age" to it) }
            pet.birthDate?.let { add("Born" to it.format(dateFmt)) }
            if (pet.sex.name != "UNKNOWN") add("Sex" to pet.sex.label)
            data.weights.maxByOrNull { it.date }?.let { add("Latest weight" to "${UnitConversion.format(it.weightKg, data.unit)} (${it.date.format(shortFmt)})") }
            if (pet.conditions.isNotEmpty()) add("Conditions" to pet.conditions.joinToString(", "))
            if (pet.vetName.isNotBlank()) add("Vet" to listOf(pet.vetName, pet.vetPhone).filter { it.isNotBlank() }.joinToString(" · "))
            if (data.ownerName.isNotBlank()) add("Owner" to data.ownerName)
        }
        facts.forEach { (k, v) ->
            ensureSpace(15f)
            canvas.drawText(k, margin, y, small)
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
                "since ${med.schedule.startDate.format(shortFmt)}",
            ).joinToString(" · ")
            textLine(detail, small, indent = 10f, gap = 13f)
            if (medSlots.isNotEmpty()) {
                val pct = adherence?.let { "${(it * 100).roundToInt()}% given" } ?: "—"
                textLine("Doses in period: $given given · $skipped skipped · $missed missed  ($pct)", body, indent = 10f)
                adherenceBar(medSlots)
            }
            y += 4f
        }
    }

    private fun adherenceBar(slots: List<DoseSlot>) {
        ensureSpace(16f)
        val left = margin + 10f
        val width = pageWidth - margin * 2 - 10f
        val cell = (width / slots.size).coerceAtMost(14f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        slots.forEachIndexed { i, s ->
            fill.color = when (s.state) {
                SlotState.GIVEN -> sage
                SlotState.SKIPPED -> honey
                SlotState.MISSED -> clay
                else -> line
            }
            val x = left + i * cell
            if (x + cell <= left + width) canvas.drawRect(RectF(x, y - 8f, x + cell - 1f, y), fill)
        }
        y += 10f
    }

    private fun qualityOfLife(data: VetReportData) {
        section("Quality of life (HHHHHMM scale, 0–70)")
        val inRange = data.checkIns.filter { !it.date.isBefore(data.options.from) }
        if (inRange.isEmpty()) {
            textLine("No daily check-ins in this period.", small)
            return
        }
        val totals = QualityOfLifeCalculator.dailyTotals(data.checkIns).filter { !it.first.isBefore(data.options.from) }
        val avg = totals.map { it.second }.average()
        val good = inRange.count { it.dayQuality == DayQuality.GOOD }
        val okay = inRange.count { it.dayQuality == DayQuality.OKAY }
        val hard = inRange.count { it.dayQuality == DayQuality.HARD }
        textLine("Average score ${avg.roundToInt()} / 70 across ${inRange.size} check-ins.  Good days: $good · Okay: $okay · Hard: $hard", body)

        // Category averages
        val cats = listOf(
            "Comfort (pain)" to inRange.map { it.pain }, "Appetite" to inRange.map { it.appetite },
            "Water" to inRange.map { it.water }, "Hygiene" to inRange.map { it.hygiene },
            "Mood" to inRange.map { it.mood }, "Mobility" to inRange.map { it.mobility },
            "Sleep" to inRange.map { it.sleep },
        )
        textLine(cats.joinToString("   ") { (n, v) -> "$n ${"%.1f".format(v.average())}/5" }, small, gap = 13f)

        // Bar chart of daily totals
        val chartH = 90f
        ensureSpace(chartH + 26f)
        val left = margin
        val right = pageWidth - margin
        val top = y
        val bottom = y + chartH
        canvas.drawRect(left, top, right, bottom, stroke)
        val thresholdY = bottom - (35f / 70f) * chartH
        val dash = Paint(stroke).apply { color = muted; pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f) }
        canvas.drawLine(left, thresholdY, right, thresholdY, dash)
        canvas.drawText("35", right + 3f, thresholdY + 3f, small)
        val days = (data.options.to.toEpochDay() - data.options.from.toEpochDay() + 1).coerceAtLeast(1)
        val barW = ((right - left) / days).coerceAtLeast(1.5f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        totals.forEach { (date, total) ->
            val idx = date.toEpochDay() - data.options.from.toEpochDay()
            val x = left + idx * barW
            val h = (total / 70.0 * chartH).toFloat()
            fill.color = when {
                total >= 49 -> sage
                total >= 35 -> honey
                else -> clay
            }
            canvas.drawRect(x + 0.5f, bottom - h, x + barW - 0.5f, bottom, fill)
        }
        y = bottom + 12f
        canvas.drawText(data.options.from.format(shortFmt), left, y, small)
        val endLabel = data.options.to.format(shortFmt)
        canvas.drawText(endLabel, right - small.measureText(endLabel), y, small)
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
            "${UnitConversion.format(first.weightKg, data.unit)} (${first.date.format(shortFmt)}) → " +
                "${UnitConversion.format(last.weightKg, data.unit)} (${last.date.format(shortFmt)})  ·  " +
                "${if (change >= 0) "+" else ""}${"%.1f".format(change)}%",
            body,
        )
        if (entries.size < 2) return

        val chartH = 100f
        ensureSpace(chartH + 26f)
        val left = margin + 24f
        val right = pageWidth - margin
        val top = y
        val bottom = y + chartH
        canvas.drawRect(left, top, right, bottom, stroke)
        val values = entries.map { UnitConversion.fromKg(it.weightKg, data.unit) }
        val min = values.min()
        val max = values.max()
        val pad = ((max - min) * 0.15).coerceAtLeast(0.2)
        val lo = min - pad
        val hi = max + pad
        val startDay = entries.first().date.toEpochDay()
        val span = (entries.last().date.toEpochDay() - startDay).coerceAtLeast(1)
        val path = Path()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = honey }
        entries.forEachIndexed { i, e ->
            val x = left + (e.date.toEpochDay() - startDay).toFloat() / span * (right - left)
            val v = values[i]
            val yy = bottom - ((v - lo) / (hi - lo)).toFloat() * chartH
            if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
            canvas.drawCircle(x, yy, 2.5f, dot)
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = honey }
        canvas.drawPath(path, linePaint)
        canvas.drawText("%.1f".format(hi), margin, top + 9f, small)
        canvas.drawText("%.1f".format(lo), margin, bottom, small)
        y = bottom + 12f
        canvas.drawText(entries.first().date.format(shortFmt), left, y, small)
        val endLabel = entries.last().date.format(shortFmt)
        canvas.drawText(endLabel, right - small.measureText(endLabel), y, small)
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
            val head = "${s.date.format(shortFmt)}  ${s.type.label}  · severity ${s.severity}/5" +
                if (s.tags.isNotEmpty()) "  · ${s.tags.joinToString(", ")}" else ""
            textLine(head, bodyBold, gap = 13f)
            if (s.notes.isNotBlank()) textLine(s.notes, small, indent = 10f, gap = 12f)
        }
    }

    private fun notes(data: VetReportData) {
        val withNotes = data.checkIns.filter { it.notes.isNotBlank() && !it.date.isBefore(data.options.from) }
        if (withNotes.isEmpty()) return
        section("Owner notes from check-ins")
        withNotes.sortedByDescending { it.date }.forEach {
            textLine("${it.date.format(shortFmt)}: ${it.notes}", body, gap = 13f)
        }
    }
}
