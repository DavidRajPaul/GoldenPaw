package com.goldenpaw.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.goldenpaw.config.AppConfig
import com.goldenpaw.core.format12h
import com.goldenpaw.data.export.DataManager
import com.goldenpaw.domain.model.CloudSession
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.CloudAuthRepository
import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.ThemeMode
import com.goldenpaw.domain.repository.UserSettings
import com.goldenpaw.domain.usecase.CareTeamService
import com.goldenpaw.platform.LocalPlatform
import com.goldenpaw.platform.ReminderHealth
import com.goldenpaw.platform.rememberNotificationPermissionRequest
import com.goldenpaw.ui.common.GpTimePickerDialog
import com.goldenpaw.ui.common.GpTopBar
import com.goldenpaw.ui.designsystem.GpCard
import com.goldenpaw.ui.designsystem.LocalWellnessColors
import com.goldenpaw.ui.designsystem.SectionHeader
import com.goldenpaw.ui.navigation.LocalAppActions
import com.goldenpaw.ui.navigation.Route
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import org.koin.compose.viewmodel.koinViewModel

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val dataManager: DataManager,
    private val reminders: ReminderGateway,
    private val careTeam: CareTeamService,
    auth: CloudAuthRepository,
) : ViewModel() {

    val settings: StateFlow<UserSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    val session: StateFlow<CloudSession?> = auth.session

    fun update(transform: (UserSettings) -> UserSettings, reschedule: Boolean = false) = viewModelScope.launch {
        settingsRepo.update(transform)
        if (reschedule) reminders.rescheduleAll()
    }

    fun setName(name: String) = viewModelScope.launch {
        settingsRepo.update { it.copy(ownerName = name.trim()) }
        careTeam.syncOwnerName(name)
    }

    fun reschedule() = viewModelScope.launch { reminders.rescheduleAll() }

    fun export(onReady: (String) -> Unit) = viewModelScope.launch { onReady(dataManager.exportJson()) }

    fun deleteEverything(onDone: () -> Unit) = viewModelScope.launch {
        dataManager.deleteEverything()
        reminders.rescheduleAll()
        onDone()
    }
}

