package com.goldenpaw.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/** One line of a vaccine card (or a deworming / flea-tick treatment, which cards list the same way). */
data class VaccineRecord(
    val name: String,
    val givenOn: LocalDate? = null,
    val nextDue: LocalDate? = null,
    /** Batch / lot number from the sticker, if any. */
    val batch: String = "",
) {
    fun status(today: LocalDate, soonDays: Int = 30): VaccineStatus {
        val due = nextDue ?: return VaccineStatus.NO_DUE_DATE
        return when {
            due < today -> VaccineStatus.OVERDUE
            due.toEpochDays() - today.toEpochDays() <= soonDays -> VaccineStatus.DUE_SOON
            else -> VaccineStatus.UP_TO_DATE
        }
    }
}

enum class VaccineStatus { OVERDUE, DUE_SOON, UP_TO_DATE, NO_DUE_DATE }

/**
 * A scanned vet card, vaccine card, prescription or lab report: the page images, a generated PDF
 * and the structured entry the owner confirmed (pre-filled by on-device text recognition).
 */
data class HealthDocument(
    val id: String,
    val petId: String,
    val type: DocumentType,
    val title: String,
    val issuedOn: LocalDate?,
    val clinic: String,
    val vetName: String,
    val notes: String,
    val vaccines: List<VaccineRecord>,
    /** App-private JPEG paths, in page order. */
    val pagePaths: List<String>,
    /** Generated PDF (summary page + every scanned page). Null until generated. */
    val pdfPath: String?,
    /** Raw text recognised on the device; kept so records stay searchable. */
    val recognizedText: String,
    val createdAt: Instant,
    val loggedBy: Attribution = Attribution.NONE,
) {
    val displayTitle: String get() = title.ifBlank { type.defaultTitle }
}

/** A vaccine due date surfaced on Today and the pet profile. */
data class VaccineDue(
    val petId: String,
    val documentId: String,
    val vaccine: VaccineRecord,
    val status: VaccineStatus,
)

object VaccineDueLogic {
    /**
     * The latest record per vaccine name wins (a newer card supersedes an older one), then only
     * overdue or due-within-[soonDays] entries are returned, soonest first.
     */
    fun dueSoon(documents: List<HealthDocument>, today: LocalDate, soonDays: Int = 30): List<VaccineDue> {
        val latest = mutableMapOf<String, Pair<HealthDocument, VaccineRecord>>()
        documents.forEach { doc ->
            doc.vaccines.forEach { v ->
                val key = doc.petId + "|" + v.name.trim().lowercase()
                val current = latest[key]
                val vDate = v.givenOn ?: v.nextDue
                val cDate = current?.second?.let { it.givenOn ?: it.nextDue }
                if (current == null || (vDate != null && (cDate == null || vDate > cDate))) latest[key] = doc to v
            }
        }
        return latest.values
            .map { (doc, v) -> VaccineDue(doc.petId, doc.id, v, v.status(today, soonDays)) }
            .filter { it.status == VaccineStatus.OVERDUE || it.status == VaccineStatus.DUE_SOON }
            .sortedBy { it.vaccine.nextDue }
    }
}
