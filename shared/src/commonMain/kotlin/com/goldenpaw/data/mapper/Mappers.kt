package com.goldenpaw.data.mapper

import com.goldenpaw.core.epochDay
import com.goldenpaw.core.hhmm
import com.goldenpaw.core.localDateOfEpochDay
import com.goldenpaw.data.local.AchievementEntity
import com.goldenpaw.data.local.CaregiverEntity
import com.goldenpaw.data.local.CheckInEntity
import com.goldenpaw.data.local.DoseEventEntity
import com.goldenpaw.data.local.HouseholdEntity
import com.goldenpaw.data.local.MedicationEntity
import com.goldenpaw.data.local.PetEntity
import com.goldenpaw.data.local.SymptomEntryEntity
import com.goldenpaw.data.local.SyncState
import com.goldenpaw.data.local.VetVisitEntity
import com.goldenpaw.data.local.WeeklySummaryEntity
import com.goldenpaw.data.local.WeightEntryEntity
import com.goldenpaw.domain.model.Attribution
import com.goldenpaw.domain.model.CaregiverRole
import com.goldenpaw.domain.model.Caregiver
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.Household
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.MedicationSchedule
import com.goldenpaw.domain.model.MemberStatus
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.PetSex
import com.goldenpaw.domain.model.ScheduleType
import com.goldenpaw.domain.model.Species
import com.goldenpaw.domain.model.SummarySource
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.SymptomType
import com.goldenpaw.domain.model.TaperStep
import com.goldenpaw.domain.model.VetVisit
import com.goldenpaw.domain.model.WeeklySummary
import com.goldenpaw.domain.model.WeightEntry
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import com.goldenpaw.data.local.HealthDocumentEntity
import com.goldenpaw.domain.model.DocumentType
import com.goldenpaw.domain.model.HealthDocument
import com.goldenpaw.domain.model.VaccineRecord
import kotlinx.serialization.json.longOrNull

private val json = Json { ignoreUnknownKeys = true }
private const val SEP = "|"

internal fun String.splitList(sep: String = SEP): List<String> =
    split(sep).map { it.trim() }.filter { it.isNotEmpty() }

internal fun Long.instant(): Instant = Instant.fromEpochMilliseconds(this)

// ---------- JSON helpers (manual, so the domain stays free of serialization annotations) ----------

internal fun encodeTaperSteps(steps: List<TaperStep>): String = buildJsonArray {
    steps.forEach { step ->
        add(buildJsonObject {
            put("fromEpochDay", step.fromEpochDay)
            put("dosage", step.dosage)
        })
    }
}.toString()

internal fun decodeTaperSteps(raw: String): List<TaperStep> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        json.parseToJsonElement(raw).jsonArray.map { el ->
            val obj = el.jsonObject
            TaperStep(
                fromEpochDay = obj["fromEpochDay"]?.jsonPrimitive?.long ?: 0L,
                dosage = obj["dosage"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())
}

internal fun encodeStrings(values: List<String>): String = JsonArray(values.map { JsonPrimitive(it) }).toString()

internal fun decodeStrings(raw: String): List<String> = if (raw.isBlank()) emptyList() else runCatching {
    json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
}.getOrDefault(emptyList())

internal fun parseTimes(raw: String): List<LocalTime> =
    raw.splitList(",").mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }

internal fun parseWeekdays(raw: String): Set<DayOfWeek> =
    raw.splitList(",").mapNotNull { v -> v.toIntOrNull()?.takeIf { it in 1..7 }?.let { DayOfWeek(it) } }.toSet()

// ---------- Pet ----------
fun PetEntity.toDomain() = Pet(
    id = id,
    householdId = householdId,
    name = name,
    species = Species.from(species),
    breed = breed,
    sex = PetSex.from(sex),
    birthDate = birthEpochDay?.let(::localDateOfEpochDay),
    photoPath = photoPath,
    conditions = conditions.splitList(),
    vetName = vetName,
    vetPhone = vetPhone,
    notes = notes,
    createdAt = createdAt.instant(),
    archivedAt = archivedAt?.instant(),
)

fun Pet.toEntity(now: Long, syncState: String = SyncState.PENDING) = PetEntity(
    id = id,
    name = name.trim(),
    species = species.name,
    breed = breed.trim(),
    sex = sex.name,
    birthEpochDay = birthDate?.epochDay(),
    photoPath = photoPath,
    conditions = conditions.joinToString(SEP),
    vetName = vetName.trim(),
    vetPhone = vetPhone.trim(),
    notes = notes,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = now,
    archivedAt = archivedAt?.toEpochMilliseconds(),
    syncState = syncState,
    householdId = householdId,
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
        times = parseTimes(times),
        intervalDays = intervalDays,
        weekdays = parseWeekdays(weekdays),
        taperSteps = decodeTaperSteps(taperSteps),
        startDate = localDateOfEpochDay(startEpochDay),
        endDate = endEpochDay?.let(::localDateOfEpochDay),
    ),
    supplyRemaining = supplyRemaining,
    refillAlertDays = refillAlertDays,
    notes = notes,
    isActive = isActive,
    createdAt = createdAt.instant(),
)

