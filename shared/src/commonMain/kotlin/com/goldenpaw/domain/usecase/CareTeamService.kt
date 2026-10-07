package com.goldenpaw.domain.usecase

import com.goldenpaw.core.AppClock
import com.goldenpaw.core.newId
import com.goldenpaw.domain.model.Attribution
import com.goldenpaw.domain.model.CarePermission
import com.goldenpaw.domain.model.CareTeam
import com.goldenpaw.domain.model.Caregiver
import com.goldenpaw.domain.model.CaregiverRole
import com.goldenpaw.domain.model.Household
import com.goldenpaw.domain.model.MemberStatus
import com.goldenpaw.domain.repository.CloudAuthRepository
import com.goldenpaw.domain.repository.HouseholdRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves "who is caring for whom" for the rest of the app:
 * which household a pet belongs to, who is logging on this device, and what they're allowed to do.
 */
class CareTeamService(
    private val households: HouseholdRepository,
    private val pets: PetRepository,
    private val settings: SettingsRepository,
    private val auth: CloudAuthRepository,
    private val clock: AppClock,
) {
    private val setupMutex = Mutex()

    /**
     * Makes sure this device has a household with an owner caregiver. Called at startup and when
     * onboarding finishes. Idempotent.
     */
    suspend fun ensureSetup(): Household = setupMutex.withLock {
        val existing = households.households()
        val s = settings.current()
        val household = existing.firstOrNull { !it.cloudEnabled || it.ownerUserId == auth.session.value?.userId }
            ?: existing.firstOrNull()
            ?: Household(
                id = newId(),
                name = if (s.ownerName.isBlank()) "Our pets" else "${s.ownerName}'s pets",
                ownerUserId = null,
                cloudEnabled = false,
                createdAt = clock.now(),
            ).also { households.upsertHousehold(it) }

        val members = households.members(household.id)
        if (members.none { it.status != MemberStatus.REMOVED }) {
            val owner = Caregiver(
                id = newId(),
                householdId = household.id,
                userId = auth.session.value?.userId,
                displayName = s.ownerName.ifBlank { "Me" },
                role = CaregiverRole.OWNER,
                colorIndex = 0,
                accessUntil = null,
                status = MemberStatus.ACTIVE,
                createdAt = clock.now(),
            )
            households.upsertMember(owner)
            if (s.activeCaregiverId == null) settings.update { it.copy(activeCaregiverId = owner.id) }
        }
        household
    }

    /** Household new pets go into: the selected pet's household, else the first one. */
    suspend fun defaultHouseholdId(): String {
        val selected = settings.current().selectedPetId?.let { pets.getPet(it) }
        return selected?.householdId?.takeIf { it.isNotBlank() } ?: ensureSetup().id
    }

    fun observeTeam(householdId: String): Flow<CareTeam?> = combine(
        households.observeHousehold(householdId),
        households.observeMembers(householdId),
        settings.settings,
        auth.session,
    ) { household, members, s, session ->
        household?.let { h -> CareTeam(h, members, resolveMe(h, members, s.activeCaregiverId, session?.userId)) }
    }

    fun observeTeamForPet(petId: String?): Flow<CareTeam?> =
        if (petId == null) flowOf(null)
        else pets.observePet(petId).flatMapLatest { pet ->
            if (pet == null || pet.householdId.isBlank()) flowOf(null) else observeTeam(pet.householdId)
        }

    /** Team of the pet currently shown on Today (or the default household). */
    fun observeCurrentTeam(): Flow<CareTeam?> = settings.settings.flatMapLatest { s ->
        val petId = s.selectedPetId
        if (petId != null) observeTeamForPet(petId)
        else households.observeHouseholds().flatMapLatest { list ->
            list.firstOrNull()?.let { observeTeam(it.id) } ?: flowOf(null)
        }
    }

    suspend fun teamForPet(petId: String): CareTeam? {
        val pet = pets.getPet(petId) ?: return null
        return team(pet.householdId)
    }

    suspend fun team(householdId: String): CareTeam? {
        val household = households.getHousehold(householdId) ?: return null
        val members = households.members(householdId)
        val s = settings.current()
        return CareTeam(household, members, resolveMe(household, members, s.activeCaregiverId, auth.session.value?.userId))
    }

    /** Who to credit for something logged now for [petId]. */
    suspend fun attributionFor(petId: String): Attribution {
        val me = teamForPet(petId)?.me
        if (me != null) return me.attribution
        val name = settings.current().ownerName
        return Attribution(name.ifBlank { "Me" }, null)
    }

    suspend fun can(petId: String, permission: CarePermission): Boolean =
        teamForPet(petId)?.can(permission, clock.now()) ?: true

    suspend fun addLocalCaregiver(householdId: String, name: String, role: CaregiverRole): Caregiver {
        val existing = households.members(householdId)
        val member = Caregiver(
            id = newId(),
            householdId = householdId,
            userId = null,
            displayName = name.trim().ifBlank { "Helper" },
            role = role,
            colorIndex = (existing.maxOfOrNull { it.colorIndex } ?: 0) + 1,
            accessUntil = null,
            status = MemberStatus.ACTIVE,
            createdAt = clock.now(),
        )
        households.upsertMember(member)
        return member
    }

    suspend fun setActiveCaregiver(caregiverId: String) {
        settings.update { it.copy(activeCaregiverId = caregiverId) }
    }

    /** Keeps the owner caregiver's name in step with the profile name. */
    suspend fun syncOwnerName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val activeId = settings.current().activeCaregiverId ?: return
        val me = households.getMember(activeId) ?: return
        if (me.displayName != trimmed) households.upsertMember(me.copy(displayName = trimmed))
        households.households().filter { !it.cloudEnabled && it.name == "Our pets" }.forEach {
            households.upsertHousehold(it.copy(name = "$trimmed's pets"))
        }
    }

    private fun resolveMe(
        household: Household,
        members: List<Caregiver>,
        activeCaregiverId: String?,
        userId: String?,
    ): Caregiver? {
        val live = members.filter { it.status != MemberStatus.REMOVED }
        if (household.cloudEnabled && userId != null) {
            live.firstOrNull { it.userId == userId }?.let { return it }
        }
        live.firstOrNull { it.id == activeCaregiverId }?.let { return it }
        return live.firstOrNull { it.role == CaregiverRole.OWNER && (it.userId == null || it.userId == userId) }
            ?: live.firstOrNull()
    }
}
