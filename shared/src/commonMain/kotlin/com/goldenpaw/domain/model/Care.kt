package com.goldenpaw.domain.model

import kotlinx.datetime.Instant

/** What a caregiver is allowed to do inside a household. */
enum class CarePermission {
    VIEW,
    LOG_CARE,
    EDIT_PETS,
    EDIT_MEDS,
    MANAGE_TEAM,
    DELETE_DATA,
}

enum class CaregiverRole(val label: String, val blurb: String, val permissions: Set<CarePermission>) {
    OWNER(
        "Owner",
        "Full access. Manages the care team and can delete data.",
        CarePermission.entries.toSet(),
    ),
    FAMILY(
        "Family",
        "Logs care and can add or change pets and medications.",
        setOf(CarePermission.VIEW, CarePermission.LOG_CARE, CarePermission.EDIT_PETS, CarePermission.EDIT_MEDS),
    ),
    SITTER(
        "Sitter",
        "Logs doses, check-ins and symptoms. Can't change medications. Access can end on a date you choose.",
        setOf(CarePermission.VIEW, CarePermission.LOG_CARE),
    );

    companion object {
        fun from(value: String): CaregiverRole = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SITTER

        /** Roles an owner can invite. */
        val invitable = listOf(FAMILY, SITTER)
    }
}

enum class MemberStatus {
    ACTIVE, INVITED, REMOVED;

    companion object {
        fun from(value: String): MemberStatus = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ACTIVE
    }
}

/**
 * A care team. Pets belong to a household; everyone in it sees the same pets, doses and journal.
 * A household is local-only until the owner turns on sharing, which uploads it to the cloud.
 */
data class Household(
    val id: String,
    val name: String,
    /** Cloud user id of the owner; null while local-only. */
    val ownerUserId: String?,
    val cloudEnabled: Boolean,
    val createdAt: Instant,
)

data class Caregiver(
    val id: String,
    val householdId: String,
    /** Cloud user id; null for people who only log on this device (e.g. a family tablet). */
    val userId: String?,
    val displayName: String,
    val role: CaregiverRole,
    /** Index into the UI's caregiver palette, so each person keeps a stable color everywhere. */
    val colorIndex: Int,
    /** Sitters can have time-boxed access. */
    val accessUntil: Instant?,
    val status: MemberStatus,
    val createdAt: Instant,
) {
    fun isActiveAt(now: Instant): Boolean =
        status == MemberStatus.ACTIVE && (accessUntil == null || accessUntil > now)

    fun can(permission: CarePermission, now: Instant): Boolean = isActiveAt(now) && permission in role.permissions

    val initials: String
        get() = displayName.trim().split(" ").filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifBlank { "?" }

    val attribution: Attribution get() = Attribution(displayName, id)
}

/** The care team context for one household, from the current user's point of view. */
data class CareTeam(
    val household: Household,
    val members: List<Caregiver>,
    /** The person logging on this device right now. */
    val me: Caregiver?,
) {
    fun can(permission: CarePermission, now: Instant): Boolean = me?.can(permission, now) ?: true
    val activeMembers: List<Caregiver> get() = members.filter { it.status != MemberStatus.REMOVED }
    fun member(id: String?): Caregiver? = members.firstOrNull { it.id == id }
}

data class Invite(
    val code: String,
    val householdId: String,
    val role: CaregiverRole,
    val expiresAt: Instant,
    val accessUntil: Instant?,
)

enum class ActivityKind(val emoji: String) {
    DOSE_GIVEN("💊"), DOSE_SKIPPED("⏭️"), CHECK_IN("🙂"), SYMPTOM("📝"), WEIGHT("⚖️")
}

/** One line in the shared care log: who did what, when. */
data class CareActivity(
    val id: String,
    val kind: ActivityKind,
    val petId: String,
    val title: String,
    val detail: String,
    val at: Instant,
    val by: Attribution,
)

/** Signed-in cloud account. */
data class CloudSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
)

enum class SyncStatus { OFF, IDLE, SYNCING, ERROR }

data class SyncState(
    val status: SyncStatus = SyncStatus.OFF,
    val lastSyncedAt: Instant? = null,
    val message: String? = null,
)
