package com.goldenpaw.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldenpaw.core.AppClock
import com.goldenpaw.core.localTime
import com.goldenpaw.core.newId
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.startOfDay
import com.goldenpaw.core.startOfWeek
import com.goldenpaw.core.toLocalDate
import com.goldenpaw.core.today
import com.goldenpaw.core.truncatedToMinute
import com.goldenpaw.domain.logic.InsightEngine
import com.goldenpaw.domain.logic.QualityOfLifeCalculator
import com.goldenpaw.domain.logic.SymptomPatternDetector
import com.goldenpaw.domain.model.CareTeam
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.Insight
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.QualityOfLifeScore
import com.goldenpaw.domain.model.ScheduleType
import com.goldenpaw.domain.model.VetVisit
import com.goldenpaw.domain.model.WeeklySummary
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.SummaryRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.VetVisitRepository
import com.goldenpaw.domain.repository.WeightRepository
import com.goldenpaw.domain.usecase.CareTeamService
import com.goldenpaw.domain.usecase.LogDoseResult
import com.goldenpaw.domain.usecase.LogDoseUseCase
import com.goldenpaw.domain.usecase.ObserveDoseSlotsUseCase
import com.goldenpaw.domain.usecase.UndoDoseUseCase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

data class TodayState(
    val loading: Boolean = true,
    val ownerName: String = "",
    val pets: List<Pet> = emptyList(),
    val pet: Pet? = null,
    val today: LocalDate? = null,
    val now: Instant = Instant.fromEpochMilliseconds(0),
    val todaySlots: List<DoseSlot> = emptyList(),
    val upcoming: List<Pair<LocalDate, List<DoseSlot>>> = emptyList(),
    val asNeeded: List<Medication> = emptyList(),
    val refills: List<Medication> = emptyList(),
    val todayCheckIn: CheckIn? = null,
    val qol: QualityOfLifeScore? = null,
    val qolDate: LocalDate? = null,
    val insights: List<Insight> = emptyList(),
    val latestWeight: WeightEntry? = null,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val milestone: Int? = null,
    val isPlus: Boolean = false,
    val team: CareTeam? = null,
    val upcomingVisit: VetVisit? = null,
    val weekSummary: WeeklySummary? = null,
) {
    val givenToday: Int get() = todaySlots.count { it.event?.status == DoseStatus.GIVEN }
    val doubleDoses: List<DoseSlot> get() = todaySlots.filter { it.isPossibleDoubleDose }
    val hasTeam: Boolean get() = (team?.activeMembers?.size ?: 0) > 1
}

sealed interface TodayEvent {
    data class DoseLogged(val petName: String, val medName: String, val eventId: String, val given: Boolean) : TodayEvent
    data class AlreadyGiven(val slot: DoseSlot, val existing: DoseEvent) : TodayEvent
    data class Message(val text: String) : TodayEvent
}

