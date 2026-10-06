package com.goldenpaw.reminders

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goldenpaw.domain.repository.CareSyncRepository
import com.goldenpaw.domain.repository.HouseholdRepository
import com.goldenpaw.widget.TodayWidget
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Safety net that runs a few times a day: re-arms reminder alarms (some OEMs drop them), pulls
 * care-team changes so reminders don't fire for doses someone else already gave, and refreshes
 * the widget.
 */
class ReminderRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val scheduler: AndroidReminderScheduler by inject()
    private val sync: CareSyncRepository by inject()
    private val households: HouseholdRepository by inject()

    override suspend fun doWork(): Result = try {
        if (sync.isAvailable && households.households().any { it.cloudEnabled }) runCatching { sync.sync() }
        scheduler.rescheduleAll()
        TodayWidget.refresh(applicationContext)
        Result.success()
    } catch (t: Throwable) {
        Result.retry()
    }

    companion object {
        private const val NAME = "goldenpaw-reminder-refresh"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderRefreshWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
