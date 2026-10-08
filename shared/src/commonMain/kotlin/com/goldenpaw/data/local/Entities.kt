package com.goldenpaw.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Sync-ready rows: every synced table carries a UUID id, updatedAt, optional deletedAt (soft delete)
 * and syncState ("pending" | "synced") so the Supabase sync engine can push dirty rows and pull
 * changes since the last sync. Dates are stored as epoch-day Longs, instants as epoch-millis Longs.
 *
 * Schema v2 (care teams, AI summaries, gamification). Columns added in v2 are listed in
 * [Migrations.MIGRATION_1_2]; keep the two in step. Schema v3 adds `health_documents`
 * ([Migrations.MIGRATION_2_3]).
 */

object SyncState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
}

@Entity(tableName = "pets", indices = [Index("householdId")])
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
    /** v2 */
    val householdId: String = "",
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
    /** JSON array of {"fromEpochDay": Long, "dosage": String}. */
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
    /** v2: caregiver id of whoever logged the dose. */
    val givenById: String? = null,
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
    /** v2 */
    val loggedBy: String = "",
    /** v2 */
    val loggedById: String? = null,
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
    /** v2 */
    val loggedBy: String = "",
    /** v2 */
    val loggedById: String? = null,
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
    /** v2 */
    val loggedBy: String = "",
    /** v2 */
    val loggedById: String? = null,
)

// ------------------------------------------------------------------ v2 tables

@Entity(tableName = "households")
data class HouseholdEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ownerUserId: String?,
    val cloudEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncState: String,
)

@Entity(tableName = "caregivers", indices = [Index("householdId")])
data class CaregiverEntity(
    @PrimaryKey val id: String,
    val householdId: String,
    val userId: String?,
    val displayName: String,
    val role: String,
    val colorIndex: Int,
    val accessUntil: Long?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val syncState: String,
)

@Entity(
    tableName = "weekly_summaries",
    foreignKeys = [
        ForeignKey(
            entity = PetEntity::class,
            parentColumns = ["id"],
            childColumns = ["petId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["petId", "weekStartEpochDay"], unique = true)],
)
data class WeeklySummaryEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val weekStartEpochDay: Long,
    val headline: String,
    val summary: String,
    /** JSON string arrays. */
    val highlights: String,
    val watchItems: String,
    val vetQuestions: String,
    val source: String,
    val createdAt: Long,
)

/** Key-value app settings (replaces the v1 Android-only DataStore so every platform shares one store). */
@Entity(tableName = "app_settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val id: String,
    val unlockedAt: Long,
    val seen: Boolean,
)

@Entity(
    tableName = "vet_visits",
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
data class VetVisitEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val title: String,
    val clinic: String,
    val at: Long,
    val notes: String,
    val completed: Boolean,
    val loggedBy: String,
    val loggedById: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncState: String,
)

/** Scanned vet / vaccine card (v3). Pages and PDF live as files; this row is the confirmed entry. */
@Entity(
    tableName = "health_documents",
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
data class HealthDocumentEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val type: String,
    val title: String,
    val issuedEpochDay: Long?,
    val clinic: String,
    val vetName: String,
    val notes: String,
    /** JSON array of {name, given, due, batch} (epoch days). */
    val vaccines: String,
    /** JSON array of app-private JPEG paths. */
    val pages: String,
    val pdfPath: String?,
    val recognizedText: String,
    val loggedBy: String,
    val loggedById: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncState: String,
)
