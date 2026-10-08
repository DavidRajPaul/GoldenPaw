package com.goldenpaw.domain.model

/** Kind of scanned health record. Emoji are for plain-text surfaces only; the UI uses icons. */
enum class DocumentType(val label: String, val defaultTitle: String, val emoji: String) {
    VACCINE_CARD("Vaccine card", "Vaccination record", "💉"),
    VET_CARD("Vet card", "Vet card", "🏥"),
    PRESCRIPTION("Prescription", "Prescription", "💊"),
    LAB_REPORT("Lab report", "Lab report", "🧪"),
    OTHER("Other", "Health record", "📄"),
    ;

    companion object {
        fun from(value: String?): DocumentType = entries.firstOrNull { it.name == value } ?: OTHER
    }
}
