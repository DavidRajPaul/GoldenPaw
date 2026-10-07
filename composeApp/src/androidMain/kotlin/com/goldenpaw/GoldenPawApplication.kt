package com.goldenpaw

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.goldenpaw.di.allModules
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.platform.LegacySettingsMigrator
import com.goldenpaw.reminders.NotificationHelper
import com.goldenpaw.reminders.ReminderRefreshWorker
import com.goldenpaw.ui.LaunchRequest
import com.goldenpaw.widget.TodayWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class GoldenPawApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notifications: NotificationHelper by inject()
    private val legacy: LegacySettingsMigrator by inject()
    private val doseEvents: DoseEventRepository by inject()
    private val settings: SettingsRepository by inject()

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@GoldenPawApplication)
            modules(allModules())
        }
        // v1.0 → v1.1: settings moved from DataStore into the shared database. This must finish
        // before the UI reads settings, and only runs (briefly) on the first launch after updating.
        if (legacy.hasLegacyData()) runBlocking { runCatching { legacy.migrate() } }

        notifications.createChannels()
        ReminderRefreshWorker.enqueue(this)
        publishShortcuts()

        // Keep the home-screen widget in step with doses logged anywhere (app, notification, sync).
        appScope.launch {
            merge(
                doseEvents.observeTotalGiven().map { "d$it" },
                settings.settings.map { "p${it.selectedPetId}" },
            ).distinctUntilChanged().drop(1).debounce(500).collect { TodayWidget.refresh(this@GoldenPawApplication) }
        }
    }

    private fun publishShortcuts() {
        fun shortcut(id: String, label: String, open: String) = ShortcutInfoCompat.Builder(this, id)
            .setShortLabel(label)
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_stat_paw))
            .setIntent(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(NotificationHelper.EXTRA_OPEN, open),
            )
            .build()
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(
                this,
                listOf(
                    shortcut("quick_log", getString(R.string.shortcut_quick_log), LaunchRequest.OPEN_QUICK_LOG),
                    shortcut("check_in", getString(R.string.shortcut_check_in), LaunchRequest.OPEN_CHECKIN),
                    shortcut("symptom", getString(R.string.shortcut_symptom), LaunchRequest.OPEN_SYMPTOM),
                ),
            )
        }
    }
}
