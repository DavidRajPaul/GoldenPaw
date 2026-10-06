package com.goldenpaw.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.goldenpaw.core.AppClock
import com.goldenpaw.core.minusDays
import com.goldenpaw.core.today
import com.goldenpaw.domain.logic.QualityOfLifeCalculator
import com.goldenpaw.domain.logic.valueOf
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.CheckInCategory
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.QualityOfLifeScore
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.usecase.CareTeamService
import com.goldenpaw.platform.rememberHaptics
import com.goldenpaw.ui.common.DateField
import com.goldenpaw.ui.common.Formats
import com.goldenpaw.ui.common.GpTopBar
import com.goldenpaw.ui.designsystem.CelebrationBurst
import com.goldenpaw.ui.designsystem.EmojiScale
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.ScoreBar
import com.goldenpaw.ui.designsystem.WellnessRing
import com.goldenpaw.ui.designsystem.rememberStaggerState
import com.goldenpaw.ui.designsystem.staggerIn
import com.goldenpaw.ui.navigation.LocalAppActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

data class CheckInForm(
    val pet: Pet? = null,
    val date: LocalDate? = null,
    val today: LocalDate? = null,
    val values: Map<CheckInCategory, Int> = emptyMap(),
    val notes: String = "",
    val isEditing: Boolean = false,
    val saved: QualityOfLifeScore? = null,
) {
    val complete: Boolean get() = CheckInCategory.entries.all { values[it] != null }
}

class CheckInViewModel(
    private val pets: PetRepository,
    private val checkIns: CheckInRepository,
    private val careTeam: CareTeamService,
    private val clock: AppClock,
) : ViewModel() {

    private val _state = MutableStateFlow(CheckInForm())
    val state: StateFlow<CheckInForm> = _state.asStateFlow()
    private var loadedFor: String? = null

    fun load(petId: String) {
        if (loadedFor == petId) return
        loadedFor = petId
        viewModelScope.launch {
            val pet = pets.getPet(petId)
            _state.update { it.copy(pet = pet, today = clock.today()) }
            loadDate(petId, clock.today())
        }
    }

    private suspend fun loadDate(petId: String, date: LocalDate) {
        val existing = checkIns.forPetBetween(petId, date, date).firstOrNull()
        _state.update {
            it.copy(
                date = date,
                values = existing?.let { c -> CheckInCategory.entries.associateWith { cat -> c.valueOf(cat) } } ?: emptyMap(),
                notes = existing?.notes.orEmpty(),
                isEditing = existing != null,
                saved = null,
            )
        }
    }

    fun setDate(date: LocalDate) {
        val petId = _state.value.pet?.id ?: return
        viewModelScope.launch { loadDate(petId, date) }
    }

    fun setValue(category: CheckInCategory, value: Int) =
        _state.update { it.copy(values = it.values + (category to value)) }

    fun setNotes(notes: String) = _state.update { it.copy(notes = notes.take(500)) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        val pet = s.pet ?: return@launch
        val date = s.date ?: return@launch
        if (!s.complete) return@launch
        val v = s.values
        val checkIn = CheckIn(
            id = "",
            petId = pet.id,
            date = date,
            appetite = v.getValue(CheckInCategory.APPETITE),
            water = v.getValue(CheckInCategory.WATER),
            mobility = v.getValue(CheckInCategory.MOBILITY),
            mood = v.getValue(CheckInCategory.MOOD),
            pain = v.getValue(CheckInCategory.PAIN),
            hygiene = v.getValue(CheckInCategory.HYGIENE),
            sleep = v.getValue(CheckInCategory.SLEEP),
            notes = s.notes.trim(),
            createdAt = clock.now(),
            loggedBy = careTeam.attributionFor(pet.id),
        )
        checkIns.upsert(checkIn)
        val history = checkIns.forPetBetween(pet.id, date.minusDays(6), date)
        val saved = history.firstOrNull { it.date == date } ?: checkIn
        _state.update { it.copy(saved = QualityOfLifeCalculator.score(saved, history)) }
    }
}

@Composable
fun CheckInScreen(petId: String) {
    val vm: CheckInViewModel = koinViewModel()
    LaunchedEffect(petId) { vm.load(petId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val haptics = rememberHaptics()
    val stagger = rememberStaggerState()
    val pet = state.pet

    Scaffold(
        topBar = { GpTopBar(title = if (pet != null) "${pet.name}'s check-in" else "Check-in", onBack = actions.back) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (state.saved == null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${state.values.size} of ${CheckInCategory.entries.size}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { haptics.confirm(); vm.save() },
                            enabled = state.complete,
                            modifier = Modifier.height(50.dp),
                        ) {
                            Text(if (state.isEditing) "Update check-in" else "Save check-in")
                        }
                    }
                }
            }
        },
    ) { padding ->
        val saved = state.saved
        if (saved != null) {
            SavedSummary(saved, petName = pet?.name.orEmpty(), padding = padding, onDone = actions.back)
            return@Scaffold
        }
        val date = state.date ?: return@Scaffold
        val today = state.today ?: date
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column {
                    Text(
                        "Tap the face that fits best. There are no wrong answers, just today's picture.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    DateField(
                        label = "Day",
                        date = date,
                        onDate = vm::setDate,
                        placeholder = Formats.relativeDay(date, today),
                    )
                    if (state.isEditing) {
                        Text(
                            "Editing the check-in for ${Formats.relativeDay(date, today).lowercase()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            itemsIndexed(CheckInCategory.entries.toList(), key = { _, c -> c.name }) { index, category ->
                EmojiScale(
                    label = category.label,
                    question = category.question,
                    lowLabel = category.lowLabel,
                    highLabel = category.highLabel,
                    value = state.values[category],
                    onValue = { haptics.tick(); vm.setValue(category, it) },
                    modifier = Modifier.staggerIn(stagger, category.name, index),
                )
            }
            item {
                OutlinedTextField(
                    value = state.notes,
                    onValueChange = vm::setNotes,
                    label = { Text("Anything else? (optional)") },
                    placeholder = { Text("e.g. Slow on the stairs this morning, ate all dinner") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SavedSummary(score: QualityOfLifeScore, petName: String, padding: PaddingValues, onDone: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 24.dp, end = 24.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                WellnessRing(
                    fraction = score.fraction,
                    centerTop = "${score.total.roundToInt()}",
                    centerBottom = "of 70",
                    size = 190.dp,
                )
            }
            item {
                Text(
                    when {
                        score.total >= 49 -> "A good day for $petName."
                        score.total >= QualityOfLifeScore.ACCEPTABLE_THRESHOLD -> "Thanks for checking in."
                        else -> "That sounds like a hard day. You're doing right by $petName by noticing."
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            item {
                GpCard {
                    Text("HHHHHMM breakdown (0–10 each)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        score.parts().forEach { (label, value) -> ScoreBar(label, value) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Based on the Villalobos quality-of-life scale. Scores above 35 are generally considered acceptable. " +
                            "It's a conversation tool for you and your vet, not a diagnosis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Done") }
            }
        }
        if (score.total >= 49) CelebrationBurst(trigger = score.total)
    }
}
