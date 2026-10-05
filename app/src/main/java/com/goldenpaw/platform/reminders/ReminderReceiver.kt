package com.goldenpaw.platform.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.goldenpaw.domain.logic.ScheduleEngine
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.usecase.LogDoseUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReminderReceiver : BroadcastReceiver(), KoinComponent {

    private val scheduler: ReminderScheduler by inject()
    private val notifications: NotificationHelper by inject()
    private val medications: MedicationRepository by inject()
    private val doseEvents: DoseEventRepository by inject()
    private val pets: PetRepository by inject()
    private val checkIns: CheckInRepository by inject()
    private val logDose: LogDoseUseCase by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handle(intent)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to handle ${intent.action}", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_DOSE_ALARM -> {
                val target = intent.getLongExtra(EXTRA_AT, -1L)
                if (target > 0) notifyDueAt(Instant.ofEpochMilli(target))
                scheduler.rescheduleFrom(Instant.ofEpochMilli(maxOf(target, System.currentTimeMillis() - 1000)))
            }
            ACTION_SNOOZE_FIRE -> {
                val medId = intent.getStringExtra(EXTRA_MED_ID) ?: return
                val scheduledAt = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_SCHEDULED_AT, 0))
                notifySlot(medId, scheduledAt)
            }
            ACTION_GIVEN, ACTION_SKIP -> {
                val medId = intent.getStringExtra(EXTRA_MED_ID) ?: return
                val scheduledAt = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_SCHEDULED_AT, 0))
                val status = if (intent.action == ACTION_GIVEN) DoseStatus.GIVEN else DoseStatus.SKIPPED
                logDose(medId, scheduledAt, status, notes = "Logged from notification")
                notifications.cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
            }
            ACTION_SNOOZE -> {
                val medId = intent.getStringExtra(EXTRA_MED_ID) ?: return
                val scheduledAt = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_SCHEDULED_AT, 0))
                notifications.cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
                scheduler.snooze(medId, scheduledAt)
            }
            ACTION_CHECKIN_ALARM -> {
                notifyCheckInIfNeeded()
                scheduler.rescheduleFrom(Instant.now().plusSeconds(60))
            }
        }
    }

    private suspend fun notifyDueAt(target: Instant) {
        val zone = ZoneId.systemDefault()
        val date = target.atZone(zone).toLocalDate()
        val meds = medications.allActiveForActivePets()
        for (med in meds) {
            val match = ScheduleEngine.scheduledInstants(med, date, zone)
                .firstOrNull { kotlin.math.abs(it.toEpochMilli() - target.toEpochMilli()) < 60_000 }
                ?: continue
            if (doseEvents.find(med.id, match) != null) continue // already logged early
            val pet = pets.getPet(med.petId) ?: continue
            notifications.showDose(pet.name, med, med.dosageOn(date), match)
        }
    }

    private suspend fun notifySlot(medicationId: String, scheduledAt: Instant) {
        val med = medications.get(medicationId) ?: return
        if (doseEvents.find(medicationId, scheduledAt) != null) return
        val pet = pets.getPet(med.petId) ?: return
        val date = scheduledAt.atZone(ZoneId.systemDefault()).toLocalDate()
        notifications.showDose(pet.name, med, med.dosageOn(date), scheduledAt)
    }

    private suspend fun notifyCheckInIfNeeded() {
        val today = LocalDate.now()
        val active = pets.observeActivePets().first()
        val missing = active.filter { checkIns.forPetBetween(it.id, today, today).isEmpty() }
        if (missing.isNotEmpty()) notifications.showCheckIn(missing.map { it.name }, missing.first().id)
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        const val ACTION_DOSE_ALARM = "com.goldenpaw.action.DOSE_ALARM"
        const val ACTION_CHECKIN_ALARM = "com.goldenpaw.action.CHECKIN_ALARM"
        const val ACTION_SNOOZE_FIRE = "com.goldenpaw.action.SNOOZE_FIRE"
        const val ACTION_GIVEN = "com.goldenpaw.action.GIVEN"
        const val ACTION_SKIP = "com.goldenpaw.action.SKIP"
        const val ACTION_SNOOZE = "com.goldenpaw.action.SNOOZE"
        const val EXTRA_AT = "at"
        const val EXTRA_MED_ID = "medId"
        const val EXTRA_SCHEDULED_AT = "scheduledAt"
        const val EXTRA_NOTIFICATION_ID = "notificationId"

        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun alarmIntent(context: Context, action: String, atMillis: Long?): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java).setAction(action)
            if (atMillis != null) intent.putExtra(EXTRA_AT, atMillis)
            val requestCode = if (action == ACTION_DOSE_ALARM) 1001 else 1002
            return PendingIntent.getBroadcast(context, requestCode, intent, FLAGS)
        }

        fun snoozeFireIntent(context: Context, medicationId: String, scheduledAt: Instant): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_SNOOZE_FIRE)
                .putExtra(EXTRA_MED_ID, medicationId)
                .putExtra(EXTRA_SCHEDULED_AT, scheduledAt.toEpochMilli())
            val code = ("snooze:$medicationId@${scheduledAt.toEpochMilli()}").hashCode()
            return PendingIntent.getBroadcast(context, code, intent, FLAGS)
        }

        fun actionIntent(
            context: Context,
            action: String,
            medicationId: String,
            scheduledAt: Instant,
            notificationId: Int,
        ): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_MED_ID, medicationId)
                .putExtra(EXTRA_SCHEDULED_AT, scheduledAt.toEpochMilli())
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            val code = ("$action:$medicationId@${scheduledAt.toEpochMilli()}").hashCode()
            return PendingIntent.getBroadcast(context, code, intent, FLAGS)
        }
    }
}
