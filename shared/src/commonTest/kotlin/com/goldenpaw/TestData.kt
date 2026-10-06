package com.goldenpaw

import com.goldenpaw.domain.model.Attribution
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.MedicationSchedule
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.PetSex
import com.goldenpaw.domain.model.ScheduleType
import com.goldenpaw.domain.model.Species
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

object TestData {
    val epoch: Instant = Instant.fromEpochMilliseconds(0)

    fun pet(id: String = "pet", householdId: String = "h1", birth: LocalDate? = LocalDate(2015, 3, 1)) = Pet(
        id = id, householdId = householdId, name = "Bruno", species = Species.DOG, breed = "Labrador Retriever",
        sex = PetSex.MALE, birthDate = birth, photoPath = null, conditions = listOf("Arthritis"),
        vetName = "Dr. Rao", vetPhone = "+91 98400 00000", notes = "", createdAt = epoch, archivedAt = null,
    )

    fun med(
        id: String = "med",
        type: ScheduleType = ScheduleType.DAILY,
        times: List<LocalTime> = listOf(LocalTime(8, 0), LocalTime(20, 0)),
        start: LocalDate = LocalDate(2026, 10, 1),
        interval: Int = 1,
        supply: Double? = null,
    ) = Medication(
        id = id, petId = "pet", name = "Carprofen", dosage = "25", unit = "mg", route = "Oral", reason = "Arthritis",
        prescribedBy = "", withFood = true,
        schedule = MedicationSchedule(type = type, times = times, intervalDays = interval, startDate = start),
        supplyRemaining = supply, refillAlertDays = 5, notes = "", isActive = true, createdAt = epoch,
    )

    fun dose(
        medId: String, scheduledAt: Instant, status: DoseStatus = DoseStatus.GIVEN, by: String = "Priya",
        byId: String? = "c1", actualAt: Instant = scheduledAt, id: String = "e-${scheduledAt.toEpochMilliseconds()}-$byId",
    ) = DoseEvent(id, medId, "pet", scheduledAt, actualAt, status, "25 mg", by, byId, "")

    fun checkIn(date: LocalDate, value: Int = 4, notes: String = "", petId: String = "pet") = CheckIn(
        id = "c-$date", petId = petId, date = date, appetite = value, water = value, mobility = value, mood = value,
        pain = value, hygiene = value, sleep = value, notes = notes, createdAt = epoch, loggedBy = Attribution("Priya", "c1"),
    )
}
