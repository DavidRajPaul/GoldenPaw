package com.goldenpaw.ui.pets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldenpaw.core.AppClock
import com.goldenpaw.core.at
import com.goldenpaw.core.newId
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.toLocalDate
import com.goldenpaw.core.toLocalTime
import com.goldenpaw.core.today
import com.goldenpaw.domain.logic.CareInstructions
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.domain.model.Attribution
import com.goldenpaw.domain.model.CarePermission
import com.goldenpaw.domain.model.CareTeam
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.PetSex
import com.goldenpaw.domain.model.Species
import com.goldenpaw.domain.model.VetVisit
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.AppFiles
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.VetVisitRepository
import com.goldenpaw.domain.repository.WeightRepository
import com.goldenpaw.domain.usecase.CareTeamService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// ------------------------------------------------------------------ List

data class PetsState(
    val loading: Boolean = true,
    val active: List<Pet> = emptyList(),
    val archived: List<Pet> = emptyList(),
    val selectedId: String? = null,
    val today: LocalDate? = null,
)

class PetsViewModel(
    pets: PetRepository,
    settings: SettingsRepository,
    clock: AppClock,
) : ViewModel() {
    val state: StateFlow<PetsState> = combine(
        pets.observeActivePets(),
        pets.observeArchivedPets(),
        settings.settings,
    ) { active, archived, s ->
        PetsState(false, active, archived, s.selectedPetId ?: active.firstOrNull()?.id, clock.today())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PetsState())
}

// ------------------------------------------------------------------ Detail

data class PetDetailState(
    val loading: Boolean = true,
    val pet: Pet? = null,
    val meds: List<Medication> = emptyList(),
    val latestWeight: WeightEntry? = null,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val dosesGiven: Int = 0,
    val weightCount: Int = 0,
    val team: CareTeam? = null,
    val visits: List<VetVisit> = emptyList(),
    val today: LocalDate? = null,
    val now: Instant? = null,
) {
    val canEditPet: Boolean get() = now == null || team?.can(CarePermission.EDIT_PETS, now) ?: true
    val canDelete: Boolean get() = now == null || team?.can(CarePermission.DELETE_DATA, now) ?: true
    val upcomingVisits: List<VetVisit> get() = now?.let { n -> visits.filter { it.isUpcoming(n) }.sortedBy { it.at } } ?: emptyList()
    val pastVisits: List<VetVisit> get() = now?.let { n -> visits.filterNot { it.isUpcoming(n) }.sortedByDescending { it.at } } ?: visits
}

