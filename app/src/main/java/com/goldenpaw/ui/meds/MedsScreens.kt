package com.goldenpaw.ui.meds

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.MedicationCatalog
import com.goldenpaw.domain.model.ScheduleType
import com.goldenpaw.domain.model.TaperStep
import com.goldenpaw.domain.model.shortLabel
import com.goldenpaw.ui.common.ConfirmDialog
import com.goldenpaw.ui.common.DateField
import com.goldenpaw.ui.common.Formats
import com.goldenpaw.ui.common.GpTopBar
import com.goldenpaw.ui.common.NumberField
import com.goldenpaw.ui.common.TimeChipButton
import com.goldenpaw.ui.designsystem.ChoiceChips
import com.goldenpaw.ui.designsystem.EmptyState
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.Pill
import com.goldenpaw.ui.designsystem.SectionHeader
import com.goldenpaw.ui.designsystem.StepProgress
import com.goldenpaw.ui.koinVm
import com.goldenpaw.ui.navigation.LocalAppActions
import com.goldenpaw.ui.navigation.Routes
import java.time.DayOfWeek
import java.time.LocalDate

// ------------------------------------------------------------------ List

@Composable
fun MedsScreen(petId: String) {
    val vm: MedsViewModel = koinVm()
    LaunchedEffect(petId) { vm.load(petId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    var refillFor by remember { mutableStateOf<Medication?>(null) }
    var deleteFor by remember { mutableStateOf<Medication?>(null) }

    Scaffold(
        topBar = { GpTopBar(state.pet?.let { "${it.name}'s medications" } ?: "Medications", onBack = actions.back) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { actions.navigate(Routes.medEdit(petId)) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add medication") },
            )
        },
    ) { padding ->
        if (!state.loading && state.active.isEmpty() && state.inactive.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState("💊", "No medications yet", "Add a medication to get reminders, refill alerts and a dose history for your vet.")
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.active, key = { it.id }) { med ->
                MedicationCard(
                    med = med,
                    onEdit = { actions.navigate(Routes.medEdit(petId, med.id)) },
                    onToggle = { vm.setActive(med, !med.isActive) },
                    onRefill = { refillFor = med },
                    onDelete = { deleteFor = med },
                )
            }
            if (state.inactive.isNotEmpty()) {
                item { SectionHeader("Paused or finished") }
                items(state.inactive, key = { "i-" + it.id }) { med ->
                    MedicationCard(
                        med = med,
                        onEdit = { actions.navigate(Routes.medEdit(petId, med.id)) },
                        onToggle = { vm.setActive(med, !med.isActive) },
                        onRefill = { refillFor = med },
                        onDelete = { deleteFor = med },
                    )
                }
            }
        }
    }

    refillFor?.let { med ->
        var amount by remember(med.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { refillFor = null },
            title = { Text("Refill ${med.name}") },
            text = {
                Column {
                    Text("How many doses are in the new supply?", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    NumberField(value = amount, onValue = { amount = it }, label = "Doses", modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = amount.toDoubleOrNull() != null, onClick = {
                    amount.toDoubleOrNull()?.let { vm.refill(med, it) }
                    refillFor = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { refillFor = null }) { Text("Cancel") } },
        )
    }
    deleteFor?.let { med ->
        ConfirmDialog(
            title = "Delete ${med.name}?",
            body = "Reminders stop. Logged doses stay in the history and vet reports. To just stop for now, use Pause instead.",
            confirm = "Delete",
            onConfirm = { vm.delete(med) },
            onDismiss = { deleteFor = null },
        )
    }
}

@Composable
private fun MedicationCard(
    med: Medication,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onRefill: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    GpCard(onClick = onEdit, containerColor = if (med.isActive) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(med.name, style = MaterialTheme.typography.titleMedium)
                Text(med.doseLabel.ifBlank { "Dose not set" }, style = MaterialTheme.typography.bodyMedium)
                Text(med.scheduleSummary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(if (med.isActive) "Pause" else "Resume") }, onClick = { menu = false; onToggle() })
                    DropdownMenuItem(text = { Text("Refill supply") }, onClick = { menu = false; onRefill() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (med.withFood) Pill("With food")
            if (med.route.isNotBlank()) Pill(med.route)
            if (med.reason.isNotBlank()) Pill(med.reason)
            med.daysOfSupplyLeft()?.let { days ->
                Pill(
                    if (days <= 0) "Out of supply" else "~$days days left",
                    container = if (med.needsRefill()) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    content = if (med.needsRefill()) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            med.schedule.endDate?.let { Pill("Until ${it.format(Formats.dayMonth)}") }
            if (!med.isActive) Pill("Paused")
        }
    }
}

// ------------------------------------------------------------------ Editor

@Composable
fun MedicationEditorScreen(petId: String, medId: String?) {
    val vm: MedicationEditorViewModel = koinVm()
    LaunchedEffect(petId, medId) { vm.load(petId, medId) }
    val form by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val reduceMotion = LocalReduceMotion.current

    Scaffold(
        topBar = {
            GpTopBar(
                title = if (form.isNew) "New medication" else "Edit ${form.name}",
                onBack = { if (form.step > 0) vm.back() else actions.back() },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (form.step > 0) {
                        OutlinedButton(onClick = vm::back, modifier = Modifier.weight(1f).height(50.dp)) { Text("Back") }
                    }
                    val last = form.step == 2
                    Button(
                        onClick = { if (last) vm.save { actions.back() } else vm.next() },
                        enabled = form.stepValid && !form.saving,
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) { Text(if (last) "Save medication" else "Next") }
                }
            }
        },
    ) { padding ->
        if (form.loading) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            StepProgress(form.step, 3, Modifier.padding(vertical = 8.dp))
            AnimatedContent(
                targetState = form.step,
                transitionSpec = {
                    if (reduceMotion) fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                    else {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(280)) { it / 4 * dir } + fadeIn(tween(280))) togetherWith
                            (slideOutHorizontally(tween(220)) { -it / 4 * dir } + fadeOut(tween(180)))
                    }
                },
                label = "medStep",
                modifier = Modifier.weight(1f),
            ) { step ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (step) {
                        0 -> StepWhat(form, vm)
                        1 -> StepWhen(form, vm)
                        else -> StepDetails(form, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepWhat(form: MedForm, vm: MedicationEditorViewModel) {
    if (form.petName.isNotBlank()) {
        Text("For ${form.petName}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    OutlinedTextField(
        value = form.name,
        onValueChange = { v -> vm.update { it.copy(name = v.take(60)) } },
        label = { Text("Medication name") },
        placeholder = { Text("e.g. Carprofen, Gabapentin, Benazepril") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.dosage,
        onValueChange = { v -> vm.update { it.copy(dosage = v.take(20)) } },
        label = { Text("Dose amount") },
        placeholder = { Text("e.g. 25, 1/2, 0.5") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Unit", style = MaterialTheme.typography.titleSmall)
    ChoiceChips(
        options = MedicationCatalog.units,
        selected = { it == form.unit },
        onToggle = { u -> vm.update { it.copy(unit = u) } },
        label = { it },
    )
    Text("How it's given", style = MaterialTheme.typography.titleSmall)
    ChoiceChips(
        options = MedicationCatalog.routes.filter { it != "With food" },
        selected = { it == form.route },
        onToggle = { r -> vm.update { it.copy(route = r) } },
        label = { it },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Give with food", style = MaterialTheme.typography.titleSmall)
            Text("Shown on the reminder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = form.withFood, onCheckedChange = { c -> vm.update { it.copy(withFood = c) } })
    }
}

@Composable
private fun StepWhen(form: MedForm, vm: MedicationEditorViewModel) {
    Text("How often?", style = MaterialTheme.typography.titleSmall)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScheduleType.entries.forEach { type ->
            val selected = form.type == type
            Surface(
                onClick = { vm.setType(type) },
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(type.label, style = MaterialTheme.typography.titleSmall)
                    Text(type.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    AnimatedVisibility(form.type == ScheduleType.EVERY_N_DAYS) {
        NumberField(
            value = form.intervalDays.toString(),
            onValue = { v -> v.toIntOrNull()?.let { n -> vm.update { it.copy(intervalDays = n.coerceIn(1, 60)) } } },
            label = "Every how many days?",
            suffix = "days",
            decimal = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    AnimatedVisibility(form.type == ScheduleType.WEEKDAYS) {
        Column {
            Text("On these days", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            ChoiceChips(
                options = DayOfWeek.values().toList(),
                selected = { it in form.weekdays },
                onToggle = vm::toggleWeekday,
                label = { it.shortLabel() },
            )
        }
    }

    if (form.type != ScheduleType.AS_NEEDED) {
        Text("At what times?", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "Once", 2 to "Twice", 3 to "3×", 4 to "4×").forEach { (n, label) ->
                OutlinedButton(onClick = { vm.setTimesPreset(n) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            form.times.forEachIndexed { index, time ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeChipButton(time = time, onTime = { vm.setTime(index, it) }, modifier = Modifier.weight(1f))
                    if (form.times.size > 1) {
                        IconButton(onClick = { vm.removeTime(index) }) { Icon(Icons.Filled.Close, contentDescription = "Remove time") }
                    }
                }
            }
            TextButton(onClick = vm::addTime) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add a time")
            }
        }
    }

    AnimatedVisibility(form.type == ScheduleType.TAPERING) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Dose changes", style = MaterialTheme.typography.titleSmall)
            Text(
                "Each row applies from its date onward. Use 0 as the dose to end the course.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            form.taperSteps.forEachIndexed { index, step ->
                GpCard(contentPadding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DateField(
                            label = "From",
                            date = step.fromDate,
                            onDate = { d -> vm.setTaperStep(index, step.copy(fromEpochDay = d.toEpochDay())) },
                            allowFuture = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.removeTaperStep(index) }) { Icon(Icons.Filled.Close, contentDescription = "Remove step") }
                    }
                    OutlinedTextField(
                        value = step.dosage,
                        onValueChange = { v -> vm.setTaperStep(index, step.copy(dosage = v.take(30))) },
                        label = { Text("Dose (e.g. 5 mg)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            TextButton(onClick = vm::addTaperStep) { Text("Add dose change") }
        }
    }
}

@Composable
private fun StepDetails(form: MedForm, vm: MedicationEditorViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DateField(
            label = "Start",
            date = form.startDate,
            onDate = { d -> vm.update { it.copy(startDate = d) } },
            allowFuture = true,
            minDate = LocalDate.now().minusYears(5),
            modifier = Modifier.weight(1f),
        )
        DateField(
            label = "End (optional)",
            date = form.endDate,
            onDate = { d -> vm.update { it.copy(endDate = d) } },
            placeholder = "Ongoing",
            allowFuture = true,
            minDate = form.startDate,
            modifier = Modifier.weight(1f),
        )
    }
    if (form.endDate != null) {
        TextButton(onClick = { vm.update { it.copy(endDate = null) } }) { Text("Make it ongoing") }
    }
    OutlinedTextField(
        value = form.reason,
        onValueChange = { v -> vm.update { it.copy(reason = v.take(60)) } },
        label = { Text("What it's for (optional)") },
        placeholder = { Text("e.g. Arthritis pain") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.prescribedBy,
        onValueChange = { v -> vm.update { it.copy(prescribedBy = v.take(60)) } },
        label = { Text("Prescribed by (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (form.type != ScheduleType.AS_NEEDED) {
        NumberField(
            value = form.supply,
            onValue = { v -> vm.update { it.copy(supply = v) } },
            label = "Doses left in supply (optional)",
            suffix = "doses",
            modifier = Modifier.fillMaxWidth(),
        )
        if (form.supply.isNotBlank()) {
            NumberField(
                value = form.refillAlertDays.toString(),
                onValue = { v -> v.toIntOrNull()?.let { n -> vm.update { it.copy(refillAlertDays = n.coerceIn(0, 60)) } } },
                label = "Remind me to refill when this many days remain",
                suffix = "days",
                decimal = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    OutlinedTextField(
        value = form.notes,
        onValueChange = { v -> vm.update { it.copy(notes = v.take(300)) } },
        label = { Text("Notes (optional)") },
        placeholder = { Text("e.g. Hide in a bit of cheese") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )

    val preview = form.preview()
    if (preview.isNotEmpty()) {
        GpCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
            Text("Coming up", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            preview.forEach { (at, dose) ->
                val date = at.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                Text("${Formats.relativeDay(date)} · ${Formats.time(at)} · $dose", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
