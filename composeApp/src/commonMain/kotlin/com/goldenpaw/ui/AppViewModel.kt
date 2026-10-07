package com.goldenpaw.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldenpaw.core.AppClock
import com.goldenpaw.core.minusDays
import com.goldenpaw.core.today
import com.goldenpaw.di.AppInitializer
import com.goldenpaw.domain.model.Badge
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.GamificationState
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.repository.CareSyncRepository
import com.goldenpaw.domain.repository.HouseholdRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.UserSettings
import com.goldenpaw.domain.usecase.ObserveDoseSlotsUseCase
import com.goldenpaw.domain.usecase.ObserveGamificationUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/** Deep-link request from a notification, widget or app shortcut. */
data class LaunchRequest(val open: String, val petId: String?, val nonce: Long = 0L) {
    companion object {
        const val OPEN_TODAY = "today"
        const val OPEN_CHECKIN = "checkin"
        const val OPEN_MEDS = "meds"
        const val OPEN_QUICK_LOG = "quicklog"
        const val OPEN_SYMPTOM = "symptom"
        const val OPEN_WEEKLY = "weekly"
        const val OPEN_CARE_TEAM = "careteam"
    }
}

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
    private val initializer: AppInitializer,
    private val sync: CareSyncRepository,
    private val households: HouseholdRepository,
) : ViewModel() {

    private val ready = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            initializer.initialize()
            ready.value = true
            runCatching { reminders.rescheduleAll() }
            // Quietly sync shared households on launch.
            if (sync.isAvailable && households.households().any { it.cloudEnabled }) runCatching { sync.sync() }
        }
    }

    val state: StateFlow<AppState> = combine(ready, settingsRepo.settings, pets.observeActivePets()) { r, s, p ->
        AppState(loaded = r, settings = s, pets = p)
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

    /** Fake-door: records intent and unlocks Plus locally (store billing not wired in the beta). */
    fun startTrial() = viewModelScope.launch {
        settingsRepo.update { it.copy(isPlus = true, paywallTrialTaps = it.paywallTrialTaps + 1) }
    }

    fun syncNow() = viewModelScope.launch { if (sync.isAvailable) runCatching { sync.sync() } }
}

/**
 * App-scoped gamification state (shared by Today, Achievements and the badge celebration overlay).
 */
class GamificationViewModel(
    private val settings: SettingsRepository,
    private val observeSlots: ObserveDoseSlotsUseCase,
    private val observeGamification: ObserveGamificationUseCase,
    private val clock: AppClock,
) : ViewModel() {

    private val ticker: Flow<Instant> = flow {
        while (true) {
            emit(clock.now())
            delay(60_000)
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val slots: Flow<List<DoseSlot>> = settings.settings
        .map { it.selectedPetId }
        .distinctUntilChanged()
        .flatMapLatest { petId ->
            if (petId == null) flowOf(emptyList())
            else {
                val today = clock.today()
                observeSlots(petId, today.minusDays(7), today, ticker)
            }
        }

    private val celebrated = mutableSetOf<String>()
    private val _celebrations = MutableStateFlow<List<Badge>>(emptyList())
    /** Badges to celebrate now (shown by the root overlay, then recorded). */
    val celebrations: StateFlow<List<Badge>> = _celebrations.asStateFlow()

    val state: StateFlow<GamificationState?> = observeGamification(slots, ticker)
        .onEach { s ->
            val fresh = s?.newlyEarned.orEmpty().filter { it.id !in celebrated }
            if (fresh.isNotEmpty()) {
                celebrated += fresh.map { it.id }
                _celebrations.value = _celebrations.value + fresh
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun dismissCelebrations() = viewModelScope.launch {
        val shown = _celebrations.value
        _celebrations.value = emptyList()
        observeGamification.recordUnlocked(shown)
        observeGamification.markSeen(shown)
    }

    fun unlockVetReady() = viewModelScope.launch { observeGamification.unlockVetReady() }
}
