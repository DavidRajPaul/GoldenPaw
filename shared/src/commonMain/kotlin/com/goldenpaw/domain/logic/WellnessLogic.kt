package com.goldenpaw.domain.logic

import com.goldenpaw.core.minusDays
import com.goldenpaw.core.toFixed
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.CheckInCategory
import com.goldenpaw.domain.model.DayQuality
import com.goldenpaw.domain.model.Insight
import com.goldenpaw.domain.model.QualityOfLifeScore
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.SymptomPattern
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

object QualityOfLifeCalculator {

    /** Maps a 1..5 check-in value to the 0..10 HHHHHMM range. */
    private fun scale(value: Int): Double = ((value.coerceIn(1, 5) - 1) * 2.5)

    /**
     * Score for [checkIn], using the 7 days ending on its date to compute
     * "more good days than bad".
     */
    fun score(checkIn: CheckIn, history: List<CheckIn>): QualityOfLifeScore {
        val windowStart = checkIn.date.minusDays(6)
        val window = history.filter { it.date >= windowStart && it.date <= checkIn.date }
            .ifEmpty { listOf(checkIn) }
        val good = window.count { it.dayQuality == DayQuality.GOOD }
        val hard = window.count { it.dayQuality == DayQuality.HARD }
        val moreGood = when {
            good + hard == 0 -> 5.0 // all "okay" days: neutral
            else -> 10.0 * good / (good + hard)
        }
        return QualityOfLifeScore(
            hurt = scale(checkIn.pain),
            hunger = scale(checkIn.appetite),
            hydration = scale(checkIn.water),
            hygiene = scale(checkIn.hygiene),
            happiness = scale(checkIn.mood),
            mobility = scale(checkIn.mobility),
            moreGoodDays = moreGood,
        )
    }

    /** Daily QoL totals for every check-in in [checkIns], oldest first. */
    fun dailyTotals(checkIns: List<CheckIn>): List<Pair<LocalDate, Double>> {
        val sorted = checkIns.sortedBy { it.date }
        return sorted.map { it.date to score(it, sorted).total }
    }

    /** Average of one check-in category (1..5) across [checkIns]. */
    fun categoryAverage(checkIns: List<CheckIn>, category: CheckInCategory): Double? {
        if (checkIns.isEmpty()) return null
        return checkIns.map { it.valueOf(category) }.average()
    }
}

fun CheckIn.valueOf(category: CheckInCategory): Int = when (category) {
    CheckInCategory.APPETITE -> appetite
    CheckInCategory.WATER -> water
    CheckInCategory.MOBILITY -> mobility
    CheckInCategory.MOOD -> mood
    CheckInCategory.PAIN -> pain
    CheckInCategory.HYGIENE -> hygiene
    CheckInCategory.SLEEP -> sleep
}

object SymptomPatternDetector {
    fun detect(
        entries: List<SymptomEntry>,
        today: LocalDate,
        windowDays: Int = 7,
        minDays: Int = 3,
    ): List<SymptomPattern> {
        val start = today.minusDays(windowDays - 1)
        return entries.asSequence()
            .filter { it.date >= start && it.date <= today }
            .groupBy { it.type }
            .map { (type, list) -> SymptomPattern(type, list.map { it.date }.distinct().size, windowDays) }
            .filter { it.days >= minDays }
            .sortedByDescending { it.days }
            .toList()
    }
}

object UnitConversion {
    const val KG_PER_LB = 0.45359237

    fun kgToLb(kg: Double) = kg / KG_PER_LB
    fun lbToKg(lb: Double) = lb * KG_PER_LB

    fun toKg(value: Double, unit: WeightUnit) = if (unit == WeightUnit.KG) value else lbToKg(value)
    fun fromKg(kg: Double, unit: WeightUnit) = if (unit == WeightUnit.KG) kg else kgToLb(kg)

    fun format(kg: Double, unit: WeightUnit): String = "${fromKg(kg, unit).toFixed(1)} ${unit.label}"
}

/** Gentle, non-diagnostic observations surfaced on Today and Insights. */
object InsightEngine {
    fun build(
        today: LocalDate,
        checkIns: List<CheckIn>,
        weights: List<WeightEntry>,
        patterns: List<SymptomPattern>,
        unit: WeightUnit,
    ): List<Insight> {
        val insights = mutableListOf<Insight>()

        patterns.forEach {
            insights += Insight(
                title = it.message,
                body = "This may be worth mentioning to your vet. It will appear in your vet report.",
                tone = Insight.Tone.WATCH,
            )
        }

        // Weight change over ~30 days
        val sortedW = weights.sortedBy { it.date }
        val latest = sortedW.lastOrNull()
        if (latest != null) {
            val baseline = sortedW.lastOrNull { it.date <= latest.date.minusDays(25) }
            if (baseline != null && baseline.weightKg > 0) {
                val change = (latest.weightKg - baseline.weightKg) / baseline.weightKg * 100
                if (abs(change) >= 5) {
                    val dir = if (change < 0) "down" else "up"
                    insights += Insight(
                        title = "Weight is $dir ${abs(change).roundToInt()}% in about a month",
                        body = "Now ${UnitConversion.format(latest.weightKg, unit)}, was " +
                            "${UnitConversion.format(baseline.weightKg, unit)}. Gradual changes are easy to miss day to day.",
                        tone = Insight.Tone.WATCH,
                    )
                }
            }
        }

        // QoL trend: last 7 days vs previous 7
        val totals = QualityOfLifeCalculator.dailyTotals(checkIns)
        val recent = totals.filter { it.first > today.minusDays(7) }.map { it.second }
        val previous = totals.filter { it.first > today.minusDays(14) && it.first <= today.minusDays(7) }.map { it.second }
        if (recent.size >= 3 && previous.size >= 3) {
            val delta = recent.average() - previous.average()
            if (delta <= -5) {
                insights += Insight(
                    title = "This week has been harder",
                    body = "Average quality-of-life score dropped ${-delta.roundToInt()} points vs last week.",
                    tone = Insight.Tone.WATCH,
                )
            } else if (delta >= 5) {
                insights += Insight(
                    title = "A brighter week",
                    body = "Average quality-of-life score rose ${delta.roundToInt()} points vs last week.",
                    tone = Insight.Tone.POSITIVE,
                )
            }
        }

        val goodStreak = checkIns.sortedByDescending { it.date }
            .takeWhile { it.dayQuality == DayQuality.GOOD }.size
        if (goodStreak >= 5) {
            insights += Insight(
                title = "$goodStreak good days in a row",
                body = "Every good day, remembered.",
                tone = Insight.Tone.POSITIVE,
            )
        }
        return insights
    }
}
