package com.goldenpaw.data.remote

import com.goldenpaw.data.local.CaregiverEntity
import com.goldenpaw.data.local.CheckInEntity
import com.goldenpaw.data.local.DoseEventEntity
import com.goldenpaw.data.local.HouseholdEntity
import com.goldenpaw.data.local.MedicationEntity
import com.goldenpaw.data.local.PetEntity
import com.goldenpaw.data.local.SymptomEntryEntity
import com.goldenpaw.data.local.SyncState
import com.goldenpaw.data.local.VetVisitEntity
import com.goldenpaw.data.local.WeightEntryEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire format for the Supabase tables in supabase/migrations/0001_goldenpaw.sql (snake_case).
 * Client timestamps travel as epoch millis (updated_at drives last-write-wins on the server).
 * `synced_at` is assigned by the server and is what devices page through when pulling.
 * Local-only columns (photo paths) are deliberately not synced.
 */

@Serializable
data class HouseholdDto(
    val id: String,
    val name: String,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    @SerialName("synced_at") val syncedAt: String? = null,
)

@Serializable
data class MemberDto(
    val id: String,
    @SerialName("household_id") val householdId: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("display_name") val displayName: String,
    val role: String,
    @SerialName("color_index") val colorIndex: Int = 0,
    @SerialName("access_until") val accessUntil: Long? = null,
    val status: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("synced_at") val syncedAt: String? = null,
)

@Serializable
data class PetDto(
    val id: String,
    @SerialName("household_id") val householdId: String,
    val name: String,
    val species: String,
    val breed: String,
    val sex: String,
    @SerialName("birth_epoch_day") val birthEpochDay: Long? = null,
    val conditions: String,
    @SerialName("vet_name") val vetName: String,
    @SerialName("vet_phone") val vetPhone: String,
    val notes: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("archived_at") val archivedAt: Long? = null,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    @SerialName("synced_at") val syncedAt: String? = null,
)

@Serializable
data class MedicationDto(
    val id: String,
    @SerialName("household_id") val householdId: String,
    @SerialName("pet_id") val petId: String,
    val name: String,
    val dosage: String,
    val unit: String,
    val route: String,
    val reason: String,
    @SerialName("prescribed_by") val prescribedBy: String,
    @SerialName("with_food") val withFood: Boolean,
    @SerialName("schedule_type") val scheduleType: String,
    val times: String,
    @SerialName("interval_days") val intervalDays: Int,
    val weekdays: String,
    @SerialName("taper_steps") val taperSteps: String,
    @SerialName("start_epoch_day") val startEpochDay: Long,
    @SerialName("end_epoch_day") val endEpochDay: Long? = null,
    @SerialName("supply_remaining") val supplyRemaining: Double? = null,
    @SerialName("refill_alert_days") val refillAlertDays: Int,
    val notes: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    @SerialName("synced_at") val syncedAt: String? = null,
)

@Serializable
data class DoseEventDto(
    val id: String,
    @SerialName("household_id") val householdId: String,
    @SerialName("medication_id") val medicationId: String,
    @SerialName("pet_id") val petId: String,
    @SerialName("scheduled_at") val scheduledAt: Long,
    @SerialName("actual_at") val actualAt: Long,
    val status: String,
    @SerialName("dosage_given") val dosageGiven: String,
    @SerialName("given_by") val givenBy: String,
    @SerialName("given_by_id") val givenById: String? = null,
    val notes: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    @SerialName("synced_at") val syncedAt: String? = null,
)

@Serializable
data class CheckInDto(
    val id: String,
    @SerialName("household_id") val householdId: String,
    @SerialName("pet_id") val petId: String,
    @SerialName("epoch_day") val epochDay: Long,
    val appetite: Int,
    val water: Int,
    val mobility: Int,
    val mood: Int,
    val pain: Int,
    val hygiene: Int,
    val sleep: Int,
    val notes: String,
    @SerialName("logged_by") val loggedBy: String = "",
    @SerialName("logged_by_id") val loggedById: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("synced_at") val syncedAt: String? = null,
)

