package com.goldenpaw.ui.journal

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.goldenpaw.domain.logic.SymptomPatternDetector
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.SymptomCatalog
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.SymptomPattern
import com.goldenpaw.domain.model.SymptomType
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.WeightRepository
import com.goldenpaw.domain.usecase.newId
import com.goldenpaw.platform.media.ImageStorage
import com.goldenpaw.ui.common.ConfirmDialog
import com.goldenpaw.ui.common.DateField
import com.goldenpaw.ui.common.Formats
import com.goldenpaw.ui.common.GpTopBar
import com.goldenpaw.ui.designsystem.ChoiceChips
import com.goldenpaw.ui.designsystem.EmptyState
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.LocalWellnessColors
import com.goldenpaw.ui.designsystem.Pill
import com.goldenpaw.ui.designsystem.SeverityPicker
import com.goldenpaw.ui.koinVm
import com.goldenpaw.ui.navigation.LocalAppActions
import com.goldenpaw.ui.navigation.Routes
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

// ------------------------------------------------------------------ Journal

sealed interface JournalItem {
    val date: LocalDate
    val sortKey: Long

    data class Symptom(val entry: SymptomEntry) : JournalItem {
        override val date get() = entry.date
        override val sortKey get() = entry.loggedAt.toEpochMilli()
    }

    data class Note(val checkIn: CheckIn) : JournalItem {
        override val date get() = checkIn.date
        override val sortKey get() = checkIn.createdAt.toEpochMilli()
    }

    data class Weight(val entry: WeightEntry) : JournalItem {
        override val date get() = entry.date
        override val sortKey get() = entry.createdAt.toEpochMilli()
    }
}

data class JournalState(
    val loading: Boolean = true,
    val pet: Pet? = null,
    val filter: SymptomType? = null,
    val availableTypes: List<SymptomType> = emptyList(),
    val groups: List<Pair<LocalDate, List<JournalItem>>> = emptyList(),
    val patterns: List<SymptomPattern> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.KG,
)

