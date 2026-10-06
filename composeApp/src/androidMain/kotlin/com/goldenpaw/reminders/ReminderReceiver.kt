package com.goldenpaw.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.goldenpaw.core.startOfWeek
import com.goldenpaw.core.toLocalDate
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.VetVisitRepository
import com.goldenpaw.domain.usecase.BuildWeeklyDigestUseCase
import com.goldenpaw.domain.usecase.GenerateWeeklySummaryUseCase
import com.goldenpaw.domain.usecase.LogDoseResult
import com.goldenpaw.domain.usecase.LogDoseUseCase
import com.goldenpaw.widget.TodayWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class ReminderReceiver : BroadcastReceiver(), KoinComponent {

    private val scheduler: AndroidReminderScheduler by inject()
    private val notifications: NotificationHelper by inject()
    private val medications: MedicationRepository by inject()
    private val doseEvents: DoseEventRepository by inject()
    private val pets: PetRepository by inject()
    private val checkIns: CheckInRepository by inject()
    private val settings: SettingsRepository by inject()
    private val vetVisits: VetVisitRepository by inject()
    private val logDose: LogDoseUseCase by inject()
    private val buildDigest: BuildWeeklyDigestUseCase by inject()
    private val generateSummary: GenerateWeeklySummaryUseCase by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handle(context, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to handle ${intent.action}", t)
            } finally {
                pending.finish()
            }
        }
    }

    private val zone get() = TimeZone.currentSystemDefault()

    private suspend fun handle(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DOSE_ALARM -> {
                val target = intent.getLongExtra(EXTRA_AT, -1L)
                if (target > 0) notifyDueAt(Instant.fromEpochMilliseconds(target))
                val from = maxOf(target, Clock.System.now().toEpochMilliseconds() - 1000)
                scheduler.rescheduleFrom(Instant.fromEpochMilliseconds(from))
                TodayWidget.refresh(context)
            }
            ACTION_SNOOZE_FIRE -> {
                val medId = intent.getStringExtra(EXTRA_MED_ID) ?: return
                notifySlot(medId, Instant.fromEpochMilliseconds(intent.getLongExtra(EXTRA_SCHEDULED_AT, 0)))
            }
            ACTION_GIVEN, ACTION_SKIP -> {
                val medId = intent.getStringExtra(EXTRA_MED_ID) ?: return
                val scheduledAt = Instant.fromEpochMilliseconds(intent.getLongExtra(EXTRA_SCHEDULED_AT, 0))
                val status = if (intent.action == ACTION_GIVEN) DoseStatus.GIVEN else DoseStatus.SKIPPED
                val result = logDose(medId, scheduledAt, status, notes = "Logged from notification")
                if (result is LogDoseResult.AlreadyGiven) {
                    val med = medications.get(medId) ?: return
                    notifications.showAlreadyGiven(med, scheduledAt, result.existing.givenBy, result.existing.actualAt)
                } else {
                    notifications.cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
                }
                TodayWidget.refresh(context)
            }
            ACTION_SNOOZE -> {
                val medId = intent.getStringExtra(EXTRA_MED_ID) ?: return
                val scheduledAt = Instant.fromEpochMilliseconds(intent.getLongExtra(EXTRA_SCHEDULED_AT, 0))
                notifications.cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
                scheduler.snooze(medId, scheduledAt)
            }
            ACTION_CHECKIN_ALARM -> {
                notifyCheckInIfNeeded()
                scheduler.rescheduleFrom(Clock.System.now() + 60.seconds)
            }
            ACTION_WEEKLY_ALARM -> {
                notifyWeekly()
                scheduler.rescheduleFrom(Clock.System.now() + 60.seconds)
            }
            ACTION_VET_ALARM -> {
                val target = intent.getLongExtra(EXTRA_AT, -1L)
                if (target > 0) notifyVet(Instant.fromEpochMilliseconds(target))
                scheduler.rescheduleFrom(Clock.System.now() + 60.seconds)
            }
        }
    }

    private suspend fun notifyDueAt(target: Instant) {
        val date = target.toLocalDate(zone)
        for (med in medications.allActiveForActivePets()) {
            val match = ScheduleEngine.scheduledInstants(med, date, zone)
                .firstOrNull { abs(it.toEpochMilliseconds() - target.toEpochMilliseconds()) < 60_000 }
                ?: continue
            if (doseEvents.find(med.id, match) != null) continue // already logged (by anyone on the team)
            val pet = pets.getPet(med.petId) ?: continue
            notifications.showDose(pet.name, med, med.dosageOn(date), match)
        }
    }

    private suspend fun notifySlot(medicationId: String, scheduledAt: Instant) {
        val med = medications.get(medicationId) ?: return
        if (doseEvents.find(medicationId, scheduledAt) != null) return
        val pet = pets.getPet(med.petId) ?: return
        notifications.showDose(pet.name, med, med.dosageOn(scheduledAt.toLocalDate(zone)), scheduledAt)
    }

    private suspend fun notifyCheckInIfNeeded() {
        val today = Clock.System.now().toLocalDate(zone)
        val active = pets.observeActivePets().first()
        val missing = active.filter { checkIns.forPetBetween(it.id, today, today).isEmpty() }
        if (missing.isNotEmpty()) notifications.showCheckIn(missing.map { it.name }, missing.first().id, streak = null)
    }

    private suspend fun notifyWeekly() {
        val s = settings.current()
        if (!s.weeklySummaryEnabled) return
        val active = pets.observeActivePets().first()
        val pet = active.firstOrNull { it.id == s.selectedPetId } ?: active.firstOrNull() ?: return
        val weekStart = Clock.System.now().toLocalDate(zone).startOfWeek()
        val headline = if (s.isPlus) {
            runCatching { generateSummary(pet.id, weekStart) }.getOrNull()?.headline
        } else null
        val text = headline ?: runCatching { buildDigest(pet.id, weekStart) }.getOrNull()?.let { d ->
            buildList {
                d.adherence?.let { add("${(it * 100).roundToInt()}% of doses given") }
                if (d.checkIns > 0) add("${d.goodDays} good ${if (d.goodDays == 1) "day" else "days"}")
            }.joinToString(" · ").ifBlank { null }
        } ?: return
        notifications.showWeekly(pet.name, text)
    }

    private suspend fun notifyVet(target: Instant) {
        val now = Clock.System.now()
        vetVisits.upcoming(now).filter { abs((scheduler.reminderTimeFor(it.at, zone) - target).inWholeSeconds) < 90 && it.at - now < 2.days }
            .forEach { visit ->
                val pet = pets.getPet(visit.petId) ?: return@forEach
                notifications.showVetVisit(pet.name, visit)
            }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        const val ACTION_DOSE_ALARM = "com.goldenpaw.action.DOSE_ALARM"
        const val ACTION_CHECKIN_ALARM = "com.goldenpaw.action.CHECKIN_ALARM"
        const val ACTION_WEEKLY_ALARM = "com.goldenpaw.action.WEEKLY_ALARM"
        const val ACTION_VET_ALARM = "com.goldenpaw.action.VET_ALARM"
        const val ACTION_SNOOZE_FIRE = "com.goldenpaw.action.SNOOZE_FIRE"
        const val ACTION_GIVEN = "com.goldenpaw.action.GIVEN"
        const val ACTION_SKIP = "com.goldenpaw.action.SKIP"
        const val ACTION_SNOOZE = "com.goldenpaw.action.SNOOZE"
        const val EXTRA_AT = "at"
        const val EXTRA_MED_ID = "medId"
        const val EXTRA_SCHEDULED_AT = "scheduledAt"
        const val EXTRA_NOTIFICATION_ID = "notificationId"

        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun requestCodeFor(action: String) = when (action) {
            ACTION_DOSE_ALARM -> 1001
            ACTION_CHECKIN_ALARM -> 1002
            ACTION_WEEKLY_ALARM -> 1003
            ACTION_VET_ALARM -> 1004
            else -> 1099
        }

        fun alarmIntent(context: Context, action: String, atMillis: Long?): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java).setAction(action)
            if (atMillis != null) intent.putExtra(EXTRA_AT, atMillis)
            return PendingIntent.getBroadcast(context, requestCodeFor(action), intent, FLAGS)
        }

        fun snoozeFireIntent(context: Context, medicationId: String, scheduledAt: Instant): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_SNOOZE_FIRE)
                .putExtra(EXTRA_MED_ID, medicationId)
                .putExtra(EXTRA_SCHEDULED_AT, scheduledAt.toEpochMilliseconds())
            val code = ("snooze:$medicationId@${scheduledAt.toEpochMilliseconds()}").hashCode()
            return PendingIntent.getBroadcast(context, code, intent, FLAGS)
        }

        fun actionIntent(context: Context, action: String, medicationId: String, scheduledAt: Instant, notificationId: Int): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_MED_ID, medicationId)
                .putExtra(EXTRA_SCHEDULED_AT, scheduledAt.toEpochMilliseconds())
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            val code = ("$action:$medicationId@${scheduledAt.toEpochMilliseconds()}").hashCode()
            return PendingIntent.getBroadcast(context, code, intent, FLAGS)
        }
    }
}
