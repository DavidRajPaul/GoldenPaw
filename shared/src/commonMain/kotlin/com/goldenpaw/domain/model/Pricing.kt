package com.goldenpaw.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A GoldenPaw Plus plan as configured on the backend (`pricing_plans`). Labels are display text
 * written by whoever edits pricing; once store billing is wired, the store's localized price for
 * [androidProductId] / [iosProductId] should replace [priceLabel] on screen.
 */
@Serializable
data class PricingPlan(
    val id: String,
    val title: String,
    @SerialName("price_label") val priceLabel: String,
    val detail: String = "",
    /** ISO-8601 period: P1M, P1Y, … */
    val period: String = "P1M",
    @SerialName("trial_days") val trialDays: Int = 0,
    /** Short ribbon such as "Best value". Empty = none. */
    val badge: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("android_product_id") val androidProductId: String = "",
    @SerialName("ios_product_id") val iosProductId: String = "",
)

/** A time-boxed promotion (`promo_offers`). Usually targeted by an offer push notification. */
@Serializable
data class PromoOffer(
    val id: String,
    val title: String,
    val body: String = "",
    /** Short ribbon such as "40% off". */
    val badge: String = "",
    /** The plan this offer discounts; null = applies to the paywall as a whole. */
    @SerialName("plan_id") val planId: String? = null,
    /** Discounted price text, e.g. "$23.99 for the first year". Empty = keep the plan's label. */
    @SerialName("price_label") val priceLabel: String = "",
    @SerialName("starts_at_ms") val startsAtMs: Long? = null,
    @SerialName("ends_at_ms") val endsAtMs: Long? = null,
    /** Shown on the paywall without a push (otherwise only when opened from the offer's notification). */
    @SerialName("show_on_paywall") val showOnPaywall: Boolean = true,
) {
    fun isLive(now: Instant): Boolean {
        val ms = now.toEpochMilliseconds()
        return (startsAtMs == null || ms >= startsAtMs) && (endsAtMs == null || ms < endsAtMs)
    }
}

@Serializable
data class PaywallCopy(
    val headline: String = "GoldenPaw Plus",
    /** "{days}" is replaced with the plan's trial length. */
    @SerialName("trial_cta") val trialCta: String = "Start {days}-day free trial",
    @SerialName("buy_cta") val buyCta: String = "Continue",
    val footer: String = "Medication reminders and daily logging always stay free.",
)

/** Everything the paywall needs, served by `gp_pricing_catalog()` and cached on the device. */
@Serializable
data class PricingCatalog(
    val version: Long = 0,
    val plans: List<PricingPlan> = emptyList(),
    val offers: List<PromoOffer> = emptyList(),
    val paywall: PaywallCopy = PaywallCopy(),
) {
    /** Plans to show; falls back to the bundled defaults if the server sent none. */
    val visiblePlans: List<PricingPlan> get() = plans.ifEmpty { DEFAULT.plans }

    val defaultPlan: PricingPlan get() = visiblePlans.firstOrNull { it.isDefault } ?: visiblePlans.first()

    /**
     * The offer to show now. An explicit [offerId] (from a push notification) wins even if the offer
     * isn't on the paywall by default; otherwise the first live paywall offer.
     */
    fun activeOffer(now: Instant, offerId: String? = null): PromoOffer? {
        val live = offers.filter { it.isLive(now) }
        if (offerId != null) live.firstOrNull { it.id == offerId }?.let { return it }
        return live.firstOrNull { it.showOnPaywall }
    }

    companion object {
        /** Bundled fallback: used offline before the first successful fetch, or with cloud off. */
        val DEFAULT = PricingCatalog(
            version = 0,
            plans = listOf(
                PricingPlan(
                    id = "plus_yearly",
                    title = "Yearly",
                    priceLabel = "$39.99 / year",
                    detail = "≈ $3.33 / month · save 44%",
                    period = "P1Y",
                    trialDays = 7,
                    badge = "Best value",
                    isDefault = true,
                    androidProductId = "goldenpaw_plus_yearly",
                    iosProductId = "com.goldenpaw.plus.yearly",
                ),
                PricingPlan(
                    id = "plus_monthly",
                    title = "Monthly",
                    priceLabel = "$5.99 / month",
                    detail = "Cancel any time",
                    period = "P1M",
                    trialDays = 7,
                    androidProductId = "goldenpaw_plus_monthly",
                    iosProductId = "com.goldenpaw.plus.monthly",
                ),
            ),
        )
    }
}
