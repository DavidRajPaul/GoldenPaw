package com.goldenpaw.domain.repository

import com.goldenpaw.domain.model.Caregiver
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.Household
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.VetVisit
import com.goldenpaw.domain.model.WeeklySummary
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

interface PetRepository {
    fun observeActivePets(): Flow<List<Pet>>
    fun observeArchivedPets(): Flow<List<Pet>>
    fun observePet(id: String): Flow<Pet?>
    suspend fun getPet(id: String): Pet?
    suspend fun activePetCount(): Int
    suspend fun upsert(pet: Pet)
    suspend fun archive(id: String)
    suspend fun restore(id: String)
    suspend fun delete(id: String)
}

interface MedicationRepository {
    fun observeForPet(petId: String): Flow<List<Medication>>
    fun observeActiveForPet(petId: String): Flow<List<Medication>>
    fun observe(id: String): Flow<Medication?>
    /** Every medication (any pet, active or not). Used by the care log. */
    fun observeAll(): Flow<List<Medication>>
    suspend fun get(id: String): Medication?
    /** All active medications of non-archived pets (used by the reminder scheduler). */
    suspend fun allActiveForActivePets(): List<Medication>
    suspend fun upsert(medication: Medication)
    suspend fun setActive(id: String, active: Boolean)
    suspend fun delete(id: String)
    suspend fun adjustSupply(id: String, delta: Double)
}

interface DoseEventRepository {
    fun observeForPetBetween(petId: String, from: Instant, to: Instant): Flow<List<DoseEvent>>
    fun observeSince(since: Instant): Flow<List<DoseEvent>>
    suspend fun forPetBetween(petId: String, from: Instant, to: Instant): List<DoseEvent>
    suspend fun forMedicationsBetween(medicationIds: List<String>, from: Instant, to: Instant): List<DoseEvent>
    suspend fun find(medicationId: String, scheduledAt: Instant): DoseEvent?
    suspend fun log(event: DoseEvent)
    suspend fun undo(eventId: String)
    fun observeTotalGiven(): Flow<Int>
    suspend fun countGivenForPet(petId: String): Int
    /** Every non-deleted event (used by gamification, which derives everything from the log). */
    fun observeAllGiven(): Flow<List<DoseEvent>>
}

interface CheckInRepository {
    fun observeForPet(petId: String): Flow<List<CheckIn>>
    fun observeForDate(petId: String, date: LocalDate): Flow<CheckIn?>
    fun observeAll(): Flow<List<CheckIn>>
    suspend fun forPetBetween(petId: String, from: LocalDate, to: LocalDate): List<CheckIn>
    suspend fun upsert(checkIn: CheckIn)
}

interface WeightRepository {
    fun observeForPet(petId: String): Flow<List<WeightEntry>>
    fun observeAll(): Flow<List<WeightEntry>>
    suspend fun forPetBetween(petId: String, from: LocalDate, to: LocalDate): List<WeightEntry>
    suspend fun add(entry: WeightEntry)
    suspend fun delete(id: String)
}

interface SymptomRepository {
    fun observeForPet(petId: String): Flow<List<SymptomEntry>>
    fun observeAll(): Flow<List<SymptomEntry>>
    suspend fun get(id: String): SymptomEntry?
    suspend fun forPetBetween(petId: String, from: LocalDate, to: LocalDate): List<SymptomEntry>
    suspend fun upsert(entry: SymptomEntry)
    suspend fun delete(id: String)
}

interface VetVisitRepository {
    fun observeForPet(petId: String): Flow<List<VetVisit>>
    /** Upcoming, not-completed visits for active pets. */
    fun observeUpcoming(from: Instant): Flow<List<VetVisit>>
    suspend fun upcoming(from: Instant): List<VetVisit>
    suspend fun get(id: String): VetVisit?
    suspend fun forPetBetween(petId: String, from: Instant, to: Instant): List<VetVisit>
    suspend fun upsert(visit: VetVisit)
    suspend fun delete(id: String)
}

interface HouseholdRepository {
    fun observeHouseholds(): Flow<List<Household>>
    fun observeHousehold(id: String): Flow<Household?>
    fun observeMembers(householdId: String): Flow<List<Caregiver>>
    fun observeAllMembers(): Flow<List<Caregiver>>
    suspend fun getHousehold(id: String): Household?
    suspend fun households(): List<Household>
    suspend fun members(householdId: String): List<Caregiver>
    suspend fun getMember(id: String): Caregiver?
    suspend fun upsertHousehold(household: Household)
    suspend fun upsertMember(member: Caregiver)
    suspend fun removeMember(id: String)
}

interface SummaryRepository {
    fun observeForPet(petId: String): Flow<List<WeeklySummary>>
    suspend fun get(petId: String, weekStart: LocalDate): WeeklySummary?
    suspend fun save(summary: WeeklySummary)
    suspend fun deleteForPet(petId: String)
}

/** Badges are derived from the care log; only the moment of unlocking is stored (for celebrations). */
interface AchievementRepository {
    fun observeUnlocked(): Flow<Map<String, Instant>>
    suspend fun unlock(badgeId: String, at: Instant)
    fun observeSeen(): Flow<Set<String>>
    suspend fun markSeen(badgeIds: Collection<String>)
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class UserSettings(
    val onboardingDone: Boolean = false,
    val ownerName: String = "",
    val selectedPetId: String? = null,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val reduceMotion: Boolean = false,
    val remindersEnabled: Boolean = true,
    val checkInReminderEnabled: Boolean = true,
    val checkInReminderTime: LocalTime = LocalTime(20, 0),
    val weeklySummaryEnabled: Boolean = true,
    val isPlus: Boolean = false,
    val paywallViews: Int = 0,
    val paywallTrialTaps: Int = 0,
    val lastMilestoneSeen: Int = 0,
    val lastSyncedAt: Instant? = null,
    /** The caregiver logging on this device right now (local household or cloud identity). */
    val activeCaregiverId: String? = null,
    /** Streaks, levels and badges. Off = "gentle mode": no counters at all. */
    val gamificationEnabled: Boolean = true,
    /** Rest days auto-applied to keep a streak alive (refilled weekly). */
    val streakRestDaysPerWeek: Int = 2,
)

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun current(): UserSettings
    suspend fun update(transform: (UserSettings) -> UserSettings)
    suspend fun clear()
}
