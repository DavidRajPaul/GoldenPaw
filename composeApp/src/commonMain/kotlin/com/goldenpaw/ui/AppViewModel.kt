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
import com.goldenpaw.data.remote.PushRegistrationService
import com.goldenpaw.data.ads.PromotionsService
import com.goldenpaw.domain.model.PricingCatalog
import com.goldenpaw.domain.model.PushMessage
import com.goldenpaw.domain.repository.CareSyncRepository
import com.goldenpaw.domain.repository.PricingRepository
import com.goldenpaw.domain.repository.PushPlatform
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
data class LaunchRequest(val open: String, val petId: String?, val nonce: Long = 0L, val offerId: String? = null) {
    companion object {
        const val OPEN_TODAY = "today"
        const val OPEN_CHECKIN = "checkin"
        const val OPEN_MEDS = "meds"
        const val OPEN_QUICK_LOG = "quicklog"
        const val OPEN_SYMPTOM = "symptom"
        const val OPEN_WEEKLY = "weekly"
        const val OPEN_CARE_TEAM = "careteam"
        /** Push offers: opens the paywall, on [offerId] when given. */
        const val OPEN_PAYWALL = PushMessage.OPEN_PAYWALL
        const val OPEN_SETTINGS = PushMessage.OPEN_SETTINGS

        /** Where a tapped push notification should take the owner. */
        fun from(message: PushMessage, nonce: Long): LaunchRequest =
            LaunchRequest(open = message.open, petId = message.petId, nonce = nonce, offerId = message.offerId)
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
    private val pricing: PricingRepository,
    private val push: PushRegistrationService,
    private val pushPlatform: PushPlatform,
    private val promotions: PromotionsService,
) : ViewModel() {

    private val ready = MutableStateFlow(false)

    /** Plans, prices and offers for the paywall (cached; refreshed from the backend on launch). */
    val pricingCatalog: StateFlow<PricingCatalog> = pricing.catalog

    init {
        viewModelScope.launch {
            initializer.initialize()
            pricing.restore()
            launch { pricing.refresh() }
            // Calm promotions: count this launch as a session and load the backend policy.
            promotions.restore()
            promotions.startSession()
            launch { promotions.refresh() }
            ready.value = true
            runCatching { reminders.rescheduleAll() }
            // Quietly sync shared households on launch.
            if (sync.isAvailable && households.households().any { it.cloudEnabled }) runCatching { sync.sync() }
        }
        // Push: keep the device registration current and ask the OS for a token.
        if (push.isAvailable && pushPlatform.isSupported) {
            push.startIn(viewModelScope)
            pushPlatform.requestToken()
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

    /** The welcome tour ends: remember it, and record the owner's choice about offer notifications. */
    fun finishTour(offerNotifications: Boolean) = viewModelScope.launch {
        settingsRepo.update { it.copy(tourDone = true, offerNotifications = offerNotifications) }
        if (offerNotifications) pushPlatform.requestToken()
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
