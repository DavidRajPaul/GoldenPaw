package com.goldenpaw.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldenpaw.core.AppClock
import com.goldenpaw.core.minusDays
import com.goldenpaw.core.newId
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.startOfDay
import com.goldenpaw.core.startOfWeek
import com.goldenpaw.core.today
import com.goldenpaw.domain.logic.QualityOfLifeCalculator
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.model.DayQuality
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.QualityOfLifeScore
import com.goldenpaw.domain.model.WeeklyDigest
import com.goldenpaw.domain.model.WeeklySummary
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.SummaryGenerator
import com.goldenpaw.domain.repository.SummaryRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.WeightRepository
import com.goldenpaw.domain.usecase.BuildWeeklyDigestUseCase
import com.goldenpaw.domain.usecase.CareTeamService
import com.goldenpaw.domain.usecase.GenerateWeeklySummaryUseCase
import com.goldenpaw.domain.usecase.ObserveGamificationUseCase
import com.goldenpaw.report.ReportOptions
import com.goldenpaw.report.VetReportService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

data class InsightsState(
    val loading: Boolean = true,
    val pet: Pet? = null,
    val unit: WeightUnit = WeightUnit.KG,
    val ownerName: String = "",
    val isPlus: Boolean = false,
    val today: LocalDate? = null,
    val latestScore: QualityOfLifeScore? = null,
    val latestScoreDate: LocalDate? = null,
    val qolTrend: List<Pair<LocalDate, Double>> = emptyList(),
    val calendar: Map<LocalDate, DayQuality> = emptyMap(),
    val good30: Int = 0,
    val okay30: Int = 0,
    val hard30: Int = 0,
    val weights: List<WeightEntry> = emptyList(),
    val adherence30: Double? = null,
    val symptomCount30: Int = 0,
    val summaries: List<WeeklySummary> = emptyList(),
)

enum class ReportRange(val label: String, val days: Int) {
    TWO_WEEKS("2 weeks", 14), MONTH("30 days", 30), QUARTER("90 days", 90), YEAR("1 year", 365)
}

data class ReportUiState(
    val range: ReportRange = ReportRange.MONTH,
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null,
    val includeMeds: Boolean = true,
    val includeQol: Boolean = true,
    val includeWeight: Boolean = true,
    val includeSymptoms: Boolean = true,
    val includeNotes: Boolean = true,
    val generating: Boolean = false,
    val filePath: String? = null,
    val error: String? = null,
) {
    fun from(today: LocalDate): LocalDate = customFrom ?: today.minusDays(range.days - 1)
    fun to(today: LocalDate): LocalDate = customTo ?: today
}

data class WeeklyUiState(
    val weekStart: LocalDate? = null,
    val digest: WeeklyDigest? = null,
    val summary: WeeklySummary? = null,
    val generating: Boolean = false,
    val error: String? = null,
    val aiAvailable: Boolean = false,
)

