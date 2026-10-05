package com.goldenpaw.domain.model

import java.time.Instant
import java.time.LocalDate

/** 30-second daily check-in. Every value is 1 (worst) .. 5 (best). */
data class CheckIn(
    val id: String,
    val petId: String,
    val date: LocalDate,
    val appetite: Int,
    val water: Int,
    val mobility: Int,
    val mood: Int,
    val pain: Int,
    val hygiene: Int,
    val sleep: Int,
    val notes: String,
    val createdAt: Instant,
) {
    val values: List<Int> get() = listOf(appetite, water, mobility, mood, pain, hygiene, sleep)
    val average: Double get() = values.average()

    val dayQuality: DayQuality
        get() = when {
            average >= 3.5 -> DayQuality.GOOD
            average < 2.5 -> DayQuality.HARD
            else -> DayQuality.OKAY
        }
}

enum class DayQuality(val label: String) { GOOD("Good day"), OKAY("Okay day"), HARD("Hard day") }

enum class CheckInCategory(
    val label: String,
    val question: String,
    val lowLabel: String,
    val highLabel: String,
) {
    APPETITE("Appetite", "How was eating today?", "Not eating", "Eating normally"),
    WATER("Water", "How was drinking?", "Barely drinking", "Normal"),
    MOBILITY("Mobility", "How were they moving?", "Very limited", "Full range"),
    MOOD("Mood", "How did they seem?", "Withdrawn", "Happy, engaged"),
    PAIN("Comfort", "Any signs of pain?", "Severe pain", "No pain"),
    HYGIENE("Hygiene", "Clean and comfortable?", "Soiled, matted", "Clean"),
    SLEEP("Sleep", "How did they rest?", "Restless", "Sound sleep"),
}

/**
 * HHHHHMM quality-of-life scale (Villalobos): Hurt, Hunger, Hydration, Hygiene, Happiness,
 * Mobility, More good days than bad. Each 0–10, total 0–70.
 */
data class QualityOfLifeScore(
    val hurt: Double,
    val hunger: Double,
    val hydration: Double,
    val hygiene: Double,
    val happiness: Double,
    val mobility: Double,
    val moreGoodDays: Double,
) {
    val total: Double get() = hurt + hunger + hydration + hygiene + happiness + mobility + moreGoodDays
    val fraction: Float get() = (total / MAX).toFloat().coerceIn(0f, 1f)

    fun parts(): List<Pair<String, Double>> = listOf(
        "Hurt" to hurt, "Hunger" to hunger, "Hydration" to hydration, "Hygiene" to hygiene,
        "Happiness" to happiness, "Mobility" to mobility, "More good days" to moreGoodDays,
    )

    companion object {
        const val MAX = 70.0
        /** Commonly cited threshold on the HHHHHMM scale. Informational only. */
        const val ACCEPTABLE_THRESHOLD = 35.0
    }
}

data class WeightEntry(
    val id: String,
    val petId: String,
    val date: LocalDate,
    /** Always stored in kilograms (canonical unit). */
    val weightKg: Double,
    val notes: String,
    val createdAt: Instant,
)

enum class WeightUnit(val label: String) {
    KG("kg"), LB("lb");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: KG
    }
}

enum class SymptomType(val label: String, val emoji: String) {
    VOMITING("Vomiting", "🤢"),
    DIARRHEA("Diarrhea", "💩"),
    LIMPING("Limping", "🦵"),
    ACCIDENT("Accident", "💧"),
    COUGHING("Coughing", "😮‍💨"),
    LETHARGY("Lethargy", "😴"),
    NOT_EATING("Not eating", "🍽️"),
    SEIZURE("Seizure", "⚡"),
    CONFUSION("Confusion", "🌀"),
    BREATHING("Heavy breathing", "🫁"),
    SKIN("Skin / itching", "🩹"),
    OTHER("Other", "📝");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: OTHER
    }
}

object SymptomCatalog {
    val tags = listOf(
        "After meal", "At night", "After walk", "Morning", "First time", "Frequent",
        "Blood seen", "Short episode", "Long episode", "Seemed in pain",
    )
}

data class SymptomEntry(
    val id: String,
    val petId: String,
    val date: LocalDate,
    val loggedAt: Instant,
    val type: SymptomType,
    val severity: Int,
    val tags: List<String>,
    val notes: String,
    val photoPath: String?,
)

data class SymptomPattern(
    val type: SymptomType,
    val days: Int,
    val windowDays: Int,
) {
    val message: String
        get() = "${type.label} on $days days in the last $windowDays days"
}

data class Insight(
    val title: String,
    val body: String,
    val tone: Tone,
) {
    enum class Tone { INFO, WATCH, POSITIVE }
}
