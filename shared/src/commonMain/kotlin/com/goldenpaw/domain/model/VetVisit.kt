package com.goldenpaw.domain.model

import kotlinx.datetime.Instant

/** A vet appointment (upcoming) or visit record (past), with optional notes and follow-up. */
data class VetVisit(
    val id: String,
    val petId: String,
    val title: String,
    val clinic: String,
    val at: Instant,
    val notes: String,
    val completed: Boolean,
    val createdAt: Instant,
    val loggedBy: Attribution = Attribution.NONE,
) {
    fun isUpcoming(now: Instant): Boolean = !completed && at >= now

    companion object {
        val commonReasons = listOf("Check-up", "Follow-up", "Blood work", "Vaccination", "Dental", "Imaging", "Physio", "Grooming")
    }
}
