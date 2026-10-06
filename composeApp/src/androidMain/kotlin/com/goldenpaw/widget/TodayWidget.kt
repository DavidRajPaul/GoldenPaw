package com.goldenpaw.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.goldenpaw.MainActivity
import com.goldenpaw.core.format12h
import com.goldenpaw.core.toLocalDate
import com.goldenpaw.core.toLocalTime
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.SlotState
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.usecase.LogDoseUseCase
import com.goldenpaw.domain.usecase.ObserveDoseSlotsUseCase
import com.goldenpaw.reminders.NotificationHelper
import com.goldenpaw.ui.LaunchRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Snapshot of what the widget shows. */
private data class WidgetSlot(val medId: String, val scheduledAtMillis: Long, val title: String, val time: String, val state: SlotState, val givenBy: String?)

private data class WidgetData(
    val petName: String?,
    val petId: String?,
    val slots: List<WidgetSlot>,
    val given: Int,
    val due: Int,
    val checkedIn: Boolean,
)

/**
 * Home-screen widget: today's doses with a one-tap "Given" button, plus quick-log and check-in
 * shortcuts. Logging goes through the same use case as the app, so the double-dose guard and
 * caregiver attribution apply here too.
 */
class TodayWidget : GlanceAppWidget(), KoinComponent {

    private val settings: SettingsRepository by inject()
    private val pets: PetRepository by inject()
    private val observeSlots: ObserveDoseSlotsUseCase by inject()
    private val checkIns: CheckInRepository by inject()

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = load()
        provideContent {
            GlanceTheme {
                WidgetContent(context, data)
            }
        }
    }

    private suspend fun load(): WidgetData {
        val zone = TimeZone.currentSystemDefault()
        val now = Clock.System.now()
        val today = now.toLocalDate(zone)
        val s = settings.current()
        val active = pets.observeActivePets().first()
        val pet = active.firstOrNull { it.id == s.selectedPetId } ?: active.firstOrNull()
            ?: return WidgetData(null, null, emptyList(), 0, 0, false)
        val slots = observeSlots(pet.id, today, today, flowOf(now)).first()
        val checkedIn = checkIns.forPetBetween(pet.id, today, today).isNotEmpty()
        val scheduled = slots.filter { it.state != SlotState.SKIPPED }
        return WidgetData(
            petName = pet.name,
            petId = pet.id,
            slots = slots.sortedWith(compareBy({ it.state == SlotState.GIVEN }, { it.scheduledAt })).take(4).map {
                WidgetSlot(
                    medId = it.medication.id,
                    scheduledAtMillis = it.scheduledAt.toEpochMilliseconds(),
                    title = "${it.medication.name} · ${it.dosage}",
                    time = it.scheduledAt.toLocalTime(zone).format12h(),
                    state = it.state,
                    givenBy = it.event?.givenBy?.takeIf { _ -> it.event?.status == DoseStatus.GIVEN },
                )
            },
            given = scheduled.count { it.state == SlotState.GIVEN },
            due = scheduled.size,
            checkedIn = checkedIn,
        )
    }

    companion object {
        /** Refreshes every placed widget. Safe to call often; Glance coalesces updates. */
        suspend fun refresh(context: Context) {
            runCatching { TodayWidget().updateAll(context) }
        }

        fun refreshAsync(context: Context) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { refresh(context) }
        }
    }
}

private fun openIntent(context: Context, open: String, petId: String?): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(NotificationHelper.EXTRA_OPEN, open)
        if (petId != null) putExtra(NotificationHelper.EXTRA_PET_ID, petId)
    }

@Composable
private fun WidgetContent(context: Context, data: WidgetData) {
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(14.dp),
    ) {
        Row(
            GlanceModifier.fillMaxWidth().clickable(actionStartActivity(openIntent(context, LaunchRequest.OPEN_TODAY, data.petId))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🐾", style = TextStyle(fontSize = 16.sp))
            Spacer(GlanceModifier.width(6.dp))
            Text(
                data.petName ?: "GoldenPaw",
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.defaultWeight(),
            )
            if (data.due > 0) {
                Text(
                    "${data.given}/${data.due} doses",
                    style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.primary, fontWeight = FontWeight.Medium),
                )
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        if (data.petName == null) {
            Text("Open GoldenPaw to add your pet.", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
            return@Column
        }
        if (data.slots.isEmpty()) {
            Text("No doses scheduled today.", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
        }
        data.slots.forEach { slot ->
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(slot.title, maxLines = 1, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface))
                    val sub = when (slot.state) {
                        SlotState.GIVEN -> "Given${slot.givenBy?.let { " by $it" } ?: ""}"
                        SlotState.MISSED -> "Overdue · ${slot.time}"
                        else -> slot.time
                    }
                    Text(sub, style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant))
                }
                if (slot.state == SlotState.DUE || slot.state == SlotState.MISSED || slot.state == SlotState.UPCOMING) {
                    Box(
                        GlanceModifier
                            .background(GlanceTheme.colors.primary)
                            .cornerRadius(14.dp)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable(
                                actionRunCallback<GivenAction>(
                                    actionParametersOf(GivenAction.MED_ID to slot.medId, GivenAction.SCHEDULED_AT to slot.scheduledAtMillis),
                                ),
                            ),
                    ) {
                        Text("Given", style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onPrimary, fontWeight = FontWeight.Medium))
                    }
                } else {
                    Text("✓", style = TextStyle(fontSize = 15.sp, color = GlanceTheme.colors.primary))
                }
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth()) {
            Chip(
                if (data.checkedIn) "Checked in ✓" else "Check-in",
                GlanceModifier.defaultWeight().clickable(actionStartActivity(openIntent(context, LaunchRequest.OPEN_CHECKIN, data.petId))),
            )
            Spacer(GlanceModifier.width(8.dp))
            Chip(
                "Quick log",
                GlanceModifier.defaultWeight().clickable(actionStartActivity(openIntent(context, LaunchRequest.OPEN_QUICK_LOG, data.petId))),
            )
        }
    }
}

@Composable
private fun Chip(label: String, modifier: GlanceModifier) {
    Box(
        modifier.background(GlanceTheme.colors.secondaryContainer).cornerRadius(14.dp).padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSecondaryContainer, fontWeight = FontWeight.Medium))
    }
}

/** Logs a dose straight from the widget. */
class GivenAction : ActionCallback, KoinComponent {
    private val logDose: LogDoseUseCase by inject()

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val medId = parameters[MED_ID] ?: return
        val at = parameters[SCHEDULED_AT] ?: return
        // A dose already given by someone else is refused by the use case (double-dose guard).
        logDose(medId, Instant.fromEpochMilliseconds(at), DoseStatus.GIVEN, notes = "Logged from widget")
        TodayWidget().update(context, glanceId)
    }

    companion object {
        val MED_ID = ActionParameters.Key<String>("medId")
        val SCHEDULED_AT = ActionParameters.Key<Long>("scheduledAt")
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
