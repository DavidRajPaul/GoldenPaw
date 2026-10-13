package com.goldenpaw.domain.ads

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** One shown promotion (house or network). */
@Serializable
data class AdImpression(
    @SerialName("at") val atMs: Long,
    val session: Int,
    val placement: String,
    /** House creative id, or null for a network ad. */
    val creative: String? = null,
)

/**
 * On-device history the caps are checked against. Kept for 48 hours (enough for every rule); never
 * sent to the backend or an ad network.
 */
@Serializable
data class AdLedger(
    @SerialName("installed_at") val installedAtMs: Long,
    val sessions: Int = 0,
    val impressions: List<AdImpression> = emptyList(),
    @SerialName("hidden_at") val lastHiddenAtMs: Long? = null,
    val rewards: List<Long> = emptyList(),
) {
    fun startSession(): AdLedger = copy(sessions = sessions + 1)

    fun record(impression: AdImpression): AdLedger = copy(impressions = impressions + impression)

    fun hidden(now: Instant): AdLedger = copy(lastHiddenAtMs = now.toEpochMilliseconds())

    fun rewarded(now: Instant): AdLedger = copy(rewards = rewards + now.toEpochMilliseconds())

    /** Drops history no rule looks at any more. */
    fun trimmed(now: Instant): AdLedger {
        val cutoff = now.toEpochMilliseconds() - KEEP_MS
        return copy(impressions = impressions.filter { it.atMs >= cutoff }, rewards = rewards.filter { it >= cutoff })
    }

    companion object {
        private val KEEP_MS = 2.days.inWholeMilliseconds
    }
}

/**
 * The calm promotions engine: one pure function that decides whether a placement may show something
 * right now, and what. Every rule in docs/MONETIZATION_ADS.md §4.3 is one check here, each with a
 * named [SkipReason]. Order matters: cheap, absolute rules first.
 */
object AdEngine {

    fun decide(
        placement: AdPlacement,
        context: AdContext,
        ledger: AdLedger,
        catalog: AdsCatalog,
        random: Random = Random.Default,
    ): AdDecision {
        val policy = catalog.policy
        val now = context.now.toEpochMilliseconds()

        if (!policy.enabled) return skip(SkipReason.DISABLED)
        if (placement.wire !in policy.placements) return skip(SkipReason.PLACEMENT_OFF)
        if (isHoldout(context.installId, policy.holdoutPercent)) return skip(SkipReason.HOLDOUT)
        // Plus members see no ads and no sales: only house news written for them.
        if (context.isPlus && placement.rewarded) return skip(SkipReason.PLUS)
        if (now - ledger.installedAtMs < policy.minDaysSinceInstall.days.inWholeMilliseconds) return skip(SkipReason.TOO_NEW)
        if (ledger.sessions < policy.minSessions) return skip(SkipReason.TOO_FEW_SESSIONS)
        if (policy.quietOnHardDay && context.hardDayToday) return skip(SkipReason.QUIET_DAY)
        val hidden = ledger.lastHiddenAtMs
        if (hidden != null && now - hidden < policy.hideCooldownHours.hours.inWholeMilliseconds) return skip(SkipReason.HIDDEN_RECENTLY)

        if (placement.rewarded) {
            // User-initiated: only the reward cap applies, and only a network can serve it.
            val today = ledger.rewards.count { now - it < 1.days.inWholeMilliseconds }
            if (today >= policy.maxRewardedPerDay) return skip(SkipReason.REWARD_CAP)
            return if (context.networkAvailable) AdDecision.ShowNetwork(placement) else skip(SkipReason.NO_CREATIVE)
        }

        val recent = ledger.impressions
        if (recent.count { it.session == ledger.sessions } >= policy.maxPerSession) return skip(SkipReason.SESSION_CAP)
        if (recent.count { now - it.atMs < 1.days.inWholeMilliseconds } >= policy.maxPerDay) return skip(SkipReason.DAILY_CAP)
        val last = recent.maxOfOrNull { it.atMs }
        if (last != null && now - last < policy.minGapMinutes.minutes.inWholeMilliseconds) return skip(SkipReason.TOO_SOON)

        val networkTurn = !context.isPlus && context.networkAvailable && random.nextInt(100) < policy.networkPercent
        if (networkTurn) return AdDecision.ShowNetwork(placement)

        val lastCreative = recent.maxByOrNull { it.atMs }?.creative
        val eligible = catalog.creatives.filter { it.weight > 0 && it.fits(placement) && it.isFor(context.isPlus) && it.isLive(context.now) }
        // Never the same card twice in a row when there's an alternative.
        val pool = eligible.filter { it.id != lastCreative }.ifEmpty { eligible }
        val pick = weightedPick(pool, random)
        return when {
            pick != null -> AdDecision.ShowHouse(pick)
            !context.isPlus && context.networkAvailable && policy.networkPercent > 0 -> AdDecision.ShowNetwork(placement)
            else -> skip(SkipReason.NO_CREATIVE)
        }
    }

    /** Stable per install: the same install is always in (or out of) the holdout. */
    fun isHoldout(installId: String, percent: Int): Boolean {
        if (percent <= 0) return false
        if (percent >= 100) return true
        return bucket(installId) < percent
    }

    /** 0..99 from a FNV-1a hash of the install id. */
    fun bucket(installId: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (c in installId) {
            hash = hash xor c.code
            hash *= 0x01000193
        }
        return ((hash.toLong() and 0xffffffffL) % 100).toInt()
    }

    private fun weightedPick(items: List<HouseCreative>, random: Random): HouseCreative? {
        val total = items.sumOf { it.weight }
        if (total <= 0) return null
        var roll = random.nextInt(total)
        for (item in items) {
            roll -= item.weight
            if (roll < 0) return item
        }
        return items.last()
    }

    private fun skip(reason: SkipReason) = AdDecision.Skip(reason)
}
