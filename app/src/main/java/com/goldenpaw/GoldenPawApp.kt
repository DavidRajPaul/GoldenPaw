package com.goldenpaw

import android.app.Application
import com.goldenpaw.di.appModules
import com.goldenpaw.platform.reminders.NotificationHelper
import com.goldenpaw.platform.reminders.ReminderRefreshWorker
import com.goldenpaw.platform.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class GoldenPawApp : Application() {

    private val notifications: NotificationHelper by inject()
    private val scheduler: ReminderScheduler by inject()
    private val appScope: CoroutineScope by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@GoldenPawApp)
            modules(appModules)
        }
        notifications.createChannels()
        ReminderRefreshWorker.enqueue(this)
        appScope.launch { runCatching { scheduler.rescheduleAll() } }
    }
}
