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

    @Query("SELECT * FROM pets WHERE deletedAt IS NULL")
    suspend fun all(): List<PetEntity>

    @Query("SELECT COUNT(*) FROM pets WHERE deletedAt IS NULL AND archivedAt IS NULL")
    suspend fun activeCount(): Int

    @Upsert
    suspend fun upsert(pet: PetEntity)

    @Query("UPDATE pets SET archivedAt = :at, updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun archive(id: String, at: Long)

    @Query("UPDATE pets SET archivedAt = NULL, updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun restore(id: String, at: Long)

    @Query("DELETE FROM pets WHERE id = :id")
    suspend fun hardDelete(id: String)
}

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications WHERE petId = :petId AND deletedAt IS NULL ORDER BY isActive DESC, name COLLATE NOCASE")
    fun observeForPet(petId: String): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE petId = :petId AND deletedAt IS NULL AND isActive = 1 ORDER BY name COLLATE NOCASE")
    fun observeActiveForPet(petId: String): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE id = :id AND deletedAt IS NULL")
    fun observe(id: String): Flow<MedicationEntity?>

    @Query("SELECT * FROM medications WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: String): MedicationEntity?

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
}

@Dao
interface CheckInDao {
    @Query("SELECT * FROM check_ins WHERE petId = :petId ORDER BY epochDay DESC")
    fun observeForPet(petId: String): Flow<List<CheckInEntity>>

    @Query("SELECT * FROM check_ins WHERE petId = :petId AND epochDay = :epochDay LIMIT 1")
    fun observeForDay(petId: String, epochDay: Long): Flow<CheckInEntity?>

    @Query("SELECT * FROM check_ins WHERE petId = :petId AND epochDay = :epochDay LIMIT 1")
    suspend fun getForDay(petId: String, epochDay: Long): CheckInEntity?

    @Query("SELECT * FROM check_ins WHERE petId = :petId AND epochDay BETWEEN :from AND :to ORDER BY epochDay")
    suspend fun forPetBetween(petId: String, from: Long, to: Long): List<CheckInEntity>

    @Query("SELECT * FROM check_ins")
    suspend fun all(): List<CheckInEntity>

    @Upsert
    suspend fun upsert(checkIn: CheckInEntity)
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weight_entries WHERE petId = :petId AND deletedAt IS NULL ORDER BY epochDay, createdAt")
    fun observeForPet(petId: String): Flow<List<WeightEntryEntity>>

    @Query(
        """
        SELECT * FROM weight_entries WHERE petId = :petId AND deletedAt IS NULL
        AND epochDay BETWEEN :from AND :to ORDER BY epochDay, createdAt
        """,
    )
    suspend fun forPetBetween(petId: String, from: Long, to: Long): List<WeightEntryEntity>

    @Query("SELECT * FROM weight_entries WHERE deletedAt IS NULL")
    suspend fun all(): List<WeightEntryEntity>

    @Upsert
    suspend fun upsert(entry: WeightEntryEntity)

    @Query("UPDATE weight_entries SET deletedAt = :at, updatedAt = :at, syncState = 'pending' WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface SymptomDao {
    @Query("SELECT * FROM symptom_entries WHERE petId = :petId AND deletedAt IS NULL ORDER BY epochDay DESC, loggedAt DESC")
    fun observeForPet(petId: String): Flow<List<SymptomEntryEntity>>

    @Query("SELECT * FROM symptom_entries WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: String): SymptomEntryEntity?

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
}
