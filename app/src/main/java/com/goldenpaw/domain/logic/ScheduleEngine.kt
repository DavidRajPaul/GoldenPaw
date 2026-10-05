package com.goldenpaw.domain.logic

import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.ScheduleType
import com.goldenpaw.domain.model.SlotState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Expands rule-based medication schedules into concrete dose slots.
 *
 * Times are wall-clock times in the device zone, so an 8:00 dose stays at 8:00 across DST changes
 * and time-zone travel. A wall time that doesn't exist (spring-forward gap) is shifted forward by
 * [ZonedDateTime.of], which is the least surprising behaviour for a reminder.
 */
object ScheduleEngine {

    /** A slot becomes "due" this long before its time. */
    val DUE_WINDOW: Duration = Duration.ofMinutes(60)

    /** After this long without a log, a slot is considered missed (it can still be logged late). */
    val MISSED_AFTER: Duration = Duration.ofHours(2)

    /** "Given" can be undone within this window. */
    val UNDO_WINDOW: Duration = Duration.ofMinutes(10)

    fun occursOn(med: Medication, date: LocalDate): Boolean {
        val s = med.schedule
        if (!med.isActive) return false
        if (date.isBefore(s.startDate)) return false
        if (s.endDate != null && date.isAfter(s.endDate)) return false
        return when (s.type) {
            ScheduleType.DAILY -> true
            ScheduleType.TAPERING -> {
                // A taper step with dose "0" or "stop" ends the course.
                val dose = med.dosageOn(date).trim().lowercase()
                !(dose == "0" || dose.startsWith("0 ") || dose == "stop")
            }
            ScheduleType.EVERY_N_DAYS -> {
                val n = s.intervalDays.coerceAtLeast(1)
                Math.floorMod(date.toEpochDay() - s.startDate.toEpochDay(), n.toLong()) == 0L
            }
            ScheduleType.WEEKDAYS -> date.dayOfWeek in s.weekdays
            ScheduleType.AS_NEEDED -> false
        }
    }

    fun scheduledInstants(med: Medication, date: LocalDate, zone: ZoneId): List<Instant> {
        if (!occursOn(med, date)) return emptyList()
        return med.schedule.times.distinct().sorted().map { time ->
            ZonedDateTime.of(date, time, zone).toInstant()
        }
    }

    fun slotsFor(
        meds: List<Medication>,
        events: List<DoseEvent>,
        from: LocalDate,
        toInclusive: LocalDate,
        now: Instant,
        zone: ZoneId,
    ): List<DoseSlot> {
        val eventIndex = events.groupBy { it.medicationId to it.scheduledAt.toEpochMilli() }
            .mapValues { (_, list) -> list.maxBy { it.actualAt } }
        val result = mutableListOf<DoseSlot>()
        var date = from
        while (!date.isAfter(toInclusive)) {
            for (med in meds) {
                for (instant in scheduledInstants(med, date, zone)) {
                    val event = eventIndex[med.id to instant.toEpochMilli()]
                    result += DoseSlot(
                        medication = med,
                        scheduledAt = instant,
                        dosage = med.dosageOn(date),
                        event = event,
                        state = stateOf(instant, event, now),
                    )
                }
            }
            date = date.plusDays(1)
        }
        return result.sortedWith(compareBy({ it.scheduledAt }, { it.medication.name }))
    }

    fun stateOf(scheduledAt: Instant, event: DoseEvent?, now: Instant): SlotState = when {
        event?.status == DoseStatus.GIVEN -> SlotState.GIVEN
        event?.status == DoseStatus.SKIPPED -> SlotState.SKIPPED
        now.isAfter(scheduledAt.plus(MISSED_AFTER)) -> SlotState.MISSED
        !now.isBefore(scheduledAt.minus(DUE_WINDOW)) -> SlotState.DUE
        else -> SlotState.UPCOMING
    }

    fun canUndo(event: DoseEvent?, now: Instant): Boolean =
        event != null && Duration.between(event.actualAt, now) <= UNDO_WINDOW

    /** Next scheduled instant strictly after [after] across all meds, searching [horizonDays]. */
    fun nextOccurrence(
        meds: List<Medication>,
        after: Instant,
        zone: ZoneId,
        horizonDays: Long = 8,
    ): Instant? {
        val startDate = after.atZone(zone).toLocalDate()
        var date = startDate
        val end = startDate.plusDays(horizonDays)
        while (!date.isAfter(end)) {
            val candidate = meds.flatMap { scheduledInstants(it, date, zone) }
                .filter { it.isAfter(after) }
                .minOrNull()
            if (candidate != null) return candidate
            date = date.plusDays(1)
        }
        return null
    }

    /** Adherence = given / (scheduled slots that are already in the past) for the range. */
    fun adherence(slots: List<DoseSlot>): Double? {
        val countable = slots.filter { it.state != SlotState.UPCOMING && it.state != SlotState.DUE }
        if (countable.isEmpty()) return null
        return countable.count { it.state == SlotState.GIVEN }.toDouble() / countable.size
    }
}
