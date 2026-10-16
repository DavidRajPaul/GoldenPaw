package com.goldenpaw.ui.support

import com.goldenpaw.platform.PlatformKind

/** Where an FAQ answer can take the owner next. */
enum class FaqAction { REMINDERS, MANAGE_SUBSCRIPTION, CARE_TEAM, SETTINGS }

data class FaqEntry(
    val id: String,
    val question: String,
    val answer: String,
    val action: FaqAction? = null,
    val actionLabel: String? = null,
)

/**
 * Bundled answers to the questions a solo developer would otherwise answer by hand. Bundled (not
 * fetched) so Help works offline and with no backend. Wording is per platform where billing differs.
 */
object Faq {
    fun forPlatform(kind: PlatformKind): List<FaqEntry> = buildList {
        add(
            FaqEntry(
                "reminders",
                "My reminders didn't go off",
                "Phones pause apps to save battery. Open the reminders health check: it shows anything blocking " +
                    "notifications and takes you to the right setting. Reminders are scheduled on this device, so " +
                    "they work without internet.",
                FaqAction.REMINDERS, "Open reminders",
            ),
        )
        when (kind) {
            PlatformKind.ANDROID -> add(
                FaqEntry(
                    "cancel",
                    "How do I cancel Plus or get a refund?",
                    "Plus is billed by Google Play. Cancel any time in Play Store → Payments & subscriptions; you keep " +
                        "Plus until the period ends. Within 48 hours of a charge you can request a refund from Google " +
                        "directly; after that, message us and we'll help.",
                    FaqAction.MANAGE_SUBSCRIPTION, "Manage subscription",
                ),
            )
            PlatformKind.IOS -> add(
                FaqEntry(
                    "cancel",
                    "How do I cancel Plus or get a refund?",
                    "Plus is billed by Apple. Cancel in Settings → your name → Subscriptions; you keep Plus until the " +
                        "period ends. Refunds are handled by Apple at reportaproblem.apple.com. We can't issue them, " +
                        "but we're happy to help if something went wrong.",
                    FaqAction.MANAGE_SUBSCRIPTION, "Manage subscription",
                ),
            )
            PlatformKind.DESKTOP -> Unit
        }
        add(
            FaqEntry(
                "restore",
                "I paid but Plus isn't showing",
                "Make sure you're signed in to the same store account you bought with, then reopen the app. If it " +
                    "still isn't there, send us a message with topic \"Plus, billing or an offer\". Your support ID " +
                    "lets us find the purchase.",
            ),
        )
        add(
            FaqEntry(
                "offers",
                "I got an offer notification. Is it real?",
                "Offers only ever open inside GoldenPaw and are charged by the App Store or Google Play. We never ask " +
                    "for card details in a message or on a website. You can turn offer notifications off in Settings " +
                    "and still get care reminders.",
                FaqAction.SETTINGS, "Notification settings",
            ),
        )
        add(
            FaqEntry(
                "team",
                "How does the care team work?",
                "Invite family or a sitter with a code. Everyone sees the same meds, doses and check-ins, and logging a " +
                    "dose on one phone updates the others. The owner can remove members at any time.",
                FaqAction.CARE_TEAM, "Open care team",
            ),
        )
        add(
            FaqEntry(
                "scan",
                "Scanning a vet record didn't read it correctly",
                "Scanning works best on flat, well-lit pages. You can always correct the fields before saving, and the " +
                    "photo of the original stays attached to the record.",
            ),
        )
        add(
            FaqEntry(
                "data",
                "Where is my data, and how do I delete it?",
                "Pet records live on this device. If you use a care team, shared records are also stored in our " +
                    "cloud database so your team can see them. Settings lets you export everything, delete all data " +
                    "on this device, or delete your account and what it shared.",
                FaqAction.SETTINGS, "Open settings",
            ),
        )
    }
}
