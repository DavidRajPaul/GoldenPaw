package com.goldenpaw.domain.logic

import com.goldenpaw.core.Fmt
import com.goldenpaw.core.minusDays
import com.goldenpaw.core.minusMonths
import com.goldenpaw.core.minusYears
import com.goldenpaw.core.plusDays
import com.goldenpaw.core.startOfWeek
import com.goldenpaw.core.toLocalDate
import com.goldenpaw.domain.model.Attribution
import com.goldenpaw.domain.model.Badge
import com.goldenpaw.domain.model.BadgeProgress
import com.goldenpaw.domain.model.CareLevel
import com.goldenpaw.domain.model.CareRings
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.model.DayMark
import com.goldenpaw.domain.model.DoseEvent
import com.goldenpaw.domain.model.DoseSlot
import com.goldenpaw.domain.model.DoseStatus
import com.goldenpaw.domain.model.GamificationState
import com.goldenpaw.domain.model.Memory
import com.goldenpaw.domain.model.SlotState
import com.goldenpaw.domain.model.StreakInfo
import com.goldenpaw.domain.model.SymptomEntry
import com.goldenpaw.domain.model.TeamContribution
import com.goldenpaw.domain.model.WeightEntry
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Pure, deterministic gamification rules. Everything is recomputed from the care log. */
object GamificationEngine {

    object Points {
        const val DOSE = 10
        const val CHECK_IN = 15
        const val SYMPTOM = 5
        const val WEIGHT = 5
        const val PERFECT_DAY_BONUS = 20
    }

    val levels = listOf(
        CareLevel(1, "New friend", 0),
        CareLevel(2, "Trusted helper", 150),
        CareLevel(3, "Steady hands", 500),
        CareLevel(4, "Care companion", 1200),
        CareLevel(5, "Golden heart", 2500),
        CareLevel(6, "Guardian", 5000),
        CareLevel(7, "Legend of the pack", 10000),
    )

    const val WEEKLY_CHECKIN_GOAL = 5
    private const val GRADUATION_DAYS = 30

    data class Input(
        val today: LocalDate,
        val zone: TimeZone,
        val now: Instant,
        /** The person using this device; their own points and level are shown. */
        val meId: String?,
        val meName: String,
        /** Every GIVEN, non-deleted dose event (all pets). */
        val givenDoses: List<DoseEvent>,
        val checkIns: List<CheckIn>,
        val symptoms: List<SymptomEntry>,
        val weights: List<WeightEntry>,
        /** Today's slots for the pet on screen (rings). */
        val todaySlots: List<DoseSlot>,
        /** Slots of the 7 full days before today for the pet on screen (perfect week). */
        val lastWeekSlots: List<DoseSlot>,
        val restDaysPerWeek: Int,
        val unlocked: Map<String, Instant>,
        val selectedPetId: String?,
    )

