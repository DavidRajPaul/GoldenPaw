package com.goldenpaw.ui.care

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldenpaw.core.AppClock
import com.goldenpaw.core.normalizeInviteCode
import com.goldenpaw.domain.logic.CareActivityFeed
import com.goldenpaw.domain.model.CareActivity
import com.goldenpaw.domain.model.CarePermission
import com.goldenpaw.domain.model.CareTeam
import com.goldenpaw.domain.model.Caregiver
import com.goldenpaw.domain.model.CaregiverRole
import com.goldenpaw.domain.model.CloudSession
import com.goldenpaw.domain.model.Invite
import com.goldenpaw.domain.model.MemberStatus
import com.goldenpaw.domain.model.SyncState
import com.goldenpaw.domain.repository.CareSyncRepository
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.CloudAuthRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.HouseholdRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.WeightRepository
import com.goldenpaw.domain.usecase.CareTeamService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

data class CareTeamState(
    val loading: Boolean = true,
    val team: CareTeam? = null,
    val session: CloudSession? = null,
    val cloudAvailable: Boolean = false,
    val sync: SyncState = SyncState(),
    val activity: List<CareActivity> = emptyList(),
    val ownerName: String = "",
    val now: Instant? = null,
) {
    val canManage: Boolean get() = now == null || team?.can(CarePermission.MANAGE_TEAM, now) ?: true
    val sharingOn: Boolean get() = team?.household?.cloudEnabled == true
}

/** Transient UI state for the sign-in and invite flows. */
data class CareTeamUi(
    val busy: Boolean = false,
    val email: String = "",
    val codeSent: Boolean = false,
    val otp: String = "",
    val invite: Invite? = null,
    val error: String? = null,
    val message: String? = null,
)

class CareTeamViewModel(
    settings: SettingsRepository,
    private val careTeam: CareTeamService,
    private val households: HouseholdRepository,
    private val auth: CloudAuthRepository,
    private val sync: CareSyncRepository,
    pets: PetRepository,
    meds: MedicationRepository,
    doseEvents: DoseEventRepository,
    checkIns: CheckInRepository,
    symptoms: SymptomRepository,
    weights: WeightRepository,
    private val clock: AppClock,
) : ViewModel() {

    private val activity = combine(
        combine(pets.observeActivePets(), meds.observeAll(), doseEvents.observeSince(clock.now() - 14.days)) { p, m, d -> Triple(p, m, d) },
        combine(checkIns.observeAll(), symptoms.observeAll(), weights.observeAll()) { c, s, w -> Triple(c, s, w) },
        settings.settings,
    ) { (p, m, d), (c, s, w), st -> Triple(Triple(p, m, d), Triple(c, s, w), st) }

    val state: StateFlow<CareTeamState> = combine(
        careTeam.observeCurrentTeam(),
        auth.session,
        sync.state,
        activity,
    ) { team, session, syncState, (core, logs, s) ->
        val (petList, medList, doses) = core
        val (cis, syms, ws) = logs
        val teamPets = petList.filter { team == null || it.householdId == team.household.id }
        val petIds = teamPets.map { it.id }.toSet()
        CareTeamState(
            loading = false,
            team = team,
            session = session,
            cloudAvailable = auth.isAvailable && sync.isAvailable,
            sync = syncState,
            activity = CareActivityFeed.build(
                pets = teamPets,
                medications = medList.filter { it.petId in petIds },
                doseEvents = doses.filter { it.petId in petIds },
                checkIns = cis.filter { it.petId in petIds },
                symptoms = syms.filter { it.petId in petIds },
                weights = ws.filter { it.petId in petIds },
                unit = s.weightUnit,
                limit = 40,
            ),
            ownerName = s.ownerName,
            now = clock.now(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CareTeamState())

    private val _ui = MutableStateFlow(CareTeamUi())
    val ui: StateFlow<CareTeamUi> = _ui.asStateFlow()

    fun setEmail(v: String) = _ui.update { it.copy(email = v.trim().take(120), error = null) }
    fun setOtp(v: String) = _ui.update { it.copy(otp = v.filter(Char::isDigit).take(8), error = null) }
    fun clearMessage() = _ui.update { it.copy(message = null, error = null) }
    fun clearInvite() = _ui.update { it.copy(invite = null) }
    fun changeEmail() = _ui.update { it.copy(codeSent = false, otp = "") }

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        if (_ui.value.busy) return@launch
        _ui.update { it.copy(busy = true, error = null, message = null) }
        try {
            block()
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.message ?: "Something went wrong") }
        } finally {
            _ui.update { it.copy(busy = false) }
        }
    }

    fun requestCode() = launchBusy {
        val email = _ui.value.email
        if (!email.contains('@')) error("Enter a valid email address")
        auth.requestCode(email).getOrThrow()
        _ui.update { it.copy(codeSent = true, message = "We emailed a code to $email") }
    }

    fun verifyCode() = launchBusy {
        val ui = _ui.value
        auth.verifyCode(ui.email, ui.otp).getOrThrow()
        _ui.update { it.copy(codeSent = false, otp = "", message = "Signed in") }
        sync.sync()
    }

    fun signOut() = launchBusy {
        auth.signOut()
        _ui.update { it.copy(message = "Signed out. Your data stays on this device.") }
    }

    fun enableSharing() = launchBusy {
        val id = state.value.team?.household?.id ?: careTeam.defaultHouseholdId()
        sync.enableSharing(id).getOrThrow()
        _ui.update { it.copy(message = "Sharing is on. Invite someone to join.") }
    }

    fun createInvite(role: CaregiverRole, accessUntil: Instant?) = launchBusy {
        val id = state.value.team?.household?.id ?: return@launchBusy
        val invite = sync.createInvite(id, role, accessUntil).getOrThrow()
        _ui.update { it.copy(invite = invite) }
    }

    fun acceptInvite(code: String) = launchBusy {
        val normalized = normalizeInviteCode(code)
        if (normalized.length < 8) error("Codes look like ABCD-EFGH")
        val name = state.value.ownerName.ifBlank { "Helper" }
        val household = sync.acceptInvite(normalized, name).getOrThrow()
        _ui.update { it.copy(message = "You've joined ${household.name}") }
    }

    fun syncNow() = launchBusy {
        sync.sync().getOrThrow()
    }

    fun addLocalPerson(name: String, role: CaregiverRole) = launchBusy {
        val id = state.value.team?.household?.id ?: careTeam.defaultHouseholdId()
        val member = careTeam.addLocalCaregiver(id, name, role)
        _ui.update { it.copy(message = "${member.displayName} can now log on this device") }
    }

    fun switchTo(member: Caregiver) = viewModelScope.launch { careTeam.setActiveCaregiver(member.id) }

    fun updateMember(member: Caregiver) = launchBusy {
        households.upsertMember(member)
        if (state.value.sharingOn) sync.updateMember(member).getOrThrow()
    }

    fun remove(member: Caregiver) = launchBusy {
        if (state.value.sharingOn && member.userId != null) {
            val removed = member.copy(status = MemberStatus.REMOVED)
            households.upsertMember(removed)
            sync.updateMember(removed).getOrThrow()
        } else {
            households.removeMember(member.id)
        }
        _ui.update { it.copy(message = "${member.displayName} was removed") }
    }
}
