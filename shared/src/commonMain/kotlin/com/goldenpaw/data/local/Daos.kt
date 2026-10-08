package com.goldenpaw.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PetDao {
    @Query("SELECT * FROM pets WHERE deletedAt IS NULL AND archivedAt IS NULL ORDER BY createdAt ASC")
    fun observeActive(): Flow<List<PetEntity>>

    @Query("SELECT * FROM pets WHERE deletedAt IS NULL AND archivedAt IS NOT NULL ORDER BY archivedAt DESC")
    fun observeArchived(): Flow<List<PetEntity>>

    @Query("SELECT * FROM pets WHERE id = :id AND deletedAt IS NULL")
    fun observe(id: String): Flow<PetEntity?>

    @Query("SELECT * FROM pets WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: String): PetEntity?

    @Query("SELECT * FROM pets WHERE id = :id")
    suspend fun getIncludingDeleted(id: String): PetEntity?

    @Query("SELECT * FROM pets WHERE deletedAt IS NULL")
    suspend fun all(): List<PetEntity>

    @Query("SELECT * FROM pets")
    suspend fun allIncludingDeleted(): List<PetEntity>

    @Query("SELECT * FROM pets WHERE householdId = :householdId")
    suspend fun forHousehold(householdId: String): List<PetEntity>

    @Query("SELECT COUNT(*) FROM pets WHERE deletedAt IS NULL AND archivedAt IS NULL")
    suspend fun activeCount(): Int

    @Upsert
    suspend fun upsert(pet: PetEntity)

    @Query("UPDATE pets SET archivedAt = :at, updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun archive(id: String, at: Long)

    @Query("UPDATE pets SET archivedAt = NULL, updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun restore(id: String, at: Long)

    @Query("UPDATE pets SET deletedAt = :at, updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    @Query("DELETE FROM pets WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("UPDATE pets SET householdId = :householdId, syncState = 'pending' WHERE householdId = ''")
    suspend fun adoptOrphans(householdId: String)

    // ---- sync
    @Query("SELECT * FROM pets WHERE syncState = 'pending' AND householdId IN (:householdIds)")
    suspend fun pending(householdIds: List<String>): List<PetEntity>

    @Query("UPDATE pets SET syncState = 'synced' WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("UPDATE pets SET syncState = 'pending' WHERE householdId = :householdId")
    suspend fun markHouseholdPending(householdId: String)

    @Query("DELETE FROM pets WHERE deletedAt IS NOT NULL AND syncState = 'synced'")
    suspend fun purgeSyncedDeletes()
}

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications WHERE petId = :petId AND deletedAt IS NULL ORDER BY isActive DESC, name COLLATE NOCASE")
    fun observeForPet(petId: String): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE petId = :petId AND deletedAt IS NULL AND isActive = 1 ORDER BY name COLLATE NOCASE")
    fun observeActiveForPet(petId: String): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE id = :id AND deletedAt IS NULL")
    fun observe(id: String): Flow<MedicationEntity?>

    @Query("SELECT * FROM medications")
    fun observeAll(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: String): MedicationEntity?

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getIncludingDeleted(id: String): MedicationEntity?

    @Query(
        """
        SELECT m.* FROM medications m
        INNER JOIN pets p ON p.id = m.petId
        WHERE m.deletedAt IS NULL AND m.isActive = 1
          AND p.deletedAt IS NULL AND p.archivedAt IS NULL
        """,
    )
    suspend fun allActiveForActivePets(): List<MedicationEntity>

    @Query("SELECT * FROM medications WHERE deletedAt IS NULL")
    suspend fun all(): List<MedicationEntity>

    @Upsert
    suspend fun upsert(med: MedicationEntity)

    @Query("UPDATE medications SET isActive = :active, updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean, at: Long)

    @Query("UPDATE medications SET deletedAt = :at, isActive = 0, updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    @Query(
        """
        UPDATE medications SET supplyRemaining = MAX(0, supplyRemaining + :delta), updatedAt = :at,
        syncState = 'pending' WHERE id = :id AND supplyRemaining IS NOT NULL
        """,
    )
    suspend fun adjustSupply(id: String, delta: Double, at: Long)

    // ---- sync
    @Query(
        """
        SELECT m.* FROM medications m INNER JOIN pets p ON p.id = m.petId
        WHERE m.syncState = 'pending' AND p.householdId IN (:householdIds)
        """,
    )
    suspend fun pending(householdIds: List<String>): List<MedicationEntity>

    @Query("UPDATE medications SET syncState = 'synced' WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("UPDATE medications SET syncState = 'pending' WHERE petId IN (SELECT id FROM pets WHERE householdId = :householdId)")
    suspend fun markHouseholdPending(householdId: String)
}

@Dao
interface DoseEventDao {
    @Query(
        """
        SELECT * FROM dose_events WHERE petId = :petId AND deletedAt IS NULL
        AND scheduledAt >= :from AND scheduledAt < :to ORDER BY scheduledAt
        """,
    )
    fun observeForPetBetween(petId: String, from: Long, to: Long): Flow<List<DoseEventEntity>>

    @Query("SELECT * FROM dose_events WHERE deletedAt IS NULL AND actualAt >= :since ORDER BY actualAt DESC")
    fun observeSince(since: Long): Flow<List<DoseEventEntity>>

    @Query("SELECT * FROM dose_events WHERE deletedAt IS NULL AND status = 'GIVEN'")
    fun observeAllGiven(): Flow<List<DoseEventEntity>>

    @Query(
        """
        SELECT * FROM dose_events WHERE petId = :petId AND deletedAt IS NULL
        AND scheduledAt >= :from AND scheduledAt < :to ORDER BY scheduledAt
        """,
    )
    suspend fun forPetBetween(petId: String, from: Long, to: Long): List<DoseEventEntity>

    @Query(
        """
        SELECT * FROM dose_events WHERE medicationId IN (:medicationIds) AND deletedAt IS NULL
        AND scheduledAt >= :from AND scheduledAt < :to ORDER BY scheduledAt
        """,
    )
    suspend fun forMedicationsBetween(medicationIds: List<String>, from: Long, to: Long): List<DoseEventEntity>

    @Query(
        """
        SELECT * FROM dose_events WHERE medicationId = :medicationId AND scheduledAt = :scheduledAt
        AND deletedAt IS NULL ORDER BY actualAt DESC LIMIT 1
        """,
    )
    suspend fun find(medicationId: String, scheduledAt: Long): DoseEventEntity?

    @Query("SELECT * FROM dose_events WHERE id = :id")
    suspend fun get(id: String): DoseEventEntity?

    @Query("SELECT * FROM dose_events WHERE deletedAt IS NULL")
    suspend fun all(): List<DoseEventEntity>

    @Upsert
    suspend fun upsert(event: DoseEventEntity)

    @Query("UPDATE dose_events SET deletedAt = :at, updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    @Query("SELECT COUNT(*) FROM dose_events WHERE deletedAt IS NULL AND status = 'GIVEN'")
    fun observeTotalGiven(): Flow<Int>

    @Query("SELECT COUNT(*) FROM dose_events WHERE petId = :petId AND deletedAt IS NULL AND status = 'GIVEN'")
    suspend fun countGivenForPet(petId: String): Int

    // ---- sync
    @Query(
        """
        SELECT e.* FROM dose_events e INNER JOIN pets p ON p.id = e.petId
        WHERE e.syncState = 'pending' AND p.householdId IN (:householdIds)
        """,
    )
    suspend fun pending(householdIds: List<String>): List<DoseEventEntity>

    @Query("UPDATE dose_events SET syncState = 'synced' WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("UPDATE dose_events SET syncState = 'pending' WHERE petId IN (SELECT id FROM pets WHERE householdId = :householdId)")
    suspend fun markHouseholdPending(householdId: String)
}

@Dao
interface CheckInDao {
    @Query("SELECT * FROM check_ins WHERE petId = :petId ORDER BY epochDay DESC")
    fun observeForPet(petId: String): Flow<List<CheckInEntity>>

    @Query("SELECT * FROM check_ins WHERE petId = :petId AND epochDay = :epochDay LIMIT 1")
    fun observeForDay(petId: String, epochDay: Long): Flow<CheckInEntity?>

    @Query("SELECT * FROM check_ins ORDER BY epochDay DESC")
    fun observeAll(): Flow<List<CheckInEntity>>

    @Query("SELECT * FROM check_ins WHERE petId = :petId AND epochDay = :epochDay LIMIT 1")
    suspend fun getForDay(petId: String, epochDay: Long): CheckInEntity?

    @Query("SELECT * FROM check_ins WHERE id = :id")
    suspend fun get(id: String): CheckInEntity?

    @Query("SELECT * FROM check_ins WHERE petId = :petId AND epochDay BETWEEN :from AND :to ORDER BY epochDay")
    suspend fun forPetBetween(petId: String, from: Long, to: Long): List<CheckInEntity>

    @Query("SELECT * FROM check_ins")
    suspend fun all(): List<CheckInEntity>

    @Upsert
    suspend fun upsert(checkIn: CheckInEntity)

    @Query("DELETE FROM check_ins WHERE petId = :petId AND epochDay = :epochDay AND id != :keepId")
    suspend fun deleteOtherForDay(petId: String, epochDay: Long, keepId: String)

    // ---- sync
    @Query(
        """
        SELECT c.* FROM check_ins c INNER JOIN pets p ON p.id = c.petId
        WHERE c.syncState = 'pending' AND p.householdId IN (:householdIds)
        """,
    )
    suspend fun pending(householdIds: List<String>): List<CheckInEntity>

    @Query("UPDATE check_ins SET syncState = 'synced' WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("UPDATE check_ins SET syncState = 'pending' WHERE petId IN (SELECT id FROM pets WHERE householdId = :householdId)")
    suspend fun markHouseholdPending(householdId: String)
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weight_entries WHERE petId = :petId AND deletedAt IS NULL ORDER BY epochDay, createdAt")
    fun observeForPet(petId: String): Flow<List<WeightEntryEntity>>

    @Query("SELECT * FROM weight_entries WHERE deletedAt IS NULL ORDER BY epochDay, createdAt")
    fun observeAll(): Flow<List<WeightEntryEntity>>

    @Query(
        """
        SELECT * FROM weight_entries WHERE petId = :petId AND deletedAt IS NULL
        AND epochDay BETWEEN :from AND :to ORDER BY epochDay, createdAt
        """,
    )
    suspend fun forPetBetween(petId: String, from: Long, to: Long): List<WeightEntryEntity>

    @Query("SELECT * FROM weight_entries WHERE id = :id")
    suspend fun get(id: String): WeightEntryEntity?

    @Query("SELECT * FROM weight_entries WHERE deletedAt IS NULL")
    suspend fun all(): List<WeightEntryEntity>

    @Upsert
    suspend fun upsert(entry: WeightEntryEntity)

    @Query("UPDATE weight_entries SET deletedAt = :at, updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    // ---- sync
    @Query(
        """
        SELECT w.* FROM weight_entries w INNER JOIN pets p ON p.id = w.petId
        WHERE w.syncState = 'pending' AND p.householdId IN (:householdIds)
        """,
    )
    suspend fun pending(householdIds: List<String>): List<WeightEntryEntity>

    @Query("UPDATE weight_entries SET syncState = 'synced' WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("UPDATE weight_entries SET syncState = 'pending' WHERE petId IN (SELECT id FROM pets WHERE householdId = :householdId)")
    suspend fun markHouseholdPending(householdId: String)
}

@Dao
interface SymptomDao {
    @Query("SELECT * FROM symptom_entries WHERE petId = :petId AND deletedAt IS NULL ORDER BY epochDay DESC, loggedAt DESC")
    fun observeForPet(petId: String): Flow<List<SymptomEntryEntity>>

    @Query("SELECT * FROM symptom_entries WHERE deletedAt IS NULL ORDER BY epochDay DESC, loggedAt DESC")
    fun observeAll(): Flow<List<SymptomEntryEntity>>

    @Query("SELECT * FROM symptom_entries WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: String): SymptomEntryEntity?

    @Query("SELECT * FROM symptom_entries WHERE id = :id")
    suspend fun getIncludingDeleted(id: String): SymptomEntryEntity?

    @Query(
        """
        SELECT * FROM symptom_entries WHERE petId = :petId AND deletedAt IS NULL
        AND epochDay BETWEEN :from AND :to ORDER BY epochDay, loggedAt
        """,
    )
    suspend fun forPetBetween(petId: String, from: Long, to: Long): List<SymptomEntryEntity>

    @Query("SELECT * FROM symptom_entries WHERE deletedAt IS NULL")
    suspend fun all(): List<SymptomEntryEntity>

    @Upsert
    suspend fun upsert(entry: SymptomEntryEntity)

    @Query("UPDATE symptom_entries SET deletedAt = :at, updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    // ---- sync
    @Query(
        """
        SELECT s.* FROM symptom_entries s INNER JOIN pets p ON p.id = s.petId
        WHERE s.syncState = 'pending' AND p.householdId IN (:householdIds)
        """,
    )
    suspend fun pending(householdIds: List<String>): List<SymptomEntryEntity>

    @Query("UPDATE symptom_entries SET syncState = 'synced' WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("UPDATE symptom_entries SET syncState = 'pending' WHERE petId IN (SELECT id FROM pets WHERE householdId = :householdId)")
    suspend fun markHouseholdPending(householdId: String)
}

@Dao
interface HouseholdDao {
    @Query("SELECT * FROM households WHERE deletedAt IS NULL ORDER BY createdAt")
    fun observeAll(): Flow<List<HouseholdEntity>>

    @Query("SELECT * FROM households WHERE id = :id AND deletedAt IS NULL")
    fun observe(id: String): Flow<HouseholdEntity?>

    @Query("SELECT * FROM households WHERE deletedAt IS NULL ORDER BY createdAt")
    suspend fun all(): List<HouseholdEntity>

    @Query("SELECT * FROM households WHERE id = :id")
    suspend fun get(id: String): HouseholdEntity?

    @Upsert
    suspend fun upsert(household: HouseholdEntity)

    @Query("SELECT * FROM caregivers WHERE householdId = :householdId ORDER BY createdAt")
    fun observeMembers(householdId: String): Flow<List<CaregiverEntity>>

    @Query("SELECT * FROM caregivers ORDER BY createdAt")
    fun observeAllMembers(): Flow<List<CaregiverEntity>>

    @Query("SELECT * FROM caregivers WHERE householdId = :householdId ORDER BY createdAt")
    suspend fun members(householdId: String): List<CaregiverEntity>

    @Query("SELECT * FROM caregivers")
    suspend fun allMembers(): List<CaregiverEntity>

    @Query("SELECT * FROM caregivers WHERE id = :id")
    suspend fun member(id: String): CaregiverEntity?

    @Upsert
    suspend fun upsertMember(member: CaregiverEntity)

    @Query("UPDATE caregivers SET status = 'REMOVED', updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun removeMember(id: String, at: Long)

    // ---- sync
    @Query("SELECT * FROM caregivers WHERE syncState = 'pending' AND householdId IN (:householdIds)")
    suspend fun pendingMembers(householdIds: List<String>): List<CaregiverEntity>

    @Query("UPDATE caregivers SET syncState = 'synced' WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markMemberSynced(id: String, updatedAt: Long)

    @Query("UPDATE caregivers SET syncState = 'pending' WHERE householdId = :householdId")
    suspend fun markMembersPending(householdId: String)

    @Query("DELETE FROM caregivers WHERE householdId = :householdId")
    suspend fun deleteMembers(householdId: String)

    @Query("DELETE FROM households WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE households SET syncState = 'synced' WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)
}

@Dao
interface SummaryDao {
    @Query("SELECT * FROM weekly_summaries WHERE petId = :petId ORDER BY weekStartEpochDay DESC")
    fun observeForPet(petId: String): Flow<List<WeeklySummaryEntity>>

    @Query("SELECT * FROM weekly_summaries WHERE petId = :petId AND weekStartEpochDay = :weekStart LIMIT 1")
    suspend fun get(petId: String, weekStart: Long): WeeklySummaryEntity?

    @Upsert
    suspend fun upsert(summary: WeeklySummaryEntity)

    @Query("DELETE FROM weekly_summaries WHERE petId = :petId")
    suspend fun deleteForPet(petId: String)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM app_settings")
    suspend fun all(): List<SettingEntity>

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun upsertAll(values: List<SettingEntity>)

    @Query("DELETE FROM app_settings WHERE `key` IN (:keys)")
    suspend fun delete(keys: List<String>)

    @Query("DELETE FROM app_settings")
    suspend fun clear()
}

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements")
    fun observeAll(): Flow<List<AchievementEntity>>

    @Query("INSERT OR IGNORE INTO achievements (id, unlockedAt, seen) VALUES (:id, :at, 0)")
    suspend fun unlock(id: String, at: Long)

    @Query("UPDATE achievements SET seen = 1 WHERE id IN (:ids)")
    suspend fun markSeen(ids: List<String>)
}

@Dao
interface VetVisitDao {
    @Query("SELECT * FROM vet_visits WHERE petId = :petId AND deletedAt IS NULL ORDER BY at DESC")
    fun observeForPet(petId: String): Flow<List<VetVisitEntity>>

    @Query(
        """
        SELECT v.* FROM vet_visits v INNER JOIN pets p ON p.id = v.petId
        WHERE v.deletedAt IS NULL AND v.completed = 0 AND v.at >= :from
          AND p.deletedAt IS NULL AND p.archivedAt IS NULL
        ORDER BY v.at
        """,
    )
    fun observeUpcoming(from: Long): Flow<List<VetVisitEntity>>

    @Query(
        """
        SELECT v.* FROM vet_visits v INNER JOIN pets p ON p.id = v.petId
        WHERE v.deletedAt IS NULL AND v.completed = 0 AND v.at >= :from
          AND p.deletedAt IS NULL AND p.archivedAt IS NULL
        ORDER BY v.at
        """,
    )
    suspend fun upcoming(from: Long): List<VetVisitEntity>

    @Query("SELECT * FROM vet_visits WHERE id = :id")
    suspend fun get(id: String): VetVisitEntity?

    @Query("SELECT * FROM vet_visits WHERE petId = :petId AND deletedAt IS NULL AND at BETWEEN :from AND :to ORDER BY at")
    suspend fun forPetBetween(petId: String, from: Long, to: Long): List<VetVisitEntity>

    @Upsert
    suspend fun upsert(visit: VetVisitEntity)

    @Query("UPDATE vet_visits SET deletedAt = :at, updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    // ---- sync
    @Query(
        """
        SELECT v.* FROM vet_visits v INNER JOIN pets p ON p.id = v.petId
        WHERE v.syncState = 'pending' AND p.householdId IN (:householdIds)
        """,
    )
    suspend fun pending(householdIds: List<String>): List<VetVisitEntity>

    @Query("UPDATE vet_visits SET syncState = 'synced' WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("UPDATE vet_visits SET syncState = 'pending' WHERE petId IN (SELECT id FROM pets WHERE householdId = :householdId)")
    suspend fun markHouseholdPending(householdId: String)
}

@Dao
interface HealthDocumentDao {
    @Query("SELECT * FROM health_documents WHERE petId = :petId AND deletedAt IS NULL ORDER BY COALESCE(issuedEpochDay, createdAt / 86400000) DESC, createdAt DESC")
    fun observeForPet(petId: String): Flow<List<HealthDocumentEntity>>

    @Query(
        """
        SELECT d.* FROM health_documents d INNER JOIN pets p ON p.id = d.petId
        WHERE d.deletedAt IS NULL AND p.deletedAt IS NULL AND p.archivedAt IS NULL
        """,
    )
    fun observeForActivePets(): Flow<List<HealthDocumentEntity>>

    @Query("SELECT * FROM health_documents WHERE id = :id")
    suspend fun get(id: String): HealthDocumentEntity?

    @Query("SELECT * FROM health_documents WHERE deletedAt IS NULL")
    suspend fun all(): List<HealthDocumentEntity>

    @Upsert
    suspend fun upsert(document: HealthDocumentEntity)

    @Query("UPDATE health_documents SET deletedAt = :at, updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

/** Wipes everything (used by "Delete all data"). Children first, then parents. */
@Dao
interface MaintenanceDao {
    @Query("DELETE FROM dose_events") suspend fun clearDoseEvents()
    @Query("DELETE FROM medications") suspend fun clearMedications()
    @Query("DELETE FROM check_ins") suspend fun clearCheckIns()
    @Query("DELETE FROM weight_entries") suspend fun clearWeights()
    @Query("DELETE FROM symptom_entries") suspend fun clearSymptoms()
    @Query("DELETE FROM weekly_summaries") suspend fun clearSummaries()
    @Query("DELETE FROM vet_visits") suspend fun clearVetVisits()
    @Query("DELETE FROM health_documents") suspend fun clearHealthDocuments()
    @Query("DELETE FROM pets") suspend fun clearPets()
    @Query("DELETE FROM caregivers") suspend fun clearCaregivers()
    @Query("DELETE FROM households") suspend fun clearHouseholds()
    @Query("DELETE FROM achievements") suspend fun clearAchievements()
    @Query("DELETE FROM app_settings") suspend fun clearSettings()
}