fun Medication.toEntity(now: Long) = MedicationEntity(
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
    times = schedule.times.distinct().sorted().joinToString(",") { it.hhmm() },
    intervalDays = schedule.intervalDays,
    weekdays = schedule.weekdays.sorted().joinToString(",") { it.isoDayNumber.toString() },
    taperSteps = encodeTaperSteps(schedule.taperSteps),
    startEpochDay = schedule.startDate.epochDay(),
    endEpochDay = schedule.endDate?.epochDay(),
    supplyRemaining = supplyRemaining,
    refillAlertDays = refillAlertDays,
    notes = notes,
    isActive = isActive,
    createdAt = createdAt.toEpochMilliseconds(),
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
    givenById = givenById,
    notes = notes,
)

fun DoseEvent.toEntity(now: Long) = DoseEventEntity(
    id = id,
    medicationId = medicationId,
    petId = petId,
    scheduledAt = scheduledAt.toEpochMilliseconds(),
    actualAt = actualAt.toEpochMilliseconds(),
    status = status.name,
    dosageGiven = dosageGiven,
    givenBy = givenBy,
    notes = notes,
    createdAt = now,
    updatedAt = now,
    givenById = givenById,
)

// ---------- Check-ins ----------
fun CheckInEntity.toDomain() = CheckIn(
    id = id,
    petId = petId,
    date = localDateOfEpochDay(epochDay),
    appetite = appetite,
    water = water,
    mobility = mobility,
    mood = mood,
    pain = pain,
    hygiene = hygiene,
    sleep = sleep,
    notes = notes,
    createdAt = createdAt.instant(),
    loggedBy = Attribution(loggedBy, loggedById),
)

fun CheckIn.toEntity(now: Long) = CheckInEntity(
    id = id,
    petId = petId,
    epochDay = date.epochDay(),
    appetite = appetite,
    water = water,
    mobility = mobility,
    mood = mood,
    pain = pain,
    hygiene = hygiene,
    sleep = sleep,
    notes = notes,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = now,
    loggedBy = loggedBy.name,
    loggedById = loggedBy.caregiverId,
)

// ---------- Weight ----------
fun WeightEntryEntity.toDomain() = WeightEntry(
    id = id,
    petId = petId,
    date = localDateOfEpochDay(epochDay),
    weightKg = weightKg,
    notes = notes,
    createdAt = createdAt.instant(),
    loggedBy = Attribution(loggedBy, loggedById),
)

fun WeightEntry.toEntity(now: Long) = WeightEntryEntity(
    id = id,
    petId = petId,
    epochDay = date.epochDay(),
    weightKg = weightKg,
    notes = notes,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = now,
    loggedBy = loggedBy.name,
    loggedById = loggedBy.caregiverId,
)

// ---------- Symptoms ----------
fun SymptomEntryEntity.toDomain() = SymptomEntry(
    id = id,
    petId = petId,
    date = localDateOfEpochDay(epochDay),
    loggedAt = loggedAt.instant(),
    type = SymptomType.from(type),
    severity = severity,
    tags = tags.splitList(),
    notes = notes,
    photoPath = photoPath,
    loggedBy = Attribution(loggedBy, loggedById),
)

fun SymptomEntry.toEntity(now: Long, createdAt: Long = now) = SymptomEntryEntity(
    id = id,
    petId = petId,
    epochDay = date.epochDay(),
    loggedAt = loggedAt.toEpochMilliseconds(),
    type = type.name,
    severity = severity,
    tags = tags.joinToString(SEP),
    notes = notes,
    photoPath = photoPath,
    createdAt = createdAt,
    updatedAt = now,
    loggedBy = loggedBy.name,
    loggedById = loggedBy.caregiverId,
)

// ---------- Households & caregivers ----------
fun HouseholdEntity.toDomain() = Household(
    id = id,
    name = name,
    ownerUserId = ownerUserId,
    cloudEnabled = cloudEnabled,
    createdAt = createdAt.instant(),
)

fun Household.toEntity(now: Long, syncState: String = SyncState.PENDING) = HouseholdEntity(
    id = id,
    name = name,
    ownerUserId = ownerUserId,
    cloudEnabled = cloudEnabled,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = now,
    deletedAt = null,
    syncState = syncState,
)

fun CaregiverEntity.toDomain() = Caregiver(
    id = id,
    householdId = householdId,
    userId = userId,
    displayName = displayName,
    role = CaregiverRole.from(role),
    colorIndex = colorIndex,
    accessUntil = accessUntil?.instant(),
    status = MemberStatus.from(status),
    createdAt = createdAt.instant(),
)

