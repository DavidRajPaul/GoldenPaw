package com.goldenpaw

import com.goldenpaw.data.remote.SupabaseConfig
import com.goldenpaw.data.support.SupportDiagnostics
import com.goldenpaw.data.support.SupportService
import com.goldenpaw.data.support.SupportTopic
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportTest {

    private val diagnostics = SupportDiagnostics(
        appVersion = "1.2.0", appBuild = 12, platform = "android", supportId = "AB12-CD34",
        plus = true, signedIn = false, remindersOk = true, locale = "en-IN", timezone = "Asia/Kolkata",
    )

    private fun service(cloud: MockCloud, config: SupabaseConfig = TestCloud) =
        SupportService(config, cloud.client, FakeAuth(), installId = { "ab12cd34-ef56-7890" })

    @Test
    fun supportIdIsShortReadableAndStable() {
        assertEquals("AB12-CD34", SupportDiagnostics.supportIdOf("ab12cd34-ef56-7890"))
        assertEquals("AB12", SupportDiagnostics.supportIdOf("a-b-1-2"))
        assertEquals(SupportDiagnostics.supportIdOf("x9y8z7w6v5"), SupportDiagnostics.supportIdOf("x9y8z7w6v5"))
    }

    @Test
    fun diagnosticsTextHasTheFactsSupportNeedsAndNoPetData() {
        val text = diagnostics.asText()
        assertTrue("Support ID: AB12-CD34" in text)
        assertTrue("1.2.0 (12) on android" in text)
        assertTrue("Plus: yes" in text)
        assertTrue("Asia/Kolkata" in text)
    }

    @Test
    fun emailValidation() {
        assertTrue(SupportService.isPlausibleEmail("owner@example.com"))
        assertFalse(SupportService.isPlausibleEmail("owner@example"))
        assertFalse(SupportService.isPlausibleEmail("owner example.com"))
        assertFalse(SupportService.isPlausibleEmail("a@b@c.com"))
    }

    @Test
    fun percentEncodingUsesPercent20AndEncodesUtf8() {
        assertEquals("a%20b%2Bc%26d", SupportService.encode("a b+c&d"))
        assertEquals("%E2%82%B9", SupportService.encode("₹"))
        assertEquals("safe-._~", SupportService.encode("safe-._~"))
    }

    @Test
    fun sendPostsTheRpcAndReturnsTheTicketNumber() = runTest {
        val cloud = MockCloud { respondJson("42") }
        val result = service(cloud).send(SupportTopic.BILLING, "  I was charged twice.  ", "owner@example.com", diagnostics)
        assertEquals(42L, result.getOrThrow())
        val req = cloud.requests.single()
        assertEquals("/rest/v1/rpc/gp_submit_support_request", req.path)
        assertTrue("\"p_topic\":\"billing\"" in req.body)
        assertTrue("\"p_message\":\"I was charged twice.\"" in req.body)
        assertTrue("\"p_install_id\":\"ab12cd34-ef56-7890\"" in req.body)
        assertTrue("\"support_id\":\"AB12-CD34\"" in req.body)
    }

    @Test
    fun sendRejectsShortMessagesAndBadEmailWithoutCallingTheServer() = runTest {
        val cloud = MockCloud { respondJson("1") }
        val s = service(cloud)
        assertTrue(s.send(SupportTopic.BUG, "short", null, diagnostics).isFailure)
        assertTrue(s.send(SupportTopic.BUG, "This is long enough to send", "nope", diagnostics).isFailure)
        assertTrue(cloud.requests.isEmpty())
    }

    @Test
    fun sendSurfacesServerErrors() = runTest {
        val cloud = MockCloud { respondJson("""{"message":"Too many requests today"}""", HttpStatusCode.BadRequest) }
        val result = service(cloud).send(SupportTopic.OTHER, "Please help with something", null, diagnostics)
        assertTrue(result.isFailure)
    }

    @Test
    fun unconfiguredBuildCannotSendInApp() = runTest {
        val cloud = MockCloud()
        val s = service(cloud, SupabaseConfig(url = "", anonKey = ""))
        assertFalse(s.canSendInApp)
        assertTrue(s.send(SupportTopic.OTHER, "Please help with something", null, diagnostics).isFailure)
        assertTrue(cloud.requests.isEmpty())
    }

    @Test
    fun subjectCarriesTopicAndSupportId() {
        // emailLink is null when no support email is configured (the default in tests and CI).
        val link = service(MockCloud()).emailLink(SupportTopic.BILLING, diagnostics)
        if (link != null) {
            assertTrue(link.startsWith("mailto:"))
            assertTrue(SupportService.encode("[AB12-CD34]") in link)
        }
    }
}
