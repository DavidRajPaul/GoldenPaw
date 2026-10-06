package com.goldenpaw.domain.usecase

import com.goldenpaw.core.AppClock
import com.goldenpaw.core.minusDays
import com.goldenpaw.core.toLocalDate
import com.goldenpaw.core.today
import com.goldenpaw.domain.logic.GamificationEngine
import com.goldenpaw.domain.model.Badge
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.GamificationState
import com.goldenpaw.domain.repository.AchievementRepository
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.WeightRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Instant

/**
 * Streaks, levels, rings and badges for the person using this device. Emits null in gentle mode.
 * [slotsForPet] supplies dose slots (today and the previous 7 days) for the pet on screen.
 */
class ObserveGamificationUseCase(
    private val settings: SettingsRepository,
    private val doseEvents: DoseEventRepository,
    private val checkIns: CheckInRepository,
    private val symptoms: SymptomRepository,
    private val weights: WeightRepository,
    private val achievements: AchievementRepository,
    private val careTeam: CareTeamService,
    private val clock: AppClock,
) {
    operator fun invoke(slotsForPet: Flow<List<DoseSlot>>, ticker: Flow<Instant>): Flow<GamificationState?> =
        settings.settings.flatMapLatest<com.goldenpaw.domain.repository.UserSettings, GamificationState?> { s ->
            if (!s.gamificationEnabled) return@flatMapLatest flowOf(null)
            val logs = combine(
                doseEvents.observeAllGiven(),
                checkIns.observeAll(),
                symptoms.observeAll(),
                weights.observeAll(),
            ) { doses, cis, syms, ws -> Logs(doses, cis, syms, ws) }
            combine(
                logs,
                slotsForPet,
                achievements.observeUnlocked(),
                careTeam.observeCurrentTeam(),
                ticker,
            ) { l, slots, unlocked, team, now ->
                val zone = clock.zone()
                val today = clock.today()
                val todaySlots = slots.filter { it.scheduledAt.toLocalDate(zone) == today }
                val weekSlots = slots.filter {
                    val d = it.scheduledAt.toLocalDate(zone)
                    d < today && d >= today.minusDays(7)
                }
                GamificationEngine.compute(
                    GamificationEngine.Input(
                        today = today,
                        zone = zone,
                        now = now,
                        meId = team?.me?.id,
                        meName = team?.me?.displayName ?: s.ownerName.ifBlank { "You" },
                        givenDoses = l.doses,
                        checkIns = l.checkIns,
                        symptoms = l.symptoms,
                        weights = l.weights,
                        todaySlots = todaySlots,
                        lastWeekSlots = weekSlots,
                        restDaysPerWeek = s.streakRestDaysPerWeek,
                        unlocked = unlocked,
                        selectedPetId = s.selectedPetId,
                    ),
                )
            }
        }

    /** Stores badges so their celebration shows once (and survives undo). */
    suspend fun recordUnlocked(badges: Collection<Badge>) {
        val now = clock.now()
        badges.forEach { achievements.unlock(it.id, now) }
    }

    suspend fun unlockVetReady() = achievements.unlock(Badge.VET_READY.id, clock.now())

    suspend fun markSeen(badges: Collection<Badge>) = achievements.markSeen(badges.map { it.id })

    private data class Logs(
        val doses: List<com.goldenpaw.domain.model.DoseEvent>,
        val checkIns: List<com.goldenpaw.domain.model.CheckIn>,
        val symptoms: List<com.goldenpaw.domain.model.SymptomEntry>,
        val weights: List<com.goldenpaw.domain.model.WeightEntry>,
    )
}
