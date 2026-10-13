package com.goldenpaw.domain.ads

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The only places a promotion or ad may ever appear. Care surfaces (Today, meds, check-in, quick
 * log, reminders, onboarding, tour, memorial, records, care team) deliberately have no entry, so they
 * can't request one. See docs/MONETIZATION_ADS.md §4.2.
 */
enum class AdPlacement(val wire: String, val rewarded: Boolean = false) {
    /** Bottom of the Insights → Weekly tab. */
    INSIGHTS_FEED("insights_feed"),
    /** In the Journal list, after the 5th entry. */
    JOURNAL_FEED("journal_feed"),
    /** "Watch a short video to read this week's summary": user-initiated, free tier only. */
    WEEKLY_SUMMARY_REWARD("weekly_summary_reward", rewarded = true),
    ;

    companion object {
        fun from(wire: String?): AdPlacement? = entries.firstOrNull { it.wire == wire }
    }
}

/** Every tunable rule. Defaults are the ones in the strategy doc; the backend can override each. */
@Serializable
data class AdPolicy(
    /** Kill switch. Off until turned on in the backend. */
    val enabled: Boolean = false,
    @SerialName("min_days_since_install") val minDaysSinceInstall: Int = 7,
    @SerialName("min_sessions") val minSessions: Int = 3,
    @SerialName("max_per_session") val maxPerSession: Int = 1,
    @SerialName("max_per_day") val maxPerDay: Int = 2,
    @SerialName("min_gap_minutes") val minGapMinutes: Int = 240,
    @SerialName("hide_cooldown_hours") val hideCooldownHours: Int = 72,
    @SerialName("max_rewarded_per_day") val maxRewardedPerDay: Int = 1,
    /** No promotions on a day with a hard check-in for any pet. */
    @SerialName("quiet_on_hard_day") val quietOnHardDay: Boolean = true,
    /** Share of installs (0..100) that never see promotions: the retention baseline. */
    @SerialName("holdout_percent") val holdoutPercent: Int = 10,
    /** Placements switched on (wire names). */
    val placements: List<String> = listOf(AdPlacement.INSIGHTS_FEED.wire, AdPlacement.JOURNAL_FEED.wire),
    /** Share (0..100) of feed impressions given to a third-party network when one is available. */
    @SerialName("network_percent") val networkPercent: Int = 0,
)

/** Who a house creative is for. */
@Serializable
enum class Audience {
    @SerialName("free") FREE,
    @SerialName("plus") PLUS,
    @SerialName("all") ALL,
}

/** One of our own promotion cards (Plus, a seasonal offer, a new feature), configured in the backend. */
@Serializable
data class HouseCreative(
    val id: String,
    val title: String,
    val body: String = "",
    val cta: String = "Learn more",
    /** In-app destination, same vocabulary as push: paywall, settings, careteam, insights, ... */
    val open: String = "paywall",
    @SerialName("offer_id") val offerId: String? = null,
    val placements: List<String> = emptyList(),
    val audience: Audience = Audience.FREE,
    val weight: Int = 1,
    @SerialName("starts_at_ms") val startsAtMs: Long? = null,
    @SerialName("ends_at_ms") val endsAtMs: Long? = null,
) {
    fun isLive(now: Instant): Boolean {
        val ms = now.toEpochMilliseconds()
        return (startsAtMs == null || ms >= startsAtMs) && (endsAtMs == null || ms < endsAtMs)
    }

    fun fits(placement: AdPlacement): Boolean = placements.isEmpty() || placement.wire in placements

    fun isFor(isPlus: Boolean): Boolean = when (audience) {
        Audience.ALL -> true
        Audience.FREE -> !isPlus
        Audience.PLUS -> isPlus
    }
}

/** Backend configuration: served by gp_ads_config(), cached on the device. */
@Serializable
data class AdsCatalog(
    val version: Long = 0,
    val policy: AdPolicy = AdPolicy(),
    val creatives: List<HouseCreative> = emptyList(),
)

/** What the engine knows about this moment. Built from local state only; nothing is sent anywhere. */
data class AdContext(
    val now: Instant,
    val installId: String,
    val isPlus: Boolean,
    /** Some pet had a hard check-in today. */
    val hardDayToday: Boolean,
    /** A third-party network is integrated and ready for this placement. */
    val networkAvailable: Boolean,
)

sealed interface AdDecision {
    data class ShowHouse(val creative: HouseCreative) : AdDecision
    data class ShowNetwork(val placement: AdPlacement) : AdDecision
    data class Skip(val reason: SkipReason) : AdDecision
}

enum class SkipReason {
    DISABLED, PLACEMENT_OFF, PLUS, HOLDOUT, TOO_NEW, TOO_FEW_SESSIONS, QUIET_DAY, HIDDEN_RECENTLY,
    SESSION_CAP, DAILY_CAP, TOO_SOON, REWARD_CAP, NO_CREATIVE,
}

/**
 * A third-party ad network (AdMob, InMobi, ...) behind one small interface. The app ships with
 * [NoAdNetwork]: until an SDK is integrated, only our own house creatives can ever appear.
 */
interface AdNetwork {
    /** An ad is loaded and allowed (consent obtained where required) for this placement. */
    fun isReady(placement: AdPlacement): Boolean

    /** Shows a rewarded ad the user asked for. True when the reward was earned. */
    suspend fun showRewarded(placement: AdPlacement): Boolean
}

object NoAdNetwork : AdNetwork {
    override fun isReady(placement: AdPlacement): Boolean = false
    override suspend fun showRewarded(placement: AdPlacement): Boolean = false
}
