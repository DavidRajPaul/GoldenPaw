package com.goldenpaw.ui.quicklog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.goldenpaw.core.AppClock
import com.goldenpaw.core.newId
import com.goldenpaw.core.today
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.SlotState
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.SymptomType
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.usecase.CareTeamService
import com.goldenpaw.domain.usecase.LogDoseResult
import com.goldenpaw.domain.usecase.LogDoseUseCase
import com.goldenpaw.domain.usecase.ObserveDoseSlotsUseCase
import com.goldenpaw.platform.rememberHaptics
import com.goldenpaw.ui.common.Formats
import com.goldenpaw.ui.common.WeightLogDialog
import com.goldenpaw.ui.designsystem.PetAvatar
import com.goldenpaw.ui.navigation.LocalAppActions
import com.goldenpaw.ui.navigation.Route
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
import org.koin.compose.viewmodel.koinViewModel
import com.goldenpaw.ui.designsystem.GpIcons
import com.goldenpaw.ui.designsystem.icon

data class QuickLogState(
    val pet: Pet? = null,
    val dueSlots: List<DoseSlot> = emptyList(),
    val checkedInToday: Boolean = false,
    val today: LocalDate? = null,
    val unit: com.goldenpaw.domain.model.WeightUnit = com.goldenpaw.domain.model.WeightUnit.KG,
)

/** Last thing logged, for the inline confirmation (and its undo-free "done" tick). */
data class QuickLogFeedback(val text: String, val id: Long)

class QuickLogViewModel(
    settings: SettingsRepository,
    pets: PetRepository,
    observeSlots: ObserveDoseSlotsUseCase,
    checkIns: CheckInRepository,
    private val logDose: LogDoseUseCase,
    private val symptoms: SymptomRepository,
    private val weights: com.goldenpaw.domain.repository.WeightRepository,
    private val careTeam: CareTeamService,
    private val clock: AppClock,
) : ViewModel() {

    val state: StateFlow<QuickLogState> = combine(settings.settings, pets.observeActivePets()) { s, list ->
        Triple(s, list.firstOrNull { it.id == s.selectedPetId } ?: list.firstOrNull(), s.weightUnit)
    }.flatMapLatest { (_, pet, unit) ->
        if (pet == null) {
            flowOf(QuickLogState())
        } else {
            val today = clock.today()
            combine(
                observeSlots(pet.id, today, today, flowOf(clock.now())),
                checkIns.observeForDate(pet.id, today),
            ) { slots, checkIn: CheckIn? ->
                QuickLogState(
                    pet = pet,
                    dueSlots = slots.filter { it.state == SlotState.DUE || it.state == SlotState.MISSED },
                    checkedInToday = checkIn != null,
                    today = today,
                    unit = unit,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuickLogState())

    private val _feedback = MutableStateFlow<QuickLogFeedback?>(null)
    val feedback: StateFlow<QuickLogFeedback?> = _feedback.asStateFlow()
    private var counter = 0L

    private fun done(text: String) = _feedback.update { QuickLogFeedback(text, ++counter) }

    fun give(slot: DoseSlot) = viewModelScope.launch {
        when (val r = logDose(slot.medication.id, slot.scheduledAt, DoseStatus.GIVEN)) {
            is LogDoseResult.Logged -> done("${slot.medication.name} given")
            is LogDoseResult.AlreadyGiven -> done("${r.existing.givenBy.ifBlank { "Someone" }} already gave ${slot.medication.name}")
            LogDoseResult.NotAllowed -> done("You can't log doses for this pet")
            LogDoseResult.NotFound -> Unit
        }
    }

    fun symptom(type: SymptomType) = viewModelScope.launch {
        val pet = state.value.pet ?: return@launch
        symptoms.upsert(
            SymptomEntry(
                id = newId(),
                petId = pet.id,
                date = clock.today(),
                loggedAt = clock.now(),
                type = type,
                severity = 2,
                tags = emptyList(),
                notes = "",
                photoPath = null,
                loggedBy = careTeam.attributionFor(pet.id),
            ),
        )
        done("${type.label} noted")
    }

    fun weight(kg: Double, date: LocalDate, notes: String) = viewModelScope.launch {
        val pet = state.value.pet ?: return@launch
        weights.add(
            com.goldenpaw.domain.model.WeightEntry(newId(), pet.id, date, kg, notes, clock.now(), careTeam.attributionFor(pet.id)),
        )
        done("Weight saved")
    }
}

@Composable
fun QuickLogSheet(onDismiss: () -> Unit) {
    val vm: QuickLogViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val haptics = rememberHaptics()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var weightOpen by rememberSaveable { mutableStateOf(false) }
    val pet = state.pet

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pet != null) PetAvatar(pet.photoPath, pet.species, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Quick log", style = MaterialTheme.typography.titleLarge)
                    Text(pet?.name ?: "Add a pet first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedContent(
                    targetState = feedback,
                    transitionSpec = { (fadeIn(tween(200)) + scaleIn(initialScale = 0.8f)) togetherWith fadeOut(tween(120)) },
                    label = "quickFeedback",
                ) { f ->
                    if (f != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(f.text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (pet == null) return@Column

            if (state.dueSlots.isNotEmpty()) {
                Text("Doses due now", style = MaterialTheme.typography.titleSmall)
                state.dueSlots.forEach { slot ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${slot.medication.name} · ${slot.dosage}", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                (if (slot.state == SlotState.MISSED) "Overdue · " else "") + Formats.time(slot.scheduledAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (slot.state == SlotState.MISSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(onClick = { haptics.confirm(); vm.give(slot) }) { Text("Given") }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onDismiss(); actions.navigate(Route.CheckIn(pet.id)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(GpIcons.CheckIn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.checkedInToday) "Edit check-in" else "Check-in")
                }
                OutlinedButton(onClick = { weightOpen = true }, modifier = Modifier.weight(1f)) {
                    Icon(GpIcons.Weight, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Weight")
                }
            }

            Text("Noticed something?", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SymptomType.quick.forEach { type ->
                    AssistChip(
                        onClick = { haptics.tick(); vm.symptom(type) },
                        label = { Text(type.label) },
                        leadingIcon = { Icon(type.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            OutlinedButton(
                onClick = { onDismiss(); actions.navigate(Route.RecordScan(pet.id)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(GpIcons.Scan, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan a vet or vaccine card")
            }
            TextButton(onClick = { onDismiss(); actions.navigate(Route.SymptomEdit(pet.id)) }) {
                Text("Add details, a photo or something else")
            }
            if (state.dueSlots.isEmpty() && state.checkedInToday) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "All caught up for today. Nice work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (weightOpen) {
        WeightLogDialog(defaultUnit = state.unit, onDismiss = { weightOpen = false }, onSave = vm::weight)
    }
}