class PetDetailViewModel(
    private val pets: PetRepository,
    private val meds: MedicationRepository,
    private val weights: WeightRepository,
    private val doseEvents: DoseEventRepository,
    private val settings: SettingsRepository,
    private val reminders: ReminderGateway,
    private val files: AppFiles,
    private val careTeam: CareTeamService,
    private val vetVisits: VetVisitRepository,
    private val clock: AppClock,
) : ViewModel() {

    private val petId = MutableStateFlow<String?>(null)

    val state: StateFlow<PetDetailState> = petId.filterNotNull().flatMapLatest { id ->
        combine(
            combine(pets.observePet(id), meds.observeForPet(id), weights.observeForPet(id)) { p, m, w -> Triple(p, m, w) },
            settings.settings,
            doseEvents.observeTotalGiven(), // re-trigger the count when doses change
            careTeam.observeTeamForPet(id),
            vetVisits.observeForPet(id),
        ) { (pet, medList, ws), s, _, team, visits ->
            PetDetailState(
                loading = false,
                pet = pet,
                meds = medList,
                latestWeight = ws.maxByOrNull { it.date },
                weightUnit = s.weightUnit,
                dosesGiven = doseEvents.countGivenForPet(id),
                weightCount = ws.size,
                team = team,
                visits = visits,
                today = clock.today(),
                now = clock.now(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PetDetailState())

    fun load(id: String) {
        petId.value = id
    }

    /** Plain-text care guide for a sitter: meds with times, vet contact, notes. */
    fun careGuide(): String? {
        val s = state.value
        val pet = s.pet ?: return null
        return CareInstructions.build(pet, s.meds.filter { it.isActive }, s.latestWeight, s.weightUnit, clock.today(), clock.zone())
    }

    fun archive(onDone: () -> Unit) = viewModelScope.launch {
        val id = petId.value ?: return@launch
        pets.archive(id)
        val current = settings.current()
        if (current.selectedPetId == id) {
            val next = pets.observeActivePets().first().firstOrNull()?.id
            settings.update { it.copy(selectedPetId = next) }
        }
        reminders.rescheduleAll()
        onDone()
    }

    fun restore() = viewModelScope.launch {
        val id = petId.value ?: return@launch
        pets.restore(id)
        reminders.rescheduleAll()
    }

    fun deleteForever(onDone: () -> Unit) = viewModelScope.launch {
        val id = petId.value ?: return@launch
        if (!state.value.canDelete) return@launch
        val pet = pets.getPet(id)
        pets.delete(id)
        files.delete(pet?.photoPath)
        if (settings.current().selectedPetId == id) settings.update { it.copy(selectedPetId = null) }
        reminders.rescheduleAll()
        onDone()
    }

    fun toggleVisitDone(visit: VetVisit) = viewModelScope.launch {
        vetVisits.upsert(visit.copy(completed = !visit.completed))
        reminders.rescheduleAll()
    }
}

// ------------------------------------------------------------------ Editor

data class PetEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val step: Int = 0,
    val id: String = "",
    val householdId: String = "",
    val name: String = "",
    val species: Species = Species.DOG,
    val breed: String = "",
    val sex: PetSex = PetSex.UNKNOWN,
    val birthDate: LocalDate? = null,
    val photoPath: String? = null,
    val photoImporting: Boolean = false,
    val conditions: List<String> = emptyList(),
    val vetName: String = "",
    val vetPhone: String = "",
    val notes: String = "",
    val initialWeight: String = "",
    val weightUnit: WeightUnit = WeightUnit.KG,
    val createdAt: Instant? = null,
    val archivedAt: Instant? = null,
    val saving: Boolean = false,
) {
    val canProceed: Boolean
        get() = when (step) {
            0 -> name.isNotBlank()
            else -> true
        }
}

class PetEditorViewModel(
    private val pets: PetRepository,
    private val files: AppFiles,
    private val settings: SettingsRepository,
    private val weights: WeightRepository,
    private val reminders: ReminderGateway,
    private val careTeam: CareTeamService,
    private val clock: AppClock,
) : ViewModel() {

    private val _state = MutableStateFlow(PetEditorState())
    val state: StateFlow<PetEditorState> = _state.asStateFlow()
    private var loaded = false
    private val importedThisSession = mutableListOf<String>()
    private var saved = false

    fun load(petId: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val unit = settings.current().weightUnit
            val pet = petId?.let { pets.getPet(it) }
            _state.value = if (pet == null) {
                PetEditorState(loading = false, id = newId(), householdId = careTeam.defaultHouseholdId(), weightUnit = unit)
            } else {
                PetEditorState(
                    loading = false,
                    isNew = false,
                    id = pet.id,
                    householdId = pet.householdId,
                    name = pet.name,
                    species = pet.species,
                    breed = pet.breed,
                    sex = pet.sex,
                    birthDate = pet.birthDate,
                    photoPath = pet.photoPath,
                    conditions = pet.conditions,
                    vetName = pet.vetName,
                    vetPhone = pet.vetPhone,
                    notes = pet.notes,
                    weightUnit = unit,
                    createdAt = pet.createdAt,
                    archivedAt = pet.archivedAt,
                )
            }
        }
    }

    fun update(transform: (PetEditorState) -> PetEditorState) = _state.update(transform)

    fun next() = _state.update { it.copy(step = (it.step + 1).coerceAtMost(2)) }
    fun back() = _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }

    fun toggleCondition(condition: String) = _state.update {
        it.copy(conditions = if (condition in it.conditions) it.conditions - condition else it.conditions + condition)
    }

    fun addCustomCondition(condition: String) {
        val c = condition.trim().replace("|", "/")
        if (c.isEmpty()) return
        _state.update { if (c in it.conditions) it else it.copy(conditions = it.conditions + c) }
    }

    fun photoPicking() = _state.update { it.copy(photoImporting = true) }

    fun photoPicked(path: String?) {
        if (path != null) importedThisSession += path
        _state.update { it.copy(photoImporting = false, photoPath = path ?: it.photoPath) }
    }

    fun save(onDone: (String) -> Unit) = viewModelScope.launch {
        val s = _state.value
        if (s.name.isBlank() || s.saving) return@launch
        _state.update { it.copy(saving = true) }
        val now = clock.now()
        val pet = Pet(
            id = s.id,
            householdId = s.householdId.ifBlank { careTeam.defaultHouseholdId() },
            name = s.name.trim(),
            species = s.species,
            breed = s.breed.trim(),
            sex = s.sex,
            birthDate = s.birthDate,
            photoPath = s.photoPath,
            conditions = s.conditions,
            vetName = s.vetName.trim(),
            vetPhone = s.vetPhone.trim(),
            notes = s.notes.trim(),
            createdAt = s.createdAt ?: now,
            archivedAt = s.archivedAt,
        )
        pets.upsert(pet)
        saved = true
        // Clean up photos picked then replaced during this edit.
        importedThisSession.filter { it != s.photoPath }.forEach { files.delete(it) }
        val w = s.initialWeight.toDoubleOrNull()
        if (s.isNew && w != null && w > 0) {
            weights.add(
                WeightEntry(newId(), pet.id, clock.today(), UnitConversion.toKg(w, s.weightUnit), "", now, careTeam.attributionFor(pet.id)),
            )
        }
        if (s.isNew) settings.update { it.copy(selectedPetId = pet.id) }
        reminders.rescheduleAll()
        onDone(pet.id)
    }

    override fun onCleared() {
        // Discard photos imported for a pet that was never saved.
        if (!saved) importedThisSession.forEach { files.delete(it) }
        super.onCleared()
    }
}

