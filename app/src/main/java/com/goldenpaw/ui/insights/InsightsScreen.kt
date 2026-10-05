package com.goldenpaw.ui.insights

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PictureAsPdf
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldenpaw.domain.logic.UnitConversion
import com.goldenpaw.domain.model.QualityOfLifeScore
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
import com.goldenpaw.ui.designsystem.ScoreBar
import com.goldenpaw.ui.designsystem.StatTile
import com.goldenpaw.ui.designsystem.WellnessRing
import com.goldenpaw.ui.koinVm
import java.io.File
import kotlin.math.roundToInt

@Composable
fun InsightsScreen() {
    val vm: InsightsViewModel = koinVm()
    val state by vm.state.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Quality of life", "Weight", "Vet report")

    Scaffold(
        topBar = {
            Column {
                GpTopBar(state.pet?.let { "${it.name}'s insights" } ?: "Insights")
                TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
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
        when (tab) {
            0 -> QolTab(state, contentPadding)
            1 -> WeightTab(state, contentPadding, onAdd = vm::addWeight, onDelete = vm::deleteWeight)
            else -> ReportTab(state, report, contentPadding, vm)
        }
    }
}

@Composable
private fun QolTab(state: InsightsState, padding: PaddingValues) {
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
                            state.latestScoreDate?.let { Formats.relativeDay(it, state.today) } ?: "No check-ins yet",
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
                GoodDayCalendar(days = state.calendar, today = state.today)
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
                        points = state.qolTrend.map { (d, v) -> ChartPoint(d.toEpochDay().toFloat(), v.toFloat(), d.format(Formats.dayMonth)) },
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

@Composable
private fun WeightTab(
    state: InsightsState,
    padding: PaddingValues,
    onAdd: (Double, java.time.LocalDate, String) -> Unit,
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
                                "${if (pct >= 0) "+" else ""}${"%.1f".format(pct)}% since ${first.date.format(Formats.dayMonthYear)}",
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
                            ChartPoint(it.date.toEpochDay().toFloat(), UnitConversion.fromKg(it.weightKg, state.unit).toFloat(), it.date.format(Formats.dayMonth))
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
            GpCard(contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(UnitConversion.format(w.weightKg, state.unit), style = MaterialTheme.typography.titleSmall)
                        Text(
                            w.date.format(Formats.dayMonthYear) + if (w.notes.isNotBlank()) " · ${w.notes}" else "",
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

@Composable
private fun ReportTab(state: InsightsState, report: ReportUiState, padding: PaddingValues, vm: InsightsViewModel) {
    val context = LocalContext.current
    var custom by rememberSaveable { mutableStateOf(false) }
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item {
            GpCard {
                Text("Vet-ready report", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A one-tap PDF with medications and adherence, quality-of-life trend, weight curve and symptom timeline. " +
                        "Made on your phone. Nothing is uploaded.",
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
                            date = report.from(state.today),
                            onDate = { d -> vm.updateReport { it.copy(customFrom = d) } },
                            modifier = Modifier.weight(1f),
                        )
                        DateField(
                            label = "To",
                            date = report.to(state.today),
                            onDate = { d -> vm.updateReport { it.copy(customTo = d) } },
                            minDate = report.from(state.today),
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
        val file = report.file
        if (file != null) {
            item {
                GpCard {
                    Text("Ready to share", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    report.preview?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Report preview, page 1",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { sharePdf(context, file) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                        OutlinedButton(onClick = { openPdf(context, file) }, modifier = Modifier.weight(1f)) { Text("Open") }
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

private fun uriFor(context: Context, file: File) =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

private fun sharePdf(context: Context, file: File) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
        putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension.replace('_', ' '))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share vet report"))
}

private fun openPdf(context: Context, file: File) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uriFor(context, file), "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        sharePdf(context, file)
    }
}
