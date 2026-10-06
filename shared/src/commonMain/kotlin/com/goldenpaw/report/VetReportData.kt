package com.goldenpaw.report

import com.goldenpaw.core.AppClock
import com.goldenpaw.core.minusDays
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.startOfDay
import com.goldenpaw.core.today
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.WeightRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

data class ReportOptions(
    val from: LocalDate,
    val to: LocalDate,
    val includeMeds: Boolean = true,
    val includeWeight: Boolean = true,
    val includeQol: Boolean = true,
    val includeSymptoms: Boolean = true,
    val includeNotes: Boolean = true,
)

data class VetReportData(
    val pet: Pet,
    val options: ReportOptions,
    val medications: List<Medication>,
    val slots: List<DoseSlot>,
    val weights: List<WeightEntry>,
    val checkIns: List<CheckIn>,
    val symptoms: List<SymptomEntry>,
    val unit: WeightUnit,
    val ownerName: String,
    val generatedOn: LocalDate,
    /** Care-team members who logged doses in the period ("given by" column in the report). */
    val caregivers: List<String>,
)

/** Gathers report data from repositories. */
class VetReportDataSource(
    private val medications: MedicationRepository,
    private val doseEvents: DoseEventRepository,
    private val weights: WeightRepository,
    private val checkIns: CheckInRepository,
    private val symptoms: SymptomRepository,
    private val clock: AppClock,
) {
    suspend fun load(pet: Pet, options: ReportOptions, unit: WeightUnit, ownerName: String): VetReportData {
        val zone = clock.zone()
        val now = clock.now()
        val meds = medications.observeForPet(pet.id).first()
        val from = options.from.startOfDay(zone)
        val to = options.to.plusDays(1).startOfDay(zone)
        val events = doseEvents.forPetBetween(pet.id, from, to)
        // Include paused meds too so history in the period is represented.
        val slots = ScheduleEngine.slotsFor(
            meds.map { it.copy(isActive = true) }, events, options.from, options.to, now, zone,
        ).filter { it.scheduledAt <= now }
        return VetReportData(
            pet = pet,
            options = options,
            medications = meds,
            slots = slots,
            weights = weights.forPetBetween(pet.id, options.from.minusDays(60), options.to),
            checkIns = checkIns.forPetBetween(pet.id, options.from.minusDays(7), options.to),
            symptoms = symptoms.forPetBetween(pet.id, options.from, options.to),
            unit = unit,
            ownerName = ownerName,
            generatedOn = clock.today(),
            caregivers = events.map { it.givenBy }.filter { it.isNotBlank() }.distinct(),
        )
    }
}
