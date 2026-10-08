package com.goldenpaw.data.repository

import com.goldenpaw.core.AppClock
import com.goldenpaw.core.epochDay
import com.goldenpaw.data.local.AchievementDao
import com.goldenpaw.data.local.CheckInDao
import com.goldenpaw.data.local.DoseEventDao
import com.goldenpaw.data.local.HouseholdDao
import com.goldenpaw.data.local.MedicationDao
import com.goldenpaw.data.local.PetDao
import com.goldenpaw.data.local.SummaryDao
import com.goldenpaw.data.local.SymptomDao
import com.goldenpaw.data.local.VetVisitDao
import com.goldenpaw.data.local.WeightDao
import com.goldenpaw.data.mapper.instant
import com.goldenpaw.data.mapper.toDomain
import com.goldenpaw.data.mapper.toEntity
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
import com.goldenpaw.domain.repository.AchievementRepository
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.HouseholdRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SummaryRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.VetVisitRepository
import com.goldenpaw.domain.repository.WeightRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import com.goldenpaw.data.local.HealthDocumentDao
import com.goldenpaw.domain.model.HealthDocument
import com.goldenpaw.domain.repository.HealthDocumentRepository

/** Stable id for "this pet's check-in on this day", so two devices checking in converge on one row. */
fun checkInId(petId: String, date: LocalDate): String = "$petId:${date.epochDay()}"

class PetRepositoryImpl(
    private val dao: PetDao,
    private val households: HouseholdDao,
    private val clock: AppClock,
) : PetRepository {
    private fun now() = clock.now().toEpochMilliseconds()

    override fun observeActivePets(): Flow<List<Pet>> = dao.observeActive().map { list -> list.map { it.toDomain() } }
    override fun observeArchivedPets(): Flow<List<Pet>> = dao.observeArchived().map { list -> list.map { it.toDomain() } }
    override fun observePet(id: String): Flow<Pet?> = dao.observe(id).map { it?.toDomain() }
    override suspend fun getPet(id: String): Pet? = dao.get(id)?.toDomain()
    override suspend fun activePetCount(): Int = dao.activeCount()
    override suspend fun upsert(pet: Pet) {
        val householdId = pet.householdId.ifBlank { households.all().firstOrNull()?.id.orEmpty() }
        dao.upsert(pet.copy(householdId = householdId).toEntity(now()))
    }
    override suspend fun archive(id: String) = dao.archive(id, now())
    override suspend fun restore(id: String) = dao.restore(id, now())

    /** Local-only pets are removed outright; shared pets leave a tombstone until the deletion syncs. */
    override suspend fun delete(id: String) {
        val pet = dao.getIncludingDeleted(id) ?: return
        val shared = households.get(pet.householdId)?.cloudEnabled == true
        if (shared) dao.softDelete(id, now()) else dao.hardDelete(id)
    }
}

class MedicationRepositoryImpl(private val dao: MedicationDao, private val clock: AppClock) : MedicationRepository {
    private fun now() = clock.now().toEpochMilliseconds()

    override fun observeForPet(petId: String): Flow<List<Medication>> =
        dao.observeForPet(petId).map { list -> list.map { it.toDomain() } }

    override fun observeActiveForPet(petId: String): Flow<List<Medication>> =
        dao.observeActiveForPet(petId).map { list -> list.map { it.toDomain() } }

    override fun observe(id: String): Flow<Medication?> = dao.observe(id).map { it?.toDomain() }
    override fun observeAll(): Flow<List<Medication>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun get(id: String): Medication? = dao.get(id)?.toDomain()
    override suspend fun allActiveForActivePets(): List<Medication> = dao.allActiveForActivePets().map { it.toDomain() }
    override suspend fun upsert(medication: Medication) = dao.upsert(medication.toEntity(now()))
    override suspend fun setActive(id: String, active: Boolean) = dao.setActive(id, active, now())
    override suspend fun delete(id: String) = dao.softDelete(id, now())
    override suspend fun adjustSupply(id: String, delta: Double) = dao.adjustSupply(id, delta, now())
}

class DoseEventRepositoryImpl(private val dao: DoseEventDao, private val clock: AppClock) : DoseEventRepository {
    private fun now() = clock.now().toEpochMilliseconds()

    override fun observeForPetBetween(petId: String, from: Instant, to: Instant): Flow<List<DoseEvent>> =
        dao.observeForPetBetween(petId, from.toEpochMilliseconds(), to.toEpochMilliseconds())
            .map { list -> list.map { it.toDomain() } }

    override fun observeSince(since: Instant): Flow<List<DoseEvent>> =
        dao.observeSince(since.toEpochMilliseconds()).map { list -> list.map { it.toDomain() } }

