package com.goldenpaw.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.UserSettings
import com.goldenpaw.domain.usecase.ReminderGateway
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/** Resolves a ViewModel from Koin, scoped to the current ViewModelStoreOwner (nav entry / activity). */
@Composable
inline fun <reified T : ViewModel> koinVm(key: String? = null): T =
    viewModel(key = key) { GlobalContext.get().get<T>() }

data class AppState(
    val loaded: Boolean = false,
    val settings: UserSettings = UserSettings(),
    val pets: List<Pet> = emptyList(),
) {
    /** The pet Today/Journal/Insights are focused on. Falls back to the first active pet. */
    val selectedPet: Pet?
        get() = pets.firstOrNull { it.id == settings.selectedPetId } ?: pets.firstOrNull()
}

class AppViewModel(
    private val settingsRepo: SettingsRepository,
    private val pets: PetRepository,
    private val reminders: ReminderGateway,
) : ViewModel() {

    val state: StateFlow<AppState> = combine(settingsRepo.settings, pets.observeActivePets()) { s, p ->
        AppState(loaded = true, settings = s, pets = p)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppState())

    fun selectPet(id: String) = viewModelScope.launch {
        settingsRepo.update { it.copy(selectedPetId = id) }
    }

    fun refreshReminders() = viewModelScope.launch { runCatching { reminders.rescheduleAll() } }

    /** Free tier = 1 active pet. Returns true when another pet may be added. */
    fun canAddPet(): Boolean = state.value.settings.isPlus || state.value.pets.isEmpty()

    fun recordPaywallView() = viewModelScope.launch {
        settingsRepo.update { it.copy(paywallViews = it.paywallViews + 1) }
    }

    /** Fake-door: records intent and unlocks Plus locally (Play Billing not wired in the beta). */
    fun startTrial() = viewModelScope.launch {
        settingsRepo.update { it.copy(isPlus = true, paywallTrialTaps = it.paywallTrialTaps + 1) }
    }
}