    fun compute(input: Input): GamificationState {
        val zone = input.zone
        val today = input.today

        // ---- whose actions count as "mine"
        fun mine(by: Attribution): Boolean = by.caregiverId == null || by.caregiverId == input.meId ||
            (input.meId == null && by.name == input.meName)

        val myDoses = input.givenDoses.filter { mine(Attribution(it.givenBy, it.givenById)) }
        val myCheckIns = input.checkIns.filter { mine(it.loggedBy) }
        val mySymptoms = input.symptoms.filter { mine(it.loggedBy) }
        val myWeights = input.weights.filter { mine(it.loggedBy) }

        // ---- care days (any pet, anyone on this device's team counts for the streak)
        val careDays = buildSet {
            input.givenDoses.forEach { add(it.actualAt.toLocalDate(zone)) }
            input.checkIns.forEach { add(it.date) }
            input.symptoms.forEach { add(it.date) }
            input.weights.forEach { add(it.date) }
        }
        val streak = streak(careDays, today, input.restDaysPerWeek)

        // ---- points
        val perfectToday = isPerfectDay(input.todaySlots, input.checkIns.any { it.date == today && it.petId == input.selectedPetId })
        val points = myDoses.size * Points.DOSE + myCheckIns.size * Points.CHECK_IN +
            mySymptoms.size * Points.SYMPTOM + myWeights.size * Points.WEIGHT +
            (if (perfectToday) Points.PERFECT_DAY_BONUS else 0)
        val pointsToday = myDoses.count { it.actualAt.toLocalDate(zone) == today } * Points.DOSE +
            myCheckIns.count { it.date == today } * Points.CHECK_IN +
            mySymptoms.count { it.date == today } * Points.SYMPTOM +
            myWeights.count { it.date == today } * Points.WEIGHT +
            (if (perfectToday) Points.PERFECT_DAY_BONUS else 0)
        val level = levels.last { points >= it.minPoints }
        val next = levels.firstOrNull { it.minPoints > points }
        val levelProgress = if (next == null) 1f else
            ((points - level.minPoints).toFloat() / (next.minPoints - level.minPoints)).coerceIn(0f, 1f)

        // ---- rings (pet on screen) + weekly goal (any pet)
        val weekStart = today.startOfWeek()
        val weeklyCheckInDays = input.checkIns.filter { it.date >= weekStart && it.date <= today }.map { it.date }.distinct().size
        val rings = CareRings(
            dosesGiven = input.todaySlots.count { it.state == SlotState.GIVEN },
            dosesDue = input.todaySlots.count { it.state != SlotState.SKIPPED },
            checkedIn = input.checkIns.any { it.date == today && it.petId == input.selectedPetId },
            weeklyCheckInDays = weeklyCheckInDays,
            weeklyGoal = WEEKLY_CHECKIN_GOAL,
        )

        // ---- badges
        val totalDoses = input.givenDoses.size
        val totalCheckIns = input.checkIns.size
        val notedEntries = input.symptoms.count { it.notes.isNotBlank() } + input.checkIns.count { it.notes.isNotBlank() }
        val recentWeighIns = input.weights.count { it.date > today.minusDays(30) }
        val caregiversGiving = input.givenDoses.map { it.givenById ?: it.givenBy }.filter { it.isNotBlank() }.distinct().size
        val perfectWeek = input.lastWeekSlots.size >= 7 && input.lastWeekSlots.all { it.state == SlotState.GIVEN }

        fun count(badge: Badge, value: Int) = Triple(badge, value >= badge.target, (value.toFloat() / badge.target).coerceIn(0f, 1f) to "${value.coerceAtMost(badge.target)} / ${badge.target}")
        fun flag(badge: Badge, earned: Boolean) = Triple(badge, earned, (if (earned) 1f else 0f) to null)

        val evaluations: List<Triple<Badge, Boolean, Pair<Float, String?>>> = listOf(
            flag(Badge.FIRST_CHECKIN, totalCheckIns > 0),
            flag(Badge.FIRST_DOSE, totalDoses > 0),
            flag(Badge.PERFECT_DAY, perfectToday),
            count(Badge.STREAK_7, maxOf(streak.current, streak.best)),
            count(Badge.STREAK_30, maxOf(streak.current, streak.best)),
            count(Badge.STREAK_100, maxOf(streak.current, streak.best)),
            flag(Badge.PERFECT_WEEK, perfectWeek),
            count(Badge.DOSES_50, totalDoses),
            count(Badge.DOSES_250, totalDoses),
            count(Badge.DOSES_1000, totalDoses),
            count(Badge.CHECKINS_30, totalCheckIns),
            count(Badge.CHECKINS_100, totalCheckIns),
            count(Badge.WEIGH_IN_REGULAR, recentWeighIns),
            count(Badge.CAREFUL_OBSERVER, notedEntries),
            flag(Badge.TEAM_CARE, caregiversGiving >= 2),
            flag(Badge.VET_READY, false), // unlocked by the report screen, never derived
        )

        val badges = evaluations.map { (badge, earned, progress) ->
            val stored = input.unlocked[badge.id]
            BadgeProgress(
                badge = badge,
                unlockedAt = stored ?: if (earned) input.now else null,
                progress = if (stored != null) 1f else progress.first,
                progressLabel = if (stored != null || earned) null else progress.second,
            )
        }
        val newlyEarned = evaluations.filter { (badge, earned, _) -> earned && badge.id !in input.unlocked }.map { it.first }

        // ---- team (this week)
        val weekEvents = buildList {
            input.givenDoses.filter { it.actualAt.toLocalDate(zone) >= weekStart }.forEach { add(Attribution(it.givenBy, it.givenById)) }
            input.checkIns.filter { it.date >= weekStart }.forEach { add(it.loggedBy) }
            input.symptoms.filter { it.date >= weekStart }.forEach { add(it.loggedBy) }
            input.weights.filter { it.date >= weekStart }.forEach { add(it.loggedBy) }
        }
        val team = weekEvents.groupBy { it.caregiverId ?: it.name.ifBlank { input.meName } }
            .map { (_, list) ->
                val first = list.first()
                TeamContribution(first.name.ifBlank { input.meName }, list.size, first.caregiverId)
            }
            .sortedByDescending { it.count }

        return GamificationState(
            points = points,
            level = level,
            nextLevel = next,
            levelProgress = levelProgress,
            pointsToday = pointsToday,
            streak = streak,
            rings = rings,
            badges = badges,
            newlyEarned = newlyEarned,
            team = team,
            memories = memories(input.checkIns.filter { it.petId == input.selectedPetId }, today),
        )
    }

