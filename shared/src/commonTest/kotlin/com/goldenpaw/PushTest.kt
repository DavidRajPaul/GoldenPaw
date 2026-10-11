package com.goldenpaw

import com.goldenpaw.data.remote.PushRegistrationService
import com.goldenpaw.data.remote.SupabaseConfig
import com.goldenpaw.data.settings.FeatureTipsStore
import com.goldenpaw.domain.model.PushCategory
import com.goldenpaw.domain.model.PushMessage
import com.goldenpaw.domain.model.isEnabled
import com.goldenpaw.domain.repository.UserSettings
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushMessageTest {

    @Test
    fun parsesAnOfferPush() {
        val m = PushMessage.parse(mapOf("category" to "offers", "title" to "40% off", "body" to "This week only", "offerId" to "diwali", "campaignId" to "c1"))!!
        assertEquals(PushCategory.OFFERS, m.category)
        assertEquals(PushMessage.OPEN_PAYWALL, m.open) // an offer opens the paywall unless told otherwise
        assertEquals("diwali", m.offerId)
        assertEquals("c1", m.campaignId)
    }

    @Test
    fun parsesAnAccountPushWithADestination() {
        val m = PushMessage.parse(mapOf("category" to "ACCOUNT", "title" to "Sam joined", "body" to "", "open" to "careteam", "petId" to " "))!!
        assertEquals(PushCategory.ACCOUNT, m.category)
        assertEquals("careteam", m.open)
        assertNull(m.petId) // blanks are dropped
        assertNull(m.offerId)
    }

    @Test
    fun defaultsToTodayAndIgnoresForeignMessages() {
        assertEquals("today", PushMessage.parse(mapOf("category" to "product", "title" to "New"))!!.open)
        assertNull(PushMessage.parse(mapOf("title" to "No category")))
        assertNull(PushMessage.parse(mapOf("category" to "marketing")))
    }

    @Test
    fun categoriesFollowTheOwnersChoices() {
        assertFalse(PushCategory.OFFERS.isEnabled(offers = false, product = true, account = true))
        assertTrue(PushCategory.OFFERS.isEnabled(offers = true, product = false, account = false))
        assertFalse(PushCategory.PRODUCT.isEnabled(offers = true, product = false, account = true))
        assertTrue(PushCategory.ACCOUNT.isEnabled(offers = false, product = false, account = true))
    }

    @Test
    fun offersAreOptInByDefault() {
        val s = UserSettings()
        assertFalse(s.offerNotifications)
        assertTrue(s.productNotifications)
        assertTrue(s.accountNotifications)
        assertFalse(s.tourDone)
    }
}

class PushRegistrationTest {

    private fun service(
        cloud: MockCloud,
        store: InMemoryKeyValues = InMemoryKeyValues(),
        settings: FakeSettings = FakeSettings(),
        auth: FakeAuth = FakeAuth(),
        config: SupabaseConfig = TestCloud,
    ) = PushRegistrationService(config, cloud.client, store, auth, settings, timeZone = { "Asia/Kolkata" })

    @Test
    fun nothingIsSentBeforeTheOsGivesAToken() = runTest {
        val cloud = MockCloud()
        assertEquals(false, service(cloud).register().getOrThrow())
        assertTrue(cloud.requests.isEmpty())
    }

    @Test
    fun registersTheInstallWithTokenPrefsAndTimeZone() = runTest {
        val cloud = MockCloud()
        val store = InMemoryKeyValues()
        val push = service(cloud, store, FakeSettings(UserSettings(offerNotifications = true, productNotifications = false)))

        push.onNewToken("android", "tok-1", "en-IN")

        val call = cloud.requests.single()
        assertEquals("/rest/v1/rpc/gp_register_device", call.path)
        assertEquals("Bearer anon-key", call.authorization) // signed out: anonymous install
        val installId = push.installId()
        assertTrue(installId.length >= 8)
        listOf(
            "\"p_install_id\":\"$installId\"", "\"p_platform\":\"android\"", "\"p_push_token\":\"tok-1\"",
            "\"p_locale\":\"en-IN\"", "\"p_timezone\":\"Asia/Kolkata\"",
            "\"p_offers\":true", "\"p_product\":false", "\"p_account\":true",
        ).forEach { assertTrue(call.body.contains(it), "missing $it in ${call.body}") }
        // The install id is stable.
        assertEquals(installId, push.installId())
    }

    @Test
    fun onlyChangesAreSent() = runTest {
        val cloud = MockCloud()
        val settings = FakeSettings()
        val auth = FakeAuth()
        val push = service(cloud, settings = settings, auth = auth)
        push.onNewToken("ios", "abc", "en-US")
        assertEquals(false, push.register().getOrThrow())
        assertEquals(1, cloud.requests.size)

        settings.update { it.copy(offerNotifications = true) }
        assertEquals(true, push.register().getOrThrow())

        auth.signIn("user-1", token = "jwt-1")
        assertEquals(true, push.register().getOrThrow())
        assertEquals("Bearer jwt-1", cloud.requests.last().authorization) // links the device to the user

        assertEquals(true, push.register(force = true).getOrThrow())
        assertEquals(4, cloud.requests.size)
    }

    @Test
    fun aRotatedTokenIsRegisteredAgain() = runTest {
        val cloud = MockCloud()
        val push = service(cloud)
        push.onNewToken("android", "old", "en")
        push.onNewToken("android", "new", "en")
        assertEquals(2, cloud.requests.size)
        assertTrue(cloud.requests.last().body.contains("\"p_push_token\":\"new\""))
    }

    @Test
    fun aFailedRegistrationIsRetriedNextTime() = runTest {
        val cloud = MockCloud { respondJson("""{"message":"down"}""", HttpStatusCode.ServiceUnavailable) }
        val push = service(cloud)
        push.onNewToken("android", "t", "en")
        assertTrue(push.register().isFailure)
        cloud.reply = { respondJson("null") }
        assertEquals(true, push.register().getOrThrow())
    }

    @Test
    fun cloudOffMeansNoRequests() = runTest {
        val cloud = MockCloud()
        val push = service(cloud, config = SupabaseConfig("", ""))
        push.onNewToken("android", "t", "en")
        assertFalse(push.isAvailable)
        assertTrue(cloud.requests.isEmpty())
    }
}

class FeatureTipsStoreTest {
    @Test
    fun tipsAreRememberedAndCanBeReset() = runTest {
        val store = FeatureTipsStore(InMemoryKeyValues())
        assertEquals(emptySet(), store.seen.first())
        store.markSeen("quick_log")
        store.markSeen("quick_log")
        store.markAllSeen(listOf("meds", "tab_journal"))
        assertEquals(setOf("quick_log", "meds", "tab_journal"), store.seen.first())
        store.reset()
        assertEquals(emptySet(), store.seen.first())
    }

    @Test
    fun encodingIsStableAndTolerant() {
        assertEquals("a,b", FeatureTipsStore.encode(setOf("b", "a")))
        assertEquals(setOf("a", "b"), FeatureTipsStore.decode(" a, ,b,"))
        assertEquals(emptySet(), FeatureTipsStore.decode(null))
    }
}
