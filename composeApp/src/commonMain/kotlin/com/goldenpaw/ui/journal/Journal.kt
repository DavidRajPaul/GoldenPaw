package com.goldenpaw.ui.journal

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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.goldenpaw.core.AppClock
import com.goldenpaw.core.at
import com.goldenpaw.core.newId
import com.goldenpaw.core.today
import com.goldenpaw.domain.logic.SymptomPatternDetector
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.domain.model.Attribution
import com.goldenpaw.domain.model.CareTeam
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.Pet
import com.goldenpaw.domain.model.SymptomCatalog
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.SymptomPattern
import com.goldenpaw.domain.model.SymptomType
import com.goldenpaw.domain.model.WeightEntry
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.AppFiles
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.WeightRepository
import com.goldenpaw.domain.usecase.CareTeamService
import com.goldenpaw.platform.rememberPhotoPicker
import com.goldenpaw.ui.common.ConfirmDialog
import com.goldenpaw.ui.common.DateField
import com.goldenpaw.ui.common.Formats
import com.goldenpaw.ui.common.GpTopBar
import com.goldenpaw.ui.common.rememberToday
import com.goldenpaw.ui.designsystem.AttributionChip
import com.goldenpaw.ui.designsystem.ChoiceChips
import com.goldenpaw.ui.designsystem.EmptyState
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.LocalAppFiles
import com.goldenpaw.ui.designsystem.LocalWellnessColors
import com.goldenpaw.ui.designsystem.Pill
import com.goldenpaw.ui.designsystem.SeverityPicker
import com.goldenpaw.ui.designsystem.rememberStaggerState
import com.goldenpaw.ui.designsystem.staggerIn
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
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.koin.compose.viewmodel.koinViewModel
import com.goldenpaw.ui.designsystem.GpIcons
import com.goldenpaw.ui.designsystem.IconBadge
import com.goldenpaw.ui.designsystem.icon

// ------------------------------------------------------------------ Journal

sealed interface JournalItem {
    val date: LocalDate
    val sortKey: Long
    val key: String

    data class Symptom(val entry: SymptomEntry) : JournalItem {
        override val date get() = entry.date
        override val sortKey get() = entry.loggedAt.toEpochMilliseconds()
        override val key get() = "s-${entry.id}"
    }

    data class Note(val checkIn: CheckIn) : JournalItem {
        override val date get() = checkIn.date
        override val sortKey get() = checkIn.createdAt.toEpochMilliseconds()
        override val key get() = "n-${checkIn.id}"
    }

