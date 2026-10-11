package com.goldenpaw.domain.model

/**
 * Kinds of remote (push) notification. Each has its own opt-in, its own Android channel and its own
 * column on the `devices` table, so the backend only targets devices that asked for that kind.
 * Local reminders (doses, check-ins, refills, vet visits) are separate and never go through push.
 */
enum class PushCategory(val wire: String) {
    /** Discounts and promotions. Opt-in. */
    OFFERS("offers"),
    /** New features, tips and app news. */
    PRODUCT("product"),
    /** About the owner's account: care-team invites and changes, subscription, backup. */
    ACCOUNT("account"),
    ;

    companion object {
        fun from(wire: String?): PushCategory? = entries.firstOrNull { it.wire == wire?.lowercase() }
    }
}

/**
 * A remote notification as the app understands it. The backend sends the same flat key/value data
 * to FCM (Android, data-only message) and APNs (iOS, custom keys next to `aps`):
 *
 *   category    offers | product | account            (required)
 *   title, body text to show (Android draws the notification itself; iOS uses `aps.alert`)
 *   open        where a tap goes: today, meds, checkin, quicklog, symptom, weekly, careteam, paywall, settings
 *   petId       optional pet for pet-specific destinations
 *   offerId     optional promo_offers.id (opens the paywall on that offer)
 *   campaignId  optional notification_campaigns.id, echoed back for open tracking
 */
data class PushMessage(
    val category: PushCategory,
    val title: String,
    val body: String,
    val open: String,
    val petId: String? = null,
    val offerId: String? = null,
    val campaignId: String? = null,
) {
    companion object {
        const val OPEN_PAYWALL = "paywall"
        const val OPEN_SETTINGS = "settings"

        /** Parses FCM `data` / APNs `userInfo`. Returns null for messages that aren't ours. */
        fun parse(data: Map<String, String?>): PushMessage? {
            val category = PushCategory.from(data["category"]) ?: return null
            val offerId = data["offerId"]?.takeIf { it.isNotBlank() }
            val open = data["open"]?.takeIf { it.isNotBlank() }
                ?: if (offerId != null) OPEN_PAYWALL else "today"
            return PushMessage(
                category = category,
                title = data["title"].orEmpty(),
                body = data["body"].orEmpty(),
                open = open,
                petId = data["petId"]?.takeIf { it.isNotBlank() },
                offerId = offerId,
                campaignId = data["campaignId"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** Whether a message may be shown, given the owner's per-category choices. */
fun PushCategory.isEnabled(offers: Boolean, product: Boolean, account: Boolean): Boolean = when (this) {
    PushCategory.OFFERS -> offers
    PushCategory.PRODUCT -> product
    PushCategory.ACCOUNT -> account
}
