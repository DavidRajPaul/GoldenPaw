package com.goldenpaw

import com.goldenpaw.core.at
import com.goldenpaw.core.minusDays
import com.goldenpaw.domain.logic.GamificationEngine
import com.goldenpaw.domain.model.Badge
import com.goldenpaw.domain.model.DayMark
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GamificationTest {
    private val today = LocalDate(2026, 10, 8) // Thursday

    @Test
    fun consecutiveDaysCount() {
        val days = (0..9).map { today.minusDays(it) }.toSet()
        val s = GamificationEngine.streak(days, today, restDaysPerWeek = 2)
        assertEquals(10, s.current)
        assertTrue(s.caredToday)
    }

    @Test
    fun todayNotYetLoggedIsPendingNotMissed() {
        val days = (1..5).map { today.minusDays(it) }.toSet()
        val s = GamificationEngine.streak(days, today, 2)
        assertEquals(5, s.current)
        assertFalse(s.caredToday)
        assertEquals(DayMark.PENDING, s.lastSeven.last())
    }

    @Test
    fun restDayBridgesASingleMiss() {
        val days = setOf(today, today.minusDays(1), today.minusDays(3), today.minusDays(4))
        val s = GamificationEngine.streak(days, today, 2)
        assertEquals(4, s.current)
        assertEquals(DayMark.REST, s.lastSeven[4]) // today-2
    }

    @Test
    fun streakBreaksWhenRestDaysRunOut() {
        // Thursday back to Monday: miss Wed, Tue, Mon of this week with only 2 rest days.
        val days = setOf(today, today.minusDays(4), today.minusDays(5))
        val s = GamificationEngine.streak(days, today, 2)
        assertEquals(1, s.current)
    }

    @Test
    fun noRestDaysMeansStrictStreak() {
        val days = setOf(today, today.minusDays(2))
        assertEquals(1, GamificationEngine.streak(days, today, 0).current)
    }

    @Test
    fun badgesAndPointsAreDerivedFromTheLog() {
        val utc = TimeZone.UTC
        val med = TestData.med(times = listOf(LocalTime(8, 0)))
        val doses = (0 until 60).map { TestData.dose(med.id, today.minusDays(it).at(LocalTime(8, 0), utc)) }
        val checkIns = (0 until 8).map { TestData.checkIn(today.minusDays(it)) }
        val state = GamificationEngine.compute(
            GamificationEngine.Input(
                today = today, zone = utc, now = today.at(LocalTime(21, 0), utc), meId = "c1", meName = "Priya",
                givenDoses = doses, checkIns = checkIns, symptoms = emptyList(), weights = emptyList(),
                todaySlots = emptyList(), lastWeekSlots = emptyList(), restDaysPerWeek = 2,
                unlocked = mapOf(Badge.FIRST_DOSE.id to TestData.epoch), selectedPetId = "pet",
            ),
        )
        assertEquals(60 * GamificationEngine.Points.DOSE + 8 * GamificationEngine.Points.CHECK_IN, state.points)
        assertEquals(60, state.streak.current)
        assertTrue(state.streak.graduated)
        val earned = state.newlyEarned.toSet()
        assertTrue(Badge.DOSES_50 in earned)
        assertTrue(Badge.STREAK_30 in earned)
        assertTrue(Badge.FIRST_CHECKIN in earned)
        assertFalse(Badge.FIRST_DOSE in earned) // already stored
        assertFalse(Badge.DOSES_250 in earned)
        assertEquals(3, state.level.number)
    }
}