class InsightsViewModel(
    settings: SettingsRepository,
    pets: PetRepository,
    private val checkIns: CheckInRepository,
    private val weights: WeightRepository,
    private val symptoms: SymptomRepository,
    private val medications: MedicationRepository,
    private val doseEvents: DoseEventRepository,
    private val summaries: SummaryRepository,
    private val reportService: VetReportService,
    private val buildDigest: BuildWeeklyDigestUseCase,
    private val generateSummary: GenerateWeeklySummaryUseCase,
    private val aiWriter: SummaryGenerator,
    private val careTeam: CareTeamService,
    private val gamification: ObserveGamificationUseCase,
    private val clock: AppClock,
) : ViewModel() {

    val state: StateFlow<InsightsState> = combine(settings.settings, pets.observeActivePets()) { s, p -> s to p }
        .flatMapLatest { (s, petList) ->
            val pet = petList.firstOrNull { it.id == s.selectedPetId } ?: petList.firstOrNull()
            if (pet == null) {
                flowOf(InsightsState(loading = false))
            } else {
                combine(
                    checkIns.observeForPet(pet.id),
                    weights.observeForPet(pet.id),
                    symptoms.observeForPet(pet.id),
                    medications.observeForPet(pet.id),
                    summaries.observeForPet(pet.id),
                ) { cis, ws, syms, meds, sums ->
                    val today = clock.today()
                    val zone = clock.zone()
                    val start30 = today.minusDays(29)
                    val recent = cis.filter { it.date >= start30 }
                    val latest = cis.maxByOrNull { it.date }
                    val events = doseEvents.forPetBetween(pet.id, start30.startOfDay(zone), today.plusDays(1).startOfDay(zone))
                    val slots = ScheduleEngine.slotsFor(meds.filter { it.isActive }, events, start30, today, clock.now(), zone)
                    InsightsState(
                        loading = false,
                        pet = pet,
                        unit = s.weightUnit,
                        ownerName = s.ownerName,
                        isPlus = s.isPlus,
                        today = today,
                        latestScore = latest?.let { QualityOfLifeCalculator.score(it, cis) },
                        latestScoreDate = latest?.date,
                        qolTrend = QualityOfLifeCalculator.dailyTotals(cis).filter { it.first >= start30 },
                        calendar = cis.associate { it.date to it.dayQuality },
                        good30 = recent.count { it.dayQuality == DayQuality.GOOD },
                        okay30 = recent.count { it.dayQuality == DayQuality.OKAY },
                        hard30 = recent.count { it.dayQuality == DayQuality.HARD },
                        weights = ws.sortedBy { it.date },
                        adherence30 = ScheduleEngine.adherence(slots),
                        symptomCount30 = syms.count { it.date >= start30 },
                        summaries = sums,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsState())

    // ------------------------------------------------------------------ weekly summary

    private val _weekly = MutableStateFlow(WeeklyUiState(aiAvailable = aiWriter.isAvailable))
    val weekly: StateFlow<WeeklyUiState> = _weekly.asStateFlow()

    /** Loads the digest (free) and, for Plus, the written summary for the given week. */
    fun loadWeek(weekStart: LocalDate = clock.today().startOfWeek(), force: Boolean = false) = viewModelScope.launch {
        val pet = state.value.pet ?: return@launch
        val start = weekStart.startOfWeek()
        _weekly.update { it.copy(weekStart = start, generating = true, error = null) }
        val digest = runCatching { buildDigest(pet.id, start) }.getOrNull()
        val summary = if (state.value.isPlus) {
            runCatching { generateSummary(pet.id, start, forceRefresh = force) }
                .onFailure { e -> _weekly.update { it.copy(error = e.message) } }
                .getOrNull()
        } else null
        _weekly.update { it.copy(digest = digest, summary = summary, generating = false) }
    }

    fun previousWeek() {
        val start = _weekly.value.weekStart ?: return
        loadWeek(start.minusDays(7))
    }

    fun nextWeek() {
        val start = _weekly.value.weekStart ?: return
        val next = start.plusDays(7)
        if (next <= clock.today()) loadWeek(next)
    }

    // ------------------------------------------------------------------ vet report

    private val _report = MutableStateFlow(ReportUiState())
    val report: StateFlow<ReportUiState> = _report.asStateFlow()

    fun updateReport(transform: (ReportUiState) -> ReportUiState) =
        _report.update { transform(it).copy(filePath = null, error = null) }

    fun generateReport() = viewModelScope.launch {
        val pet = state.value.pet ?: return@launch
        val r = _report.value
        val today = clock.today()
        _report.update { it.copy(generating = true, error = null) }
        runCatching {
            val options = ReportOptions(
                from = r.from(today),
                to = r.to(today),
                includeMeds = r.includeMeds,
                includeWeight = r.includeWeight,
                includeQol = r.includeQol,
                includeSymptoms = r.includeSymptoms,
                includeNotes = r.includeNotes,
            )
            reportService.generate(pet, options, state.value.unit, state.value.ownerName)
        }.onSuccess { path ->
            _report.update { it.copy(generating = false, filePath = path) }
            gamification.unlockVetReady()
        }.onFailure { e ->
            _report.update { it.copy(generating = false, error = e.message ?: "Couldn't create the report") }
        }
    }

    fun addWeight(kg: Double, date: LocalDate, notes: String) = viewModelScope.launch {
        val pet = state.value.pet ?: return@launch
        weights.add(WeightEntry(newId(), pet.id, date, kg, notes, clock.now(), careTeam.attributionFor(pet.id)))
    }

    fun deleteWeight(id: String) = viewModelScope.launch { weights.delete(id) }
}