    fun isPerfectDay(todaySlots: List<DoseSlot>, checkedIn: Boolean): Boolean {
        val due = todaySlots.filter { it.state != SlotState.SKIPPED }
        return checkedIn && due.isNotEmpty() && due.all { it.state == SlotState.GIVEN }
    }

    /**
     * Walks back from today. Missed days are covered by rest days (up to [restDaysPerWeek] per ISO
     * week) without breaking the streak; rest days bridge but don't add to the count. Today without
     * care yet is "pending", never a miss.
     */
    fun streak(careDays: Set<LocalDate>, today: LocalDate, restDaysPerWeek: Int): StreakInfo {
        val restUsed = mutableMapOf<LocalDate, Int>() // week start -> rest days used
        fun tryRest(day: LocalDate): Boolean {
            val week = day.startOfWeek()
            val used = restUsed[week] ?: 0
            if (used >= restDaysPerWeek) return false
            restUsed[week] = used + 1
            return true
        }

        val caredToday = today in careDays
        var current = if (caredToday) 1 else 0
        val marks = mutableMapOf<LocalDate, DayMark>()
        marks[today] = if (caredToday) DayMark.CARED else DayMark.PENDING
        var day = today.minusDays(1)
        val earliest = careDays.minOrNull()
        var guard = 0
        while (earliest != null && day >= earliest && guard < 4000) {
            if (day in careDays) {
                current++
                marks[day] = DayMark.CARED
            } else if (tryRest(day)) {
                // A rest day bridges the gap (capped per week), so a busy day never breaks the chain.
                marks[day] = DayMark.REST
            } else {
                marks[day] = DayMark.MISSED
                break
            }
            day = day.minusDays(1)
            guard++
        }
        // If the only thing keeping a "streak" alive is rest days with no cared day, it's zero.
        if (marks.values.none { it == DayMark.CARED }) current = 0

        val lastSeven = (6 downTo 0).map { offset ->
            val d = today.minusDays(offset)
            marks[d] ?: if (d in careDays) DayMark.CARED else if (d == today) DayMark.PENDING else DayMark.MISSED
        }
        val thisWeekUsed = restUsed[today.startOfWeek()] ?: 0
        return StreakInfo(
            current = current,
            best = maxOf(current, bestStreak(careDays, restDaysPerWeek)),
            caredToday = caredToday,
            restDaysLeftThisWeek = (restDaysPerWeek - thisWeekUsed).coerceAtLeast(0),
            restDaysPerWeek = restDaysPerWeek,
            graduated = current >= GRADUATION_DAYS,
            lastSeven = lastSeven,
        )
    }

    /** Longest streak in history, using the same rest-day rule. */
    fun bestStreak(careDays: Set<LocalDate>, restDaysPerWeek: Int): Int {
        if (careDays.isEmpty()) return 0
        val sorted = careDays.sorted()
        var best = 0
        var run = 0
        val restUsed = mutableMapOf<LocalDate, Int>()
        var day = sorted.first()
        val last = sorted.last()
        while (day <= last) {
            if (day in careDays) {
                run++
            } else {
                val week = day.startOfWeek()
                val used = restUsed[week] ?: 0
                if (run > 0 && used < restDaysPerWeek) {
                    restUsed[week] = used + 1
                } else {
                    run = 0
                }
            }
            best = maxOf(best, run)
            day = day.plusDays(1)
        }
        return best
    }

    /** "On this day" check-in notes from a month, six months and a year ago. */
    fun memories(checkIns: List<CheckIn>, today: LocalDate): List<Memory> {
        val targets = listOf(
            today.minusYears(1) to "One year ago",
            today.minusMonths(6) to "Six months ago",
            today.minusMonths(1) to "A month ago",
        )
        return targets.mapNotNull { (date, label) ->
            val c = checkIns.firstOrNull { it.date == date } ?: return@mapNotNull null
            val text = c.notes.ifBlank { "${c.dayQuality.label} (${Fmt.dayMonthYear(date)})" }
            Memory(date, label, text, c.dayQuality)
        }
    }
}
