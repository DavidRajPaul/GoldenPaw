package com.goldenpaw.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/*
 * Gentle gamification. Design rules (from habit-design research, see docs/MARKET_RESEARCH_2.md):
 *  - Never punish. A missed day costs nothing but the streak count, and earned rest days cover gaps.
 *  - Everything is derived from the care log, so undoing a dose also undoes its points.
 *  - Daily rings reset every day and weekly goals are capped, so there's no mounting dread.
 *  - After 30 days the streak "graduates" into a permanent badge and is shown more quietly.
 *  - One switch (gentle mode) turns all of it off.
 */

enum class BadgeTier { BRONZE, SILVER, GOLD }

enum class Badge(
    val id: String,
    val title: String,
    val description: String,
    val emoji: String,
    val tier: BadgeTier,
    /** Count needed for progress-style badges (0 = one-off). */
    val target: Int = 0,
) {
    FIRST_CHECKIN("first_checkin", "First check-in", "Logged a first daily check-in.", "🌱", BadgeTier.BRONZE),
    FIRST_DOSE("first_dose", "First dose", "Logged a first medication dose.", "💊", BadgeTier.BRONZE),
    PERFECT_DAY("perfect_day", "Perfect day", "Every dose due today given, plus a check-in.", "☀️", BadgeTier.BRONZE),
    STREAK_7("streak_7", "One good week", "Cared for 7 days in a row (rest days count).", "🔥", BadgeTier.BRONZE, 7),
    STREAK_30("streak_30", "Habit established", "30-day care streak. Care is part of your day now.", "🏅", BadgeTier.SILVER, 30),
    STREAK_100("streak_100", "Steady heart", "100-day care streak.", "💛", BadgeTier.GOLD, 100),
    PERFECT_WEEK("perfect_week", "Perfect week", "Every scheduled dose given for 7 days straight.", "🌟", BadgeTier.SILVER),
    DOSES_50("doses_50", "50 doses of care", "50 medication doses given.", "🐾", BadgeTier.BRONZE, 50),
    DOSES_250("doses_250", "250 doses of care", "250 medication doses given.", "🧡", BadgeTier.SILVER, 250),
    DOSES_1000("doses_1000", "1,000 doses of care", "1,000 medication doses given. Every one mattered.", "👑", BadgeTier.GOLD, 1000),
    CHECKINS_30("checkins_30", "Month of moments", "30 daily check-ins.", "📅", BadgeTier.SILVER, 30),
    CHECKINS_100("checkins_100", "Keeper of good days", "100 daily check-ins.", "📖", BadgeTier.GOLD, 100),
    WEIGH_IN_REGULAR("weigh_regular", "Weigh-in regular", "4 weigh-ins within 30 days.", "⚖️", BadgeTier.BRONZE, 4),
    CAREFUL_OBSERVER("careful_observer", "Careful observer", "10 journal entries with notes.", "🔎", BadgeTier.BRONZE, 10),
    TEAM_CARE("team_care", "Team care", "Doses given by two or more people.", "🤝", BadgeTier.SILVER),
    VET_READY("vet_ready", "Vet-ready", "Created a first vet report.", "🩺", BadgeTier.BRONZE);

    companion object {
        fun byId(id: String): Badge? = entries.firstOrNull { it.id == id }
    }
}

data class BadgeProgress(
    val badge: Badge,
    val unlockedAt: Instant?,
    /** 0..1 */
    val progress: Float,
    val progressLabel: String?,
) {
    val unlocked: Boolean get() = unlockedAt != null
}

data class CareLevel(
    val number: Int,
    val title: String,
    val minPoints: Int,
)

data class StreakInfo(
    /** Consecutive care days (rest days bridge gaps but don't add to the count). */
    val current: Int,
    val best: Int,
    /** True when today already has care logged. */
    val caredToday: Boolean,
    val restDaysLeftThisWeek: Int,
    val restDaysPerWeek: Int,
    /** Past 30 days: the streak is now a habit and is shown more quietly. */
    val graduated: Boolean,
    /** The last 7 days, oldest first: CARED, REST or MISSED (today may be PENDING). */
    val lastSeven: List<DayMark>,
)

enum class DayMark { CARED, REST, MISSED, PENDING }

/** Today's rings: reset daily, never carry guilt. */
data class CareRings(
    val dosesGiven: Int,
    val dosesDue: Int,
    val checkedIn: Boolean,
    val weeklyCheckInDays: Int,
    val weeklyGoal: Int,
) {
    val doseFraction: Float get() = if (dosesDue == 0) 1f else (dosesGiven.toFloat() / dosesDue).coerceIn(0f, 1f)
    val checkInFraction: Float get() = if (checkedIn) 1f else 0f
    val weeklyFraction: Float get() = (weeklyCheckInDays.toFloat() / weeklyGoal).coerceIn(0f, 1f)
    val allClosed: Boolean get() = doseFraction >= 1f && checkedIn
}

data class TeamContribution(val name: String, val count: Int, val caregiverId: String?)

data class Memory(val date: LocalDate, val label: String, val text: String, val quality: DayQuality)

data class GamificationState(
    val points: Int,
    val level: CareLevel,
    val nextLevel: CareLevel?,
    /** 0..1 progress to [nextLevel]. */
    val levelProgress: Float,
    val pointsToday: Int,
    val streak: StreakInfo,
    val rings: CareRings,
    val badges: List<BadgeProgress>,
    /** Badges earned by the data but not yet stored as unlocked (trigger a celebration once). */
    val newlyEarned: List<Badge>,
    val team: List<TeamContribution>,
    val memories: List<Memory>,
) {
    val unlockedCount: Int get() = badges.count { it.unlocked }
}