    override suspend fun forPetBetween(petId: String, from: Instant, to: Instant): List<DoseEvent> =
        dao.forPetBetween(petId, from.toEpochMilliseconds(), to.toEpochMilliseconds()).map { it.toDomain() }

    override suspend fun forMedicationsBetween(medicationIds: List<String>, from: Instant, to: Instant): List<DoseEvent> =
        if (medicationIds.isEmpty()) emptyList()
        else dao.forMedicationsBetween(medicationIds, from.toEpochMilliseconds(), to.toEpochMilliseconds()).map { it.toDomain() }

    override suspend fun find(medicationId: String, scheduledAt: Instant): DoseEvent? =
        dao.find(medicationId, scheduledAt.toEpochMilliseconds())?.toDomain()

    override suspend fun log(event: DoseEvent) = dao.upsert(event.toEntity(now()))
    override suspend fun undo(eventId: String) = dao.softDelete(eventId, now())
    override fun observeTotalGiven(): Flow<Int> = dao.observeTotalGiven()
    override suspend fun countGivenForPet(petId: String): Int = dao.countGivenForPet(petId)
    override fun observeAllGiven(): Flow<List<DoseEvent>> = dao.observeAllGiven().map { list -> list.map { it.toDomain() } }
}

class CheckInRepositoryImpl(private val dao: CheckInDao, private val clock: AppClock) : CheckInRepository {
    private fun now() = clock.now().toEpochMilliseconds()

    override fun observeForPet(petId: String): Flow<List<CheckIn>> =
        dao.observeForPet(petId).map { list -> list.map { it.toDomain() } }

    override fun observeForDate(petId: String, date: LocalDate): Flow<CheckIn?> =
        dao.observeForDay(petId, date.epochDay()).map { it?.toDomain() }

    override fun observeAll(): Flow<List<CheckIn>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun forPetBetween(petId: String, from: LocalDate, to: LocalDate): List<CheckIn> =
        dao.forPetBetween(petId, from.epochDay(), to.epochDay()).map { it.toDomain() }

    override suspend fun upsert(checkIn: CheckIn) {
        // One check-in per pet per day: keep the existing row id so the unique index is respected.
        val existing = dao.getForDay(checkIn.petId, checkIn.date.epochDay())
        val toSave = if (existing != null) {
            checkIn.copy(id = existing.id, createdAt = existing.createdAt.instant())
        } else {
            checkIn.copy(id = checkInId(checkIn.petId, checkIn.date))
        }
        dao.upsert(toSave.toEntity(now()))
    }
}

class WeightRepositoryImpl(private val dao: WeightDao, private val clock: AppClock) : WeightRepository {
    private fun now() = clock.now().toEpochMilliseconds()

    override fun observeForPet(petId: String): Flow<List<WeightEntry>> =
        dao.observeForPet(petId).map { list -> list.map { it.toDomain() } }

    override fun observeAll(): Flow<List<WeightEntry>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun forPetBetween(petId: String, from: LocalDate, to: LocalDate): List<WeightEntry> =
        dao.forPetBetween(petId, from.epochDay(), to.epochDay()).map { it.toDomain() }

    override suspend fun add(entry: WeightEntry) = dao.upsert(entry.toEntity(now()))
    override suspend fun delete(id: String) = dao.softDelete(id, now())
}

class SymptomRepositoryImpl(private val dao: SymptomDao, private val clock: AppClock) : SymptomRepository {
    private fun now() = clock.now().toEpochMilliseconds()

    override fun observeForPet(petId: String): Flow<List<SymptomEntry>> =
        dao.observeForPet(petId).map { list -> list.map { it.toDomain() } }

    override fun observeAll(): Flow<List<SymptomEntry>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): SymptomEntry? = dao.get(id)?.toDomain()

    override suspend fun forPetBetween(petId: String, from: LocalDate, to: LocalDate): List<SymptomEntry> =
        dao.forPetBetween(petId, from.epochDay(), to.epochDay()).map { it.toDomain() }

    override suspend fun upsert(entry: SymptomEntry) {
        val now = now()
        val createdAt = dao.getIncludingDeleted(entry.id)?.createdAt ?: now
        dao.upsert(entry.toEntity(now, createdAt))
    }

    override suspend fun delete(id: String) = dao.softDelete(id, now())
}

class HouseholdRepositoryImpl(private val dao: HouseholdDao, private val clock: AppClock) : HouseholdRepository {
    private fun now() = clock.now().toEpochMilliseconds()