@Serializable
data class WeightDto(
    val id: String,
    @SerialName("household_id") val householdId: String,
    @SerialName("pet_id") val petId: String,
    @SerialName("epoch_day") val epochDay: Long,
    @SerialName("weight_kg") val weightKg: Double,
    val notes: String,
    @SerialName("logged_by") val loggedBy: String = "",
    @SerialName("logged_by_id") val loggedById: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    @SerialName("synced_at") val syncedAt: String? = null,
)

@Serializable
data class SymptomDto(
    val id: String,
    @SerialName("household_id") val householdId: String,
    @SerialName("pet_id") val petId: String,
    @SerialName("epoch_day") val epochDay: Long,
    @SerialName("logged_at") val loggedAt: Long,
    val type: String,
    val severity: Int,
    val tags: String,
    val notes: String,
    @SerialName("logged_by") val loggedBy: String = "",
    @SerialName("logged_by_id") val loggedById: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    @SerialName("synced_at") val syncedAt: String? = null,
)

@Serializable
data class VetVisitDto(
    val id: String,
    @SerialName("household_id") val householdId: String,
    @SerialName("pet_id") val petId: String,
    val title: String,
    val clinic: String,
    val at: Long,
    val notes: String,
    val completed: Boolean,
    @SerialName("logged_by") val loggedBy: String = "",
    @SerialName("logged_by_id") val loggedById: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    @SerialName("synced_at") val syncedAt: String? = null,
)

