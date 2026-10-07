package com.goldenpaw.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.goldenpaw.MainActivity
import com.goldenpaw.R
import com.goldenpaw.core.format12h
import com.goldenpaw.core.toLocalTime
import com.goldenpaw.domain.model.Medication
import com.goldenpaw.domain.model.VetVisit
import com.goldenpaw.ui.LaunchRequest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_MEDS = "meds"
        const val CHANNEL_REFILLS = "refills"
        const val CHANNEL_CHECKIN = "checkin"
        const val CHANNEL_WEEKLY = "weekly"
        const val CHANNEL_VET = "vet"
        const val CHANNEL_GENERAL = "general"
        const val GROUP_MEDS = "com.goldenpaw.MEDS"
        const val EXTRA_OPEN = "open"
        const val EXTRA_PET_ID = "petId"

        fun doseNotificationId(medicationId: String, scheduledAt: Instant): Int =
            ("dose:$medicationId@${scheduledAt.toEpochMilliseconds()}").hashCode()

        fun refillNotificationId(medicationId: String): Int = ("refill:$medicationId").hashCode()
        fun vetNotificationId(visitId: String): Int = ("vet:$visitId").hashCode()
        const val CHECKIN_NOTIFICATION_ID = 7001
        const val TEST_NOTIFICATION_ID = 7002
        const val WEEKLY_NOTIFICATION_ID = 7003
    }

    private val manager = NotificationManagerCompat.from(context)
    private val zone get() = TimeZone.currentSystemDefault()

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MEDS, "Medication reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Doses that are due now"
                    enableVibration(true)
                },
                NotificationChannel(CHANNEL_REFILLS, "Refill reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When a medication supply is running low"
                },
                NotificationChannel(CHANNEL_CHECKIN, "Daily check-in", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A gentle nudge for the 30-second daily check-in"
                },
                NotificationChannel(CHANNEL_WEEKLY, "Weekly summary", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "A Sunday recap of the week"
                },
                NotificationChannel(CHANNEL_VET, "Vet visits", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "The day before an appointment"
                },
                NotificationChannel(CHANNEL_GENERAL, "General", NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    fun canPost(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun openAppIntent(open: String, petId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN, open)
            if (petId != null) putExtra(EXTRA_PET_ID, petId)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission")
    fun showDose(petName: String, medication: Medication, dosage: String, scheduledAt: Instant, alreadyGivenBy: String? = null) {
        if (!canPost()) return
        val id = doseNotificationId(medication.id, scheduledAt)
        val time = scheduledAt.toLocalTime(zone).format12h()
        val details = buildList {
            add(dosage)
            if (medication.withFood) add("with food")
            add("due $time")
        }.joinToString(" · ")

        val given = ReminderReceiver.actionIntent(context, ReminderReceiver.ACTION_GIVEN, medication.id, scheduledAt, id)
        val snooze = ReminderReceiver.actionIntent(context, ReminderReceiver.ACTION_SNOOZE, medication.id, scheduledAt, id)
        val skip = ReminderReceiver.actionIntent(context, ReminderReceiver.ACTION_SKIP, medication.id, scheduledAt, id)

        val notification = NotificationCompat.Builder(context, CHANNEL_MEDS)
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle("$petName · ${medication.name}")
            .setContentText(details)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    details + if (medication.notes.isNotBlank()) "\n${medication.notes}" else "",
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup(GROUP_MEDS)
            .setWhen(scheduledAt.toEpochMilliseconds())
            .setShowWhen(true)
            .setContentIntent(openAppIntent(LaunchRequest.OPEN_TODAY, medication.petId, id))
            .addAction(0, "Given", given)
            .addAction(0, "Snooze 15 min", snooze)
            .addAction(0, "Skip", skip)
            .build()
        manager.notify(id, notification)
    }

    /** Replaces a dose reminder when another caregiver already gave it (double-dose guard). */
    @SuppressLint("MissingPermission")
    fun showAlreadyGiven(medication: Medication, scheduledAt: Instant, givenBy: String, givenAt: Instant) {
        if (!canPost()) return
        val id = doseNotificationId(medication.id, scheduledAt)
        val notification = NotificationCompat.Builder(context, CHANNEL_MEDS)
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle("Already given: ${medication.name}")
            .setContentText("${givenBy.ifBlank { "Someone" }} gave it at ${givenAt.toLocalTime(zone).format12h()}. No need to give it again.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(LaunchRequest.OPEN_TODAY, medication.petId, id))
            .build()
        manager.notify(id, notification)
    }

    @SuppressLint("MissingPermission")
    fun showRefill(petName: String, medication: Medication) {
        if (!canPost()) return
        val days = medication.daysOfSupplyLeft()
        val text = when {
            days == null -> "Supply is running low."
            days <= 0 -> "Supply has run out. Time to refill."
            days == 1 -> "About 1 day of supply left."
            else -> "About $days days of supply left."
        }
        val id = refillNotificationId(medication.id)
        val notification = NotificationCompat.Builder(context, CHANNEL_REFILLS)
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle("Refill ${medication.name} for $petName")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(LaunchRequest.OPEN_MEDS, medication.petId, id))
            .build()
        manager.notify(id, notification)
    }

    @SuppressLint("MissingPermission")
    fun showCheckIn(petNames: List<String>, petId: String?, streak: Int?) {
        if (!canPost() || petNames.isEmpty()) return
        val who = when (petNames.size) {
            1 -> petNames.first()
            2 -> "${petNames[0]} and ${petNames[1]}"
            else -> "your pets"
        }
        val body = if (streak != null && streak >= 3) {
            "Keep your $streak-day care streak going with a 30-second check-in."
        } else {
            "A 30-second check-in keeps the picture clear for you and your vet."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_CHECKIN)
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle("How was $who's day?")
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(LaunchRequest.OPEN_CHECKIN, petId, CHECKIN_NOTIFICATION_ID))
            .build()
        manager.notify(CHECKIN_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    fun showWeekly(petName: String, headline: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_WEEKLY)
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle("$petName's week")
            .setContentText(headline)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(LaunchRequest.OPEN_WEEKLY, null, WEEKLY_NOTIFICATION_ID))
            .build()
        manager.notify(WEEKLY_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    fun showVetVisit(petName: String, visit: VetVisit) {
        if (!canPost()) return
        val id = vetNotificationId(visit.id)
        val time = visit.at.toLocalTime(zone).format12h()
        val notification = NotificationCompat.Builder(context, CHANNEL_VET)
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle("Tomorrow: ${visit.title} for $petName")
            .setContentText(listOf(time, visit.clinic).filter { it.isNotBlank() }.joinToString(" · ") + ". A vet report is one tap away in Insights.")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(LaunchRequest.OPEN_TODAY, visit.petId, id))
            .build()
        manager.notify(id, notification)
    }

    @SuppressLint("MissingPermission")
    fun showTest(): Boolean {
        if (!canPost()) return false
        val notification = NotificationCompat.Builder(context, CHANNEL_MEDS)
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle("Reminders are working")
            .setContentText("This is how medication reminders will look.")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(LaunchRequest.OPEN_TODAY, null, TEST_NOTIFICATION_ID))
            .build()
        manager.notify(TEST_NOTIFICATION_ID, notification)
        return true
    }

    fun cancel(id: Int) = manager.cancel(id)
}
