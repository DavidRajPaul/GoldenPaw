package com.goldenpaw

import com.goldenpaw.data.remote.RemotePricingRepository
import com.goldenpaw.data.remote.SupabaseConfig
import com.goldenpaw.domain.model.PricingCatalog
import com.goldenpaw.domain.model.PricingPlan
import com.goldenpaw.domain.model.PromoOffer
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PricingTest {

    /** Exactly the shape gp_pricing_catalog() returns (see 0002_pricing_push_scale.sql). */
    private fun serverJson(version: Long, yearly: String = "$29.99 / year") = """
        {
          "version": $version,
          "plans": [
            {"id":"plus_monthly","title":"Monthly","price_label":"$4.99 / month","detail":"","period":"P1M",
             "trial_days":0,"badge":"","is_default":false,"android_product_id":"m","ios_product_id":"im"},
            {"id":"plus_yearly","title":"Yearly","price_label":"$yearly","detail":"Best for seniors","period":"P1Y",
             "trial_days":14,"badge":"Best value","is_default":true,"android_product_id":"y","ios_product_id":"iy"}
          ],
          "offers": [
            {"id":"diwali","title":"Diwali offer","body":"40% off your first year","badge":"40% off","plan_id":"plus_yearly",
             "price_label":"$17.99 first year","show_on_paywall":false,"starts_at_ms":1000,"ends_at_ms":5000}
          ],
          "paywall": {"headline":"Care for every pet","trial_cta":"Try {days} days free","unknown_key":"ignored"}
        }
    """.trimIndent()

    private val at = { ms: Long -> Instant.fromEpochMilliseconds(ms) }

    @Test
    fun decodesTheServerCatalog() {
        val c = RemotePricingRepository.decode(serverJson(7))!!
        assertEquals(7, c.version)
        assertEquals(listOf("plus_monthly", "plus_yearly"), c.plans.map { it.id })
        assertEquals("plus_yearly", c.defaultPlan.id)
        assertEquals(14, c.defaultPlan.trialDays)
        assertEquals("Care for every pet", c.paywall.headline)
        assertEquals("Try {days} days free", c.paywall.trialCta)
        // Missing copy keeps the defaults.
        assertEquals(PricingCatalog().paywall.footer, c.paywall.footer)
    }

    @Test
    fun garbageDecodesToNull() {
        assertNull(RemotePricingRepository.decode("not json"))
        assertNull(RemotePricingRepository.decode("""{"plans": 3}"""))
    }

    @Test
    fun emptyServerPlansFallBackToTheBundledOnes() {
        val c = PricingCatalog(version = 3, plans = emptyList())
        assertEquals(PricingCatalog.DEFAULT.plans, c.visiblePlans)
        assertEquals("plus_yearly", c.defaultPlan.id)
    }

    @Test
    fun defaultPlanIsTheFirstWhenNoneIsMarked() {
        val c = PricingCatalog(plans = listOf(PricingPlan("a", "A", "1"), PricingPlan("b", "B", "2")))
        assertEquals("a", c.defaultPlan.id)
    }

    @Test
    fun offersRespectTheirWindowAndThePaywallFlag() {
        val c = RemotePricingRepository.decode(serverJson(1))!!
        // Pushed offer: shown when opened from its notification, inside its window only.
        assertEquals("diwali", c.activeOffer(at(2000), offerId = "diwali")?.id)
        assertNull(c.activeOffer(at(999), offerId = "diwali"))
        assertNull(c.activeOffer(at(5000), offerId = "diwali"))
        // Not on the paywall by default.
        assertNull(c.activeOffer(at(2000)))
        // An open-ended paywall offer shows to everyone.
        val open = c.copy(offers = c.offers + PromoOffer(id = "always", title = "Hello"))
        assertEquals("always", open.activeOffer(at(10)).let { it?.id })
        // An unknown id falls back to the paywall offer.
        assertEquals("always", open.activeOffer(at(10), offerId = "gone")?.id)
    }

    @Test
    fun refreshCachesAndRestoreReadsTheCache() = runTest {
        val cloud = MockCloud { respondJson(serverJson(5)) }
        val store = InMemoryKeyValues()
        val repo = RemotePricingRepository(TestCloud, cloud.client, store, platform = "android", country = { "IN" }, appVersion = 9)

        assertEquals(PricingCatalog.DEFAULT, repo.catalog.value)
        val fresh = repo.refresh().getOrThrow()
        assertEquals(5, fresh.version)
        assertEquals("$29.99 / year", repo.catalog.value.defaultPlan.priceLabel)

        val call = cloud.requests.single()
        assertEquals("/rest/v1/rpc/gp_pricing_catalog", call.path)
        assertTrue(call.body.contains("\"p_platform\":\"android\""), call.body)
        assertTrue(call.body.contains("\"p_country\":\"IN\""), call.body)
        assertTrue(call.body.contains("\"p_app_version\":9"), call.body)

        // A new process starts from the cache, before any network.
        val offline = RemotePricingRepository(TestCloud, cloud.client, store, platform = "android")
        offline.restore()
        assertEquals(5, offline.catalog.value.version)
    }

    @Test
    fun anOlderCatalogNeverReplacesANewerOne() = runTest {
        val cloud = MockCloud { respondJson(serverJson(8, yearly = "new")) }
        val store = InMemoryKeyValues()
        val repo = RemotePricingRepository(TestCloud, cloud.client, store, platform = "ios")
        repo.refresh()
        cloud.reply = { respondJson(serverJson(6, yearly = "stale")) }
        repo.refresh()
        assertEquals("new", repo.catalog.value.defaultPlan.priceLabel)
        assertEquals(8, RemotePricingRepository.decode(store.get(RemotePricingRepository.KEY_CACHE)!!)!!.version)
    }

    @Test
    fun failuresKeepTheCurrentCatalog() = runTest {
        val cloud = MockCloud { respondJson("""{"message":"boom"}""", HttpStatusCode.InternalServerError) }
        val repo = RemotePricingRepository(TestCloud, cloud.client, InMemoryKeyValues(), platform = "android")
        assertTrue(repo.refresh().isFailure)
        assertEquals(PricingCatalog.DEFAULT, repo.catalog.value)
    }

    @Test
    fun cloudOffMeansNoRequest() = runTest {
        val cloud = MockCloud()
        val repo = RemotePricingRepository(SupabaseConfig("", ""), cloud.client, InMemoryKeyValues(), platform = "android")
        assertTrue(repo.refresh().isFailure)
        assertTrue(cloud.requests.isEmpty())
    }
}
