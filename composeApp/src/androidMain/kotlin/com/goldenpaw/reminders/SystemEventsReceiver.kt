package com.goldenpaw.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Re-arms reminders after reboot, app update, time/zone change and exact-alarm permission grant. */
class SystemEventsReceiver : BroadcastReceiver(), KoinComponent {

    private val scheduler: AndroidReminderScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                scheduler.rescheduleAll()
            } catch (t: Throwable) {
                Log.e("SystemEventsReceiver", "Reschedule failed for ${intent.action}", t)
            } finally {
                pending.finish()
            }
        }
    }
}