@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModel(
    settings: SettingsRepository,
    pets: PetRepository,
    private val symptoms: SymptomRepository,
    private val checkIns: CheckInRepository,
    private val weights: WeightRepository,
) : ViewModel() {

    private val filter = MutableStateFlow<SymptomType?>(null)

    val state: StateFlow<JournalState> = combine(settings.settings, pets.observeActivePets()) { s, p -> s to p }
        .flatMapLatest { (s, petList) ->
            val pet = petList.firstOrNull { it.id == s.selectedPetId } ?: petList.firstOrNull()
            if (pet == null) {
                flowOf(JournalState(loading = false))
            } else {
                combine(
                    symptoms.observeForPet(pet.id),
                    checkIns.observeForPet(pet.id),
                    weights.observeForPet(pet.id),
                    filter,
                ) { syms, cis, ws, f ->
                    val items = buildList<JournalItem> {
                        syms.filter { f == null || it.type == f }.forEach { add(JournalItem.Symptom(it)) }
                        if (f == null) {
                            cis.filter { it.notes.isNotBlank() }.forEach { add(JournalItem.Note(it)) }
                            ws.forEach { add(JournalItem.Weight(it)) }
                        }
                    }
                    JournalState(
                        loading = false,
                        pet = pet,
                        filter = f,
                        availableTypes = syms.map { it.type }.distinct().sortedBy { it.ordinal },
                        groups = items.groupBy { it.date }.toList()
                            .sortedByDescending { it.first }
                            .map { (d, list) -> d to list.sortedByDescending { it.sortKey } },
                        patterns = SymptomPatternDetector.detect(syms, LocalDate.now()),
                        weightUnit = s.weightUnit,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalState())

    fun setFilter(type: SymptomType?) {
        filter.value = if (filter.value == type) null else type
    }

    fun deleteWeight(id: String) = viewModelScope.launch { weights.delete(id) }
}

@Composable
fun JournalScreen() {
    val vm: JournalViewModel = koinVm()
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val pet = state.pet

    Scaffold(
        topBar = { GpTopBar(if (pet != null) "${pet.name}'s journal" else "Journal") },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (pet != null) {
                ExtendedFloatingActionButton(
                    onClick = { actions.navigate(Routes.symptomEdit(pet.id)) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Log symptom") },
                )
            }
        },
    ) { padding ->
        if (pet == null) {
            if (!state.loading) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    EmptyState("📖", "Nothing here yet", "Add a pet first, then log symptoms as they happen.")
                }
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.patterns.isNotEmpty()) {
                items(state.patterns, key = { "p-" + it.type.name }) { p ->
                    GpCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentPadding = PaddingValues(14.dp)) {
                        Text("${p.type.emoji}  ${p.message}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Patterns like this are worth mentioning to your vet. They're included in the vet report.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (state.availableTypes.isNotEmpty()) {
                item(key = "filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(selected = state.filter == null, onClick = { vm.setFilter(null) }, label = { Text("All") })
                        }
                        items(state.availableTypes) { t ->
                            FilterChip(
                                selected = state.filter == t,
                                onClick = { vm.setFilter(t) },
                                label = { Text("${t.emoji} ${t.label}") },
                            )
                        }
                    }
                }
            }
            if (state.groups.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        "🌿",
                        "A quiet journal is a good sign",
                        "Log vomiting, limping, accidents or anything unusual. Photos help your vet too.",
                    )
                }
            }
            state.groups.forEach { (date, dayItems) ->
                item(key = "d-$date") {
                    Text(
                        Formats.relativeDay(date),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                items(dayItems, key = { item ->
                    when (item) {
                        is JournalItem.Symptom -> "s-" + item.entry.id
                        is JournalItem.Note -> "n-" + item.checkIn.id
                        is JournalItem.Weight -> "w-" + item.entry.id
                    }
                }) { item ->
                    when (item) {
                        is JournalItem.Symptom -> SymptomCard(item.entry) {
                            actions.navigate(Routes.symptomEdit(pet.id, item.entry.id))
                        }
                        is JournalItem.Note -> GpCard(contentPadding = PaddingValues(14.dp)) {
                            Text("📝 Check-in note", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.checkIn.notes, style = MaterialTheme.typography.bodyMedium)
                        }
                        is JournalItem.Weight -> GpCard(contentPadding = PaddingValues(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⚖️", fontSize = 20.sp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Weighed ${UnitConversion.format(item.entry.weightKg, state.weightUnit)}", style = MaterialTheme.typography.titleSmall)
                                    if (item.entry.notes.isNotBlank()) Text(item.entry.notes, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SymptomCard(entry: SymptomEntry, onClick: () -> Unit) {
    val wellness = LocalWellnessColors.current
    GpCard(onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) { Text(entry.type.emoji, fontSize = 22.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.type.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(Formats.time(entry.loggedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    (1..5).forEach { i ->
                        val color = if (i <= entry.severity) {
                            when {
                                entry.severity >= 4 -> wellness.hard
                                entry.severity == 3 -> wellness.okay
                                else -> wellness.good
                            }
                        } else MaterialTheme.colorScheme.surfaceVariant
                        Box(Modifier.size(width = 18.dp, height = 6.dp).clip(CircleShape).background(color))
                    }
                }
                if (entry.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        entry.tags.forEach { Pill(it) }
                    }
                }
                if (entry.notes.isNotBlank()) {
                    Text(entry.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
            val photo = entry.photoPath
            if (photo != null && File(photo).exists()) {
                Spacer(Modifier.width(10.dp))
                AsyncImage(
                    model = File(photo),
                    contentDescription = "Symptom photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Symptom editor

data class SymptomForm(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val id: String = newId(),
    val petId: String = "",
    val petName: String = "",
    val type: SymptomType? = null,
    val severity: Int = 2,
    val tags: List<String> = emptyList(),
    val date: LocalDate = LocalDate.now(),
    val loggedAt: Instant = Instant.now(),
    val notes: String = "",
    val photoPath: String? = null,
    val photoImporting: Boolean = false,
)

class SymptomEditorViewModel(
    private val symptoms: SymptomRepository,
    private val pets: PetRepository,
    private val images: ImageStorage,
    @Suppress("unused") private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SymptomForm())
    val state: StateFlow<SymptomForm> = _state.asStateFlow()
    private var loaded = false

    fun load(petId: String, entryId: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val pet = pets.getPet(petId)
            val entry = entryId?.let { symptoms.get(it) }
            _state.value = if (entry == null) {
                SymptomForm(loading = false, petId = petId, petName = pet?.name.orEmpty())
            } else {
                SymptomForm(
                    loading = false, isNew = false, id = entry.id, petId = entry.petId, petName = pet?.name.orEmpty(),
                    type = entry.type, severity = entry.severity, tags = entry.tags, date = entry.date,
                    loggedAt = entry.loggedAt, notes = entry.notes, photoPath = entry.photoPath,
                )
            }
        }
    }

    fun update(transform: (SymptomForm) -> SymptomForm) = _state.update(transform)

    fun toggleTag(tag: String) = _state.update {
        it.copy(tags = if (tag in it.tags) it.tags - tag else it.tags + tag)
    }

    fun importPhoto(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(photoImporting = true) }
        val path = images.import(uri)
        _state.update { it.copy(photoImporting = false, photoPath = path ?: it.photoPath) }
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val f = _state.value
        val type = f.type ?: return@launch
        symptoms.upsert(
            SymptomEntry(
                id = f.id, petId = f.petId, date = f.date,
                loggedAt = if (f.isNew && f.date != LocalDate.now()) f.date.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant() else f.loggedAt,
                type = type, severity = f.severity, tags = f.tags, notes = f.notes.trim(), photoPath = f.photoPath,
            ),
        )
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val f = _state.value
        symptoms.delete(f.id)
        images.delete(f.photoPath)
        onDone()
    }
}

@Composable
fun SymptomEditorScreen(petId: String, entryId: String?) {
    val vm: SymptomEditorViewModel = koinVm()
    LaunchedEffect(petId, entryId) { vm.load(petId, entryId) }
    val form by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importPhoto(uri)
    }

    Scaffold(
        topBar = {
            GpTopBar(if (form.isNew) "Log a symptom" else "Edit entry", onBack = actions.back) {
                if (!form.isNew) TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = { vm.save { actions.back() } },
                    enabled = form.type != null,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp).height(50.dp),
                ) { Text("Save") }
            }
        },
    ) { padding ->
        if (form.loading) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("What happened${if (form.petName.isNotBlank()) " with ${form.petName}" else ""}?", style = MaterialTheme.typography.titleMedium)
            ChoiceChips(
                options = SymptomType.entries.toList(),
                selected = { it == form.type },
                onToggle = { t -> vm.update { it.copy(type = t) } },
                label = { "${it.emoji} ${it.label}" },
            )
            Text("How severe?", style = MaterialTheme.typography.titleMedium)
            SeverityPicker(value = form.severity, onValue = { v -> vm.update { it.copy(severity = v) } })
            Text("Details", style = MaterialTheme.typography.titleMedium)
            ChoiceChips(
                options = SymptomCatalog.tags,
                selected = { it in form.tags },
                onToggle = vm::toggleTag,
                label = { it },
            )
            DateField(label = "When", date = form.date, onDate = { d -> vm.update { it.copy(date = d) } })
            OutlinedTextField(
                value = form.notes,
                onValueChange = { v -> vm.update { it.copy(notes = v.take(500)) } },
                label = { Text("Notes") },
                placeholder = { Text("What did you notice? How long did it last?") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (form.photoPath == null) "Add photo" else "Change photo")
                }
                val photo = form.photoPath
                if (photo != null && File(photo).exists()) {
                    Spacer(Modifier.width(12.dp))
                    AsyncImage(
                        model = File(photo),
                        contentDescription = "Selected photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.small),
                    )
                    TextButton(onClick = { vm.update { it.copy(photoPath = null) } }) { Text("Remove") }
                }
            }
            Text(
                "Not veterinary advice. If symptoms are severe, sudden, or you're worried, contact your vet or an emergency clinic.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this entry?",
            body = "It will be removed from the journal and vet reports.",
            confirm = "Delete",
            onConfirm = { vm.delete { actions.back() } },
            onDismiss = { confirmDelete = false },
        )
    }
}
