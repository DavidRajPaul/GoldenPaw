package com.goldenpaw.domain.logic

import com.goldenpaw.core.at
import com.goldenpaw.core.epochDay
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.toLocalDate
import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.ScheduleType
import com.goldenpaw.domain.model.SlotState
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Expands rule-based medication schedules into concrete dose slots.
 *
 * Times are wall-clock times in the device zone, so an 8:00 dose stays at 8:00 across DST changes
 * and time-zone travel. A wall time inside a spring-forward gap is shifted forward, which is the
 * least surprising behaviour for a reminder.
 */
object ScheduleEngine {

    /** A slot becomes "due" this long before its time. */
    val DUE_WINDOW: Duration = 60.minutes

    /** After this long without a log, a slot is considered missed (it can still be logged late). */
    val MISSED_AFTER: Duration = 2.hours

    /** "Given" can be undone within this window. */
    val UNDO_WINDOW: Duration = 10.minutes

    fun occursOn(med: Medication, date: LocalDate): Boolean {
        val s = med.schedule
        if (!med.isActive) return false
        if (date < s.startDate) return false
        if (s.endDate != null && date > s.endDate) return false
        return when (s.type) {
            ScheduleType.DAILY -> true
            ScheduleType.TAPERING -> {
                // A taper step with dose "0" or "stop" ends the course.
                val dose = med.dosageOn(date).trim().lowercase()
                !(dose == "0" || dose.startsWith("0 ") || dose == "stop")
            }
            ScheduleType.EVERY_N_DAYS -> {
                val n = s.intervalDays.coerceAtLeast(1).toLong()
                (date.epochDay() - s.startDate.epochDay()).mod(n) == 0L
            }
            ScheduleType.WEEKDAYS -> date.dayOfWeek in s.weekdays
            ScheduleType.AS_NEEDED -> false
        }
    }

    fun scheduledInstants(med: Medication, date: LocalDate, zone: TimeZone): List<Instant> {
        if (!occursOn(med, date)) return emptyList()
        return med.schedule.times.distinct().sorted().map { time -> date.at(time, zone) }
    }

    fun slotsFor(
        meds: List<Medication>,
        events: List<DoseEvent>,
        from: LocalDate,
        toInclusive: LocalDate,
        now: Instant,
        zone: TimeZone,
    ): List<DoseSlot> {
        val byKey = events.groupBy { it.medicationId to it.scheduledAt.toEpochMilliseconds() }
        val result = mutableListOf<DoseSlot>()
        var date = from
        while (date <= toInclusive) {
            for (med in meds) {
                for (instant in scheduledInstants(med, date, zone)) {
                    val slotEvents = byKey[med.id to instant.toEpochMilliseconds()].orEmpty()
                    val given = slotEvents.filter { it.status == DoseStatus.GIVEN }
                    // A GIVEN wins over a SKIPPED from another caregiver: safer to show "given".
                    val event = given.maxByOrNull { it.actualAt } ?: slotEvents.maxByOrNull { it.actualAt }
                    result += DoseSlot(
                        medication = med,
                        scheduledAt = instant,
                        dosage = med.dosageOn(date),
                        event = event,
                        state = stateOf(instant, event, now),
                        givenEvents = given.sortedBy { it.actualAt },
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
        now > scheduledAt + MISSED_AFTER -> SlotState.MISSED
        now >= scheduledAt - DUE_WINDOW -> SlotState.DUE
        else -> SlotState.UPCOMING
    }

    fun canUndo(event: DoseEvent?, now: Instant): Boolean =
        event != null && (now - event.actualAt) <= UNDO_WINDOW

    /** Next scheduled instant strictly after [after] across all meds, searching [horizonDays]. */
    fun nextOccurrence(
        meds: List<Medication>,
        after: Instant,
        zone: TimeZone,
        horizonDays: Int = 8,
    ): Instant? {
        val startDate = after.toLocalDate(zone)
        var date = startDate
        val end = startDate.plusDays(horizonDays)
        while (date <= end) {
            val candidate = meds.flatMap { scheduledInstants(it, date, zone) }
                .filter { it > after }
                .minOrNull()
            if (candidate != null) return candidate
            date = date.plusDays(1)
        }
        return null
    }

    /** Upcoming instants (for platforms like iOS that pre-schedule a batch of local notifications). */
    fun upcomingOccurrences(
        meds: List<Medication>,
        after: Instant,
        zone: TimeZone,
        limit: Int,
        horizonDays: Int = 14,
    ): List<Pair<Medication, Instant>> {
        val startDate = after.toLocalDate(zone)
        val result = mutableListOf<Pair<Medication, Instant>>()
        var date = startDate
        val end = startDate.plusDays(horizonDays)
        while (date <= end && result.size < limit) {
            meds.flatMap { med -> scheduledInstants(med, date, zone).map { med to it } }
                .filter { it.second > after }
                .sortedBy { it.second }
                .forEach { if (result.size < limit) result += it }
            date = date.plusDays(1)
        }
        return result
    }

    /** Adherence = given / (scheduled slots that are already in the past) for the range. */
    fun adherence(slots: List<DoseSlot>): Double? {
        val countable = slots.filter { it.state != SlotState.UPCOMING && it.state != SlotState.DUE }
        if (countable.isEmpty()) return null
        return countable.count { it.state == SlotState.GIVEN }.toDouble() / countable.size
    }
}
