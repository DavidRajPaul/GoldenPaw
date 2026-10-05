package com.goldenpaw.domain.repository

import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

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
    suspend fun forPetBetween(petId: String, from: Instant, to: Instant): List<DoseEvent>
    suspend fun forMedicationsBetween(medicationIds: List<String>, from: Instant, to: Instant): List<DoseEvent>
    suspend fun find(medicationId: String, scheduledAt: Instant): DoseEvent?
    suspend fun log(event: DoseEvent)
    suspend fun undo(eventId: String)
    fun observeTotalGiven(): Flow<Int>
    suspend fun countGivenForPet(petId: String): Int
}

interface CheckInRepository {
    fun observeForPet(petId: String): Flow<List<CheckIn>>
    fun observeForDate(petId: String, date: LocalDate): Flow<CheckIn?>
    suspend fun forPetBetween(petId: String, from: LocalDate, to: LocalDate): List<CheckIn>
    suspend fun upsert(checkIn: CheckIn)
}

interface WeightRepository {
    fun observeForPet(petId: String): Flow<List<WeightEntry>>
    suspend fun forPetBetween(petId: String, from: LocalDate, to: LocalDate): List<WeightEntry>
    suspend fun add(entry: WeightEntry)
    suspend fun delete(id: String)
}

interface SymptomRepository {
    fun observeForPet(petId: String): Flow<List<SymptomEntry>>
    suspend fun get(id: String): SymptomEntry?
    suspend fun forPetBetween(petId: String, from: LocalDate, to: LocalDate): List<SymptomEntry>
    suspend fun upsert(entry: SymptomEntry)
    suspend fun delete(id: String)
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
    val checkInReminderTime: LocalTime = LocalTime.of(20, 0),
    val isPlus: Boolean = false,
    val paywallViews: Int = 0,
    val paywallTrialTaps: Int = 0,
    val lastMilestoneSeen: Int = 0,
    val lastSyncedAt: Instant? = null,
)

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun current(): UserSettings
    suspend fun update(transform: (UserSettings) -> UserSettings)
    suspend fun clear()
}

/** Cloud sync contract. MVP ships a local-only implementation; Supabase plugs in here. */
interface SyncRepository {
    val isCloudEnabled: Boolean
    suspend fun sync(): Result<Instant>
}
