package com.goldenpaw

import com.goldenpaw.core.at
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.ScheduleType
import com.goldenpaw.domain.model.SlotState
import com.goldenpaw.domain.model.TaperStep
import com.goldenpaw.core.epochDay
import com.goldenpaw.core.plusDays
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ScheduleEngineTest {
    private val utc = TimeZone.UTC
    private val day = LocalDate(2026, 10, 6)

    @Test
    fun dailyScheduleExpandsTwoSlotsPerDay() {
        val med = TestData.med()
        val slots = ScheduleEngine.slotsFor(listOf(med), emptyList(), day, day.plusDays(2), day.at(LocalTime(0, 0), utc), utc)
        assertEquals(6, slots.size)
        assertTrue(slots.all { it.state == SlotState.UPCOMING })
    }

    @Test
    fun everyThirdDayFollowsTheStartDate() {
        val med = TestData.med(type = ScheduleType.EVERY_N_DAYS, interval = 3, start = LocalDate(2026, 10, 1))
        assertTrue(ScheduleEngine.occursOn(med, LocalDate(2026, 10, 1)))
        assertFalse(ScheduleEngine.occursOn(med, LocalDate(2026, 10, 2)))
        assertTrue(ScheduleEngine.occursOn(med, LocalDate(2026, 10, 4)))
        assertFalse(ScheduleEngine.occursOn(med, LocalDate(2026, 9, 30)))
    }

    @Test
    fun weekdaysOnlyOnChosenDays() {
        val med = TestData.med(type = ScheduleType.WEEKDAYS).let {
            it.copy(schedule = it.schedule.copy(weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)))
        }
        assertTrue(ScheduleEngine.occursOn(med, LocalDate(2026, 10, 5))) // Monday
        assertFalse(ScheduleEngine.occursOn(med, LocalDate(2026, 10, 6))) // Tuesday
    }

    @Test
    fun taperStepWithZeroEndsTheCourse() {
        val base = TestData.med(type = ScheduleType.TAPERING)
        val med = base.copy(
            schedule = base.schedule.copy(
                taperSteps = listOf(
                    TaperStep(LocalDate(2026, 10, 1).epochDay(), "10 mg"),
                    TaperStep(LocalDate(2026, 10, 8).epochDay(), "5 mg"),
                    TaperStep(LocalDate(2026, 10, 15).epochDay(), "0"),
                ),
            ),
        )
        assertEquals("10 mg", med.dosageOn(LocalDate(2026, 10, 3)))
        assertEquals("5 mg", med.dosageOn(LocalDate(2026, 10, 9)))
        assertTrue(ScheduleEngine.occursOn(med, LocalDate(2026, 10, 14)))
        assertFalse(ScheduleEngine.occursOn(med, LocalDate(2026, 10, 15)))
    }

    @Test
    fun slotStatesDueMissedGiven() {
        val med = TestData.med(times = listOf(LocalTime(8, 0)))
        val at8 = day.at(LocalTime(8, 0), utc)
        assertEquals(SlotState.UPCOMING, ScheduleEngine.stateOf(at8, null, at8 - 2.hours))
        assertEquals(SlotState.DUE, ScheduleEngine.stateOf(at8, null, at8 - 30.minutes))
        assertEquals(SlotState.MISSED, ScheduleEngine.stateOf(at8, null, at8 + 3.hours))
        val given = TestData.dose(med.id, at8)
        assertEquals(SlotState.GIVEN, ScheduleEngine.stateOf(at8, given, at8 + 3.hours))
    }

    @Test
    fun dstGapKeepsWallClockTime() {
        // US spring-forward 2026-03-08: 02:30 doesn't exist and shifts forward; 08:00 stays 08:00.
        val ny = TimeZone.of("America/New_York")
        val date = LocalDate(2026, 3, 8)
        val med = TestData.med(times = listOf(LocalTime(8, 0)), start = LocalDate(2026, 3, 1))
        val instant = ScheduleEngine.scheduledInstants(med, date, ny).single()
        assertEquals(12, (instant.toEpochMilliseconds() / 3_600_000L % 24).toInt()) // 08:00 EDT == 12:00 UTC
    }

    @Test
    fun twoCaregiversGivingTheSameDoseIsFlagged() {
        val med = TestData.med(times = listOf(LocalTime(8, 0)))
        val at8 = day.at(LocalTime(8, 0), utc)
        val events = listOf(
            TestData.dose(med.id, at8, by = "Priya", byId = "c1"),
            TestData.dose(med.id, at8, by = "Sam", byId = "c2", actualAt = at8 + 10.minutes),
        )
        val slot = ScheduleEngine.slotsFor(listOf(med), events, day, day, at8 + 1.hours, utc).single()
        assertEquals(SlotState.GIVEN, slot.state)
        assertTrue(slot.isPossibleDoubleDose)
    }

    @Test
    fun givenBeatsSkippedFromAnotherCaregiver() {
        val med = TestData.med(times = listOf(LocalTime(8, 0)))
        val at8 = day.at(LocalTime(8, 0), utc)
        val events = listOf(
            TestData.dose(med.id, at8, status = DoseStatus.GIVEN, byId = "c1"),
            TestData.dose(med.id, at8, status = DoseStatus.SKIPPED, byId = "c2", actualAt = at8 + 5.minutes),
        )
        val slot = ScheduleEngine.slotsFor(listOf(med), events, day, day, at8 + 1.hours, utc).single()
        assertEquals(SlotState.GIVEN, slot.state)
    }

    @Test
    fun adherenceIgnoresFutureSlots() {
        val med = TestData.med(times = listOf(LocalTime(8, 0), LocalTime(20, 0)))
        val at8 = day.at(LocalTime(8, 0), utc)
        val slots = ScheduleEngine.slotsFor(listOf(med), listOf(TestData.dose(med.id, at8)), day, day, at8 + 3.hours, utc)
        assertEquals(1.0, ScheduleEngine.adherence(slots))
    }

    @Test
    fun refillCountdown() {
        val med = TestData.med(supply = 9.0) // 2 per day
        assertEquals(4, med.daysOfSupplyLeft())
        assertTrue(med.needsRefill())
    }
}

