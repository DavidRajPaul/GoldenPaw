package com.goldenpaw.ui.meds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.MedicationSchedule
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.ScheduleType
import com.goldenpaw.domain.model.TaperStep
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.usecase.ReminderGateway
import com.goldenpaw.domain.usecase.newId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// ------------------------------------------------------------------ List

data class MedsState(
    val loading: Boolean = true,
    val pet: Pet? = null,
    val active: List<Medication> = emptyList(),
    val inactive: List<Medication> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class MedsViewModel(
    private val meds: MedicationRepository,
    private val pets: PetRepository,
    private val reminders: ReminderGateway,
) : ViewModel() {
    private val petId = MutableStateFlow<String?>(null)

    val state: StateFlow<MedsState> = petId.filterNotNull().flatMapLatest { id ->
        combine(pets.observePet(id), meds.observeForPet(id)) { pet, list ->
            MedsState(false, pet, list.filter { it.isActive }, list.filter { !it.isActive })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MedsState())

    fun load(id: String) {
        petId.value = id
    }

    fun setActive(med: Medication, active: Boolean) = viewModelScope.launch {
        meds.setActive(med.id, active)
        reminders.rescheduleAll()
    }

    fun delete(med: Medication) = viewModelScope.launch {
        meds.delete(med.id)
        reminders.rescheduleAll()
    }

    fun refill(med: Medication, doses: Double) = viewModelScope.launch {
        meds.upsert(med.copy(supplyRemaining = doses))
    }
}

// ------------------------------------------------------------------ Editor

data class MedForm(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val step: Int = 0,
    val id: String = newId(),
    val petId: String = "",
    val petName: String = "",
    val name: String = "",
    val dosage: String = "",
    val unit: String = "mg",
    val route: String = "Oral",
    val withFood: Boolean = false,
    val type: ScheduleType = ScheduleType.DAILY,
    val times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
    val intervalDays: Int = 2,
    val weekdays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
    val taperSteps: List<TaperStep> = emptyList(),
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,
    val reason: String = "",
    val prescribedBy: String = "",
    val supply: String = "",
    val refillAlertDays: Int = 5,
    val notes: String = "",
    val isActive: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val saving: Boolean = false,
) {
    val stepValid: Boolean
        get() = when (step) {
            0 -> name.isNotBlank()
            1 -> when (type) {
                ScheduleType.AS_NEEDED -> true
                ScheduleType.WEEKDAYS -> times.isNotEmpty() && weekdays.isNotEmpty()
                ScheduleType.TAPERING -> times.isNotEmpty() && taperSteps.isNotEmpty()
                else -> times.isNotEmpty()
            }
            else -> endDate == null || !endDate.isBefore(startDate)
        }

    fun toMedication(): Medication = Medication(
        id = id,
        petId = petId,
        name = name.trim(),
        dosage = dosage.trim(),
        unit = unit,
        route = route,
        reason = reason.trim(),
        prescribedBy = prescribedBy.trim(),
        withFood = withFood,
        schedule = MedicationSchedule(
            type = type,
            times = if (type == ScheduleType.AS_NEEDED) emptyList() else times.distinct().sorted(),
            intervalDays = intervalDays.coerceIn(1, 60),
            weekdays = weekdays,
            taperSteps = taperSteps.sortedBy { it.fromEpochDay },
            startDate = startDate,
            endDate = endDate,
        ),
        supplyRemaining = supply.toDoubleOrNull(),
        refillAlertDays = refillAlertDays,
        notes = notes.trim(),
        isActive = isActive,
        createdAt = createdAt,
    )

    /** First few upcoming doses, for a "here's what will happen" preview. */
    fun preview(count: Int = 5): List<Pair<Instant, String>> {
        val med = toMedication()
        val zone = ZoneId.systemDefault()
        val result = mutableListOf<Pair<Instant, String>>()
        var date = maxOf(LocalDate.now(), startDate)
        var guard = 0
        val now = Instant.now()
        while (result.size < count && guard < 120) {
            ScheduleEngine.scheduledInstants(med, date, zone).filter { it.isAfter(now) }.forEach {
                if (result.size < count) result += it to med.dosageOn(date)
            }
            date = date.plusDays(1)
            guard++
        }
        return result
    }
}

class MedicationEditorViewModel(
    private val meds: MedicationRepository,
    private val pets: PetRepository,
    private val reminders: ReminderGateway,
    @Suppress("unused") private val settings: com.goldenpaw.domain.repository.SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MedForm())
    val state: StateFlow<MedForm> = _state.asStateFlow()
    private var loaded = false

    fun load(petId: String, medId: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val pet = pets.getPet(petId)
            val med = medId?.let { meds.get(it) }
            _state.value = if (med == null) {
                MedForm(loading = false, petId = petId, petName = pet?.name.orEmpty())
            } else {
                MedForm(
                    loading = false,
                    isNew = false,
                    id = med.id,
                    petId = med.petId,
                    petName = pet?.name.orEmpty(),
                    name = med.name,
                    dosage = med.dosage,
                    unit = med.unit,
                    route = med.route,
                    withFood = med.withFood,
                    type = med.schedule.type,
                    times = med.schedule.times.ifEmpty { listOf(LocalTime.of(8, 0)) },
                    intervalDays = med.schedule.intervalDays,
                    weekdays = med.schedule.weekdays.ifEmpty { setOf(DayOfWeek.MONDAY) },
                    taperSteps = med.schedule.taperSteps,
                    startDate = med.schedule.startDate,
                    endDate = med.schedule.endDate,
                    reason = med.reason,
                    prescribedBy = med.prescribedBy,
                    supply = med.supplyRemaining?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty(),
                    refillAlertDays = med.refillAlertDays,
                    notes = med.notes,
                    isActive = med.isActive,
                    createdAt = med.createdAt,
                )
            }
        }
    }

    fun update(transform: (MedForm) -> MedForm) = _state.update(transform)
    fun next() = _state.update { it.copy(step = (it.step + 1).coerceAtMost(2)) }
    fun back() = _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }

    fun setType(type: ScheduleType) = _state.update {
        val times = when {
            type == ScheduleType.AS_NEEDED -> it.times
            it.times.isEmpty() -> listOf(LocalTime.of(8, 0))
            else -> it.times
        }
        val steps = if (type == ScheduleType.TAPERING && it.taperSteps.isEmpty()) {
            val base = listOf(it.dosage, it.unit).filter { s -> s.isNotBlank() }.joinToString(" ")
            listOf(
                TaperStep(it.startDate.toEpochDay(), base),
                TaperStep(it.startDate.plusDays(7).toEpochDay(), ""),
            )
        } else it.taperSteps
        it.copy(type = type, times = times, taperSteps = steps)
    }

    /** Quick presets for common frequencies. */
    fun setTimesPreset(perDay: Int) = _state.update {
        val presets = when (perDay) {
            1 -> listOf(LocalTime.of(8, 0))
            2 -> listOf(LocalTime.of(8, 0), LocalTime.of(20, 0))
            3 -> listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0))
            else -> listOf(LocalTime.of(7, 0), LocalTime.of(12, 0), LocalTime.of(17, 0), LocalTime.of(22, 0))
        }
        it.copy(times = presets)
    }

    fun addTime() = _state.update {
        val last = it.times.maxOrNull() ?: LocalTime.of(8, 0)
        it.copy(times = (it.times + last.plusHours(4)).distinct())
    }

    fun setTime(index: Int, time: LocalTime) = _state.update {
        it.copy(times = it.times.toMutableList().also { list -> if (index in list.indices) list[index] = time }.distinct())
    }

    fun removeTime(index: Int) = _state.update {
        if (it.times.size <= 1) it else it.copy(times = it.times.filterIndexed { i, _ -> i != index })
    }

    fun toggleWeekday(day: DayOfWeek) = _state.update {
        it.copy(weekdays = if (day in it.weekdays) it.weekdays - day else it.weekdays + day)
    }

    fun addTaperStep() = _state.update {
        val lastDate = it.taperSteps.maxOfOrNull { s -> s.fromEpochDay }?.let(LocalDate::ofEpochDay) ?: it.startDate
        it.copy(taperSteps = it.taperSteps + TaperStep(lastDate.plusDays(7).toEpochDay(), ""))
    }

    fun setTaperStep(index: Int, step: TaperStep) = _state.update {
        it.copy(taperSteps = it.taperSteps.toMutableList().also { l -> if (index in l.indices) l[index] = step })
    }

    fun removeTaperStep(index: Int) = _state.update {
        it.copy(taperSteps = it.taperSteps.filterIndexed { i, _ -> i != index })
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val form = _state.value
        if (form.saving || form.name.isBlank()) return@launch
        _state.update { it.copy(saving = true) }
        meds.upsert(form.toMedication())
        reminders.rescheduleAll()
        onDone()
    }
}
