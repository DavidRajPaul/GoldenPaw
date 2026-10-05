package com.goldenpaw.ui.insights

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldenpaw.domain.logic.QualityOfLifeCalculator
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.model.DayQuality
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.QualityOfLifeScore
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.WeightRepository
import com.goldenpaw.domain.usecase.newId
import com.goldenpaw.platform.report.ReportOptions
import com.goldenpaw.platform.report.VetReportDataSource
import com.goldenpaw.platform.report.VetReportGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class InsightsState(
    val loading: Boolean = true,
    val pet: Pet? = null,
    val unit: WeightUnit = WeightUnit.KG,
    val ownerName: String = "",
    val today: LocalDate = LocalDate.now(),
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
)

enum class ReportRange(val label: String, val days: Long) {
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
    val file: File? = null,
    val preview: Bitmap? = null,
    val error: String? = null,
) {
    fun from(today: LocalDate): LocalDate = customFrom ?: today.minusDays(range.days - 1)
    fun to(today: LocalDate): LocalDate = customTo ?: today
}

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel(
    settings: SettingsRepository,
    pets: PetRepository,
    private val checkIns: CheckInRepository,
    private val weights: WeightRepository,
    private val symptoms: SymptomRepository,
    private val medications: MedicationRepository,
    private val doseEvents: DoseEventRepository,
    private val reportSource: VetReportDataSource,
    private val reportGenerator: VetReportGenerator,
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
                ) { cis, ws, syms, meds ->
                    val today = LocalDate.now()
                    val start30 = today.minusDays(29)
                    val recent = cis.filter { !it.date.isBefore(start30) }
                    val latest = cis.maxByOrNull { it.date }

                    // Adherence over the last 30 days
                    val zone = ZoneId.systemDefault()
                    val events = doseEvents.forPetBetween(
                        pet.id, start30.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant(),
                    )
                    val slots = ScheduleEngine.slotsFor(meds.filter { it.isActive }, events, start30, today, Instant.now(), zone)

                    InsightsState(
                        loading = false,
                        pet = pet,
                        unit = s.weightUnit,
                        ownerName = s.ownerName,
                        today = today,
                        latestScore = latest?.let { QualityOfLifeCalculator.score(it, cis) },
                        latestScoreDate = latest?.date,
                        qolTrend = QualityOfLifeCalculator.dailyTotals(cis).filter { !it.first.isBefore(start30) },
                        calendar = cis.associate { it.date to it.dayQuality },
                        good30 = recent.count { it.dayQuality == DayQuality.GOOD },
                        okay30 = recent.count { it.dayQuality == DayQuality.OKAY },
                        hard30 = recent.count { it.dayQuality == DayQuality.HARD },
                        weights = ws.sortedBy { it.date },
                        adherence30 = ScheduleEngine.adherence(slots),
                        symptomCount30 = syms.count { !it.date.isBefore(start30) },
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsState())

    private val _report = MutableStateFlow(ReportUiState())
    val report: StateFlow<ReportUiState> = _report.asStateFlow()

    fun updateReport(transform: (ReportUiState) -> ReportUiState) =
        _report.update { transform(it).copy(file = null, preview = null, error = null) }

    fun generateReport() = viewModelScope.launch {
        val pet = state.value.pet ?: return@launch
        val r = _report.value
        val today = LocalDate.now()
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
            val data = reportSource.load(pet, options, state.value.unit, state.value.ownerName)
            val file = reportGenerator.generate(data)
            val preview = reportGenerator.renderPreview(file)
            file to preview
        }.onSuccess { (file, preview) ->
            _report.update { it.copy(generating = false, file = file, preview = preview) }
        }.onFailure { e ->
            _report.update { it.copy(generating = false, error = e.message ?: "Couldn't create the report") }
        }
    }

    fun addWeight(kg: Double, date: LocalDate, notes: String) = viewModelScope.launch {
        val pet = state.value.pet ?: return@launch
        weights.add(WeightEntry(newId(), pet.id, date, kg, notes, Instant.now()))
    }

    fun deleteWeight(id: String) = viewModelScope.launch { weights.delete(id) }
}
