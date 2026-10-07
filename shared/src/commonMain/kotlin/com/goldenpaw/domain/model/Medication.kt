package com.goldenpaw.domain.model

import com.goldenpaw.core.format12h
import com.goldenpaw.core.localDateOfEpochDay
import com.goldenpaw.core.shortLabel
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

enum class ScheduleType(val label: String, val description: String) {
    DAILY("Every day", "At the same times every day"),
    EVERY_N_DAYS("Every few days", "Every 2nd, 3rd… day"),
    WEEKDAYS("Specific days", "e.g. Mon · Wed · Fri"),
    TAPERING("Tapering", "Dose changes on set dates"),
    AS_NEEDED("As needed", "No reminders, log when given");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: DAILY
    }
}

object MedicationCatalog {
    val units = listOf("mg", "ml", "tablet", "capsule", "drop", "puff", "unit", "g", "patch")
    val routes = listOf("Oral", "Injection", "Topical", "Ear", "Eye", "Inhaled", "Transdermal")
}

/** A dosage change that applies from [fromEpochDay] onwards (for tapering schedules). */
data class TaperStep(
    val fromEpochDay: Long,
    val dosage: String,
) {
    val fromDate: LocalDate get() = localDateOfEpochDay(fromEpochDay)
}

/**
 * Rule-based schedule. Dose slots are expanded from the rule at read time, so editing a schedule
 * never rewrites history (logged [DoseEvent]s keep their own scheduled time and dosage).
 */
data class MedicationSchedule(
    val type: ScheduleType,
    val times: List<LocalTime>,
    val intervalDays: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val taperSteps: List<TaperStep> = emptyList(),
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
)

data class Medication(
    val id: String,
    val petId: String,
    val name: String,
    val dosage: String,
    val unit: String,
    val route: String,
    val reason: String,
    val prescribedBy: String,
    val withFood: Boolean,
    val schedule: MedicationSchedule,
    /** Doses left in the current supply; null when not tracked. */
    val supplyRemaining: Double?,
    /** Alert when this many days of supply remain. */
    val refillAlertDays: Int,
    val notes: String,
    val isActive: Boolean,
    val createdAt: Instant,
) {
    val doseLabel: String get() = listOf(dosage, unit).filter { it.isNotBlank() }.joinToString(" ")

    /** Dose for a given date, honouring taper steps. */
    fun dosageOn(date: LocalDate): String {
        if (schedule.type != ScheduleType.TAPERING) return doseLabel
        val step = schedule.taperSteps.sortedBy { it.fromEpochDay }.lastOrNull { it.fromDate <= date }
        return step?.dosage?.takeIf { it.isNotBlank() } ?: doseLabel
    }

    /** Average scheduled doses per day, used for the refill countdown. */
    fun dosesPerDay(): Double {
        val perDay = schedule.times.size.coerceAtLeast(1).toDouble()
        return when (schedule.type) {
            ScheduleType.DAILY, ScheduleType.TAPERING -> perDay
            ScheduleType.EVERY_N_DAYS -> perDay / schedule.intervalDays.coerceAtLeast(1)
            ScheduleType.WEEKDAYS -> perDay * schedule.weekdays.size.coerceAtLeast(1) / 7.0
            ScheduleType.AS_NEEDED -> 0.0
        }
    }

    fun daysOfSupplyLeft(): Int? {
        val remaining = supplyRemaining ?: return null
        val perDay = dosesPerDay()
        if (perDay <= 0.0) return null
        return (remaining / perDay).toInt()
    }

    fun needsRefill(): Boolean {
        val days = daysOfSupplyLeft() ?: return false
        return days <= refillAlertDays
    }

    fun scheduleSummary(): String {
        val times = schedule.times.sorted().joinToString(", ") { it.format12h() }
        val base = when (schedule.type) {
            ScheduleType.DAILY -> "Daily"
            ScheduleType.EVERY_N_DAYS -> "Every ${schedule.intervalDays} days"
            ScheduleType.WEEKDAYS -> schedule.weekdays.sorted().joinToString(" · ") { it.shortLabel() }
            ScheduleType.TAPERING -> "Tapering"
            ScheduleType.AS_NEEDED -> "As needed"
        }
        return if (schedule.type == ScheduleType.AS_NEEDED || times.isEmpty()) base else "$base · $times"
    }
}

enum class DoseStatus { GIVEN, SKIPPED }

/** Append-only log of what actually happened to a dose. */
data class DoseEvent(
    val id: String,
    val medicationId: String,
    val petId: String,
    val scheduledAt: Instant,
    val actualAt: Instant,
    val status: DoseStatus,
    val dosageGiven: String,
    /** Display name of the caregiver who logged it ("who gave the dose"). */
    val givenBy: String,
    /** Caregiver id; null for doses logged before care teams existed. */
    val givenById: String?,
    val notes: String,
)

enum class SlotState { UPCOMING, DUE, GIVEN, SKIPPED, MISSED }

/** One expanded dose occurrence, joined with its log event if any. */
data class DoseSlot(
    val medication: Medication,
    val scheduledAt: Instant,
    val dosage: String,
    val event: DoseEvent?,
    val state: SlotState,
    /**
     * Every GIVEN event recorded for this slot. More than one means two caregivers logged the same
     * dose (e.g. both offline): surfaced as a possible double dose.
     */
    val givenEvents: List<DoseEvent> = emptyList(),
) {
    val key: String get() = "${medication.id}@${scheduledAt.toEpochMilliseconds()}"
    val isPossibleDoubleDose: Boolean get() = givenEvents.size > 1
}