@Composable
fun SettingsScreen() {
    val vm: SettingsViewModel = koinViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val platform = LocalPlatform.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var health by remember { mutableStateOf(ReminderHealth()) }
    var editName by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var versionTaps by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { health = platform.reminderHealth() }

    Scaffold(
        topBar = { GpTopBar("Settings") },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                GpCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    onClick = if (s.isPlus) null else ({ actions.showPaywall("Unlock everything GoldenPaw can do.") }),
                ) {
                    Text(if (s.isPlus) "GoldenPaw Plus ✨" else "GoldenPaw Free", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (s.isPlus) "Plus is unlocked on this device for the beta. Billing isn't connected yet."
                        else "1 pet, reminders, check-ins, journal, care team and vet reports. Tap to see Plus.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item { SectionHeader("Profile") }
            item { SettingRow(title = "Your name", subtitle = s.ownerName.ifBlank { "Not set" }, onClick = { editName = true }) }
            item {
                SettingRow(
                    title = "Care team",
                    subtitle = session?.let { "Signed in as ${it.email}" } ?: "Family, sitters and who gave each dose",
                    onClick = { actions.navigate(Route.CareTeam) },
                    chevron = true,
                )
            }

            item { SectionHeader("Motivation") }
            item {
                SettingSwitch(
                    "Streaks, levels and badges",
                    if (s.gamificationEnabled) "Celebrate care with gentle rewards" else "Gentle mode: no counters at all",
                    s.gamificationEnabled,
                ) { v -> vm.update({ it.copy(gamificationEnabled = v) }) }
            }
            if (s.gamificationEnabled) {
                item {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("Rest days per week", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Days off that keep your streak alive automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        val options = listOf(0, 1, 2, 3)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            options.forEachIndexed { i, n ->
                                SegmentedButton(
                                    selected = s.streakRestDaysPerWeek == n,
                                    onClick = { vm.update({ it.copy(streakRestDaysPerWeek = n) }) },
                                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                ) { Text("$n") }
                            }
                        }
                    }
                }
                item { SettingRow(title = "Care journey", subtitle = "Badges, level and streak", onClick = { actions.navigate(Route.Achievements) }, chevron = true) }
            }

            item { SectionHeader("Reminders") }
            item {
                SettingSwitch("Medication reminders", "Notifications when doses are due", s.remindersEnabled) { v ->
                    vm.update({ it.copy(remindersEnabled = v) }, reschedule = true)
                }
            }
            item {
                SettingSwitch("Daily check-in nudge", "A gentle evening reminder if you haven't checked in", s.checkInReminderEnabled) { v ->
                    vm.update({ it.copy(checkInReminderEnabled = v) }, reschedule = true)
                }
            }
            if (s.checkInReminderEnabled) {
                item {
                    SettingRow(title = "Check-in reminder time", subtitle = s.checkInReminderTime.format12h(), onClick = { pickTime = true })
                }
            }
            item {
                SettingSwitch("Weekly summary", "A Sunday-evening recap of the week", s.weeklySummaryEnabled) { v ->
                    vm.update({ it.copy(weeklySummaryEnabled = v) }, reschedule = true)
                }
            }
            item {
                val wellness = LocalWellnessColors.current
                SettingRow(
                    title = "Reminders health check",
                    subtitle = if (health.allGood) "Everything looks good" else "Some settings may delay reminders",
                    onClick = { actions.navigate(Route.Reminders) },
                    trailing = {
                        Icon(
                            if (health.allGood) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = if (health.allGood) wellness.good else wellness.hard,
                        )
                    },
                )
            }
            if (platform.supportsWidgets) {
                item {
                    SettingRow(
                        title = "Add home-screen widget",
                        subtitle = "See and log today's doses without opening the app",
                        onClick = {
                            if (!platform.requestPinWidget()) {
                                scope.launch { snackbar.showSnackbar("Long-press your home screen, tap Widgets and find GoldenPaw") }
                            }
                        },
                    )
                }
            }

            item { SectionHeader("Appearance") }
            item {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = s.themeMode == mode,
                                onClick = { vm.update({ it.copy(themeMode = mode) }) },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            ) { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }
                        }
                    }
                }
            }
            if (platform.supportsDynamicColor) {
                item {
                    SettingSwitch("Dynamic color", "Use colors from your wallpaper", s.dynamicColor) { v -> vm.update({ it.copy(dynamicColor = v) }) }
                }
            }
            item {
                SettingSwitch("Reduce motion", "Swap animations for simple fades", s.reduceMotion) { v -> vm.update({ it.copy(reduceMotion = v) }) }
            }
            item {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("Weight unit", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        WeightUnit.entries.forEachIndexed { i, unit ->
                            SegmentedButton(
                                selected = s.weightUnit == unit,
                                onClick = { vm.update({ it.copy(weightUnit = unit) }) },
                                shape = SegmentedButtonDefaults.itemShape(i, WeightUnit.entries.size),
                            ) { Text(unit.label) }
                        }
                    }
                }
            }

            item { SectionHeader("Your data") }
            item {
                SettingRow(
                    title = "Export my data",
                    subtitle = "Everything as a JSON file",
                    onClick = { vm.export { path -> platform.shareFile(path, "application/json", "Export GoldenPaw data") } },
                )
            }
            item {
                SettingRow(
                    title = "Delete all data",
                    subtitle = "Removes every pet, record, photo and setting from this device",
                    onClick = { confirmDelete = true },
                    danger = true,
                )
            }

            item { SectionHeader("About") }
            item { SettingRow(title = "Privacy & disclaimer", subtitle = "How GoldenPaw handles your data", onClick = { showAbout = true }) }
            item {
                SettingRow(
                    title = "Version",
                    subtitle = "${AppConfig.VERSION_NAME} (${AppConfig.VERSION_CODE})",
                    onClick = {
                        versionTaps++
                        if (versionTaps >= 5) {
                            versionTaps = 0
                            vm.reschedule()
                            scope.launch { snackbar.showSnackbar("Reminders re-armed") }
                        }
                    },
                )
            }
        }
    }

    if (editName) {
        var name by remember { mutableStateOf(s.ownerName) }
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text("Your name") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    vm.setName(name)
                    editName = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editName = false }) { Text("Cancel") } },
        )
    }
    if (pickTime) {
        GpTimePickerDialog(
            initial = s.checkInReminderTime,
            onDismiss = { pickTime = false },
            onPicked = { t: LocalTime -> vm.update({ it.copy(checkInReminderTime = t) }, reschedule = true) },
        )
    }
    if (confirmDelete) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete everything?") },
            text = {
                Column {
                    Text(
                        "This permanently deletes all pets, medications, check-ins, journal entries, photos and settings on this device. " +
                            "Shared care teams keep their copy in the cloud. Export first if you want a copy.",
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, label = { Text("Type DELETE to confirm") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typed.trim() == "DELETE",
                    onClick = {
                        confirmDelete = false
                        vm.deleteEverything { platform.restartApp() }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("Privacy & disclaimer") },
            text = {
                Text(
                    "• GoldenPaw keeps your data on this device. Nothing is uploaded unless you turn on care-team sharing. No ads or trackers.\n\n" +
                        "• Weekly summaries (Plus) send that week's logged numbers, without photos, to generate the text.\n\n" +
                        "• You can export or delete everything at any time from Settings.\n\n" +
                        "• GoldenPaw is an informational tool for pet owners. It does not diagnose and is not a substitute " +
                        "for professional veterinary advice. In an emergency, contact your vet or nearest emergency clinic.",
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    danger: Boolean = false,
    chevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) trailing()
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onChange(!checked) }.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ------------------------------------------------------------------ Reminders health check

@Composable
fun RemindersHealthScreen() {
    val vm: SettingsViewModel = koinViewModel()
    val actions = LocalAppActions.current
    val platform = LocalPlatform.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var health by remember { mutableStateOf(ReminderHealth()) }
    val requestPermission = rememberNotificationPermissionRequest { health = platform.reminderHealth() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        health = platform.reminderHealth()
        vm.reschedule()
    }

    Scaffold(
        topBar = { GpTopBar("Reminders health check", onBack = actions.back) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Some phones pause apps to save battery, which can delay medication reminders. " +
                        "These checks make reminders as reliable as possible.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                HealthItem(health.notifications, "Notifications allowed", "Needed to show reminders at all.", "Allow") {
                    requestPermission()
                }
            }
            if (health.exactAlarmsApplicable) {
                item {
                    HealthItem(
                        health.exactAlarms,
                        "Alarms & reminders permission",
                        "Lets reminders fire at the exact minute instead of being batched by the system.",
                        "Open settings",
                        platform::openExactAlarmSettings,
                    )
                }
            }
            if (health.batteryApplicable) {
                item {
                    HealthItem(
                        health.batteryUnrestricted,
                        "Battery: unrestricted",
                        "On Xiaomi, Samsung, OnePlus, Oppo and Vivo phones, set GoldenPaw's battery usage to " +
                            "\"Unrestricted\" and allow auto-start if offered.",
                        "Open settings",
                        platform::openBatterySettings,
                    )
                }
            }
            item {
                GpCard {
                    Text("Send a test reminder", style = MaterialTheme.typography.titleMedium)
                    Text("Checks that notifications appear on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        val ok = platform.sendTestNotification()
                        scope.launch { snackbar.showSnackbar(if (ok) "Test sent" else "Notifications are blocked") }
                    }) { Text("Send test") }
                }
            }
        }
    }
}

@Composable
private fun HealthItem(ok: Boolean, title: String, body: String, action: String, onFix: () -> Unit) {
    val wellness = LocalWellnessColors.current
    GpCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                contentDescription = if (ok) "OK" else "Needs attention",
                tint = if (ok) wellness.good else wellness.hard,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!ok) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onFix) { Text(action) }
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.background)
}
