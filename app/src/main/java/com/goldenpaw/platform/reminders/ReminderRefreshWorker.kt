package com.goldenpaw.platform.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.SyncRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Safety net that runs a few times a day: re-arms reminder alarms (some OEMs drop them) and
 * runs cloud sync when available.
 */
class ReminderRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val scheduler: ReminderScheduler by inject()
    private val sync: SyncRepository by inject()
    private val settings: SettingsRepository by inject()

    override suspend fun doWork(): Result {
        return try {
            scheduler.rescheduleAll()
            if (sync.isCloudEnabled) {
                sync.sync().onSuccess { at -> settings.update { it.copy(lastSyncedAt = at) } }
            }
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "goldenpaw-reminder-refresh"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderRefreshWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
