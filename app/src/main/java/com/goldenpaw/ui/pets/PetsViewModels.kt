package com.goldenpaw.ui.pets

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.PetSex
import com.goldenpaw.domain.model.Species
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.WeightRepository
import com.goldenpaw.domain.usecase.ReminderGateway
import com.goldenpaw.domain.usecase.newId
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.platform.media.ImageStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.Instant
import java.time.LocalDate

// ------------------------------------------------------------------ List

data class PetsState(
    val loading: Boolean = true,
    val active: List<Pet> = emptyList(),
    val archived: List<Pet> = emptyList(),
    val selectedId: String? = null,
)

class PetsViewModel(
    pets: PetRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<PetsState> = combine(
        pets.observeActivePets(),
        pets.observeArchivedPets(),
        settings.settings,
    ) { active, archived, s ->
        PetsState(false, active, archived, s.selectedPetId ?: active.firstOrNull()?.id)
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
)

@OptIn(ExperimentalCoroutinesApi::class)
class PetDetailViewModel(
    private val pets: PetRepository,
    private val meds: MedicationRepository,
    private val weights: WeightRepository,
    private val doseEvents: DoseEventRepository,
    private val settings: SettingsRepository,
    private val reminders: ReminderGateway,
    private val images: ImageStorage,
) : ViewModel() {

    private val petId = MutableStateFlow<String?>(null)

    val state: StateFlow<PetDetailState> = petId.filterNotNull().flatMapLatest { id ->
        combine(
            pets.observePet(id),
            meds.observeForPet(id),
            weights.observeForPet(id),
            settings.settings,
            doseEvents.observeTotalGiven(), // re-trigger count when doses change
        ) { pet, medList, ws, s, _ ->
            PetDetailState(
                loading = false,
                pet = pet,
                meds = medList,
                latestWeight = ws.maxByOrNull { it.date },
                weightUnit = s.weightUnit,
                dosesGiven = doseEvents.countGivenForPet(id),
                weightCount = ws.size,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PetDetailState())

    fun load(id: String) {
        petId.value = id
    }

    fun archive(onDone: () -> Unit) = viewModelScope.launch {
        val id = petId.value ?: return@launch
        pets.archive(id)
        val s = settings.current()
        if (s.selectedPetId == id) {
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
        val pet = pets.getPet(id)
        pets.delete(id)
        images.delete(pet?.photoPath)
        val s = settings.current()
        if (s.selectedPetId == id) settings.update { it.copy(selectedPetId = null) }
        reminders.rescheduleAll()
        onDone()
    }
}

// ------------------------------------------------------------------ Editor

data class PetEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val step: Int = 0,
    val id: String = newId(),
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
    val createdAt: Instant = Instant.now(),
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
    private val images: ImageStorage,
    private val settings: SettingsRepository,
    private val weights: WeightRepository,
    private val reminders: ReminderGateway,
) : ViewModel() {

    private val _state = MutableStateFlow(PetEditorState())
    val state: StateFlow<PetEditorState> = _state.asStateFlow()
    private var loaded = false
    private val importedThisSession = mutableListOf<String>()

    fun load(petId: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val unit = settings.current().weightUnit
            val pet = petId?.let { pets.getPet(it) }
            _state.value = if (pet == null) {
                PetEditorState(loading = false, weightUnit = unit)
            } else {
                PetEditorState(
                    loading = false,
                    isNew = false,
                    id = pet.id,
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

    fun importPhoto(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(photoImporting = true) }
        val path = images.import(uri)
        if (path != null) importedThisSession += path
        _state.update { it.copy(photoImporting = false, photoPath = path ?: it.photoPath) }
    }

    fun save(onDone: (String) -> Unit) = viewModelScope.launch {
        val s = _state.value
        if (s.name.isBlank() || s.saving) return@launch
        _state.update { it.copy(saving = true) }
        val pet = Pet(
            id = s.id,
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
            createdAt = s.createdAt,
            archivedAt = s.archivedAt,
        )
        pets.upsert(pet)
        // Clean up photos picked then replaced during this edit
        importedThisSession.filter { it != s.photoPath }.forEach { images.delete(it) }
        val w = s.initialWeight.toDoubleOrNull()
        if (s.isNew && w != null && w > 0) {
            weights.add(WeightEntry(newId(), pet.id, LocalDate.now(), UnitConversion.toKg(w, s.weightUnit), "", Instant.now()))
        }
        if (s.isNew) settings.update { it.copy(selectedPetId = pet.id) }
        reminders.rescheduleAll()
        onDone(pet.id)
    }

    override fun onCleared() {
        // Discard photos imported for a pet that was never saved.
        if (_state.value.saving.not()) {
            importedThisSession.forEach { images.delete(it) }
        }
        super.onCleared()
    }
}
