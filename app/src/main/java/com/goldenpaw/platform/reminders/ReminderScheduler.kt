package com.goldenpaw.platform.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.usecase.ReminderGateway
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Exact-alarm based reminder scheduling.
 *
 * Strategy: keep exactly one "next dose" alarm armed (the earliest upcoming slot across all meds)
 * plus one daily check-in alarm. When the dose alarm fires, the receiver notifies every slot due at
 * that instant and re-arms the next one. This avoids alarm bookkeeping on edits: any change simply
 * calls [rescheduleAll]. A periodic WorkManager job and boot/time-change receivers re-arm as a
 * safety net for aggressive OEM battery managers.
 */
class ReminderScheduler(
    private val context: Context,
    private val medications: MedicationRepository,
    private val pets: PetRepository,
    private val settings: SettingsRepository,
    private val notifications: NotificationHelper,
) : ReminderGateway {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    override suspend fun rescheduleAll() {
        mutex.withLock { rescheduleFrom(Instant.now()) }
    }

    /** Re-arm alarms for occurrences strictly after [after]. */
    suspend fun rescheduleFrom(after: Instant) {
        val s = settings.current()
        val zone = ZoneId.systemDefault()

        // Medication alarm
        val doseIntent = ReminderReceiver.alarmIntent(context, ReminderReceiver.ACTION_DOSE_ALARM, null)
        if (s.remindersEnabled) {
            val meds = medications.allActiveForActivePets()
            val next = ScheduleEngine.nextOccurrence(meds, after, zone)
            if (next != null) {
                val pi = ReminderReceiver.alarmIntent(context, ReminderReceiver.ACTION_DOSE_ALARM, next.toEpochMilli())
                setAlarm(next.toEpochMilli(), pi)
                Log.d(TAG, "Next dose alarm at $next")
            } else {
                alarmManager.cancel(doseIntent)
            }
        } else {
            alarmManager.cancel(doseIntent)
        }

        // Daily check-in alarm
        val checkInIntent = ReminderReceiver.alarmIntent(context, ReminderReceiver.ACTION_CHECKIN_ALARM, null)
        if (s.remindersEnabled && s.checkInReminderEnabled) {
            val today = LocalDate.now(zone)
            var target = ZonedDateTime.of(today, s.checkInReminderTime, zone).toInstant()
            if (!target.isAfter(after)) target = ZonedDateTime.of(today.plusDays(1), s.checkInReminderTime, zone).toInstant()
            val pi = ReminderReceiver.alarmIntent(context, ReminderReceiver.ACTION_CHECKIN_ALARM, target.toEpochMilli())
            setAlarm(target.toEpochMilli(), pi)
        } else {
            alarmManager.cancel(checkInIntent)
        }
    }

    /** Snooze one specific dose; uses its own PendingIntent so it doesn't replace the main alarm. */
    fun snooze(medicationId: String, scheduledAt: Instant, minutes: Long = 15) {
        val at = System.currentTimeMillis() + minutes * 60_000
        val pi = ReminderReceiver.snoozeFireIntent(context, medicationId, scheduledAt)
        setAlarm(at, pi)
    }

    private fun setAlarm(atMillis: Long, pi: PendingIntent) {
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                // Inexact fallback; may be deferred a few minutes by Doze.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm denied, falling back to inexact", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    override fun cancelDoseNotification(medicationId: String, scheduledAt: Instant) {
        notifications.cancel(NotificationHelper.doseNotificationId(medicationId, scheduledAt))
    }

    override suspend fun notifyRefill(medication: Medication) {
        val pet = pets.getPet(medication.petId) ?: return
        notifications.showRefill(pet.name, medication)
    }

    companion object {
        private const val TAG = "ReminderScheduler"
    }
}
