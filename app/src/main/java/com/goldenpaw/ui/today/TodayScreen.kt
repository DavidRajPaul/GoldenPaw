package com.goldenpaw.ui.today

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.Insight
import com.goldenpaw.domain.model.QualityOfLifeScore
import com.goldenpaw.domain.model.SlotState
import com.goldenpaw.ui.common.Formats
import com.goldenpaw.ui.common.WeightLogDialog
import com.goldenpaw.ui.designsystem.EmptyState
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.LocalWellnessColors
import com.goldenpaw.ui.designsystem.MilestoneShimmer
import com.goldenpaw.ui.designsystem.PetAvatar
import com.goldenpaw.ui.designsystem.Pill
import com.goldenpaw.ui.designsystem.SectionHeader
import com.goldenpaw.ui.designsystem.WellnessRing
import com.goldenpaw.ui.koinVm
import com.goldenpaw.ui.navigation.LocalAppActions
import com.goldenpaw.ui.navigation.Routes
import kotlin.math.roundToInt

@Composable
fun TodayScreen() {
    val vm: TodayViewModel = koinVm()
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val snackbar = remember { SnackbarHostState() }
    var fabOpen by rememberSaveable { mutableStateOf(false) }
    var weightDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is TodayEvent.DoseLogged -> {
                    val result = snackbar.showSnackbar(
                        message = if (event.given) "${event.medName} given to ${event.petName}" else "${event.medName} skipped",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.undoById(event.eventId)
                }
                is TodayEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    val pet = state.pet
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (pet != null) {
                SpeedDial(
                    expanded = fabOpen,
                    onToggle = { fabOpen = !fabOpen },
                    items = listOf(
                        FabItem("Check-in", Icons.Outlined.CheckCircle) { actions.navigate(Routes.checkIn(pet.id)) },
                        FabItem("Medication", Icons.Outlined.LocalPharmacy) { actions.navigate(Routes.medEdit(pet.id)) },
                        FabItem("Symptom", Icons.Outlined.Healing) { actions.navigate(Routes.symptomEdit(pet.id)) },
                        FabItem("Weight", Icons.Outlined.FitnessCenter) { weightDialog = true },
                    ),
                )
            }
        },
    ) { padding ->
        if (!state.loading && pet == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    emoji = "🐾",
                    title = "Let's add your companion",
                    body = "Add your dog or cat to start tracking medications, good days and everything in between.",
                ) {
                    Button(onClick = { actions.navigate(Routes.petEdit()) }) { Text("Add a pet") }
                }
            }
            return@Scaffold
        }
        if (pet == null) return@Scaffold

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header") {
                Header(
                    greeting = vm.greeting(),
                    ownerName = state.ownerName,
                    dateLabel = state.today.format(Formats.weekdayDayMonth),
                )
            }
            if (state.pets.size > 1) {
                item(key = "pets") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.pets, key = { it.id }) { p ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { vm.selectPet(p.id) }.padding(4.dp),
                            ) {
                                PetAvatar(p.photoPath, p.species, size = 56.dp, selected = p.id == pet.id)
                                Text(p.name, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            item(key = "hero") {
                HeroCard(
                    petName = pet.name,
                    qol = state.qol,
                    qolIsToday = state.qolDate == state.today,
                    checkedInToday = state.todayCheckIn != null,
                    given = state.givenToday,
                    total = state.todaySlots.size,
                    onCheckIn = { actions.navigate(Routes.checkIn(pet.id)) },
                )
            }
            state.milestone?.let { m ->
                item(key = "milestone") { MilestoneCard(m, onDismiss = vm::dismissMilestone) }
            }
            if (state.insights.isNotEmpty()) {
                items(state.insights, key = { "insight-" + it.title }) { InsightCard(it) }
            }
            if (state.refills.isNotEmpty()) {
                items(state.refills, key = { "refill-" + it.id }) { med ->
                    GpCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer, onClick = { actions.navigate(Routes.meds(pet.id)) }) {
                        Text("Refill ${med.name} soon", style = MaterialTheme.typography.titleMedium)
                        val days = med.daysOfSupplyLeft()
                        Text(
                            if (days != null && days > 0) "About $days days of supply left" else "Supply has run out",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item(key = "meds-header") {
                SectionHeader(
                    title = "Meds today",
                    action = "Manage",
                    onAction = { actions.navigate(Routes.meds(pet.id)) },
                )
            }
            if (state.todaySlots.isEmpty() && state.asNeeded.isEmpty()) {
                item(key = "meds-empty") {
                    GpCard(onClick = { actions.navigate(Routes.medEdit(pet.id)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LocalPharmacy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("No doses scheduled today", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Add a medication to get gentle reminders.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Filled.Add, contentDescription = "Add medication")
                        }
                    }
                }
            }
            items(state.todaySlots, key = { it.key }) { slot ->
                DoseCard(
                    slot = slot,
                    canUndo = ScheduleEngine.canUndo(slot.event, state.now),
                    onGive = { vm.give(slot) },
                    onSkip = { vm.skip(slot) },
                    onUndo = { vm.undo(slot) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (state.asNeeded.isNotEmpty()) {
                item(key = "prn") {
                    GpCard {
                        Text("As needed", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.asNeeded.forEach { med ->
                                AssistChip(
                                    onClick = { vm.giveAsNeeded(med) },
                                    label = { Text("Give ${med.name}") },
                                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp)) },
                                )
                            }
                        }
                    }
                }
            }

            if (state.todayCheckIn == null) {
                item(key = "checkin") {
                    GpCard(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        onClick = { actions.navigate(Routes.checkIn(pet.id)) },
                    ) {
                        Text("How was ${pet.name}'s day?", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A 30-second check-in: appetite, water, mobility, mood, comfort, hygiene and sleep.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(onClick = { actions.navigate(Routes.checkIn(pet.id)) }) { Text("Start check-in") }
                    }
                }
            }

            state.latestWeight?.let { w ->
                item(key = "weight") {
                    GpCard(onClick = { weightDialog = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.FitnessCenter, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(UnitConversion.format(w.weightKg, state.weightUnit), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Last weighed ${Formats.relativeDay(w.date, state.today).lowercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { weightDialog = true }) { Text("Log") }
                        }
                    }
                }
            }

            if (state.upcoming.isNotEmpty()) {
                item(key = "upcoming") { UpcomingSection(state.upcoming) }
            }
            item(key = "disclaimer") {
                Text(
                    "GoldenPaw helps you keep track. It isn't veterinary advice. If something worries you, call your vet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (weightDialog) {
        WeightLogDialog(
            defaultUnit = state.weightUnit,
            onDismiss = { weightDialog = false },
            onSave = { kg, date, notes -> vm.logWeight(kg, date, notes) },
        )
    }
}

@Composable
private fun Header(greeting: String, ownerName: String, dateLabel: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(dateLabel.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            if (ownerName.isBlank()) "$greeting." else "$greeting, $ownerName.",
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

@Composable
private fun HeroCard(
    petName: String,
    qol: QualityOfLifeScore?,
    qolIsToday: Boolean,
    checkedInToday: Boolean,
    given: Int,
    total: Int,
    onCheckIn: () -> Unit,
) {
    GpCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WellnessRing(
                fraction = qol?.fraction,
                size = 132.dp,
                strokeWidth = 12.dp,
                centerTop = qol?.total?.roundToInt()?.toString() ?: "–",
                centerBottom = "of 70",
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(petName, style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        qol == null -> "Check in to see a quality-of-life score."
                        qol.total >= 49 -> "Doing well" + if (qolIsToday) " today" else " recently"
                        qol.total >= QualityOfLifeScore.ACCEPTABLE_THRESHOLD -> "Getting by" + if (qolIsToday) " today" else " recently"
                        else -> "A harder stretch. Be gentle with you both."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (total > 0) Pill("$given of $total doses given")
                Spacer(Modifier.height(6.dp))
                if (!checkedInToday) {
                    TextButton(onClick = onCheckIn, contentPadding = PaddingValues(0.dp)) { Text("Check in now") }
                } else {
                    Pill(
                        "Checked in today",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun DoseCard(
    slot: DoseSlot,
    canUndo: Boolean,
    onGive: () -> Unit,
    onSkip: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wellness = LocalWellnessColors.current
    val reduceMotion = LocalReduceMotion.current
    val view = LocalView.current
    val done = slot.state == SlotState.GIVEN || slot.state == SlotState.SKIPPED
    val container by animateColorAsState(
        when (slot.state) {
            SlotState.GIVEN -> wellness.good.copy(alpha = 0.16f)
            SlotState.MISSED -> MaterialTheme.colorScheme.tertiaryContainer
            SlotState.DUE -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "doseBg",
    )
    GpCard(modifier = modifier.animateContentSize(), containerColor = container, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Formats.time(slot.scheduledAt),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    when (slot.state) {
                        SlotState.MISSED -> Pill("Missed", container = MaterialTheme.colorScheme.tertiary, content = MaterialTheme.colorScheme.onTertiary)
                        SlotState.DUE -> Pill("Due now", container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                        SlotState.SKIPPED -> Pill("Skipped", container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Unit
                    }
                }
                Text(
                    slot.medication.name,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (slot.state == SlotState.SKIPPED) TextDecoration.LineThrough else null,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(slot.dosage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (slot.medication.withFood) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.Restaurant, contentDescription = "With food", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" with food", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                slot.event?.let { e ->
                    if (done) {
                        Text(
                            "${if (slot.state == SlotState.GIVEN) "Given" else "Skipped"} at ${Formats.time(e.actualAt)} by ${e.givenBy}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Pill-check morph: the round "give" button becomes a checkmark when given.
            AnimatedContent(
                targetState = slot.state == SlotState.GIVEN,
                transitionSpec = {
                    if (reduceMotion) fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                    else (scaleIn(initialScale = 0.4f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.4f) + fadeOut())
                },
                label = "giveMorph",
            ) { given ->
                if (given) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(wellness.good),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Given", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                } else if (slot.state != SlotState.SKIPPED) {
                    Button(
                        onClick = {
                            view.performHapticFeedback(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                                else HapticFeedbackConstants.VIRTUAL_KEY,
                            )
                            onGive()
                        },
                        colors = ButtonDefaults.buttonColors(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(48.dp),
                    ) { Text(if (slot.state == SlotState.MISSED) "Give late" else "Given") }
                } else {
                    Spacer(Modifier.size(1.dp))
                }
            }
        }
        if (!done) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onSkip) { Text("Skip this dose") }
            }
        } else if (canUndo || slot.state == SlotState.SKIPPED) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onUndo) { Text("Undo") }
            }
        }
    }
}

@Composable
private fun InsightCard(insight: Insight) {
    val container = when (insight.tone) {
        Insight.Tone.WATCH -> MaterialTheme.colorScheme.tertiaryContainer
        Insight.Tone.POSITIVE -> MaterialTheme.colorScheme.secondaryContainer
        Insight.Tone.INFO -> MaterialTheme.colorScheme.surfaceContainer
    }
    GpCard(containerColor = container, contentPadding = PaddingValues(16.dp)) {
        Row {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(insight.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(insight.body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MilestoneCard(count: Int, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            MilestoneShimmer(Modifier.matchParentSize())
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$count doses of care", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Every one of them mattered. Thank you for showing up.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
            }
        }
    }
}

@Composable
private fun UpcomingSection(upcoming: List<Pair<java.time.LocalDate, List<DoseSlot>>>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    GpCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Next 7 days", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${upcoming.sumOf { it.second.size }} doses scheduled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand")
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                upcoming.forEach { (date, slots) ->
                    Text(Formats.relativeDay(date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    slots.forEach { s ->
                        Row {
                            Text(Formats.time(s.scheduledAt), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(76.dp))
                            Text("${s.medication.name} · ${s.dosage}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private data class FabItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)

@Composable
private fun SpeedDial(expanded: Boolean, onToggle: () -> Unit, items: List<FabItem>) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shadowElevation = 2.dp,
                        ) {
                            Text(item.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        SmallFloatingActionButton(
                            onClick = { onToggle(); item.onClick() },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ) { Icon(item.icon, contentDescription = item.label) }
                    }
                }
            }
        }
        val rotation by androidx.compose.animation.core.animateFloatAsState(if (expanded) 45f else 0f, label = "fabRot")
        FloatingActionButton(onClick = onToggle) {
            Icon(Icons.Filled.Add, contentDescription = if (expanded) "Close menu" else "Quick log", modifier = Modifier.rotate(rotation))
        }
    }
}
