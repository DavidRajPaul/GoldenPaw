package com.goldenpaw.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.ThemeMode
import com.goldenpaw.domain.repository.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalTime

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "goldenpaw_settings")

class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val store = context.applicationContext.dataStore

    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val ownerName = stringPreferencesKey("owner_name")
        val selectedPetId = stringPreferencesKey("selected_pet_id")
        val weightUnit = stringPreferencesKey("weight_unit")
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val remindersEnabled = booleanPreferencesKey("reminders_enabled")
        val checkInReminderEnabled = booleanPreferencesKey("checkin_reminder_enabled")
        val checkInReminderMinutes = intPreferencesKey("checkin_reminder_minutes")
        val isPlus = booleanPreferencesKey("is_plus")
        val paywallViews = intPreferencesKey("paywall_views")
        val paywallTrialTaps = intPreferencesKey("paywall_trial_taps")
        val lastMilestoneSeen = intPreferencesKey("last_milestone_seen")
        val lastSyncedAt = longPreferencesKey("last_synced_at")
    }

    override val settings: Flow<UserSettings> = store.data.map { it.toSettings() }

    override suspend fun current(): UserSettings = settings.first()

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        store.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs.write(updated)
        }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private fun Preferences.toSettings(): UserSettings {
        val minutes = this[Keys.checkInReminderMinutes] ?: (20 * 60)
        return UserSettings(
            onboardingDone = this[Keys.onboardingDone] ?: false,
            ownerName = this[Keys.ownerName] ?: "",
            selectedPetId = this[Keys.selectedPetId],
            weightUnit = WeightUnit.from(this[Keys.weightUnit]),
            themeMode = ThemeMode.entries.firstOrNull { it.name == this[Keys.themeMode] } ?: ThemeMode.SYSTEM,
            dynamicColor = this[Keys.dynamicColor] ?: false,
            reduceMotion = this[Keys.reduceMotion] ?: false,
            remindersEnabled = this[Keys.remindersEnabled] ?: true,
            checkInReminderEnabled = this[Keys.checkInReminderEnabled] ?: true,
            checkInReminderTime = LocalTime.of((minutes / 60).coerceIn(0, 23), (minutes % 60).coerceIn(0, 59)),
            isPlus = this[Keys.isPlus] ?: false,
            paywallViews = this[Keys.paywallViews] ?: 0,
            paywallTrialTaps = this[Keys.paywallTrialTaps] ?: 0,
            lastMilestoneSeen = this[Keys.lastMilestoneSeen] ?: 0,
            lastSyncedAt = this[Keys.lastSyncedAt]?.let(Instant::ofEpochMilli),
        )
    }

    private fun MutablePreferences.write(s: UserSettings) {
        this[Keys.onboardingDone] = s.onboardingDone
        this[Keys.ownerName] = s.ownerName
        if (s.selectedPetId != null) this[Keys.selectedPetId] = s.selectedPetId else remove(Keys.selectedPetId)
        this[Keys.weightUnit] = s.weightUnit.name
        this[Keys.themeMode] = s.themeMode.name
        this[Keys.dynamicColor] = s.dynamicColor
        this[Keys.reduceMotion] = s.reduceMotion
        this[Keys.remindersEnabled] = s.remindersEnabled
        this[Keys.checkInReminderEnabled] = s.checkInReminderEnabled
        this[Keys.checkInReminderMinutes] = s.checkInReminderTime.hour * 60 + s.checkInReminderTime.minute
        this[Keys.isPlus] = s.isPlus
        this[Keys.paywallViews] = s.paywallViews
        this[Keys.paywallTrialTaps] = s.paywallTrialTaps
        this[Keys.lastMilestoneSeen] = s.lastMilestoneSeen
        if (s.lastSyncedAt != null) this[Keys.lastSyncedAt] = s.lastSyncedAt.toEpochMilli() else remove(Keys.lastSyncedAt)
    }
}
