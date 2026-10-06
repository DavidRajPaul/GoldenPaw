package com.goldenpaw.data.settings

import com.goldenpaw.core.localTimeOfMinutes
import com.goldenpaw.core.minutesOfDay
import com.goldenpaw.data.local.SettingEntity
import com.goldenpaw.data.local.SettingsDao
import com.goldenpaw.domain.model.WeightUnit
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.ThemeMode
import com.goldenpaw.domain.repository.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant

/** Small key-value store on top of the `app_settings` table (shared by settings, session and sync cursors). */
class KeyValueStore(private val dao: SettingsDao) {
    private val mutex = Mutex()

    fun observe(): Flow<Map<String, String>> = dao.observeAll().map { list -> list.associate { it.key to it.value } }

    suspend fun all(): Map<String, String> = dao.all().associate { it.key to it.value }

    suspend fun get(key: String): String? = dao.get(key)

    suspend fun put(values: Map<String, String?>) = mutex.withLock {
        val (removed, kept) = values.entries.partition { it.value == null }
        if (kept.isNotEmpty()) dao.upsertAll(kept.map { SettingEntity(it.key, it.value!!) })
        if (removed.isNotEmpty()) dao.delete(removed.map { it.key })
    }

    suspend fun put(key: String, value: String?) = put(mapOf(key to value))

    /** Read-modify-write under the store lock. */
    suspend fun <T> edit(block: suspend (Map<String, String>) -> Pair<Map<String, String?>, T>): T = mutex.withLock {
        val current = dao.all().associate { it.key to it.value }
        val (changes, result) = block(current)
        val (removed, kept) = changes.entries.partition { it.value == null }
        if (kept.isNotEmpty()) dao.upsertAll(kept.map { SettingEntity(it.key, it.value!!) })
        if (removed.isNotEmpty()) dao.delete(removed.map { it.key })
        result
    }

    suspend fun clear() = mutex.withLock { dao.clear() }
}

class SettingsRepositoryImpl(private val store: KeyValueStore) : SettingsRepository {

    object Keys {
        const val ONBOARDING_DONE = "onboarding_done"
        const val OWNER_NAME = "owner_name"
        const val SELECTED_PET_ID = "selected_pet_id"
        const val WEIGHT_UNIT = "weight_unit"
        const val THEME_MODE = "theme_mode"
        const val DYNAMIC_COLOR = "dynamic_color"
        const val REDUCE_MOTION = "reduce_motion"
        const val REMINDERS_ENABLED = "reminders_enabled"
        const val CHECKIN_REMINDER_ENABLED = "checkin_reminder_enabled"
        const val CHECKIN_REMINDER_MINUTES = "checkin_reminder_minutes"
        const val WEEKLY_SUMMARY_ENABLED = "weekly_summary_enabled"
        const val IS_PLUS = "is_plus"
        const val PAYWALL_VIEWS = "paywall_views"
        const val PAYWALL_TRIAL_TAPS = "paywall_trial_taps"
        const val LAST_MILESTONE_SEEN = "last_milestone_seen"
        const val LAST_SYNCED_AT = "last_synced_at"
        const val ACTIVE_CAREGIVER_ID = "active_caregiver_id"
        const val GAMIFICATION_ENABLED = "gamification_enabled"
        const val STREAK_REST_DAYS = "streak_rest_days_per_week"

        val all = listOf(
            ONBOARDING_DONE, OWNER_NAME, SELECTED_PET_ID, WEIGHT_UNIT, THEME_MODE, DYNAMIC_COLOR, REDUCE_MOTION,
            REMINDERS_ENABLED, CHECKIN_REMINDER_ENABLED, CHECKIN_REMINDER_MINUTES, WEEKLY_SUMMARY_ENABLED, IS_PLUS,
            PAYWALL_VIEWS, PAYWALL_TRIAL_TAPS, LAST_MILESTONE_SEEN, LAST_SYNCED_AT, ACTIVE_CAREGIVER_ID,
            GAMIFICATION_ENABLED, STREAK_REST_DAYS,
        )
    }

