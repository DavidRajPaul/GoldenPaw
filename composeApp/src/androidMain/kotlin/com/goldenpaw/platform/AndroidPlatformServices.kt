package com.goldenpaw.platform

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.goldenpaw.reminders.AndroidReminderScheduler
import com.goldenpaw.reminders.NotificationHelper
import com.goldenpaw.widget.TodayWidgetReceiver
import java.io.File

class AndroidPlatformServices(
    private val context: Context,
    private val scheduler: AndroidReminderScheduler,
    private val notifications: NotificationHelper,
) : PlatformServices {
    override val kind = PlatformKind.ANDROID
    override val supportsDynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    override val supportsWidgets: Boolean = true

    private fun start(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun share(text: String, subject: String?) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        start(Intent.createChooser(send, subject ?: "Share"))
    }

    private fun uriFor(path: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))

    override fun shareFile(path: String, mimeType: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uriFor(path))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        start(Intent.createChooser(send, title))
    }

    override fun openFile(path: String, mimeType: String) {
        start(Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(path), mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun dial(phone: String) = start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))

    override fun reminderHealth() = ReminderHealth(
        notifications = notifications.canPost(),
        exactAlarms = scheduler.canScheduleExact(),
        batteryUnrestricted = scheduler.isIgnoringBatteryOptimizations(),
        exactAlarmsApplicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        batteryApplicable = true,
    )

    override fun openNotificationSettings() {
        start(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }

    override fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            start(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
    }

    override fun openBatterySettings() {
        val ok = runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!ok) start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    override fun sendTestNotification(): Boolean = notifications.showTest()

    override fun requestPinWidget(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(AppWidgetManager::class.java) ?: return false
        if (!manager.isRequestPinAppWidgetSupported) return false
        return manager.requestPinAppWidget(ComponentName(context, TodayWidgetReceiver::class.java), null, null)
    }

    override fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
