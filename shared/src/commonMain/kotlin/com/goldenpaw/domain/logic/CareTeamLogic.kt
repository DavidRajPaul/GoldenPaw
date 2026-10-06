package com.goldenpaw.domain.logic

import com.goldenpaw.core.Fmt
import com.goldenpaw.domain.model.ActivityKind
import com.goldenpaw.domain.model.Attribution
import com.goldenpaw.domain.model.CareActivity
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.ScheduleType
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Builds the shared care log ("who did what, when") from the underlying records. */
object CareActivityFeed {
    fun build(
        pets: List<Pet>,
        medications: List<Medication>,
        doseEvents: List<DoseEvent>,
        checkIns: List<CheckIn>,
        symptoms: List<SymptomEntry>,
        weights: List<WeightEntry>,
        unit: WeightUnit,
        limit: Int = 60,
    ): List<CareActivity> {
        val petNames = pets.associate { it.id to it.name }
        val meds = medications.associateBy { it.id }
        val items = buildList {
            doseEvents.forEach { e ->
                val med = meds[e.medicationId]
                val given = e.status == DoseStatus.GIVEN
                add(
                    CareActivity(
                        id = "d-${e.id}",
                        kind = if (given) ActivityKind.DOSE_GIVEN else ActivityKind.DOSE_SKIPPED,
                        petId = e.petId,
                        title = "${if (given) "Gave" else "Skipped"} ${med?.name ?: "a medication"}",
                        detail = listOfNotNull(petNames[e.petId], e.dosageGiven.takeIf { it.isNotBlank() }).joinToString(" · "),
                        at = e.actualAt,
                        by = Attribution(e.givenBy, e.givenById),
                    ),
                )
            }
            checkIns.forEach { c ->
                add(
                    CareActivity(
                        id = "c-${c.id}",
                        kind = ActivityKind.CHECK_IN,
                        petId = c.petId,
                        title = "Daily check-in: ${c.dayQuality.label.lowercase()}",
                        detail = petNames[c.petId].orEmpty(),
                        at = c.createdAt,
                        by = c.loggedBy,
                    ),
                )
            }
            symptoms.forEach { s ->
                add(
                    CareActivity(
                        id = "s-${s.id}",
                        kind = ActivityKind.SYMPTOM,
                        petId = s.petId,
                        title = "Logged ${s.type.label.lowercase()}",
                        detail = listOfNotNull(petNames[s.petId], "severity ${s.severity}/5").joinToString(" · "),
                        at = s.loggedAt,
                        by = s.loggedBy,
                    ),
                )
            }
            weights.forEach { w ->
                add(
                    CareActivity(
                        id = "w-${w.id}",
                        kind = ActivityKind.WEIGHT,
                        petId = w.petId,
                        title = "Weighed ${UnitConversion.format(w.weightKg, unit)}",
                        detail = petNames[w.petId].orEmpty(),
                        at = w.createdAt,
                        by = w.loggedBy,
                    ),
                )
            }
        }
        return items.sortedByDescending { it.at }.take(limit)
    }
}

/**
 * Plain-text care guide a pet parent can send to a sitter or family member: medications with
 * times, vet contact and notes. Shared through the platform share sheet.
 */
object CareInstructions {
    fun build(
        pet: Pet,
        medications: List<Medication>,
        latestWeight: WeightEntry?,
        unit: WeightUnit,
        today: LocalDate,
        zone: TimeZone,
    ): String = buildString {
        appendLine("GoldenPaw care guide for ${pet.name} ${pet.species.emoji}")
        val facts = listOfNotNull(
            pet.breed.takeIf { it.isNotBlank() } ?: pet.species.label,
            pet.ageLabel(today),
            latestWeight?.let { UnitConversion.format(it.weightKg, unit) },
        )
        appendLine(facts.joinToString(" · "))
        if (pet.conditions.isNotEmpty()) appendLine("Conditions: ${pet.conditions.joinToString(", ")}")
        appendLine()

        val active = medications.filter { it.isActive }
        appendLine("MEDICATIONS")
        if (active.isEmpty()) {
            appendLine("• None right now")
        } else {
            active.sortedBy { it.schedule.times.minOrNull() }.forEach { med ->
                val parts = listOfNotNull(
                    med.dosageOn(today).takeIf { it.isNotBlank() },
                    med.scheduleSummary(),
                    "with food".takeIf { med.withFood },
                    med.route.takeIf { it.isNotBlank() && it != "Oral" },
                )
                appendLine("• ${med.name}: ${parts.joinToString(" · ")}")
                if (med.reason.isNotBlank()) appendLine("   For: ${med.reason}")
                if (med.notes.isNotBlank()) appendLine("   Tip: ${med.notes}")
                if (med.schedule.type == ScheduleType.TAPERING) appendLine("   Dose changes over time. Check the app for today's dose.")
            }
        }
        appendLine()
        if (pet.vetName.isNotBlank() || pet.vetPhone.isNotBlank()) {
            appendLine("VET")
            appendLine(listOf(pet.vetName, pet.vetPhone).filter { it.isNotBlank() }.joinToString(" · "))
            appendLine()
        }
        if (pet.notes.isNotBlank()) {
            appendLine("NOTES")
            appendLine(pet.notes)
            appendLine()
        }
        appendLine("Please log each dose in GoldenPaw so everyone can see it was given.")
        appendLine("Prepared ${Fmt.dayMonthYear(today)}. Not veterinary advice. In an emergency, call the vet.")
    }.trimEnd()
}
