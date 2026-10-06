package com.goldenpaw.ui.today

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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldenpaw.LocalGamification
import com.goldenpaw.core.Fmt
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.domain.model.CareTeam
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.GamificationState
import com.goldenpaw.domain.model.Insight
import com.goldenpaw.domain.model.MemberStatus
import com.goldenpaw.domain.model.QualityOfLifeScore
import com.goldenpaw.domain.model.SlotState
import com.goldenpaw.platform.rememberHaptics
import com.goldenpaw.ui.common.Formats
import com.goldenpaw.ui.common.WeightLogDialog
import com.goldenpaw.ui.designsystem.AttributionChip
import com.goldenpaw.ui.designsystem.CareRingsView
import com.goldenpaw.ui.designsystem.CaregiverAvatar
import com.goldenpaw.ui.designsystem.EmptyState
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.LevelBar
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.LocalWellnessColors
import com.goldenpaw.ui.designsystem.MilestoneShimmer
import com.goldenpaw.ui.designsystem.Motion
import com.goldenpaw.ui.designsystem.PetAvatar
import com.goldenpaw.ui.designsystem.Pill
import com.goldenpaw.ui.designsystem.SectionHeader
import com.goldenpaw.ui.designsystem.SkeletonCard
import com.goldenpaw.ui.designsystem.StreakDots
import com.goldenpaw.ui.designsystem.WellnessRing
import com.goldenpaw.ui.designsystem.rememberStaggerState
import com.goldenpaw.ui.designsystem.staggerIn
import com.goldenpaw.ui.navigation.LocalAppActions
import com.goldenpaw.ui.navigation.Route
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

