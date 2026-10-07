package com.goldenpaw.domain.usecase

import com.goldenpaw.core.AppClock
import com.goldenpaw.core.newId
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.startOfDay
import com.goldenpaw.core.toLocalDate
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.model.CarePermission
import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.ReminderGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

class ObserveDoseSlotsUseCase(
    private val medications: MedicationRepository,
    private val doseEvents: DoseEventRepository,
    private val clock: AppClock,
) {
    operator fun invoke(
        petId: String,
        from: LocalDate,
        toInclusive: LocalDate,
        now: Flow<Instant>,
    ): Flow<List<DoseSlot>> {
        val zone = clock.zone()
        val fromInstant = from.startOfDay(zone)
        val toInstant = toInclusive.plusDays(1).startOfDay(zone)
        return combine(
            medications.observeActiveForPet(petId),
            doseEvents.observeForPetBetween(petId, fromInstant, toInstant),
            now,
        ) { meds, events, instant ->
            ScheduleEngine.slotsFor(meds, events, from, toInclusive, instant, zone)
        }
    }
}

sealed interface LogDoseResult {
    data class Logged(val event: DoseEvent) : LogDoseResult
    /** Another caregiver already gave this dose; nothing was written. */
    data class AlreadyGiven(val existing: DoseEvent) : LogDoseResult
    data object NotAllowed : LogDoseResult
    data object NotFound : LogDoseResult
}

class LogDoseUseCase(
    private val medications: MedicationRepository,
    private val doseEvents: DoseEventRepository,
    private val careTeam: CareTeamService,
    private val reminders: ReminderGateway,
    private val clock: AppClock,
) {
    /**
     * Records a dose as given or skipped. Idempotent per (medication, scheduledAt): logging again
     * replaces this caregiver's previous event for that slot.
     *
     * Double-dose guard: if someone else already marked the slot GIVEN, a second GIVEN is refused
     * with [LogDoseResult.AlreadyGiven] unless [overrideOtherCaregiver] is true.
     */
    suspend operator fun invoke(
        medicationId: String,
        scheduledAt: Instant,
        status: DoseStatus,
        dosage: String? = null,
        notes: String = "",
        at: Instant = clock.now(),
        overrideOtherCaregiver: Boolean = false,
    ): LogDoseResult {
        val med = medications.get(medicationId) ?: return LogDoseResult.NotFound
        if (!careTeam.can(med.petId, CarePermission.LOG_CARE)) return LogDoseResult.NotAllowed
        val who = careTeam.attributionFor(med.petId)

        val existing = doseEvents.find(medicationId, scheduledAt)
        if (existing != null) {
            val sameCaregiver = existing.givenById == null || existing.givenById == who.caregiverId
            if (existing.status == status && sameCaregiver) return LogDoseResult.Logged(existing)
            if (existing.status == DoseStatus.GIVEN && status == DoseStatus.GIVEN && !sameCaregiver && !overrideOtherCaregiver) {
                return LogDoseResult.AlreadyGiven(existing)
            }
            if (sameCaregiver) {
                doseEvents.undo(existing.id)
                if (existing.status == DoseStatus.GIVEN) medications.adjustSupply(medicationId, +1.0)
            }
        }
        val date = scheduledAt.toLocalDate(clock.zone())
        val event = DoseEvent(
            id = newId(),
            medicationId = medicationId,
            petId = med.petId,
            scheduledAt = scheduledAt,
            actualAt = at,
            status = status,
            dosageGiven = dosage ?: med.dosageOn(date),
            givenBy = who.name,
            givenById = who.caregiverId,
            notes = notes,
        )
        doseEvents.log(event)
        reminders.cancelDoseNotification(medicationId, scheduledAt)
        if (status == DoseStatus.GIVEN) {
            medications.adjustSupply(medicationId, -1.0)
            val updated = medications.get(medicationId)
            if (updated != null && updated.needsRefill()) reminders.notifyRefill(updated)
        }
        return LogDoseResult.Logged(event)
    }
}

class UndoDoseUseCase(
    private val medications: MedicationRepository,
    private val doseEvents: DoseEventRepository,
    private val reminders: ReminderGateway,
) {
    suspend operator fun invoke(event: DoseEvent) {
        doseEvents.undo(event.id)
        if (event.status == DoseStatus.GIVEN) medications.adjustSupply(event.medicationId, +1.0)
        reminders.rescheduleAll()
    }
}
