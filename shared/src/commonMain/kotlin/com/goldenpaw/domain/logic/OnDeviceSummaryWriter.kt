package com.goldenpaw.domain.logic

import com.goldenpaw.core.newId
import com.goldenpaw.core.plural
import com.goldenpaw.core.toFixed
import com.goldenpaw.domain.model.SummarySource
import com.goldenpaw.domain.model.WeeklyDigest
import com.goldenpaw.domain.model.WeeklySummary
import com.goldenpaw.domain.model.WeightUnit
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Writes a warm, plain-language weekly summary from a [WeeklyDigest] without any network call.
 * Used offline, when the cloud isn't configured, and as the fallback if the AI writer fails.
 * Tone rules (shared with the AI prompt): observe, never diagnose; never shame missed days.
 */
object OnDeviceSummaryWriter {

    fun write(d: WeeklyDigest, unit: WeightUnit, now: Instant): WeeklySummary {
        val name = d.petName
        val qolDelta = d.qolAverage?.let { avg -> d.previousQolAverage?.let { avg - it } }
        val adherencePct = d.adherence?.let { (it * 100).roundToInt() }
        val prevAdherencePct = d.previousAdherence?.let { (it * 100).roundToInt() }
        val scheduled = d.dosesGiven + d.dosesSkipped + d.dosesMissed

        val headline = when {
            !d.hasAnyData -> "A quiet week in $name's log"
            qolDelta != null && qolDelta >= 5 -> "A brighter week for $name"
            qolDelta != null && qolDelta <= -5 -> "A harder week for $name"
            d.hardDays >= 3 -> "Some tough days for $name"
            adherencePct != null && adherencePct >= 95 && scheduled >= 3 -> "Every dose looked after"
            d.goodDays >= 5 -> "$name had a good week"
            else -> "$name's week in review"
        }

        val sentences = mutableListOf<String>()
        if (d.checkIns > 0) {
            val parts = listOfNotNull(
                plural(d.goodDays, "good day").takeIf { d.goodDays > 0 },
                plural(d.okayDays, "okay day").takeIf { d.okayDays > 0 },
                plural(d.hardDays, "hard day").takeIf { d.hardDays > 0 },
            )
            sentences += "$name had ${parts.joinNatural()} across ${plural(d.checkIns, "check-in")}."
        }
        if (d.qolAverage != null) {
            val avg = d.qolAverage.roundToInt()
            sentences += when {
                qolDelta == null -> "Average quality-of-life score was $avg out of 70."
                abs(qolDelta) < 2 -> "Average quality-of-life score held steady at $avg out of 70."
                qolDelta > 0 -> "Average quality-of-life score was $avg out of 70, up ${qolDelta.roundToInt()} from last week."
                else -> "Average quality-of-life score was $avg out of 70, down ${(-qolDelta).roundToInt()} from last week."
            }
        }
        if (scheduled > 0 && adherencePct != null) {
            val trend = when {
                prevAdherencePct == null -> ""
                adherencePct > prevAdherencePct -> ", up from $prevAdherencePct% last week"
                adherencePct < prevAdherencePct -> ", compared with $prevAdherencePct% last week"
                else -> ""
            }
            sentences += "${d.dosesGiven} of $scheduled scheduled doses were given ($adherencePct%$trend)."
        }
        if (d.weakestArea != null && d.strongestArea != null && d.weakestArea != d.strongestArea) {
            sentences += "${d.weakestArea} scored lowest in check-ins, and ${d.strongestArea.lowercase()} was the strongest area."
        }
        if (!d.hasAnyData) {
            sentences += "Nothing was logged for $name this week. That's okay. A 30-second check-in on a few days " +
                "makes next week's summary much more useful."
        }

        val highlights = mutableListOf<String>()
        if (d.checkIns >= 5) highlights += "Checked in on ${d.checkIns} of 7 days"
        if (adherencePct != null && adherencePct >= 90 && scheduled >= 3) highlights += "$adherencePct% of doses given on schedule"
        if (d.totalSymptoms == 0 && d.checkIns > 0) highlights += "No symptoms logged this week"
        d.weightChangePercent?.let { pct ->
            if (abs(pct) < 2 && d.weightEndKg != null) {
                highlights += "Weight steady at ${UnitConversion.format(d.weightEndKg, unit)}"
            }
        }
        if (d.goodDays >= 4) highlights += "${plural(d.goodDays, "good day")} to remember"
        val helpers = d.caregiverContributions.filterKeys { it != "You" }
        if (helpers.isNotEmpty() && d.caregiverContributions.size > 1) {
            val top = helpers.maxByOrNull { it.value }
            if (top != null) highlights += "${top.key} logged ${plural(top.value, "entry", "entries")}: real teamwork"
        }

        val watch = mutableListOf<String>()
        val questions = mutableListOf<String>()
        d.patterns.forEach { p ->
            watch += "${p.type.label} showed up on ${p.days} days this week."
            questions += "${p.type.label} happened on ${p.days} days this week. Is that something you'd like to look at?"
        }
        d.weightChangePercent?.let { pct ->
            if (abs(pct) >= 3 && d.weightStartKg != null && d.weightEndKg != null) {
                val dir = if (pct < 0) "down" else "up"
                watch += "Weight is $dir ${abs(pct).toFixed(1)}% (${UnitConversion.format(d.weightStartKg, unit)} → " +
                    "${UnitConversion.format(d.weightEndKg, unit)})."
                questions += "$name's weight moved ${abs(pct).toFixed(1)}% this week. Should we track it more closely?"
            }
        }
        if (d.hardDays >= 3) {
            watch += "${plural(d.hardDays, "hard day")} this week."
            questions += "There were ${d.hardDays} hard days this week. Is there anything that could make $name more comfortable?"
        }
        if (d.dosesMissed >= 2) {
            watch += "${plural(d.dosesMissed, "dose")} went unlogged. If they were given, logging them keeps the record accurate."
        }
        if (adherencePct != null && prevAdherencePct != null && prevAdherencePct - adherencePct >= 10) {
            watch += "Fewer doses were logged than last week ($adherencePct% vs $prevAdherencePct%)."
        }
        if (d.weakestArea != null && d.hardDays + d.okayDays >= 2 && questions.size < 3) {
            questions += "${d.weakestArea} has been the lowest check-in score. Is there anything to try at home?"
        }

        return WeeklySummary(
            id = newId(),
            petId = d.petId,
            weekStart = d.weekStart,
            headline = headline,
            summary = sentences.joinToString(" "),
            highlights = highlights.take(4),
            watchItems = watch.take(4),
            vetQuestions = questions.distinct().take(3),
            source = SummarySource.ON_DEVICE,
            createdAt = now,
        )
    }

    private fun List<String>.joinNatural(): String = when (size) {
        0 -> "no recorded days"
        1 -> first()
        2 -> "${this[0]} and ${this[1]}"
        else -> dropLast(1).joinToString(", ") + " and " + last()
    }
}
