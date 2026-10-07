package com.goldenpaw.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.goldenpaw.core.at
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.startOfWeek
import com.goldenpaw.core.toLocalDate
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.VetVisitRepository
import com.goldenpaw.widget.TodayWidget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Exact-alarm reminder scheduling.
 *
 * Keeps exactly one "next dose" alarm armed (the earliest upcoming slot across all meds) plus one
 * alarm each for the daily check-in, the weekly summary and the next vet visit. When the dose
 * alarm fires, the receiver notifies every slot due at that instant and re-arms the next one, so
 * any edit simply calls [rescheduleAll]. A periodic WorkManager job and boot/time-change
 * receivers re-arm as a safety net for aggressive OEM battery managers.
 */
class AndroidReminderScheduler(
    private val context: Context,
    private val medications: MedicationRepository,
    private val pets: PetRepository,
    private val settings: SettingsRepository,
    private val vetVisits: VetVisitRepository,
    private val notifications: NotificationHelper,
) : ReminderGateway {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    override suspend fun rescheduleAll() {
        mutex.withLock { rescheduleFrom(Clock.System.now()) }
        TodayWidget.refresh(context)
    }

    /** Re-arms alarms for occurrences strictly after [after]. */
    suspend fun rescheduleFrom(after: Instant) {
        val s = settings.current()
        val zone = TimeZone.currentSystemDefault()

        // Next medication dose
        val doseCancel = ReminderReceiver.alarmIntent(context, ReminderReceiver.ACTION_DOSE_ALARM, null)
        val next = if (s.remindersEnabled) ScheduleEngine.nextOccurrence(medications.allActiveForActivePets(), after, zone) else null
        if (next != null) {
            setAlarm(next, ReminderReceiver.alarmIntent(context, ReminderReceiver.ACTION_DOSE_ALARM, next.toEpochMilliseconds()))
            Log.d(TAG, "Next dose alarm at $next")
        } else {
            alarmManager.cancel(doseCancel)
        }

        // Daily check-in nudge
        val checkInCancel = ReminderReceiver.alarmIntent(context, ReminderReceiver.ACTION_CHECKIN_ALARM, null)
        if (s.remindersEnabled && s.checkInReminderEnabled) {
            val today = after.toLocalDate(zone)
            var target = today.at(s.checkInReminderTime, zone)
            if (target <= after) target = today.plusDays(1).at(s.checkInReminderTime, zone)
            setAlarm(target, ReminderReceiver.alarmIntent(context, ReminderReceiver.ACTION_CHECKIN_ALARM, target.toEpochMilliseconds()))
        } else {
            alarmManager.cancel(checkInCancel)
        }

        // Weekly summary: Sunday 18:00
        val weeklyCancel = ReminderReceiver.alarmIntent(context, ReminderReceiver.ACTION_WEEKLY_ALARM, null)
        if (s.weeklySummaryEnabled) {
            val sunday = after.toLocalDate(zone).startOfWeek().plusDays(6)
            var target = sunday.at(WEEKLY_TIME, zone)
            if (target <= after) target = sunday.plusDays(7).at(WEEKLY_TIME, zone)
            setAlarm(target, ReminderReceiver.alarmIntent(context, ReminderReceiver.ACTION_WEEKLY_ALARM, target.toEpochMilliseconds()), exact = false)
        } else {
            alarmManager.cancel(weeklyCancel)
        }

        // Next vet visit: the evening before (or 24 h before for early-morning visits)
        val vetCancel = ReminderReceiver.alarmIntent(context, ReminderReceiver.ACTION_VET_ALARM, null)
        val nextVet = if (s.remindersEnabled) {
            vetVisits.upcoming(after).asSequence()
                .map { it to reminderTimeFor(it.at, zone) }
                .filter { (_, at) -> at > after }
                .minByOrNull { it.second }
        } else null
        if (nextVet != null) {
            setAlarm(nextVet.second, ReminderReceiver.alarmIntent(context, ReminderReceiver.ACTION_VET_ALARM, nextVet.second.toEpochMilliseconds()))
        } else {
            alarmManager.cancel(vetCancel)
        }
    }

    /** 18:00 the day before, or 24 hours before if that's earlier than 18:00 the day before. */
    fun reminderTimeFor(visitAt: Instant, zone: TimeZone): Instant {
        val dayBefore = visitAt.toLocalDate(zone).plusDays(-1).at(LocalTime(18, 0), zone)
        return minOf(dayBefore, visitAt - 1.days)
    }

    /** Snoozes one dose with its own PendingIntent so it doesn't replace the main alarm. */
    fun snooze(medicationId: String, scheduledAt: Instant, minutes: Long = 15) {
        val at = Clock.System.now() + minutes.minutes
        setAlarm(at, ReminderReceiver.snoozeFireIntent(context, medicationId, scheduledAt))
    }

    private fun setAlarm(at: Instant, pi: PendingIntent, exact: Boolean = true) {
        val millis = at.toEpochMilliseconds()
        try {
            if (exact && canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                // Inexact fallback; may be deferred a few minutes by Doze.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm denied, falling back to inexact", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    override fun cancelDoseNotification(medicationId: String, scheduledAt: Instant) {
        notifications.cancel(NotificationHelper.doseNotificationId(medicationId, scheduledAt))
        TodayWidget.refreshAsync(context)
    }

    override suspend fun notifyRefill(medication: Medication) {
        val pet = pets.getPet(medication.petId) ?: return
        notifications.showRefill(pet.name, medication)
    }

    companion object {
        private const val TAG = "ReminderScheduler"
        private val WEEKLY_TIME = LocalTime(18, 0)
    }
}