    override fun observeHouseholds(): Flow<List<Household>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override fun observeHousehold(id: String): Flow<Household?> = dao.observe(id).map { it?.toDomain() }
    override fun observeMembers(householdId: String): Flow<List<Caregiver>> =
        dao.observeMembers(householdId).map { list -> list.map { it.toDomain() } }
    override fun observeAllMembers(): Flow<List<Caregiver>> = dao.observeAllMembers().map { list -> list.map { it.toDomain() } }
    override suspend fun getHousehold(id: String): Household? = dao.get(id)?.takeIf { it.deletedAt == null }?.toDomain()
    override suspend fun households(): List<Household> = dao.all().map { it.toDomain() }
    override suspend fun members(householdId: String): List<Caregiver> = dao.members(householdId).map { it.toDomain() }
    override suspend fun getMember(id: String): Caregiver? = dao.member(id)?.toDomain()
    override suspend fun upsertHousehold(household: Household) = dao.upsert(household.toEntity(now()))
    override suspend fun upsertMember(member: Caregiver) = dao.upsertMember(member.toEntity(now()))
    override suspend fun removeMember(id: String) = dao.removeMember(id, now())
}

class SummaryRepositoryImpl(private val dao: SummaryDao) : SummaryRepository {
    override fun observeForPet(petId: String): Flow<List<WeeklySummary>> =
        dao.observeForPet(petId).map { list -> list.map { it.toDomain() } }

    override suspend fun get(petId: String, weekStart: LocalDate): WeeklySummary? =
        dao.get(petId, weekStart.epochDay())?.toDomain()

    override suspend fun save(summary: WeeklySummary) {
        // One summary per pet-week: reuse the existing row id if a different one is passed.
        val existing = dao.get(summary.petId, summary.weekStart.epochDay())
        dao.upsert(summary.copy(id = existing?.id ?: summary.id).toEntity())
    }

    override suspend fun deleteForPet(petId: String) = dao.deleteForPet(petId)
}

class AchievementRepositoryImpl(private val dao: AchievementDao) : AchievementRepository {
    override fun observeUnlocked(): Flow<Map<String, Instant>> =
        dao.observeAll().map { list -> list.associate { it.id to it.unlockedAt.instant() } }

    override suspend fun unlock(badgeId: String, at: Instant) = dao.unlock(badgeId, at.toEpochMilliseconds())

    override fun observeSeen(): Flow<Set<String>> = dao.observeAll().map { list -> list.filter { it.seen }.map { it.id }.toSet() }

    override suspend fun markSeen(badgeIds: Collection<String>) {
        if (badgeIds.isNotEmpty()) dao.markSeen(badgeIds.toList())
    }
}

class VetVisitRepositoryImpl(private val dao: VetVisitDao, private val clock: AppClock) : VetVisitRepository {
    private fun now() = clock.now().toEpochMilliseconds()

    override fun observeForPet(petId: String): Flow<List<VetVisit>> =
        dao.observeForPet(petId).map { list -> list.map { it.toDomain() } }

    override fun observeUpcoming(from: Instant): Flow<List<VetVisit>> =
        dao.observeUpcoming(from.toEpochMilliseconds()).map { list -> list.map { it.toDomain() } }

    override suspend fun upcoming(from: Instant): List<VetVisit> = dao.upcoming(from.toEpochMilliseconds()).map { it.toDomain() }

    override suspend fun get(id: String): VetVisit? = dao.get(id)?.takeIf { it.deletedAt == null }?.toDomain()

    override suspend fun forPetBetween(petId: String, from: Instant, to: Instant): List<VetVisit> =
        dao.forPetBetween(petId, from.toEpochMilliseconds(), to.toEpochMilliseconds()).map { it.toDomain() }

    override suspend fun upsert(visit: VetVisit) {
        val existing = dao.get(visit.id)
        dao.upsert(visit.toEntity(now(), existing?.createdAt ?: visit.createdAt.toEpochMilliseconds()))
    }

    override suspend fun delete(id: String) = dao.softDelete(id, now())
}

class HealthDocumentRepositoryImpl(private val dao: HealthDocumentDao, private val clock: AppClock) : HealthDocumentRepository {
    private fun now() = clock.now().toEpochMilliseconds()

    override fun observeForPet(petId: String): Flow<List<HealthDocument>> =
        dao.observeForPet(petId).map { list -> list.map { it.toDomain() } }

    override fun observeForActivePets(): Flow<List<HealthDocument>> =
        dao.observeForActivePets().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): HealthDocument? = dao.get(id)?.takeIf { it.deletedAt == null }?.toDomain()

    override suspend fun upsert(document: HealthDocument) {
        val existing = dao.get(document.id)
        dao.upsert(document.toEntity(now(), existing?.createdAt ?: document.createdAt.toEpochMilliseconds()))
    }

    override suspend fun delete(id: String) = dao.softDelete(id, now())
}
