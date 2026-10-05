package com.goldenpaw.data.mapper

import com.goldenpaw.data.local.CheckInEntity
import com.goldenpaw.data.local.DoseEventEntity
import com.goldenpaw.data.local.MedicationEntity
import com.goldenpaw.data.local.PetEntity
import com.goldenpaw.data.local.SymptomEntryEntity
import com.goldenpaw.data.local.WeightEntryEntity
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.MedicationSchedule
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.PetSex
import com.goldenpaw.domain.model.ScheduleType
import com.goldenpaw.domain.model.Species
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.SymptomType
import com.goldenpaw.domain.model.TaperStep
import com.goldenpaw.domain.model.WeightEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

private val json = Json { ignoreUnknownKeys = true }
private val taperSerializer = ListSerializer(TaperStep.serializer())
private const val SEP = "|"

private fun String.splitList(sep: String = SEP): List<String> =
    split(sep).map { it.trim() }.filter { it.isNotEmpty() }

private fun Long.instant(): Instant = Instant.ofEpochMilli(this)

// ---------- Pet ----------
fun PetEntity.toDomain() = Pet(
    id = id,
    name = name,
    species = Species.from(species),
    breed = breed,
    sex = PetSex.from(sex),
    birthDate = birthEpochDay?.let(LocalDate::ofEpochDay),
    photoPath = photoPath,
    conditions = conditions.splitList(),
    vetName = vetName,
    vetPhone = vetPhone,
    notes = notes,
    createdAt = createdAt.instant(),
    archivedAt = archivedAt?.instant(),
)

fun Pet.toEntity(now: Long = System.currentTimeMillis()) = PetEntity(
    id = id,
    name = name.trim(),
    species = species.name,
    breed = breed.trim(),
    sex = sex.name,
    birthEpochDay = birthDate?.toEpochDay(),
    photoPath = photoPath,
    conditions = conditions.joinToString(SEP),
    vetName = vetName.trim(),
    vetPhone = vetPhone.trim(),
    notes = notes,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = now,
    archivedAt = archivedAt?.toEpochMilli(),
)

// ---------- Medication ----------
fun MedicationEntity.toDomain() = Medication(
    id = id,
    petId = petId,
    name = name,
    dosage = dosage,
    unit = unit,
    route = route,
    reason = reason,
    prescribedBy = prescribedBy,
    withFood = withFood,
    schedule = MedicationSchedule(
        type = ScheduleType.from(scheduleType),
        times = times.splitList(",").mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() },
        intervalDays = intervalDays,
        weekdays = weekdays.splitList(",").mapNotNull { it.toIntOrNull()?.let(DayOfWeek::of) }.toSet(),
        taperSteps = if (taperSteps.isBlank()) emptyList()
        else runCatching { json.decodeFromString(taperSerializer, taperSteps) }.getOrDefault(emptyList()),
        startDate = LocalDate.ofEpochDay(startEpochDay),
        endDate = endEpochDay?.let(LocalDate::ofEpochDay),
    ),
    supplyRemaining = supplyRemaining,
    refillAlertDays = refillAlertDays,
    notes = notes,
    isActive = isActive,
    createdAt = createdAt.instant(),
)

fun Medication.toEntity(now: Long = System.currentTimeMillis()) = MedicationEntity(
    id = id,
    petId = petId,
    name = name.trim(),
    dosage = dosage.trim(),
    unit = unit,
    route = route,
    reason = reason.trim(),
    prescribedBy = prescribedBy.trim(),
    withFood = withFood,
    scheduleType = schedule.type.name,
    times = schedule.times.distinct().sorted().joinToString(",") { "%02d:%02d".format(it.hour, it.minute) },
    intervalDays = schedule.intervalDays,
    weekdays = schedule.weekdays.sorted().joinToString(",") { it.value.toString() },
    taperSteps = json.encodeToString(taperSerializer, schedule.taperSteps),
    startEpochDay = schedule.startDate.toEpochDay(),
    endEpochDay = schedule.endDate?.toEpochDay(),
    supplyRemaining = supplyRemaining,
    refillAlertDays = refillAlertDays,
    notes = notes,
    isActive = isActive,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = now,
)

// ---------- Dose events ----------
fun DoseEventEntity.toDomain() = DoseEvent(
    id = id,
    medicationId = medicationId,
    petId = petId,
    scheduledAt = scheduledAt.instant(),
    actualAt = actualAt.instant(),
    status = if (status == DoseStatus.SKIPPED.name) DoseStatus.SKIPPED else DoseStatus.GIVEN,
    dosageGiven = dosageGiven,
    givenBy = givenBy,
    notes = notes,
)

fun DoseEvent.toEntity(now: Long = System.currentTimeMillis()) = DoseEventEntity(
    id = id,
    medicationId = medicationId,
    petId = petId,
    scheduledAt = scheduledAt.toEpochMilli(),
    actualAt = actualAt.toEpochMilli(),
    status = status.name,
    dosageGiven = dosageGiven,
    givenBy = givenBy,
    notes = notes,
    createdAt = now,
    updatedAt = now,
)

// ---------- Check-ins ----------
fun CheckInEntity.toDomain() = CheckIn(
    id = id,
    petId = petId,
    date = LocalDate.ofEpochDay(epochDay),
    appetite = appetite,
    water = water,
    mobility = mobility,
    mood = mood,
    pain = pain,
    hygiene = hygiene,
    sleep = sleep,
    notes = notes,
    createdAt = createdAt.instant(),
)

fun CheckIn.toEntity(now: Long = System.currentTimeMillis()) = CheckInEntity(
    id = id,
    petId = petId,
    epochDay = date.toEpochDay(),
    appetite = appetite,
    water = water,
    mobility = mobility,
    mood = mood,
    pain = pain,
    hygiene = hygiene,
    sleep = sleep,
    notes = notes,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = now,
)

// ---------- Weight ----------
fun WeightEntryEntity.toDomain() = WeightEntry(
    id = id,
    petId = petId,
    date = LocalDate.ofEpochDay(epochDay),
    weightKg = weightKg,
    notes = notes,
    createdAt = createdAt.instant(),
)

fun WeightEntry.toEntity(now: Long = System.currentTimeMillis()) = WeightEntryEntity(
    id = id,
    petId = petId,
    epochDay = date.toEpochDay(),
    weightKg = weightKg,
    notes = notes,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = now,
)

// ---------- Symptoms ----------
fun SymptomEntryEntity.toDomain() = SymptomEntry(
    id = id,
    petId = petId,
    date = LocalDate.ofEpochDay(epochDay),
    loggedAt = loggedAt.instant(),
    type = SymptomType.from(type),
    severity = severity,
    tags = tags.splitList(),
    notes = notes,
    photoPath = photoPath,
)

fun SymptomEntry.toEntity(now: Long = System.currentTimeMillis()) = SymptomEntryEntity(
    id = id,
    petId = petId,
    epochDay = date.toEpochDay(),
    loggedAt = loggedAt.toEpochMilli(),
    type = type.name,
    severity = severity,
    tags = tags.joinToString(SEP),
    notes = notes,
    photoPath = photoPath,
    createdAt = now,
    updatedAt = now,
)
