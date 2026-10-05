package com.goldenpaw.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldenpaw.domain.logic.InsightEngine
import com.goldenpaw.domain.logic.QualityOfLifeCalculator
import com.goldenpaw.domain.logic.SymptomPatternDetector
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.Insight
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.QualityOfLifeScore
import com.goldenpaw.domain.model.ScheduleType
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.WeightRepository
import com.goldenpaw.domain.usecase.LogDoseUseCase
import com.goldenpaw.domain.usecase.ObserveDoseSlotsUseCase
import com.goldenpaw.domain.usecase.UndoDoseUseCase
import com.goldenpaw.domain.usecase.newId
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class TodayState(
    val loading: Boolean = true,
    val ownerName: String = "",
    val pets: List<Pet> = emptyList(),
    val pet: Pet? = null,
    val today: LocalDate = LocalDate.now(),
    val now: Instant = Instant.now(),
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
) {
    val givenToday: Int get() = todaySlots.count { it.event?.status == DoseStatus.GIVEN }
}

sealed interface TodayEvent {
    data class DoseLogged(val petName: String, val medName: String, val eventId: String, val given: Boolean) : TodayEvent
    data class Message(val text: String) : TodayEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
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
) : ViewModel() {

    private val milestones = listOf(10, 50, 100, 250, 500, 1000, 2500, 5000)

    private val ticker: Flow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(30_000)
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val dateFlow = ticker.map { LocalDate.now() }.distinctUntilChanged()

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
                val petContent = combine(
                    observeSlots(pet.id, date, date.plusDays(7), ticker),
                    checkIns.observeForPet(pet.id),
                    weights.observeForPet(pet.id),
                    symptoms.observeForPet(pet.id),
                    medications.observeActiveForPet(pet.id),
                ) { slots, cis, ws, syms, meds ->
                    val todaySlots = slots.filter { it.scheduledAt.atZone(ZoneId.systemDefault()).toLocalDate() == date }
                    val upcoming = slots.filter { it.scheduledAt.atZone(ZoneId.systemDefault()).toLocalDate().isAfter(date) }
                        .groupBy { it.scheduledAt.atZone(ZoneId.systemDefault()).toLocalDate() }
                        .toList()
                        .sortedBy { it.first }
                    val latestCheckIn = cis.maxByOrNull { it.date }
                    val qol = latestCheckIn?.let { QualityOfLifeCalculator.score(it, cis) }
                    val patterns = SymptomPatternDetector.detect(syms, date)
                    TodayState(
                        loading = false,
                        ownerName = s.ownerName,
                        pets = pets,
                        pet = pet,
                        today = date,
                        now = Instant.now(),
                        todaySlots = todaySlots,
                        upcoming = upcoming,
                        asNeeded = meds.filter { it.schedule.type == ScheduleType.AS_NEEDED },
                        refills = meds.filter { it.needsRefill() },
                        todayCheckIn = cis.firstOrNull { it.date == date },
                        qol = qol,
                        qolDate = latestCheckIn?.date,
                        insights = InsightEngine.build(date, cis, ws, patterns, s.weightUnit),
                        latestWeight = ws.maxByOrNull { it.date },
                        weightUnit = s.weightUnit,
                        isPlus = s.isPlus,
                    )
                }
                combine(petContent, doseEvents.observeTotalGiven()) { content, total ->
                    val reached = milestones.lastOrNull { total >= it }
                    content.copy(milestone = reached?.takeIf { it > s.lastMilestoneSeen })
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    fun give(slot: DoseSlot) = log(slot.medication, slot.scheduledAt, DoseStatus.GIVEN)

    fun skip(slot: DoseSlot) = log(slot.medication, slot.scheduledAt, DoseStatus.SKIPPED)

    /** As-needed doses get a synthetic slot at "now" (minute precision). */
    fun giveAsNeeded(med: Medication) =
        log(med, Instant.now().truncatedTo(ChronoUnit.MINUTES), DoseStatus.GIVEN)

    private fun log(med: Medication, scheduledAt: Instant, status: DoseStatus) = viewModelScope.launch {
        val event = logDose(med.id, scheduledAt, status) ?: return@launch
        val petName = state.value.pet?.name.orEmpty()
        _events.send(TodayEvent.DoseLogged(petName, med.name, event.id, status == DoseStatus.GIVEN))
    }

    fun undo(slot: DoseSlot) = viewModelScope.launch {
        slot.event?.let { undoDose(it) }
    }

    fun undoById(eventId: String) = viewModelScope.launch {
        val slot = state.value.todaySlots.firstOrNull { it.event?.id == eventId }
        val event = slot?.event
        if (event != null) {
            undoDose(event)
        } else {
            // As-needed or slot no longer visible: look it up by scanning today's events.
            val pet = state.value.pet ?: return@launch
            val zone = ZoneId.systemDefault()
            val from = state.value.today.atStartOfDay(zone).toInstant()
            val to = state.value.today.plusDays(1).atStartOfDay(zone).toInstant()
            doseEvents.forPetBetween(pet.id, from, to).firstOrNull { it.id == eventId }?.let { undoDose(it) }
        }
    }

    fun logWeight(kg: Double, date: LocalDate, notes: String) = viewModelScope.launch {
        val pet = state.value.pet ?: return@launch
        weights.add(WeightEntry(newId(), pet.id, date, kg, notes, Instant.now()))
        _events.send(TodayEvent.Message("Weight saved"))
    }

    fun dismissMilestone() = viewModelScope.launch {
        val m = state.value.milestone ?: return@launch
        settingsRepo.update { it.copy(lastMilestoneSeen = m) }
    }

    fun selectPet(id: String) = viewModelScope.launch { settingsRepo.update { it.copy(selectedPetId = id) } }

    fun greeting(): String {
        val hour = LocalTime.now().hour
        return when {
            hour < 5 -> "Good evening"
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
    }
}