// ------------------------------------------------------------------ Vet visit editor

data class VetVisitForm(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val id: String = "",
    val petId: String = "",
    val petName: String = "",
    val title: String = "Check-up",
    val clinic: String = "",
    val date: LocalDate? = null,
    val time: LocalTime = LocalTime(10, 0),
    val notes: String = "",
    val completed: Boolean = false,
    val createdAt: Instant? = null,
    val loggedBy: Attribution = Attribution.NONE,
    val saving: Boolean = false,
) {
    val valid: Boolean get() = title.isNotBlank() && date != null
}

class VetVisitEditorViewModel(
    private val visits: VetVisitRepository,
    private val pets: PetRepository,
    private val reminders: ReminderGateway,
    private val careTeam: CareTeamService,
    private val clock: AppClock,
) : ViewModel() {
    private val _state = MutableStateFlow(VetVisitForm())
    val state: StateFlow<VetVisitForm> = _state.asStateFlow()
    private var loaded = false

    fun load(petId: String, visitId: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val pet = pets.getPet(petId)
            val visit = visitId?.let { visits.get(it) }
            val zone = clock.zone()
            _state.value = if (visit == null) {
                VetVisitForm(
                    loading = false,
                    id = newId(),
                    petId = petId,
                    petName = pet?.name.orEmpty(),
                    clinic = pet?.vetName.orEmpty(),
                    date = clock.today().plusDays(7),
                )
            } else {
                VetVisitForm(
                    loading = false,
                    isNew = false,
                    id = visit.id,
                    petId = visit.petId,
                    petName = pet?.name.orEmpty(),
                    title = visit.title,
                    clinic = visit.clinic,
                    date = visit.at.toLocalDate(zone),
                    time = visit.at.toLocalTime(zone),
                    notes = visit.notes,
                    completed = visit.completed,
                    createdAt = visit.createdAt,
                    loggedBy = visit.loggedBy,
                )
            }
        }
    }

    fun update(transform: (VetVisitForm) -> VetVisitForm) = _state.update(transform)

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val f = _state.value
        val date = f.date ?: return@launch
        if (!f.valid || f.saving) return@launch
        _state.update { it.copy(saving = true) }
        val who = if (f.isNew || f.loggedBy.name.isBlank()) careTeam.attributionFor(f.petId) else f.loggedBy
        visits.upsert(
            VetVisit(
                id = f.id,
                petId = f.petId,
                title = f.title.trim(),
                clinic = f.clinic.trim(),
                at = date.at(f.time, clock.zone()),
                notes = f.notes.trim(),
                completed = f.completed,
                createdAt = f.createdAt ?: clock.now(),
                loggedBy = who,
            ),
        )
        reminders.rescheduleAll()
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        visits.delete(_state.value.id)
        reminders.rescheduleAll()
        onDone()
    }
}
