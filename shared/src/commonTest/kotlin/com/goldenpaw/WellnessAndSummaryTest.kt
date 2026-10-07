package com.goldenpaw

import com.goldenpaw.core.FixedAppClock
import com.goldenpaw.core.at
import com.goldenpaw.core.minusDays
import com.goldenpaw.core.plusDays
import com.goldenpaw.domain.logic.OnDeviceSummaryWriter
import com.goldenpaw.domain.logic.QualityOfLifeCalculator
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.domain.logic.WeeklyDigestBuilder
import com.goldenpaw.domain.model.Attribution
import com.goldenpaw.domain.model.SummarySource
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.SymptomType
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WellnessAndSummaryTest {
    private val utc = TimeZone.UTC

    @Test
    fun allFivesScoreSeventy() {
        val c = TestData.checkIn(LocalDate(2026, 10, 6), value = 5)
        assertEquals(70.0, QualityOfLifeCalculator.score(c, listOf(c)).total)
    }

    @Test
    fun allOnesScoreZero() {
        val c = TestData.checkIn(LocalDate(2026, 10, 6), value = 1)
        assertEquals(0.0, QualityOfLifeCalculator.score(c, listOf(c)).total)
    }

    @Test
    fun kgLbRoundTrip() {
        val kg = 23.4
        val back = UnitConversion.lbToKg(UnitConversion.kgToLb(kg))
        assertTrue(kotlin.math.abs(back - kg) < 1e-9)
        assertEquals("51.6 lb", UnitConversion.format(23.4, WeightUnit.LB))
    }

    @Test
    fun weeklyDigestAndOnDeviceSummary() {
        val weekStart = LocalDate(2026, 9, 28) // Monday
        val today = LocalDate(2026, 10, 4) // Sunday
        val med = TestData.med(times = listOf(LocalTime(8, 0)), start = LocalDate(2026, 9, 1))
        val doses = (0..6).map { TestData.dose(med.id, weekStart.plusDays(it).at(LocalTime(8, 0), utc)) } +
            (1..7).filter { it != 3 }.map { TestData.dose(med.id, weekStart.minusDays(it).at(LocalTime(8, 0), utc)) }
        val checkIns = (0..6).map { TestData.checkIn(weekStart.plusDays(it), value = if (it < 5) 5 else 2) } +
            (1..7).map { TestData.checkIn(weekStart.minusDays(it), value = 3) }
        val symptoms = listOf(0, 2, 4).map { i ->
            SymptomEntry(
                id = "s$i", petId = "pet", date = weekStart.plusDays(i), loggedAt = weekStart.plusDays(i).at(LocalTime(9, 0), utc),
                type = SymptomType.LIMPING, severity = 2, tags = emptyList(), notes = "Slow on stairs", photoPath = null,
                loggedBy = Attribution("Sam", "c2"),
            )
        }
        val weights = listOf(
            WeightEntry("w1", "pet", weekStart.minusDays(10), 30.0, "", TestData.epoch),
            WeightEntry("w2", "pet", weekStart.plusDays(5), 28.5, "", TestData.epoch),
        )
        val clock = FixedAppClock(today.at(LocalTime(20, 0), utc), utc)
        val digest = WeeklyDigestBuilder.build(
            WeeklyDigestBuilder.Input(
                pet = TestData.pet(), weekStart = weekStart, medications = listOf(med), doseEvents = doses,
                checkIns = checkIns, symptoms = symptoms, weights = weights, now = clock.now(), zone = utc, today = today,
            ),
        )
        assertEquals(7, digest.dosesGiven)
        assertEquals(1.0, digest.adherence)
        assertEquals(7, digest.checkIns)
        assertEquals(5, digest.goodDays)
        assertEquals(2, digest.hardDays)
        assertEquals(1, digest.patterns.size)
        assertTrue((digest.weightChangePercent ?: 0.0) < -4.9)
        assertTrue(digest.caregiverContributions.keys.containsAll(setOf("Priya", "Sam")))

        val summary = OnDeviceSummaryWriter.write(digest, WeightUnit.KG, clock.now())
        assertEquals(SummarySource.ON_DEVICE, summary.source)
        assertTrue(summary.headline.isNotBlank())
        assertTrue(summary.summary.contains("7 of 7"), summary.summary)
        assertTrue(summary.watchItems.any { it.contains("Limping") }, summary.watchItems.toString())
        assertTrue(summary.watchItems.any { it.contains("Weight is down") }, summary.watchItems.toString())
        assertTrue(summary.vetQuestions.isNotEmpty())
        // Never diagnostic language
        val all = (listOf(summary.headline, summary.summary) + summary.watchItems + summary.vetQuestions).joinToString(" ").lowercase()
        listOf("diagnos", "disease is", "you should give", "increase the dose").forEach { assertTrue(it !in all, "found '$it'") }
    }

    @Test
    fun emptyWeekStillWritesAKindSummary() {
        val weekStart = LocalDate(2026, 9, 28)
        val clock = FixedAppClock(weekStart.plusDays(6).at(LocalTime(20, 0), utc), utc)
        val digest = WeeklyDigestBuilder.build(
            WeeklyDigestBuilder.Input(
                TestData.pet(), weekStart, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                clock.now(), utc, weekStart.plusDays(6),
            ),
        )
        val summary = OnDeviceSummaryWriter.write(digest, WeightUnit.KG, clock.now())
        assertTrue(summary.headline.contains("quiet"))
        assertTrue(summary.summary.contains("That's okay"))
    }
}
