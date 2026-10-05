package com.goldenpaw.data.repository

import com.goldenpaw.data.local.CheckInDao
import com.goldenpaw.data.local.DoseEventDao
import com.goldenpaw.data.local.MedicationDao
import com.goldenpaw.data.local.PetDao
import com.goldenpaw.data.local.SymptomDao
import com.goldenpaw.data.local.WeightDao
import com.goldenpaw.data.mapper.toDomain
import com.goldenpaw.data.mapper.toEntity
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.SyncRepository
import com.goldenpaw.domain.repository.WeightRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

private fun now() = System.currentTimeMillis()

class PetRepositoryImpl(private val dao: PetDao) : PetRepository {
    override fun observeActivePets(): Flow<List<Pet>> = dao.observeActive().map { list -> list.map { it.toDomain() } }
    override fun observeArchivedPets(): Flow<List<Pet>> = dao.observeArchived().map { list -> list.map { it.toDomain() } }
    override fun observePet(id: String): Flow<Pet?> = dao.observe(id).map { it?.toDomain() }
    override suspend fun getPet(id: String): Pet? = dao.get(id)?.toDomain()
    override suspend fun activePetCount(): Int = dao.activeCount()
    override suspend fun upsert(pet: Pet) = dao.upsert(pet.toEntity(now()))
    override suspend fun archive(id: String) = dao.archive(id, now())
    override suspend fun restore(id: String) = dao.restore(id, now())
    override suspend fun delete(id: String) = dao.hardDelete(id)
}

class MedicationRepositoryImpl(private val dao: MedicationDao) : MedicationRepository {
    override fun observeForPet(petId: String): Flow<List<Medication>> =
        dao.observeForPet(petId).map { list -> list.map { it.toDomain() } }

    override fun observeActiveForPet(petId: String): Flow<List<Medication>> =
        dao.observeActiveForPet(petId).map { list -> list.map { it.toDomain() } }

    override fun observe(id: String): Flow<Medication?> = dao.observe(id).map { it?.toDomain() }
    override suspend fun get(id: String): Medication? = dao.get(id)?.toDomain()
    override suspend fun allActiveForActivePets(): List<Medication> = dao.allActiveForActivePets().map { it.toDomain() }
    override suspend fun upsert(medication: Medication) = dao.upsert(medication.toEntity(now()))
    override suspend fun setActive(id: String, active: Boolean) = dao.setActive(id, active, now())
    override suspend fun delete(id: String) = dao.softDelete(id, now())
    override suspend fun adjustSupply(id: String, delta: Double) = dao.adjustSupply(id, delta, now())
}

class DoseEventRepositoryImpl(private val dao: DoseEventDao) : DoseEventRepository {
    override fun observeForPetBetween(petId: String, from: Instant, to: Instant): Flow<List<DoseEvent>> =
        dao.observeForPetBetween(petId, from.toEpochMilli(), to.toEpochMilli()).map { list -> list.map { it.toDomain() } }

    override suspend fun forPetBetween(petId: String, from: Instant, to: Instant): List<DoseEvent> =
        dao.forPetBetween(petId, from.toEpochMilli(), to.toEpochMilli()).map { it.toDomain() }

    override suspend fun forMedicationsBetween(medicationIds: List<String>, from: Instant, to: Instant): List<DoseEvent> =
        if (medicationIds.isEmpty()) emptyList()
        else dao.forMedicationsBetween(medicationIds, from.toEpochMilli(), to.toEpochMilli()).map { it.toDomain() }

    override suspend fun find(medicationId: String, scheduledAt: Instant): DoseEvent? =
        dao.find(medicationId, scheduledAt.toEpochMilli())?.toDomain()

    override suspend fun log(event: DoseEvent) = dao.upsert(event.toEntity(now()))
    override suspend fun undo(eventId: String) = dao.softDelete(eventId, now())
    override fun observeTotalGiven(): Flow<Int> = dao.observeTotalGiven()
    override suspend fun countGivenForPet(petId: String): Int = dao.countGivenForPet(petId)
}

class CheckInRepositoryImpl(private val dao: CheckInDao) : CheckInRepository {
    override fun observeForPet(petId: String): Flow<List<CheckIn>> =
        dao.observeForPet(petId).map { list -> list.map { it.toDomain() } }

    override fun observeForDate(petId: String, date: LocalDate): Flow<CheckIn?> =
        dao.observeForDay(petId, date.toEpochDay()).map { it?.toDomain() }

    override suspend fun forPetBetween(petId: String, from: LocalDate, to: LocalDate): List<CheckIn> =
        dao.forPetBetween(petId, from.toEpochDay(), to.toEpochDay()).map { it.toDomain() }

    override suspend fun upsert(checkIn: CheckIn) {
        // One check-in per pet per day: keep the existing row id so the unique index is respected.
        val existing = dao.getForDay(checkIn.petId, checkIn.date.toEpochDay())
        val toSave = if (existing != null) {
            checkIn.copy(id = existing.id, createdAt = Instant.ofEpochMilli(existing.createdAt))
        } else checkIn
        dao.upsert(toSave.toEntity(now()))
    }
}

class WeightRepositoryImpl(private val dao: WeightDao) : WeightRepository {
    override fun observeForPet(petId: String): Flow<List<WeightEntry>> =
        dao.observeForPet(petId).map { list -> list.map { it.toDomain() } }

    override suspend fun forPetBetween(petId: String, from: LocalDate, to: LocalDate): List<WeightEntry> =
        dao.forPetBetween(petId, from.toEpochDay(), to.toEpochDay()).map { it.toDomain() }

    override suspend fun add(entry: WeightEntry) = dao.upsert(entry.toEntity(now()))
    override suspend fun delete(id: String) = dao.softDelete(id, now())
}

class SymptomRepositoryImpl(private val dao: SymptomDao) : SymptomRepository {
    override fun observeForPet(petId: String): Flow<List<SymptomEntry>> =
        dao.observeForPet(petId).map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): SymptomEntry? = dao.get(id)?.toDomain()

    override suspend fun forPetBetween(petId: String, from: LocalDate, to: LocalDate): List<SymptomEntry> =
        dao.forPetBetween(petId, from.toEpochDay(), to.toEpochDay()).map { it.toDomain() }

    override suspend fun upsert(entry: SymptomEntry) = dao.upsert(entry.toEntity(now()))
    override suspend fun delete(id: String) = dao.softDelete(id, now())
}

/**
 * Local-only sync. Rows are already tracked with updatedAt/deletedAt/syncState, so a Supabase-backed
 * implementation (push dirty rows, pull changes since lastSyncedAt, last-write-wins per row,
 * append-only dose events) can replace this class without touching the UI.
 */
class LocalOnlySyncRepository : SyncRepository {
    override val isCloudEnabled: Boolean = false
    override suspend fun sync(): Result<Instant> =
        Result.failure(UnsupportedOperationException("Cloud backup arrives with GoldenPaw Plus"))
}
