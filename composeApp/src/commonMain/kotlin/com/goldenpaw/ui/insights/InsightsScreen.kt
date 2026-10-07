package com.goldenpaw.ui.insights

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldenpaw.core.Fmt
import com.goldenpaw.core.epochDay
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.signedPercent
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.domain.model.QualityOfLifeScore
import com.goldenpaw.domain.model.SummaryDisclaimer
import com.goldenpaw.domain.model.WeeklyDigest
import com.goldenpaw.domain.model.WeeklySummary
import com.goldenpaw.platform.LocalPlatform
import com.goldenpaw.platform.rememberPdfPreview
import com.goldenpaw.ui.common.DateField
import com.goldenpaw.ui.common.Formats
import com.goldenpaw.ui.common.GpTopBar
import com.goldenpaw.ui.common.WeightLogDialog
import com.goldenpaw.ui.designsystem.AnimatedLineChart
import com.goldenpaw.ui.designsystem.ChartPoint
import com.goldenpaw.ui.designsystem.ChoiceChips
import com.goldenpaw.ui.designsystem.EmptyState
import com.goldenpaw.ui.designsystem.GoodDayCalendar
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.LocalReduceMotion
import com.goldenpaw.ui.designsystem.Motion
import com.goldenpaw.ui.designsystem.Pill
import com.goldenpaw.ui.designsystem.ScoreBar
import com.goldenpaw.ui.designsystem.SkeletonCard
import com.goldenpaw.ui.designsystem.StatTile
import com.goldenpaw.ui.designsystem.WellnessRing
import com.goldenpaw.ui.navigation.LocalAppActions
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

@Composable
fun InsightsScreen() {
    val vm: InsightsViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val weekly by vm.weekly.collectAsStateWithLifecycle()
    val reduceMotion = LocalReduceMotion.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Weekly", "Quality of life", "Weight", "Vet report")

    LaunchedEffect(state.pet?.id, state.isPlus) {
        if (state.pet != null) {
            val start = weekly.weekStart
            if (start != null) vm.loadWeek(start) else vm.loadWeek()
        }
    }

    Scaffold(
        topBar = {
            Column {
                GpTopBar(state.pet?.let { "${it.name}'s insights" } ?: "Insights")
                ScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 12.dp) {
                    tabs.forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.pet == null) {
            if (!state.loading) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    EmptyState("📈", "No insights yet", "Add a pet and do a few check-ins to see trends here.")
                }
            }
            return@Scaffold
        }
        val contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 32.dp,
        )
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                if (reduceMotion) fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                else {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(Motion.MEDIUM2, easing = Motion.EmphasizedDecelerate)) { it / 8 * dir } + fadeIn(tween(Motion.MEDIUM2))) togetherWith
                        (slideOutHorizontally(tween(Motion.SHORT4, easing = Motion.EmphasizedAccelerate)) { -it / 8 * dir } + fadeOut(tween(Motion.SHORT2)))
                }
            },
            label = "insightsTab",
        ) { selected ->
            when (selected) {
                0 -> WeeklyTab(state, weekly, contentPadding, vm)
                1 -> QolTab(state, contentPadding)
                2 -> WeightTab(state, contentPadding, onAdd = vm::addWeight, onDelete = vm::deleteWeight)
                else -> ReportTab(state, report, contentPadding, vm)
            }
        }
    }
}

// ------------------------------------------------------------------ Weekly