fun Caregiver.toEntity(now: Long, syncState: String = SyncState.PENDING) = CaregiverEntity(
    id = id,
    householdId = householdId,
    userId = userId,
    displayName = displayName,
    role = role.name,
    colorIndex = colorIndex,
    accessUntil = accessUntil?.toEpochMilliseconds(),
    status = status.name,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = now,
    syncState = syncState,
)

// ---------- Weekly summaries ----------
fun WeeklySummaryEntity.toDomain() = WeeklySummary(
    id = id,
    petId = petId,
    weekStart = localDateOfEpochDay(weekStartEpochDay),
    headline = headline,
    summary = summary,
    highlights = decodeStrings(highlights),
    watchItems = decodeStrings(watchItems),
    vetQuestions = decodeStrings(vetQuestions),
    source = SummarySource.entries.firstOrNull { it.name == source } ?: SummarySource.ON_DEVICE,
    createdAt = createdAt.instant(),
)

fun WeeklySummary.toEntity() = WeeklySummaryEntity(
    id = id,
    petId = petId,
    weekStartEpochDay = weekStart.epochDay(),
    headline = headline,
    summary = summary,
    highlights = encodeStrings(highlights),
    watchItems = encodeStrings(watchItems),
    vetQuestions = encodeStrings(vetQuestions),
    source = source.name,
    createdAt = createdAt.toEpochMilliseconds(),
)

fun AchievementEntity.unlockedInstant(): Instant = unlockedAt.instant()

// ---------- Vet visits ----------
fun VetVisitEntity.toDomain() = VetVisit(
    id = id,
    petId = petId,
    title = title,
    clinic = clinic,
    at = at.instant(),
    notes = notes,
    completed = completed,
    createdAt = createdAt.instant(),
    loggedBy = Attribution(loggedBy, loggedById),
)

fun VetVisit.toEntity(now: Long, createdAtMillis: Long = createdAt.toEpochMilliseconds()) = VetVisitEntity(
    id = id,
    petId = petId,
    title = title.trim(),
    clinic = clinic.trim(),
    at = at.toEpochMilliseconds(),
    notes = notes.trim(),
    completed = completed,
    loggedBy = loggedBy.name,
    loggedById = loggedBy.caregiverId,
    createdAt = createdAtMillis,
    updatedAt = now,
    deletedAt = null,
    syncState = SyncState.PENDING,
)

// ---------- Health documents (scanned vet / vaccine cards) ----------
internal fun encodeVaccines(values: List<VaccineRecord>): String = buildJsonArray {
    values.forEach { v ->
        add(buildJsonObject {
            put("name", v.name)
            put("given", v.givenOn?.epochDay())
            put("due", v.nextDue?.epochDay())
            put("batch", v.batch)
        })
    }
}.toString()

internal fun decodeVaccines(raw: String): List<VaccineRecord> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        json.parseToJsonElement(raw).jsonArray.map { el ->
            val obj = el.jsonObject
            VaccineRecord(
                name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                givenOn = obj["given"]?.jsonPrimitive?.longOrNull?.let { localDateOfEpochDay(it) },
                nextDue = obj["due"]?.jsonPrimitive?.longOrNull?.let { localDateOfEpochDay(it) },
                batch = obj["batch"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())
}

fun HealthDocumentEntity.toDomain() = HealthDocument(
    id = id,
    petId = petId,
    type = DocumentType.from(type),
    title = title,
    issuedOn = issuedEpochDay?.let { localDateOfEpochDay(it) },
    clinic = clinic,
    vetName = vetName,
    notes = notes,
    vaccines = decodeVaccines(vaccines),
    pagePaths = decodeStrings(pages),
    pdfPath = pdfPath,
    recognizedText = recognizedText,
    createdAt = createdAt.instant(),
    loggedBy = Attribution(loggedBy, loggedById),
)

fun HealthDocument.toEntity(now: Long, createdAtMillis: Long = createdAt.toEpochMilliseconds()) = HealthDocumentEntity(
    id = id,
    petId = petId,
    type = type.name,
    title = title.trim(),
    issuedEpochDay = issuedOn?.epochDay(),
    clinic = clinic.trim(),
    vetName = vetName.trim(),
    notes = notes.trim(),
    vaccines = encodeVaccines(vaccines.filter { it.name.isNotBlank() }.map { it.copy(name = it.name.trim(), batch = it.batch.trim()) }),
    pages = encodeStrings(pagePaths),
    pdfPath = pdfPath,
    recognizedText = recognizedText,
    loggedBy = loggedBy.name,
    loggedById = loggedBy.caregiverId,
    createdAt = createdAtMillis,
    updatedAt = now,
    deletedAt = null,
    syncState = SyncState.PENDING,
)
