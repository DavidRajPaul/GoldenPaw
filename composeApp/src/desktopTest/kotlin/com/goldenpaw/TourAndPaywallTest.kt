package com.goldenpaw

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.goldenpaw.domain.model.PricingCatalog
import com.goldenpaw.domain.model.PromoOffer
import com.goldenpaw.domain.model.PushMessage
import com.goldenpaw.ui.LaunchRequest
import com.goldenpaw.ui.paywall.paywallTerms
import com.goldenpaw.ui.tour.CoachMarkController
import com.goldenpaw.ui.tour.FeatureTips
import com.goldenpaw.ui.tour.bubblePosition
import com.goldenpaw.ui.tour.tourPages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoachMarkTest {
    private val window = Size(1080f, 2000f)

    private fun controller(seen: Set<String> = emptySet()) = CoachMarkController().apply {
        this.seen = seen
        enabled = true
    }

    @Test
    fun showsTheHighestPriorityUnseenTipOnScreen() {
        val c = controller()
        c.update(FeatureTips.Meds, Rect(0f, 500f, 1000f, 600f))
        c.update(FeatureTips.QuickLog, Rect(700f, 1800f, 1050f, 1900f))
        assertEquals(FeatureTips.QuickLog, c.candidate(window))

        c.seen = setOf(FeatureTips.QuickLog.id)
        assertEquals(FeatureTips.Meds, c.candidate(window))
    }

    @Test
    fun waitsUntilLoadedAndEnabled() {
        val c = CoachMarkController()
        c.update(FeatureTips.Meds, Rect(0f, 500f, 1000f, 600f))
        assertNull(c.candidate(window)) // seen not loaded yet
        c.seen = emptySet()
        assertNull(c.candidate(window)) // not enabled (tour or a sheet is up)
        c.enabled = true
        assertEquals(FeatureTips.Meds, c.candidate(window))
    }

    @Test
    fun ignoresAnchorsThatAreOffScreenOrGone() {
        val c = controller()
        c.update(FeatureTips.Meds, Rect(0f, 1950f, 1000f, 2100f)) // half below the fold
        c.update(FeatureTips.CheckIn, Rect(0f, 0f, 0f, 0f)) // not laid out
        assertNull(c.candidate(window))
        c.update(FeatureTips.Meds, Rect(0f, 900f, 1000f, 1000f)) // scrolled into view
        assertEquals(FeatureTips.Meds, c.candidate(window))
        c.remove(FeatureTips.Meds.id)
        assertNull(c.candidate(window))
    }

    @Test
    fun tipIdsAreUnique() {
        assertEquals(FeatureTips.all.size, FeatureTips.all.map { it.id }.toSet().size)
    }

    @Test
    fun bubbleGoesBelowWhenThereIsRoomElseAbove() {
        val below = bubblePosition(Rect(100f, 100f, 300f, 160f), 400, 200, 1080, 2000, margin = 40, gap = 50)
        assertEquals(40 to 210, below) // x clamped to the margin, y under the anchor
        val above = bubblePosition(Rect(700f, 1800f, 1000f, 1900f), 400, 200, 1080, 2000, margin = 40, gap = 50)
        assertEquals(640 to 1550, above) // x clamped to the right edge, y over the anchor
    }

    @Test
    fun tourUsesThePetsName() {
        val pages = tourPages("Biscuit")
        assertTrue(pages.first().body.contains("Biscuit"))
        assertTrue(tourPages(null).first().body.contains("your pet"))
    }
}

class PaywallAndLaunchTest {
    @Test
    fun termsFollowTheBackendPlan() {
        val yearly = PricingCatalog.DEFAULT.defaultPlan
        assertTrue(paywallTerms(yearly, null).startsWith("Free for 7 days, then $39.99 / year."))
        val noTrial = yearly.copy(trialDays = 0)
        assertTrue(paywallTerms(noTrial, null).startsWith("$39.99 / year."))
        val offer = PromoOffer(id = "o", title = "Sale", planId = yearly.id, priceLabel = "$19.99 first year")
        assertTrue(paywallTerms(yearly, offer).contains("then $19.99 first year"))
        val otherPlan = offer.copy(planId = "plus_monthly")
        assertTrue(paywallTerms(yearly, otherPlan).contains("then $39.99 / year"))
    }

    @Test
    fun pushTapsBecomeLaunchRequests() {
        val push = PushMessage.parse(mapOf("category" to "offers", "title" to "Sale", "offerId" to "diwali"))!!
        val r = LaunchRequest.from(push, nonce = 42)
        assertEquals(LaunchRequest.OPEN_PAYWALL, r.open)
        assertEquals("diwali", r.offerId)
        assertEquals(42L, r.nonce)
    }
}