    data class Weight(val entry: WeightEntry) : JournalItem {
        override val date get() = entry.date
        override val sortKey get() = entry.createdAt.toEpochMilliseconds()
        override val key get() = "w-${entry.id}"
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
    val team: CareTeam? = null,
)

class JournalViewModel(
    settings: SettingsRepository,
    pets: PetRepository,
    private val symptoms: SymptomRepository,
    private val checkIns: CheckInRepository,
    private val weights: WeightRepository,
    private val careTeam: CareTeamService,
    private val clock: AppClock,
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
                    careTeam.observeTeamForPet(pet.id),
                ) { syms, cis, ws, f, team ->
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
                        patterns = SymptomPatternDetector.detect(syms, clock.today()),
                        weightUnit = s.weightUnit,
                        team = team,
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
    val vm: JournalViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val today = rememberToday()
    val stagger = rememberStaggerState()
    val pet = state.pet
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        topBar = { GpTopBar(if (pet != null) "${pet.name}'s journal" else "Journal", scrollBehavior = scrollBehavior) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (pet != null) {
                ExtendedFloatingActionButton(
                    onClick = { actions.navigate(Route.SymptomEdit(pet.id)) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Log symptom") },
                )
            }
        },
    ) { padding ->
        if (pet == null) {
            if (!state.loading) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    EmptyState(GpIcons.Journal, "Nothing here yet", "Add a pet first, then log symptoms as they happen.")
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
                items(state.patterns, key = { "p-" + it.type.name }, contentType = { "pattern" }) { p ->
                    GpCard(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentPadding = PaddingValues(14.dp),
                        modifier = Modifier.animateItem(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(p.type.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(p.message, style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            "Patterns like this are worth mentioning to your vet. They're included in the vet report.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (state.availableTypes.isNotEmpty()) {
                item(key = "filters", contentType = "filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = state.filter == null, onClick = { vm.setFilter(null) }, label = { Text("All") }) }
                        items(state.availableTypes) { t ->
                            FilterChip(selected = state.filter == t, onClick = { vm.setFilter(t) }, label = { Text(t.label) }, leadingIcon = { Icon(t.icon, contentDescription = null, modifier = Modifier.size(18.dp)) })
                        }
                    }
                }
            }
            if (state.groups.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        GpIcons.Growth,
                        "A quiet journal is a good sign",
                        "Log vomiting, limping, accidents or anything unusual. Photos help your vet too.",
                    )
                }
            }
            var index = 0
            state.groups.forEach { (date, dayItems) ->
                item(key = "d-$date", contentType = "day") {
                    Text(
                        Formats.relativeDay(date, today),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp).animateItem(),
                    )
                }
                dayItems.forEach { journalItem ->
                    val i = index++
                    item(key = journalItem.key, contentType = journalItem::class.simpleName) {
                        val mod = Modifier.animateItem().staggerIn(stagger, journalItem.key, i)
                        when (val item = journalItem) {
                            is JournalItem.Symptom -> SymptomCard(item.entry, state.team, mod) {
                                actions.navigate(Route.SymptomEdit(pet.id, item.entry.id))
                            }
                            is JournalItem.Note -> GpCard(contentPadding = PaddingValues(14.dp), modifier = mod) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(GpIcons.Journal, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Check-in note", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(item.checkIn.notes, style = MaterialTheme.typography.bodyMedium)
                                AttributionLine(item.checkIn.loggedBy, state.team)
                            }
                            is JournalItem.Weight -> GpCard(contentPadding = PaddingValues(14.dp), modifier = mod) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconBadge(GpIcons.Weight, size = 36.dp, container = MaterialTheme.colorScheme.surfaceContainerHigh, content = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Weighed ${UnitConversion.format(item.entry.weightKg, state.weightUnit)}", style = MaterialTheme.typography.titleSmall)
                                        if (item.entry.notes.isNotBlank()) Text(item.entry.notes, style = MaterialTheme.typography.bodySmall)
                                        AttributionLine(item.entry.loggedBy, state.team)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Logged by Sam" — only shown when more than one person is on the care team. */
@Composable
private fun AttributionLine(by: Attribution, team: CareTeam?) {
    if (by.name.isBlank() || (team?.activeMembers?.size ?: 0) < 2) return
    AttributionChip(by.name, team?.member(by.caregiverId)?.colorIndex, Modifier.padding(top = 6.dp), prefix = "Logged by ")
}

@Composable
private fun SymptomCard(entry: SymptomEntry, team: CareTeam?, modifier: Modifier, onClick: () -> Unit) {
    val wellness = LocalWellnessColors.current
    val files = LocalAppFiles.current
    GpCard(onClick = onClick, contentPadding = PaddingValues(14.dp), modifier = modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) { Icon(entry.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) }
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
                AttributionLine(entry.loggedBy, team)
            }
            val photo = entry.photoPath
            if (photo != null && files?.exists(photo) != false) {
                Spacer(Modifier.width(10.dp))
                AsyncImage(
                    model = photo,
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
    val date: LocalDate? = null,
    val loggedAt: Instant? = null,
    val notes: String = "",
    val photoPath: String? = null,
    val photoImporting: Boolean = false,
    val loggedBy: Attribution = Attribution.NONE,
)

class SymptomEditorViewModel(
    private val symptoms: SymptomRepository,
    private val pets: PetRepository,
    private val files: AppFiles,
    private val careTeam: CareTeamService,
    private val clock: AppClock,
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
                SymptomForm(loading = false, petId = petId, petName = pet?.name.orEmpty(), date = clock.today(), loggedAt = clock.now())
            } else {
                SymptomForm(
                    loading = false, isNew = false, id = entry.id, petId = entry.petId, petName = pet?.name.orEmpty(),
                    type = entry.type, severity = entry.severity, tags = entry.tags, date = entry.date,
                    loggedAt = entry.loggedAt, notes = entry.notes, photoPath = entry.photoPath, loggedBy = entry.loggedBy,
                )
            }
        }
    }

    fun update(transform: (SymptomForm) -> SymptomForm) = _state.update(transform)

    fun toggleTag(tag: String) = _state.update {
        it.copy(tags = if (tag in it.tags) it.tags - tag else it.tags + tag)
    }

    fun photoPicked(path: String?) = _state.update { it.copy(photoImporting = false, photoPath = path ?: it.photoPath) }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val f = _state.value
        val type = f.type ?: return@launch
        val date = f.date ?: clock.today()
        val loggedAt = if (f.isNew && date != clock.today()) date.at(LocalTime(12, 0), clock.zone()) else (f.loggedAt ?: clock.now())
        val who = if (f.isNew || f.loggedBy.name.isBlank()) careTeam.attributionFor(f.petId) else f.loggedBy
        symptoms.upsert(
            SymptomEntry(
                id = f.id, petId = f.petId, date = date, loggedAt = loggedAt, type = type, severity = f.severity,
                tags = f.tags, notes = f.notes.trim(), photoPath = f.photoPath, loggedBy = who,
            ),
        )
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val f = _state.value
        symptoms.delete(f.id)
        files.delete(f.photoPath)
        onDone()
    }
}

@Composable
fun SymptomEditorScreen(petId: String, entryId: String?) {
    val vm: SymptomEditorViewModel = koinViewModel()
    LaunchedEffect(petId, entryId) { vm.load(petId, entryId) }
    val form by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val files = LocalAppFiles.current
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberPhotoPicker { path -> vm.photoPicked(path) }

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
                label = { it.label },
            )
            Text("How severe?", style = MaterialTheme.typography.titleMedium)
            SeverityPicker(value = form.severity, onValue = { v -> vm.update { it.copy(severity = v) } })
            Text("Details", style = MaterialTheme.typography.titleMedium)
            ChoiceChips(options = SymptomCatalog.tags, selected = { it in form.tags }, onToggle = vm::toggleTag, label = { it })
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
                OutlinedButton(onClick = { vm.update { it.copy(photoImporting = true) }; picker() }) {
                    if (form.photoImporting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (form.photoPath == null) "Add photo" else "Change photo")
                }
                val photo = form.photoPath
                if (photo != null && files?.exists(photo) != false) {
                    Spacer(Modifier.width(12.dp))
                    AsyncImage(
                        model = photo,
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