class TodayViewModel(
    private val settingsRepo: SettingsRepository,
    private val petRepo: PetRepository,
    private val observeSlots: ObserveDoseSlotsUseCase,
    private val logDose: LogDoseUseCase,
    private val undoDose: UndoDoseUseCase,
    private val checkIns: CheckInRepository,
    private val weights: WeightRepository,
    private val symptoms: SymptomRepository,
    private val doseEvents: DoseEventRepository,
    private val medications: MedicationRepository,
    private val careTeam: CareTeamService,
    private val vetVisits: VetVisitRepository,
    private val summaries: SummaryRepository,
    private val clock: AppClock,
) : ViewModel() {

    private val milestones = listOf(10, 50, 100, 250, 500, 1000, 2500, 5000)

    private val ticker: Flow<Instant> = flow {
        while (true) {
            emit(clock.now())
            delay(30_000)
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val dateFlow = ticker.map { clock.today() }.distinctUntilChanged()

    private val _events = Channel<TodayEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<TodayState> = combine(
        settingsRepo.settings,
        petRepo.observeActivePets(),
        dateFlow,
    ) { s, pets, date -> Triple(s, pets, date) }
        .flatMapLatest { (s, pets, date) ->
            val pet = pets.firstOrNull { it.id == s.selectedPetId } ?: pets.firstOrNull()
            if (pet == null) {
                flowOf(TodayState(loading = false, ownerName = s.ownerName, pets = pets, today = date))
            } else {
                val zone = clock.zone()
                val logs = combine(
                    observeSlots(pet.id, date, date.plusDays(7), ticker),
                    checkIns.observeForPet(pet.id),
                    weights.observeForPet(pet.id),
                    symptoms.observeForPet(pet.id),
                    medications.observeActiveForPet(pet.id),
                ) { slots, cis, ws, syms, meds -> Logs(slots, cis, ws, syms, meds) }
                val extras = combine(
                    careTeam.observeTeamForPet(pet.id),
                    vetVisits.observeUpcoming(date.startOfDay(zone)),
                    summaries.observeForPet(pet.id),
                    doseEvents.observeTotalGiven(),
                ) { team, visits, sums, total -> Extras(team, visits.firstOrNull { it.petId == pet.id }, sums, total) }

                combine(logs, extras) { l, x ->
                    val todaySlots = l.slots.filter { it.scheduledAt.toLocalDate(zone) == date }
                    val upcoming = l.slots.filter { it.scheduledAt.toLocalDate(zone) > date }
                        .groupBy { it.scheduledAt.toLocalDate(zone) }
                        .toList()
                        .sortedBy { it.first }
                    val latestCheckIn = l.checkIns.maxByOrNull { it.date }
                    val reached = milestones.lastOrNull { x.totalGiven >= it }
                    TodayState(
                        loading = false,
                        ownerName = s.ownerName,
                        pets = pets,
                        pet = pet,
                        today = date,
                        now = clock.now(),
                        todaySlots = todaySlots,
                        upcoming = upcoming,
                        asNeeded = l.meds.filter { it.schedule.type == ScheduleType.AS_NEEDED },
                        refills = l.meds.filter { it.needsRefill() },
                        todayCheckIn = l.checkIns.firstOrNull { it.date == date },
                        qol = latestCheckIn?.let { QualityOfLifeCalculator.score(it, l.checkIns) },
                        qolDate = latestCheckIn?.date,
                        insights = InsightEngine.build(date, l.checkIns, l.weights, SymptomPatternDetector.detect(l.symptoms, date), s.weightUnit),
                        latestWeight = l.weights.maxByOrNull { it.date },
                        weightUnit = s.weightUnit,
                        milestone = reached?.takeIf { it > s.lastMilestoneSeen && !s.gamificationEnabled },
                        isPlus = s.isPlus,
                        team = x.team,
                        upcomingVisit = x.visit,
                        weekSummary = x.summaries.firstOrNull { it.weekStart == date.startOfWeek() },
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    fun give(slot: DoseSlot) = log(slot, slot.medication, slot.scheduledAt, DoseStatus.GIVEN)

    fun giveAnyway(slot: DoseSlot) = log(slot, slot.medication, slot.scheduledAt, DoseStatus.GIVEN, override = true)

    fun skip(slot: DoseSlot) = log(slot, slot.medication, slot.scheduledAt, DoseStatus.SKIPPED)

    /** As-needed doses get a synthetic slot at "now" (minute precision). */
    fun giveAsNeeded(med: Medication) = log(null, med, clock.now().truncatedToMinute(), DoseStatus.GIVEN)

    private fun log(slot: DoseSlot?, med: Medication, scheduledAt: Instant, status: DoseStatus, override: Boolean = false) =
        viewModelScope.launch {
            when (val result = logDose(med.id, scheduledAt, status, overrideOtherCaregiver = override)) {
                is LogDoseResult.Logged -> {
                    val petName = state.value.pet?.name.orEmpty()
                    _events.send(TodayEvent.DoseLogged(petName, med.name, result.event.id, status == DoseStatus.GIVEN))
                }
                is LogDoseResult.AlreadyGiven -> if (slot != null) _events.send(TodayEvent.AlreadyGiven(slot, result.existing))
                LogDoseResult.NotAllowed -> _events.send(TodayEvent.Message("Your access to log care has ended. Ask the owner to extend it."))
                LogDoseResult.NotFound -> Unit
            }
        }

    fun undo(slot: DoseSlot) = viewModelScope.launch {
        slot.event?.let { undoDose(it) }
    }

    fun undoById(eventId: String) = viewModelScope.launch {
        val event = state.value.todaySlots.firstOrNull { it.event?.id == eventId }?.event
        if (event != null) {
            undoDose(event)
        } else {
            // As-needed or slot no longer visible: look it up in today's events.
            val pet = state.value.pet ?: return@launch
            val zone = clock.zone()
            val today = clock.today()
            doseEvents.forPetBetween(pet.id, today.startOfDay(zone), today.plusDays(1).startOfDay(zone))
                .firstOrNull { it.id == eventId }?.let { undoDose(it) }
        }
    }

    fun logWeight(kg: Double, date: LocalDate, notes: String) = viewModelScope.launch {
        val pet = state.value.pet ?: return@launch
        val who = careTeam.attributionFor(pet.id)
        weights.add(WeightEntry(newId(), pet.id, date, kg, notes, clock.now(), who))
        _events.send(TodayEvent.Message("Weight saved"))
    }

    fun dismissMilestone() = viewModelScope.launch {
        val m = state.value.milestone ?: return@launch
        settingsRepo.update { it.copy(lastMilestoneSeen = m) }
    }

    fun selectPet(id: String) = viewModelScope.launch { settingsRepo.update { it.copy(selectedPetId = id) } }

    fun switchCaregiver(id: String) = viewModelScope.launch { careTeam.setActiveCaregiver(id) }

    fun greeting(): String {
        val hour = clock.localTime().hour
        return when {
            hour < 5 -> "Good evening"
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    private data class Logs(
        val slots: List<DoseSlot>,
        val checkIns: List<CheckIn>,
        val weights: List<WeightEntry>,
        val symptoms: List<com.goldenpaw.domain.model.SymptomEntry>,
        val meds: List<Medication>,
    )

    private data class Extras(
        val team: CareTeam?,
        val visit: VetVisit?,
        val summaries: List<WeeklySummary>,
        val totalGiven: Int,
    )
}