    override val settings: Flow<UserSettings> = store.observe().map { it.toSettings() }.distinctUntilChanged()

    override suspend fun current(): UserSettings = store.all().toSettings()

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        store.edit { current ->
            val updated = transform(current.toSettings())
            updated.toMap() to Unit
        }
    }

    /** Clears user preferences (keeps session/sync keys, which are owned by other components). */
    override suspend fun clear() = store.put(Keys.all.associateWith { null })

    companion object {
        fun Map<String, String>.toSettings(): UserSettings {
            fun bool(key: String, default: Boolean) = this[key]?.toBooleanStrictOrNull() ?: default
            fun int(key: String, default: Int) = this[key]?.toIntOrNull() ?: default
            val minutes = int(Keys.CHECKIN_REMINDER_MINUTES, 20 * 60)
            return UserSettings(
                onboardingDone = bool(Keys.ONBOARDING_DONE, false),
                ownerName = this[Keys.OWNER_NAME].orEmpty(),
                selectedPetId = this[Keys.SELECTED_PET_ID],
                weightUnit = WeightUnit.from(this[Keys.WEIGHT_UNIT]),
                themeMode = ThemeMode.entries.firstOrNull { it.name == this[Keys.THEME_MODE] } ?: ThemeMode.SYSTEM,
                dynamicColor = bool(Keys.DYNAMIC_COLOR, false),
                reduceMotion = bool(Keys.REDUCE_MOTION, false),
                remindersEnabled = bool(Keys.REMINDERS_ENABLED, true),
                checkInReminderEnabled = bool(Keys.CHECKIN_REMINDER_ENABLED, true),
                checkInReminderTime = localTimeOfMinutes(minutes),
                weeklySummaryEnabled = bool(Keys.WEEKLY_SUMMARY_ENABLED, true),
                isPlus = bool(Keys.IS_PLUS, false),
                paywallViews = int(Keys.PAYWALL_VIEWS, 0),
                paywallTrialTaps = int(Keys.PAYWALL_TRIAL_TAPS, 0),
                lastMilestoneSeen = int(Keys.LAST_MILESTONE_SEEN, 0),
                lastSyncedAt = this[Keys.LAST_SYNCED_AT]?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) },
                activeCaregiverId = this[Keys.ACTIVE_CAREGIVER_ID],
                gamificationEnabled = bool(Keys.GAMIFICATION_ENABLED, true),
                streakRestDaysPerWeek = int(Keys.STREAK_REST_DAYS, 2),
            )
        }

        fun UserSettings.toMap(): Map<String, String?> = mapOf(
            Keys.ONBOARDING_DONE to onboardingDone.toString(),
            Keys.OWNER_NAME to ownerName,
            Keys.SELECTED_PET_ID to selectedPetId,
            Keys.WEIGHT_UNIT to weightUnit.name,
            Keys.THEME_MODE to themeMode.name,
            Keys.DYNAMIC_COLOR to dynamicColor.toString(),
            Keys.REDUCE_MOTION to reduceMotion.toString(),
            Keys.REMINDERS_ENABLED to remindersEnabled.toString(),
            Keys.CHECKIN_REMINDER_ENABLED to checkInReminderEnabled.toString(),
            Keys.CHECKIN_REMINDER_MINUTES to checkInReminderTime.minutesOfDay.toString(),
            Keys.WEEKLY_SUMMARY_ENABLED to weeklySummaryEnabled.toString(),
            Keys.IS_PLUS to isPlus.toString(),
            Keys.PAYWALL_VIEWS to paywallViews.toString(),
            Keys.PAYWALL_TRIAL_TAPS to paywallTrialTaps.toString(),
            Keys.LAST_MILESTONE_SEEN to lastMilestoneSeen.toString(),
            Keys.LAST_SYNCED_AT to lastSyncedAt?.toEpochMilliseconds()?.toString(),
            Keys.ACTIVE_CAREGIVER_ID to activeCaregiverId,
            Keys.GAMIFICATION_ENABLED to gamificationEnabled.toString(),
            Keys.STREAK_REST_DAYS to streakRestDaysPerWeek.toString(),
        )
    }
}
