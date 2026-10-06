package com.goldenpaw.domain.usecase

import com.goldenpaw.core.AppClock
import com.goldenpaw.core.minusDays
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.startOfDay
import com.goldenpaw.core.startOfWeek
import com.goldenpaw.core.today
import com.goldenpaw.domain.logic.OnDeviceSummaryWriter
import com.goldenpaw.domain.logic.WeeklyDigestBuilder
import com.goldenpaw.domain.model.WeeklyDigest
import com.goldenpaw.domain.model.WeeklySummary
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.SummaryGenerator
import com.goldenpaw.domain.repository.SummaryRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.WeightRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.hours

class BuildWeeklyDigestUseCase(
    private val pets: PetRepository,
    private val medications: MedicationRepository,
    private val doseEvents: DoseEventRepository,
    private val checkIns: CheckInRepository,
    private val symptoms: SymptomRepository,
    private val weights: WeightRepository,
    private val clock: AppClock,
) {
    suspend operator fun invoke(petId: String, weekStart: LocalDate): WeeklyDigest? {
        val pet = pets.getPet(petId) ?: return null
        val zone = clock.zone()
        val start = weekStart.startOfWeek()
        val end = start.plusDays(6)
        val meds = medications.observeForPet(petId).first()
        return WeeklyDigestBuilder.build(
            WeeklyDigestBuilder.Input(
                pet = pet,
                weekStart = start,
                medications = meds,
                doseEvents = doseEvents.forPetBetween(petId, start.minusDays(7).startOfDay(zone), end.plusDays(1).startOfDay(zone)),
                checkIns = checkIns.forPetBetween(petId, start.minusDays(13), end),
                symptoms = symptoms.forPetBetween(petId, start, end),
                weights = weights.forPetBetween(petId, start.minusDays(60), end),
                now = clock.now(),
                zone = zone,
                today = clock.today(),
            ),
        )
    }
}

/**
 * Produces (and caches) the weekly summary for a pet. Plus members get the AI-written version when
 * the cloud is configured; everyone else, and every offline case, gets the on-device writer.
 */
class GenerateWeeklySummaryUseCase(
    private val buildDigest: BuildWeeklyDigestUseCase,
    private val summaries: SummaryRepository,
    private val settings: SettingsRepository,
    private val aiWriter: SummaryGenerator,
    private val clock: AppClock,
) {
    suspend operator fun invoke(
        petId: String,
        weekStart: LocalDate = clock.today().startOfWeek(),
        forceRefresh: Boolean = false,
    ): WeeklySummary? {
        val start = weekStart.startOfWeek()
        val cached = summaries.get(petId, start)
        val isCurrentWeek = start == clock.today().startOfWeek()
        // Past weeks never change; the current week refreshes at most every 6 hours unless forced.
        if (cached != null && !forceRefresh) {
            if (!isCurrentWeek || clock.now() - cached.createdAt < 6.hours) return cached
        }
        val digest = buildDigest(petId, start) ?: return cached
        val s = settings.current()
        val ai = if (s.isPlus && aiWriter.isAvailable && digest.hasAnyData) {
            aiWriter.generate(digest, s.weightUnit).getOrNull()
        } else null
        val summary = (ai ?: OnDeviceSummaryWriter.write(digest, s.weightUnit, clock.now()))
            .copy(id = cached?.id ?: (ai?.id ?: "${petId}_$start"), petId = petId, weekStart = start)
        summaries.save(summary)
        return summary
    }
}
