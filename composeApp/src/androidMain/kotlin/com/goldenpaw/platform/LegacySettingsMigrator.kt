package com.goldenpaw.platform

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalTime
import java.io.File

private val Context.legacyStore by preferencesDataStore(name = "goldenpaw_settings")

/**
 * v1.0 (Android-only) kept settings in DataStore. v1.1 keeps them in the shared database so every
 * platform reads the same store. This copies them over once, then clears the old file.
 */
class LegacySettingsMigrator(private val context: Context, private val settings: SettingsRepository) {

    fun hasLegacyData(): Boolean =
        File(context.filesDir, "datastore/goldenpaw_settings.preferences_pb").exists()

    suspend fun migrate() {
        if (!hasLegacyData()) return
        val prefs = context.legacyStore.data.first()
        if (prefs.asMap().isEmpty()) return
        val minutes = prefs[intPreferencesKey("checkin_reminder_minutes")] ?: (20 * 60)
        settings.update { s ->
            s.copy(
                onboardingDone = prefs[booleanPreferencesKey("onboarding_done")] ?: s.onboardingDone,
                ownerName = prefs[stringPreferencesKey("owner_name")] ?: s.ownerName,
                selectedPetId = prefs[stringPreferencesKey("selected_pet_id")] ?: s.selectedPetId,
                weightUnit = WeightUnit.from(prefs[stringPreferencesKey("weight_unit")]),
                themeMode = ThemeMode.entries.firstOrNull { it.name == prefs[stringPreferencesKey("theme_mode")] } ?: s.themeMode,
                dynamicColor = prefs[booleanPreferencesKey("dynamic_color")] ?: s.dynamicColor,
                reduceMotion = prefs[booleanPreferencesKey("reduce_motion")] ?: s.reduceMotion,
                remindersEnabled = prefs[booleanPreferencesKey("reminders_enabled")] ?: s.remindersEnabled,
                checkInReminderEnabled = prefs[booleanPreferencesKey("checkin_reminder_enabled")] ?: s.checkInReminderEnabled,
                checkInReminderTime = LocalTime((minutes / 60).coerceIn(0, 23), (minutes % 60).coerceIn(0, 59)),
                isPlus = prefs[booleanPreferencesKey("is_plus")] ?: s.isPlus,
                paywallViews = prefs[intPreferencesKey("paywall_views")] ?: s.paywallViews,
                paywallTrialTaps = prefs[intPreferencesKey("paywall_trial_taps")] ?: s.paywallTrialTaps,
                lastMilestoneSeen = prefs[intPreferencesKey("last_milestone_seen")] ?: s.lastMilestoneSeen,
            )
        }
        context.legacyStore.edit { it.clear() }
        File(context.filesDir, "datastore/goldenpaw_settings.preferences_pb").delete()
    }
}
