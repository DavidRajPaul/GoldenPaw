package com.goldenpaw.domain.usecase

import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Platform hooks the domain needs (implemented by AlarmManager + notifications on Android). */
interface ReminderGateway {
    suspend fun rescheduleAll()
    fun cancelDoseNotification(medicationId: String, scheduledAt: Instant)
    suspend fun notifyRefill(medication: Medication)
}

fun newId(): String = UUID.randomUUID().toString()

class ObserveDoseSlotsUseCase(
    private val medications: MedicationRepository,
    private val doseEvents: DoseEventRepository,
) {
    operator fun invoke(
        petId: String,
        from: LocalDate,
        toInclusive: LocalDate,
        now: Flow<Instant>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<DoseSlot>> {
        val fromInstant = from.atStartOfDay(zone).toInstant()
        val toInstant = toInclusive.plusDays(1).atStartOfDay(zone).toInstant()
        return combine(
            medications.observeActiveForPet(petId),
            doseEvents.observeForPetBetween(petId, fromInstant, toInstant),
            now,
        ) { meds, events, instant ->
            ScheduleEngine.slotsFor(meds, events, from, toInclusive, instant, zone)
        }
    }
}

class LogDoseUseCase(
    private val medications: MedicationRepository,
    private val doseEvents: DoseEventRepository,
    private val settings: SettingsRepository,
    private val reminders: ReminderGateway,
) {
    /**
     * Records a dose as given or skipped. Idempotent per (medication, scheduledAt): logging again
     * replaces the previous event for that slot.
     */
    suspend operator fun invoke(
        medicationId: String,
        scheduledAt: Instant,
        status: DoseStatus,
        dosage: String? = null,
        notes: String = "",
        at: Instant = Instant.now(),
    ): DoseEvent? {
        val med = medications.get(medicationId) ?: return null
        val existing = doseEvents.find(medicationId, scheduledAt)
        if (existing != null) {
            if (existing.status == status) return existing
            doseEvents.undo(existing.id)
            if (existing.status == DoseStatus.GIVEN) medications.adjustSupply(medicationId, +1.0)
        }
        val owner = settings.current().ownerName.ifBlank { "Me" }
        val date = scheduledAt.atZone(ZoneId.systemDefault()).toLocalDate()
        val event = DoseEvent(
            id = newId(),
            medicationId = medicationId,
            petId = med.petId,
            scheduledAt = scheduledAt,
            actualAt = at,
            status = status,
            dosageGiven = dosage ?: med.dosageOn(date),
            givenBy = owner,
            notes = notes,
        )
        doseEvents.log(event)
        reminders.cancelDoseNotification(medicationId, scheduledAt)
        if (status == DoseStatus.GIVEN) {
            medications.adjustSupply(medicationId, -1.0)
            val updated = medications.get(medicationId)
            if (updated != null && updated.needsRefill()) reminders.notifyRefill(updated)
        }
        return event
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
