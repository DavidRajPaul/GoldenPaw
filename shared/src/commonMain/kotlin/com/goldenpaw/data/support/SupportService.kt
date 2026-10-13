package com.goldenpaw.data.support

import com.goldenpaw.config.AppConfig
import com.goldenpaw.data.remote.CloudException
import com.goldenpaw.data.remote.SupabaseConfig
import com.goldenpaw.data.remote.errorMessage
import com.goldenpaw.data.remote.supabaseHeaders
import com.goldenpaw.domain.repository.CloudAuthRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a support message is about. Lets the developer triage quickly. */
enum class SupportTopic(val wire: String, val label: String) {
    BILLING("billing", "Plus, billing or an offer"),
    REMINDERS("reminders", "Reminders or notifications"),
    SYNC("sync", "Care team or sync"),
    BUG("bug", "Something isn't working"),
    IDEA("idea", "An idea or request"),
    OTHER("other", "Something else"),
}

/**
 * The facts that make a support request answerable without back-and-forth. No pet data, no health
 * logs: only app and device state. Shown to the owner before sending.
 */
@Serializable
data class SupportDiagnostics(
    @SerialName("app_version") val appVersion: String = AppConfig.VERSION_NAME,
    @SerialName("app_build") val appBuild: Int = AppConfig.VERSION_CODE,
    val platform: String,
    @SerialName("support_id") val supportId: String,
    val plus: Boolean,
    @SerialName("signed_in") val signedIn: Boolean,
    @SerialName("reminders_ok") val remindersOk: Boolean,
    val locale: String,
    val timezone: String,
) {
    /** The lines appended to a support email (plain text, readable by a person). */
    fun asText(): String = buildString {
        appendLine("Support ID: $supportId")
        appendLine("App: GoldenPaw $appVersion ($appBuild) on $platform")
        appendLine("Plus: ${if (plus) "yes" else "no"} · Signed in: ${if (signedIn) "yes" else "no"} · Reminders OK: ${if (remindersOk) "yes" else "no"}")
        append("Locale: $locale · Time zone: $timezone")
    }

    companion object {
        /** Short, readable support id derived from the install id (what the owner reads out or pastes). */
        fun supportIdOf(installId: String): String =
            installId.filter { it.isLetterOrDigit() }.take(8).uppercase().chunked(4).joinToString("-")
    }
}

@Serializable
internal data class SupportRequestBody(
    @SerialName("p_install_id") val installId: String,
    @SerialName("p_topic") val topic: String,
    @SerialName("p_message") val message: String,
    @SerialName("p_email") val email: String?,
    @SerialName("p_diagnostics") val diagnostics: SupportDiagnostics,
)

/**
 * In-app support: sends a message to the backend inbox (support_requests), or prepares an email.
 * Designed for a solo developer: every request arrives with a support id and diagnostics, so most
 * can be answered in one reply.
 */
class SupportService(
    private val config: SupabaseConfig,
    private val http: HttpClient,
    private val auth: CloudAuthRepository,
    private val installId: suspend () -> String,
) {
    /** The in-app form needs the backend; email works without it. */
    val canSendInApp: Boolean get() = config.isConfigured
    val email: String get() = AppConfig.SUPPORT_EMAIL
    val privacyPolicyUrl: String get() = AppConfig.PRIVACY_POLICY_URL

    suspend fun supportId(): String = SupportDiagnostics.supportIdOf(installId())

    /** Sends a request; returns the ticket number. */
    suspend fun send(topic: SupportTopic, message: String, contactEmail: String?, diagnostics: SupportDiagnostics): Result<Long> = runCatching {
        check(canSendInApp) { "Messaging isn't set up in this build" }
        val text = message.trim()
        require(text.length in MIN_LENGTH..MAX_LENGTH) { "Please write between $MIN_LENGTH and $MAX_LENGTH characters." }
        val email = contactEmail?.trim()?.takeIf { it.isNotEmpty() }
        require(email == null || isPlausibleEmail(email)) { "That email address doesn't look right." }
        val session = auth.validSession()
        val response = http.post("${config.baseUrl}/rest/v1/rpc/gp_submit_support_request") {
            supabaseHeaders(config, session?.accessToken)
            contentType(ContentType.Application.Json)
            setBody(SupportRequestBody(installId(), topic.wire, text, email, diagnostics))
        }
        if (!response.status.isSuccess()) throw CloudException(response.errorMessage(), response.status.value)
        response.bodyAsText().trim().trim('"').toLongOrNull() ?: throw CloudException("Unexpected response")
    }

    /** A mailto: link with the diagnostics already filled in, or null when no support email is configured. */
    fun emailLink(topic: SupportTopic, diagnostics: SupportDiagnostics): String? {
        if (email.isBlank()) return null
        val subject = "GoldenPaw: ${topic.label} [${diagnostics.supportId}]"
        val body = "\n\n\n-- Please keep this so we can help faster --\n" + diagnostics.asText()
        return "mailto:$email?subject=${encode(subject)}&body=${encode(body)}"
    }

    companion object {
        const val MIN_LENGTH = 10
        const val MAX_LENGTH = 4000

        fun isPlausibleEmail(value: String): Boolean =
            Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$").matches(value)

        /** RFC 3986 percent-encoding for mailto query values (spaces as %20, not +). */
        fun encode(value: String): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val c = byte.toInt().toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") append(c)
                else append('%').append(((byte.toInt() and 0xff) or 0x100).toString(16).substring(1).uppercase())
            }
        }
    }
}