@Composable
private fun WeeklyTab(state: InsightsState, weekly: WeeklyUiState, padding: PaddingValues, vm: InsightsViewModel) {
    val actions = LocalAppActions.current
    val platform = LocalPlatform.current
    val start = weekly.weekStart
    val isCurrentWeek = start != null && state.today != null && start.plusDays(6) >= state.today
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "nav") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previousWeek) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (isCurrentWeek) "This week" else "Week of", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(start?.let { Fmt.range(it, it.plusDays(6)) } ?: "", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = vm::nextWeek, enabled = !isCurrentWeek) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week")
                }
            }
        }
        weekly.digest?.let { d -> item(key = "stats") { DigestStats(d) } }
        item(key = "summary") {
            when {
                !state.isPlus -> LockedSummaryCard(state.pet?.name.orEmpty()) {
                    actions.showPaywall("Weekly summaries turn your logs into a short, kind story of the week, with things worth mentioning to your vet.")
                }
                weekly.generating && weekly.summary == null -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonCard(height = 28.dp)
                    SkeletonCard(height = 140.dp)
                }
                weekly.summary != null -> SummaryCard(
                    summary = weekly.summary,
                    refreshing = weekly.generating,
                    onRefresh = { start?.let { vm.loadWeek(it, force = true) } },
                    onShare = {
                        val s = weekly.summary
                        platform.share(shareText(state.pet?.name.orEmpty(), s), subject = s.headline)
                    },
                )
                else -> GpCard {
                    Text("Not enough logged yet", style = MaterialTheme.typography.titleMedium)
                    Text(weekly.error ?: "Log a few doses or check-ins and the summary will appear here.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val older = state.summaries.filter { it.weekStart != start }
        if (state.isPlus && older.isNotEmpty()) {
            item(key = "olderHeader") {
                Text("Earlier weeks", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            }
            items(older, key = { "w-${it.id}" }) { s ->
                GpCard(onClick = { vm.loadWeek(s.weekStart) }, contentPadding = PaddingValues(14.dp), modifier = Modifier.animateItem()) {
                    Text(Fmt.range(s.weekStart, s.weekStart.plusDays(6)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(s.headline, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

private fun shareText(petName: String, s: WeeklySummary): String = buildString {
    appendLine("$petName's week: ${s.headline}")
    appendLine()
    appendLine(s.summary)
    if (s.highlights.isNotEmpty()) {
        appendLine()
        s.highlights.forEach { appendLine("• $it") }
    }
    if (s.watchItems.isNotEmpty()) {
        appendLine()
        appendLine("Worth keeping an eye on:")
        s.watchItems.forEach { appendLine("• $it") }
    }
    appendLine()
    append("From GoldenPaw. ${SummaryDisclaimer.SHORT}.")
}

@Composable
private fun DigestStats(d: WeeklyDigest) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(d.adherence?.let { "${(it * 100).roundToInt()}%" } ?: "–", "Doses given")
        StatTile("${d.checkIns}/7", "Check-ins")
        StatTile("${d.goodDays}", "Good days")
        StatTile("${d.symptomCounts.values.sum()}", "Symptoms")
    }
}

@Composable
private fun SummaryCard(summary: WeeklySummary, refreshing: Boolean, onRefresh: () -> Unit, onShare: () -> Unit) {
    GpCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(summary.source.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh summary") }
            IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, contentDescription = "Share summary") }
        }
        Text(summary.headline, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(summary.summary, style = MaterialTheme.typography.bodyLarge)
        if (summary.highlights.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Highlights", style = MaterialTheme.typography.titleSmall)
            summary.highlights.forEach { BulletLine("✓", it) }
        }
        if (summary.watchItems.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Worth keeping an eye on", style = MaterialTheme.typography.titleSmall)
            summary.watchItems.forEach { BulletLine("•", it) }
        }
        if (summary.vetQuestions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Questions you might bring to your vet", style = MaterialTheme.typography.titleSmall)
            summary.vetQuestions.forEach { BulletLine("?", it) }
        }
        Spacer(Modifier.height(14.dp))
        Pill(SummaryDisclaimer.SHORT, container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer)
        Spacer(Modifier.height(6.dp))
        Text(SummaryDisclaimer.FULL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BulletLine(mark: String, text: String) {
    Row(Modifier.padding(top = 6.dp)) {
        Text(mark, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LockedSummaryCard(petName: String, onUnlock: () -> Unit) {
    GpCard(containerColor = MaterialTheme.colorScheme.surfaceContainer, onClick = onUnlock) {
        Box {
            Column(Modifier.blur(6.dp)) {
                Text("A brighter week for $petName", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "$petName had five good days and two okay days. Every dose was given on time, and mobility " +
                        "scores were up compared with last week...",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Column(Modifier.matchParentSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.Lock, contentDescription = null)
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(onClick = onUnlock) { Text("Unlock weekly summaries") }
            }
        }
    }
}

// ------------------------------------------------------------------ Quality of life

@Composable
private fun QolTab(state: InsightsState, padding: PaddingValues) {
    val today = state.today ?: return
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item {
            GpCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WellnessRing(
                        fraction = state.latestScore?.fraction,
                        size = 120.dp,
                        strokeWidth = 11.dp,
                        centerTop = state.latestScore?.total?.roundToInt()?.toString() ?: "–",
                        centerBottom = "of 70",
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Latest score", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.latestScoreDate?.let { Formats.relativeDay(it, today) } ?: "No check-ins yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "HHHHHMM scale. Above ${QualityOfLifeScore.ACCEPTABLE_THRESHOLD.toInt()} is generally considered acceptable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.latestScore?.let { score ->
                    Spacer(Modifier.height(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        score.parts().forEach { (label, value) -> ScoreBar(label, value) }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${state.good30}", "Good days")
                StatTile("${state.okay30}", "Okay days")
                StatTile("${state.hard30}", "Hard days")
            }
        }
        item {
            GpCard {
                Text("Good days calendar", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                GoodDayCalendar(days = state.calendar, today = today)
            }
        }
        item {
            GpCard {
                Text("30-day trend", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                if (state.qolTrend.size < 2) {
                    Text(
                        "A trend line appears after two or more check-ins.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AnimatedLineChart(
                        points = state.qolTrend.map { (d, v) -> ChartPoint(d.epochDay().toFloat(), v.toFloat(), Fmt.dayMonth(d)) },
                        minY = 0f,
                        maxY = 70f,
                        threshold = 35f,
                        yLabel = { it.roundToInt().toString() },
                        contentDescription = "Quality of life over the last 30 days",
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(state.adherence30?.let { "${(it * 100).roundToInt()}%" } ?: "–", "Doses given (30d)")
                StatTile("${state.symptomCount30}", "Symptoms logged (30d)")
            }
        }
    }
}

// ------------------------------------------------------------------ Weight

@Composable
private fun WeightTab(
    state: InsightsState,
    padding: PaddingValues,
    onAdd: (Double, kotlinx.datetime.LocalDate, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var dialog by rememberSaveable { mutableStateOf(false) }
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            GpCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Weight", style = MaterialTheme.typography.titleMedium)
                        val latest = state.weights.lastOrNull()
                        val first = state.weights.firstOrNull()
                        Text(
                            if (latest == null) "No weights yet" else UnitConversion.format(latest.weightKg, state.unit),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        if (latest != null && first != null && first !== latest && first.weightKg > 0) {
                            val pct = (latest.weightKg - first.weightKg) / first.weightKg * 100
                            Text(
                                "${pct.signedPercent()} since ${Fmt.dayMonthYear(first.date)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    FilledTonalButton(onClick = { dialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Log")
                    }
                }
                if (state.weights.size >= 2) {
                    Spacer(Modifier.height(16.dp))
                    AnimatedLineChart(
                        points = state.weights.map {
                            ChartPoint(it.date.epochDay().toFloat(), UnitConversion.fromKg(it.weightKg, state.unit).toFloat(), Fmt.dayMonth(it.date))
                        },
                        contentDescription = "Weight trend",
                    )
                    Text(
                        "Stored in kg and converted for display, so reports are always unit-safe.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        items(state.weights.reversed(), key = { it.id }) { w ->
            GpCard(contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), modifier = Modifier.animateItem()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(UnitConversion.format(w.weightKg, state.unit), style = MaterialTheme.typography.titleSmall)
                        Text(
                            Fmt.dayMonthYear(w.date) + if (w.notes.isNotBlank()) " · ${w.notes}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onDelete(w.id) }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete entry") }
                }
            }
        }
    }
    if (dialog) {
        WeightLogDialog(defaultUnit = state.unit, onDismiss = { dialog = false }, onSave = onAdd)
    }
}

// ------------------------------------------------------------------ Vet report

@Composable
private fun ReportTab(state: InsightsState, report: ReportUiState, padding: PaddingValues, vm: InsightsViewModel) {
    val platform = LocalPlatform.current
    val today = state.today ?: return
    var custom by rememberSaveable { mutableStateOf(false) }
    val preview = rememberPdfPreview(report.filePath)
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item {
            GpCard {
                Text("Vet-ready report", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A one-tap PDF with medications and adherence, quality-of-life trend, weight curve and symptom timeline. " +
                        "Made on your device. Nothing is uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Text("Period", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                ChoiceChips(
                    options = ReportRange.entries.toList(),
                    selected = { !custom && it == report.range },
                    onToggle = { r ->
                        custom = false
                        vm.updateReport { it.copy(range = r, customFrom = null, customTo = null) }
                    },
                    label = { it.label },
                )
                TextButton(onClick = { custom = !custom }) { Text(if (custom) "Use a preset" else "Custom dates") }
                if (custom) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DateField(
                            label = "From",
                            date = report.from(today),
                            onDate = { d -> vm.updateReport { it.copy(customFrom = d) } },
                            modifier = Modifier.weight(1f),
                        )
                        DateField(
                            label = "To",
                            date = report.to(today),
                            onDate = { d -> vm.updateReport { it.copy(customTo = d) } },
                            minDate = report.from(today),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Include", style = MaterialTheme.typography.titleSmall)
                CheckRow("Medications & adherence", report.includeMeds) { v -> vm.updateReport { it.copy(includeMeds = v) } }
                CheckRow("Quality of life", report.includeQol) { v -> vm.updateReport { it.copy(includeQol = v) } }
                CheckRow("Weight", report.includeWeight) { v -> vm.updateReport { it.copy(includeWeight = v) } }
                CheckRow("Symptom journal", report.includeSymptoms) { v -> vm.updateReport { it.copy(includeSymptoms = v) } }
                CheckRow("Check-in notes", report.includeNotes) { v -> vm.updateReport { it.copy(includeNotes = v) } }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.generateReport() },
                    enabled = !report.generating,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    if (report.generating) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Generate PDF")
                    }
                }
                report.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        val path = report.filePath
        if (path != null) {
            item {
                GpCard {
                    Text("Ready to share", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    if (preview != null) {
                        Image(
                            bitmap = preview,
                            contentDescription = "Report preview, page 1",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { platform.shareFile(path, "application/pdf", "Share vet report") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                        OutlinedButton(onClick = { platform.openFile(path, "application/pdf") }, modifier = Modifier.weight(1f)) { Text("Open") }
                    }
                }
            }
        }
        item {
            Text(
                "The report is an owner-kept record to support your vet's judgement. It isn't a diagnosis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
