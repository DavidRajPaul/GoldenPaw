package com.goldenpaw.domain.logic

import com.goldenpaw.core.minusDays
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.toLocalDate
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.CheckInCategory
import com.goldenpaw.domain.model.DayQuality
import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.ScheduleType
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.SlotState
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeeklyDigest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Turns a week of logs into structured, privacy-light facts for the weekly summary. */
object WeeklyDigestBuilder {

    data class Input(
        val pet: Pet,
        val weekStart: LocalDate,
        val medications: List<Medication>,
        /** Dose events covering at least [weekStart] - 7 days .. week end. */
        val doseEvents: List<DoseEvent>,
        /** Check-ins covering at least [weekStart] - 13 days .. week end (for the QoL window). */
        val checkIns: List<CheckIn>,
        val symptoms: List<SymptomEntry>,
        val weights: List<WeightEntry>,
        val now: Instant,
        val zone: TimeZone,
        val today: LocalDate,
    )

    fun build(input: Input): WeeklyDigest {
        val weekStart = input.weekStart
        val weekEnd = weekStart.plusDays(6)
        val prevStart = weekStart.minusDays(7)
        val prevEnd = weekStart.minusDays(1)
        val activeMeds = input.medications.filter { it.isActive }

        // Doses: only count up to today so a half-finished week isn't full of "missed" future slots.
        val countEnd = minOf(weekEnd, input.today)
        val slots = if (countEnd >= weekStart) {
            ScheduleEngine.slotsFor(activeMeds, input.doseEvents, weekStart, countEnd, input.now, input.zone)
        } else emptyList()
        val prevSlots = ScheduleEngine.slotsFor(activeMeds, input.doseEvents, prevStart, prevEnd, input.now, input.zone)
        val asNeededGiven = input.doseEvents.count { e ->
            val med = input.medications.firstOrNull { it.id == e.medicationId }
            med?.schedule?.type == ScheduleType.AS_NEEDED &&
                e.status == DoseStatus.GIVEN &&
                e.scheduledAt.toLocalDate(input.zone) in weekStart..weekEnd
        }

        // Check-ins & QoL
        val totals = QualityOfLifeCalculator.dailyTotals(input.checkIns)
        val weekTotals = totals.filter { it.first in weekStart..weekEnd }.map { it.second }
        val prevTotals = totals.filter { it.first in prevStart..prevEnd }.map { it.second }
        val weekCheckIns = input.checkIns.filter { it.date in weekStart..weekEnd }
        val categoryAverages = if (weekCheckIns.size >= 2) {
            CheckInCategory.entries.associateWith { cat -> weekCheckIns.map { it.valueOf(cat) }.average() }
        } else emptyMap()
        val weakest = categoryAverages.minByOrNull { it.value }?.takeIf { it.value < 4.0 }?.key?.label
        val strongest = categoryAverages.maxByOrNull { it.value }?.takeIf { it.value >= 3.5 }?.key?.label

        // Symptoms
        val weekSymptoms = input.symptoms.filter { it.date in weekStart..weekEnd }
        val symptomCounts = weekSymptoms.groupingBy { it.type }.eachCount()
        val symptomDays = weekSymptoms.groupBy { it.type }.mapValues { (_, l) -> l.map { it.date }.distinct().size }
        val patterns = SymptomPatternDetector.detect(weekSymptoms, weekEnd, windowDays = 7)

        // Weight: baseline is the last reading at or before the week start, else the first in the week.
        val sortedWeights = input.weights.sortedBy { it.date }
        val inWeek = sortedWeights.filter { it.date in weekStart..weekEnd }
        val baseline = sortedWeights.lastOrNull { it.date < weekStart } ?: inWeek.firstOrNull()
        val end = inWeek.lastOrNull()

        // Notes: what the owner wrote, trimmed. These give the summary its human texture.
        val notes = (weekCheckIns.filter { it.notes.isNotBlank() }.map { it.notes.trim() } +
            weekSymptoms.filter { it.notes.isNotBlank() }.map { "${it.type.label}: ${it.notes.trim()}" })
            .map { if (it.length > 160) it.take(157) + "…" else it }
            .take(8)

        // Who logged what (care team).
        val weekEvents = input.doseEvents.filter { it.scheduledAt.toLocalDate(input.zone) in weekStart..weekEnd }
        val contributions = buildMap<String, Int> {
            fun add(name: String) {
                val key = name.ifBlank { "You" }
                put(key, (get(key) ?: 0) + 1)
            }
            weekEvents.forEach { add(it.givenBy) }
            weekCheckIns.forEach { add(it.loggedBy.name) }
            weekSymptoms.forEach { add(it.loggedBy.name) }
            inWeek.forEach { add(it.loggedBy.name) }
        }

        return WeeklyDigest(
            petId = input.pet.id,
            petName = input.pet.name,
            species = input.pet.species,
            ageYears = input.pet.ageYears(input.today),
            conditions = input.pet.conditions,
            weekStart = weekStart,
            weekEnd = weekEnd,
            medicationNames = activeMeds.map { it.name },
            dosesGiven = slots.count { it.state == SlotState.GIVEN } + asNeededGiven,
            dosesSkipped = slots.count { it.state == SlotState.SKIPPED },
            dosesMissed = slots.count { it.state == SlotState.MISSED },
            adherence = ScheduleEngine.adherence(slots),
            previousAdherence = ScheduleEngine.adherence(prevSlots),
            checkIns = weekCheckIns.size,
            qolAverage = weekTotals.takeIf { it.isNotEmpty() }?.average(),
            previousQolAverage = prevTotals.takeIf { it.isNotEmpty() }?.average(),
            goodDays = weekCheckIns.count { it.dayQuality == DayQuality.GOOD },
            okayDays = weekCheckIns.count { it.dayQuality == DayQuality.OKAY },
            hardDays = weekCheckIns.count { it.dayQuality == DayQuality.HARD },
            weakestArea = weakest,
            strongestArea = strongest,
            symptomCounts = symptomCounts,
            symptomDays = symptomDays,
            patterns = patterns,
            weightStartKg = baseline?.weightKg,
            weightEndKg = end?.weightKg,
            notes = notes,
            caregiverContributions = contributions,
        )
    }
}
