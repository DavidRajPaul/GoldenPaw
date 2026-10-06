package com.goldenpaw.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * Structured facts about one pet's week, computed on the device from what the owner logged.
 * This is the only thing ever sent to the AI writer: no photos, no vet contact, no owner identity.
 */
data class WeeklyDigest(
    val petId: String,
    val petName: String,
    val species: Species,
    val ageYears: Int?,
    val conditions: List<String>,
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val medicationNames: List<String>,
    val dosesGiven: Int,
    val dosesSkipped: Int,
    val dosesMissed: Int,
    val adherence: Double?,
    val previousAdherence: Double?,
    val checkIns: Int,
    val qolAverage: Double?,
    val previousQolAverage: Double?,
    val goodDays: Int,
    val okayDays: Int,
    val hardDays: Int,
    /** Lowest-scoring check-in category this week, e.g. "Mobility" (null if not enough data). */
    val weakestArea: String?,
    val strongestArea: String?,
    val symptomCounts: Map<SymptomType, Int>,
    val symptomDays: Map<SymptomType, Int>,
    val patterns: List<SymptomPattern>,
    val weightStartKg: Double?,
    val weightEndKg: Double?,
    val notes: List<String>,
    /** Display name → number of things they logged this week. */
    val caregiverContributions: Map<String, Int>,
) {
    val weightChangePercent: Double?
        get() {
            val start = weightStartKg ?: return null
            val end = weightEndKg ?: return null
            if (start <= 0.0) return null
            return (end - start) / start * 100.0
        }

    val totalSymptoms: Int get() = symptomCounts.values.sum()
    val hasAnyData: Boolean get() = dosesGiven + dosesSkipped + dosesMissed + checkIns + totalSymptoms > 0 || weightEndKg != null
}

enum class SummarySource(val label: String) {
    AI("Written by GoldenPaw AI"),
    ON_DEVICE("Written on your device"),
}

data class WeeklySummary(
    val id: String,
    val petId: String,
    val weekStart: LocalDate,
    val headline: String,
    val summary: String,
    val highlights: List<String>,
    /** Gentle "worth keeping an eye on" observations. Never a diagnosis. */
    val watchItems: List<String>,
    /** Questions the owner might bring to the next vet visit. */
    val vetQuestions: List<String>,
    val source: SummarySource,
    val createdAt: Instant,
)

object SummaryDisclaimer {
    const val SHORT = "Not veterinary advice"
    const val FULL = "This summary only reflects what was logged in GoldenPaw. It isn't veterinary advice and " +
        "can't diagnose anything. If something worries you, call your vet."
}