@Composable
fun TodayScreen() {
    val vm: TodayViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val gamification = LocalGamification.current
    val actions = LocalAppActions.current
    val snackbar = remember { SnackbarHostState() }
    var weightDialog by rememberSaveable { mutableStateOf(false) }
    var switcherOpen by remember { mutableStateOf(false) }
    var alreadyGiven by remember { mutableStateOf<TodayEvent.AlreadyGiven?>(null) }
    val listState = rememberLazyListState()
    val stagger = rememberStaggerState()

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
                is TodayEvent.AlreadyGiven -> alreadyGiven = event
                is TodayEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    val pet = state.pet
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (pet != null) {
                ExtendedFloatingActionButton(
                    onClick = actions.openQuickLog,
                    expanded = fabExpanded,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Quick log") },
                )
            }
        },
    ) { padding ->
        if (state.loading) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SkeletonCard(height = 56.dp)
                SkeletonCard(height = 168.dp)
                SkeletonCard()
                SkeletonCard()
            }
            return@Scaffold
        }
        if (pet == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    emoji = "🐾",
                    title = "Let's add your companion",
                    body = "Add your dog or cat to start tracking medications, good days and everything in between.",
                ) {
                    Button(onClick = { actions.navigate(Route.PetEdit()) }) { Text("Add a pet") }
                }
            }
            return@Scaffold
        }
        val today = state.today ?: return@Scaffold

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header", contentType = "header") {
                Header(
                    greeting = vm.greeting(),
                    ownerName = state.team?.me?.displayName ?: state.ownerName,
                    dateLabel = Fmt.weekdayDayMonth(today),
                    team = state.team,
                    onWho = { switcherOpen = true },
                )
            }
            if (state.pets.size > 1) {
                item(key = "pets", contentType = "pets") {
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
            item(key = "hero", contentType = "hero") {
                HeroCard(
                    petName = pet.name,
                    qol = state.qol,
                    qolIsToday = state.qolDate == today,
                    checkedInToday = state.todayCheckIn != null,
                    given = state.givenToday,
                    total = state.todaySlots.size,
                    onCheckIn = { actions.navigate(Route.CheckIn(pet.id)) },
                    modifier = Modifier.staggerIn(stagger, "hero", 0),
                )
            }
            if (gamification != null) {
                item(key = "care", contentType = "care") {
                    CareProgressCard(
                        state = gamification,
                        onClick = { actions.navigate(Route.Achievements) },
                        modifier = Modifier.staggerIn(stagger, "care", 1),
                    )
                }
            }
            state.doubleDoses.forEach { slot ->
                item(key = "double-${slot.key}", contentType = "warning") {
                    DoubleDoseCard(slot, state.team, Modifier.animateItem())
                }
            }
            state.milestone?.let { m ->
                item(key = "milestone", contentType = "milestone") { MilestoneCard(m, onDismiss = vm::dismissMilestone) }
            }
            if (state.insights.isNotEmpty()) {
                items(state.insights, key = { "insight-" + it.title }, contentType = { "insight" }) {
                    InsightCard(it, Modifier.animateItem())
                }
            }
            state.upcomingVisit?.let { visit ->
                item(key = "visit", contentType = "visit") {
                    GpCard(
                        onClick = { actions.navigate(Route.VetVisitEdit(pet.id, visit.id)) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.animateItem(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LocalHospital, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(visit.title.ifBlank { "Vet visit" }, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${Formats.relativeDay(Formats.date(visit.at), today)} at ${Formats.time(visit.at)}" +
                                        if (visit.clinic.isNotBlank()) " · ${visit.clinic}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            if (state.refills.isNotEmpty()) {
                items(state.refills, key = { "refill-" + it.id }, contentType = { "refill" }) { med ->
                    GpCard(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        onClick = { actions.navigate(Route.Meds(pet.id)) },
                        modifier = Modifier.animateItem(),
                    ) {
                        Text("Refill ${med.name} soon", style = MaterialTheme.typography.titleMedium)
                        val days = med.daysOfSupplyLeft()
                        Text(
                            if (days != null && days > 0) "About $days days of supply left" else "Supply has run out",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item(key = "meds-header", contentType = "section") {
                SectionHeader(title = "Meds today", action = "Manage", onAction = { actions.navigate(Route.Meds(pet.id)) })
            }
            if (state.todaySlots.isEmpty() && state.asNeeded.isEmpty()) {
                item(key = "meds-empty", contentType = "empty") {
                    GpCard(onClick = { actions.navigate(Route.MedEdit(pet.id)) }) {
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
            state.todaySlots.forEachIndexed { index, slot ->
                item(key = slot.key, contentType = "dose") {
                    DoseCard(
                        slot = slot,
                        team = state.team,
                        canUndo = ScheduleEngine.canUndo(slot.event, state.now),
                        onGive = { vm.give(slot) },
                        onSkip = { vm.skip(slot) },
                        onUndo = { vm.undo(slot) },
                        modifier = Modifier.animateItem().staggerIn(stagger, slot.key, index + 2),
                    )
                }
            }
            if (state.asNeeded.isNotEmpty()) {
                item(key = "prn", contentType = "prn") {
                    GpCard {
                        Text("As needed", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                item(key = "checkin", contentType = "checkin") {
                    GpCard(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        onClick = { actions.navigate(Route.CheckIn(pet.id)) },
                        modifier = Modifier.animateItem(),
                    ) {
                        Text("How was ${pet.name}'s day?", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A 30-second check-in: appetite, water, mobility, mood, comfort, hygiene and sleep.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(onClick = { actions.navigate(Route.CheckIn(pet.id)) }) { Text("Start check-in") }
                    }
                }
            }

            item(key = "week", contentType = "week") {
                WeekCard(
                    petName = pet.name,
                    headline = state.weekSummary?.headline,
                    isPlus = state.isPlus,
                    onClick = { actions.navigate(Route.Insights) },
                )
            }

            gamification?.memories?.firstOrNull()?.let { memory ->
                item(key = "memory", contentType = "memory") {
                    GpCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Text("${memory.label} · ${Fmt.dayMonthYear(memory.date)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text(memory.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            state.latestWeight?.let { w ->
                item(key = "weight", contentType = "weight") {
                    GpCard(onClick = { weightDialog = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.FitnessCenter, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(UnitConversion.format(w.weightKg, state.weightUnit), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Last weighed ${Formats.relativeDay(w.date, today).lowercase()}",
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
                item(key = "upcoming", contentType = "upcoming") { UpcomingSection(state.upcoming, today) }
            }
            item(key = "disclaimer", contentType = "disclaimer") {
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
    if (switcherOpen) {
        CaregiverSwitcher(
            team = state.team,
            onPick = { vm.switchCaregiver(it); switcherOpen = false },
            onManage = { switcherOpen = false; actions.navigate(Route.CareTeam) },
            onDismiss = { switcherOpen = false },
        )
    }
    alreadyGiven?.let { event ->
        val who = event.existing.givenBy.ifBlank { "Someone" }
        AlertDialog(
            onDismissRequest = { alreadyGiven = null },
            icon = { Icon(Icons.Outlined.Warning, contentDescription = null) },
            title = { Text("Already given") },
            text = {
                Text(
                    "$who gave ${event.slot.medication.name} at ${Formats.time(event.existing.actualAt)}. " +
                        "Giving it again could mean a double dose. Only log another if you're sure it was given twice.",
                )
            },
            confirmButton = { TextButton(onClick = { alreadyGiven = null }) { Text("Don't log") } },
            dismissButton = {
                TextButton(onClick = { vm.giveAnyway(event.slot); alreadyGiven = null }) {
                    Text("Log anyway", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
private fun Header(greeting: String, ownerName: String, dateLabel: String, team: CareTeam?, onWho: () -> Unit) {
    Row(Modifier.padding(top = 8.dp).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(dateLabel.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                if (ownerName.isBlank()) "$greeting." else "$greeting, $ownerName.",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        val me = team?.me
        if (team != null) {
            Surface(
                onClick = onWho,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CaregiverAvatar(me, size = 32.dp, fallbackName = ownerName)
                    val others = team.activeMembers.size - 1
                    if (others > 0) {
                        Text("+$others", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
            }
        }
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
    modifier: Modifier = Modifier,
) {
    GpCard(containerColor = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
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
                AnimatedContent(targetState = checkedInToday, label = "checkedIn") { done ->
                    if (!done) {
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
}

/** Today's rings, streak and level in one glanceable card. */
@Composable
private fun CareProgressCard(state: GamificationState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GpCard(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CareRingsView(state.rings, size = 84.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val streak = state.streak
                Text(
                    when {
                        streak.current == 0 -> "Start a care streak today"
                        streak.graduated -> "${streak.current} days of care · habit established"
                        else -> "${streak.current}-day care streak"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                StreakDots(streak.lastSeven)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${state.rings.dosesGiven}/${state.rings.dosesDue} doses · " +
                        (if (state.rings.checkedIn) "checked in" else "check-in to go") +
                        " · ${streak.restDaysLeftThisWeek} rest ${if (streak.restDaysLeftThisWeek == 1) "day" else "days"} left",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        LevelBar(
            title = state.level.title,
            levelNumber = state.level.number,
            progress = state.levelProgress,
            caption = if (state.pointsToday > 0) "+${state.pointsToday} today" else "${state.points} pts",
        )
    }
}

@Composable
private fun DoubleDoseCard(slot: DoseSlot, team: CareTeam?, modifier: Modifier = Modifier) {
    GpCard(containerColor = MaterialTheme.colorScheme.errorContainer, modifier = modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Possible double dose: ${slot.medication.name}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    slot.givenEvents.joinToString(" and ") { "${it.givenBy.ifBlank { "someone" }} at ${Formats.time(it.actualAt)}" } +
                        " both marked the ${Formats.time(slot.scheduledAt)} dose as given. If a second dose really was given, " +
                        "it may be worth calling your vet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (team != null) Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun DoseCard(
    slot: DoseSlot,
    team: CareTeam?,
    canUndo: Boolean,
    onGive: () -> Unit,
    onSkip: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wellness = LocalWellnessColors.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val done = slot.state == SlotState.GIVEN || slot.state == SlotState.SKIPPED
    val container by animateColorAsState(
        when (slot.state) {
            SlotState.GIVEN -> wellness.good.copy(alpha = 0.16f)
            SlotState.MISSED -> MaterialTheme.colorScheme.tertiaryContainer
            SlotState.DUE -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(Motion.MEDIUM2),
        label = "doseBg",
    )
    GpCard(modifier = modifier.animateContentSize(Motion.spatialDefault()), containerColor = container, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Formats.time(slot.scheduledAt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
                val event = slot.event
                AnimatedVisibility(visible = done && event != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    if (event != null) {
                        val member = team?.member(event.givenById)
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AttributionChip(
                                name = event.givenBy.ifBlank { "You" },
                                colorIndex = member?.colorIndex,
                                prefix = "${if (slot.state == SlotState.GIVEN) "Given" else "Skipped"} ${Formats.time(event.actualAt)} · ",
                            )
                        }
                    }
                }
            }
            // Pill → check morph: the round "Given" button becomes a checkmark with a springy pop.
            AnimatedContent(
                targetState = slot.state == SlotState.GIVEN,
                transitionSpec = {
                    if (reduceMotion) fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                    else (scaleIn(Motion.bouncy(), initialScale = 0.3f) + fadeIn(tween(Motion.SHORT4))) togetherWith
                        (scaleOut(tween(Motion.SHORT4), targetScale = 0.4f) + fadeOut(tween(Motion.SHORT2)))
                },
                label = "giveMorph",
            ) { given ->
                if (given) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(wellness.good), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, contentDescription = "Given", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                } else if (slot.state != SlotState.SKIPPED) {
                    Button(
                        onClick = {
                            haptics.confirm()
                            onGive()
                        },
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
private fun InsightCard(insight: Insight, modifier: Modifier = Modifier) {
    val container = when (insight.tone) {
        Insight.Tone.WATCH -> MaterialTheme.colorScheme.tertiaryContainer
        Insight.Tone.POSITIVE -> MaterialTheme.colorScheme.secondaryContainer
        Insight.Tone.INFO -> MaterialTheme.colorScheme.surfaceContainer
    }
    GpCard(containerColor = container, contentPadding = PaddingValues(16.dp), modifier = modifier) {
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
private fun WeekCard(petName: String, headline: String?, isPlus: Boolean, onClick: () -> Unit) {
    GpCard(onClick = onClick, containerColor = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("$petName's week", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    headline ?: if (isPlus) "Read this week's summary" else "Weekly summaries come with Plus",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun MilestoneCard(count: Int, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Box {
            MilestoneShimmer(Modifier.matchParentSize())
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$count doses of care", style = MaterialTheme.typography.titleLarge)
                    Text("Every one of them mattered. Thank you for showing up.", style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
            }
        }
    }
}

@Composable
private fun UpcomingSection(upcoming: List<Pair<LocalDate, List<DoseSlot>>>, today: LocalDate) {
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
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(Motion.spatialDefault()) + fadeIn(),
            exit = shrinkVertically(Motion.spatialFast()) + fadeOut(),
        ) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                upcoming.forEach { (date, slots) ->
                    Text(Formats.relativeDay(date, today), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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

/** "Who's logging?" for shared devices (family tablet) plus a shortcut to the care team. */
@Composable
private fun CaregiverSwitcher(team: CareTeam?, onPick: (String) -> Unit, onManage: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Who's logging?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                "Doses and check-ins are credited to this person on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            val pickable = team?.members.orEmpty().filter { it.status == MemberStatus.ACTIVE && (it.userId == null || it.id == team?.me?.id) }
            pickable.forEach { member ->
                ListItem(
                    headlineContent = { Text(member.displayName) },
                    supportingContent = { Text(member.role.label) },
                    leadingContent = { CaregiverAvatar(member, size = 40.dp) },
                    trailingContent = { if (member.id == team?.me?.id) Icon(Icons.Filled.Check, contentDescription = "Current") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick(member.id) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onManage, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Manage care team") }
        }
    }
}