@Serializable
data class InviteDto(
    val code: String,
    @SerialName("household_id") val householdId: String,
    val role: String,
    @SerialName("access_until") val accessUntil: Long? = null,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class AcceptInviteResult(
    @SerialName("household_id") val householdId: String,
    @SerialName("member_id") val memberId: String,
)

// ------------------------------------------------------------------ mapping

fun HouseholdEntity.toDto() = HouseholdDto(id, name, ownerUserId, createdAt, updatedAt, deletedAt)
fun HouseholdDto.toEntity() = HouseholdEntity(
    id = id, name = name, ownerUserId = ownerId, cloudEnabled = true,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, syncState = SyncState.SYNCED,
)

fun CaregiverEntity.toDto() = MemberDto(
    id, householdId, userId, displayName, role, colorIndex, accessUntil, status, createdAt, updatedAt,
)
fun MemberDto.toEntity() = CaregiverEntity(
    id = id, householdId = householdId, userId = userId, displayName = displayName, role = role,
    colorIndex = colorIndex, accessUntil = accessUntil, status = status, createdAt = createdAt,
    updatedAt = updatedAt, syncState = SyncState.SYNCED,
)

fun PetEntity.toDto() = PetDto(
    id, householdId, name, species, breed, sex, birthEpochDay, conditions, vetName, vetPhone, notes,
    createdAt, updatedAt, archivedAt, deletedAt,
)
/** [localPhotoPath] keeps this device's photo; photos aren't synced yet. */
fun PetDto.toEntity(localPhotoPath: String?) = PetEntity(
    id = id, name = name, species = species, breed = breed, sex = sex, birthEpochDay = birthEpochDay,
    photoPath = localPhotoPath, conditions = conditions, vetName = vetName, vetPhone = vetPhone, notes = notes,
    createdAt = createdAt, updatedAt = updatedAt, archivedAt = archivedAt, deletedAt = deletedAt,
    syncState = SyncState.SYNCED, householdId = householdId,
)

fun MedicationEntity.toDto(householdId: String) = MedicationDto(
    id, householdId, petId, name, dosage, unit, route, reason, prescribedBy, withFood, scheduleType, times,
    intervalDays, weekdays, taperSteps, startEpochDay, endEpochDay, supplyRemaining, refillAlertDays, notes,
    isActive, createdAt, updatedAt, deletedAt,
)
fun MedicationDto.toEntity() = MedicationEntity(
    id = id, petId = petId, name = name, dosage = dosage, unit = unit, route = route, reason = reason,
    prescribedBy = prescribedBy, withFood = withFood, scheduleType = scheduleType, times = times,
    intervalDays = intervalDays, weekdays = weekdays, taperSteps = taperSteps, startEpochDay = startEpochDay,
    endEpochDay = endEpochDay, supplyRemaining = supplyRemaining, refillAlertDays = refillAlertDays,
    notes = notes, isActive = isActive, createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
    syncState = SyncState.SYNCED,
)

fun DoseEventEntity.toDto(householdId: String) = DoseEventDto(
    id, householdId, medicationId, petId, scheduledAt, actualAt, status, dosageGiven, givenBy, givenById, notes,
    createdAt, updatedAt, deletedAt,
)
fun DoseEventDto.toEntity() = DoseEventEntity(
    id = id, medicationId = medicationId, petId = petId, scheduledAt = scheduledAt, actualAt = actualAt,
    status = status, dosageGiven = dosageGiven, givenBy = givenBy, notes = notes, createdAt = createdAt,
    updatedAt = updatedAt, deletedAt = deletedAt, syncState = SyncState.SYNCED, givenById = givenById,
)

fun CheckInEntity.toDto(householdId: String) = CheckInDto(
    id, householdId, petId, epochDay, appetite, water, mobility, mood, pain, hygiene, sleep, notes,
    loggedBy, loggedById, createdAt, updatedAt,
)
fun CheckInDto.toEntity() = CheckInEntity(
    id = id, petId = petId, epochDay = epochDay, appetite = appetite, water = water, mobility = mobility,
    mood = mood, pain = pain, hygiene = hygiene, sleep = sleep, notes = notes, createdAt = createdAt,
    updatedAt = updatedAt, syncState = SyncState.SYNCED, loggedBy = loggedBy, loggedById = loggedById,
)

fun WeightEntryEntity.toDto(householdId: String) = WeightDto(
    id, householdId, petId, epochDay, weightKg, notes, loggedBy, loggedById, createdAt, updatedAt, deletedAt,
)
fun WeightDto.toEntity() = WeightEntryEntity(
    id = id, petId = petId, epochDay = epochDay, weightKg = weightKg, notes = notes, createdAt = createdAt,
    updatedAt = updatedAt, deletedAt = deletedAt, syncState = SyncState.SYNCED, loggedBy = loggedBy,
    loggedById = loggedById,
)

fun SymptomEntryEntity.toDto(householdId: String) = SymptomDto(
    id, householdId, petId, epochDay, loggedAt, type, severity, tags, notes, loggedBy, loggedById,
    createdAt, updatedAt, deletedAt,
)
/** [localPhotoPath] keeps this device's photo; photos aren't synced yet. */
fun SymptomDto.toEntity(localPhotoPath: String?) = SymptomEntryEntity(
    id = id, petId = petId, epochDay = epochDay, loggedAt = loggedAt, type = type, severity = severity,
    tags = tags, notes = notes, photoPath = localPhotoPath, createdAt = createdAt, updatedAt = updatedAt,
    deletedAt = deletedAt, syncState = SyncState.SYNCED, loggedBy = loggedBy, loggedById = loggedById,
)

fun VetVisitEntity.toDto(householdId: String) = VetVisitDto(
    id, householdId, petId, title, clinic, at, notes, completed, loggedBy, loggedById, createdAt, updatedAt, deletedAt,
)
fun VetVisitDto.toEntity() = VetVisitEntity(
    id = id, petId = petId, title = title, clinic = clinic, at = at, notes = notes, completed = completed,
    loggedBy = loggedBy, loggedById = loggedById, createdAt = createdAt, updatedAt = updatedAt,
    deletedAt = deletedAt, syncState = SyncState.SYNCED,
)
