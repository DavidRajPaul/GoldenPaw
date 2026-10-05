package com.goldenpaw.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Sync-ready rows: every table carries a UUID id, updatedAt, optional deletedAt (soft delete)
 * and syncState ("pending" | "synced") so a push/pull SyncEngine can be added without migrations.
 * Dates are stored as epoch-day Longs, instants as epoch-millis Longs.
 */

object SyncState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
}

@Entity(tableName = "pets")
data class PetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val species: String,
    val breed: String,
    val sex: String,
    val birthEpochDay: Long?,
    val photoPath: String?,
    /** Pipe-separated list of conditions. */
    val conditions: String,
    val vetName: String,
    val vetPhone: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?,
    val deletedAt: Long? = null,
    val syncState: String = SyncState.PENDING,
)

@Entity(
    tableName = "medications",
    foreignKeys = [
        ForeignKey(
            entity = PetEntity::class,
            parentColumns = ["id"],
            childColumns = ["petId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("petId")],
)
data class MedicationEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val name: String,
    val dosage: String,
    val unit: String,
    val route: String,
    val reason: String,
    val prescribedBy: String,
    val withFood: Boolean,
    val scheduleType: String,
    /** Comma-separated HH:mm wall-clock times. */
    val times: String,
    val intervalDays: Int,
    /** Comma-separated ISO day numbers (1 = Monday). */
    val weekdays: String,
    /** JSON array of TaperStep. */
    val taperSteps: String,
    val startEpochDay: Long,
    val endEpochDay: Long?,
    val supplyRemaining: Double?,
    val refillAlertDays: Int,
    val notes: String,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: String = SyncState.PENDING,
)

/** Append-only. "Undo" soft-deletes the row so the history (and sync) stay conflict-free. */
@Entity(
    tableName = "dose_events",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("medicationId"), Index("petId"), Index(value = ["petId", "scheduledAt"])],
)
data class DoseEventEntity(
    @PrimaryKey val id: String,
    val medicationId: String,
    val petId: String,
    val scheduledAt: Long,
    val actualAt: Long,
    val status: String,
    val dosageGiven: String,
    val givenBy: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: String = SyncState.PENDING,
)

@Entity(
    tableName = "check_ins",
    foreignKeys = [
        ForeignKey(
            entity = PetEntity::class,
            parentColumns = ["id"],
            childColumns = ["petId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["petId", "epochDay"], unique = true)],
)
data class CheckInEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val epochDay: Long,
    val appetite: Int,
    val water: Int,
    val mobility: Int,
    val mood: Int,
    val pain: Int,
    val hygiene: Int,
    val sleep: Int,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val syncState: String = SyncState.PENDING,
)

@Entity(
    tableName = "weight_entries",
    foreignKeys = [
        ForeignKey(
            entity = PetEntity::class,
            parentColumns = ["id"],
            childColumns = ["petId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("petId")],
)
data class WeightEntryEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val epochDay: Long,
    /** Always kilograms. */
    val weightKg: Double,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: String = SyncState.PENDING,
)

@Entity(
    tableName = "symptom_entries",
    foreignKeys = [
        ForeignKey(
            entity = PetEntity::class,
            parentColumns = ["id"],
            childColumns = ["petId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("petId")],
)
data class SymptomEntryEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val epochDay: Long,
    val loggedAt: Long,
    val type: String,
    val severity: Int,
    /** Pipe-separated tags. */
    val tags: String,
    val notes: String,
    val photoPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: String = SyncState.PENDING,
)
