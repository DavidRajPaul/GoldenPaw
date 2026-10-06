package com.goldenpaw.data.remote

import com.goldenpaw.core.AppClock
import com.goldenpaw.data.settings.KeyValueStore
import com.goldenpaw.domain.model.CloudSession
import com.goldenpaw.domain.repository.CloudAuthRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/** Supabase project coordinates (from the generated AppConfig). Blank = cloud off. */
data class SupabaseConfig(val url: String, val anonKey: String) {
    val isConfigured: Boolean get() = url.isNotBlank() && anonKey.isNotBlank()
    val baseUrl: String get() = url.trimEnd('/')
}

/**
 * Nulls are always written: an upsert that omitted `archived_at` would leave a stale value on the
 * server (PostgREST merge only touches the columns it receives).
 */
val SupabaseJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun createSupabaseHttpClient(engine: io.ktor.client.engine.HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) { json(SupabaseJson) }
}

class CloudException(message: String, val status: Int = 0) : Exception(message)

internal fun HttpRequestBuilder.supabaseHeaders(config: SupabaseConfig, accessToken: String?) {
    header("apikey", config.anonKey)
    header("Authorization", "Bearer ${accessToken ?: config.anonKey}")
}

internal suspend fun HttpResponse.errorMessage(): String {
    val text = runCatching { bodyAsText() }.getOrDefault("")
    // Supabase errors come as {"msg": ...}, {"message": ...} or {"error_description": ...}
    val parsed = runCatching { SupabaseJson.decodeFromString(ErrorDto.serializer(), text) }.getOrNull()
    return parsed?.let { it.message ?: it.msg ?: it.errorDescription ?: it.error }
        ?: text.take(200).ifBlank { "Request failed (${status.value})" }
}

@Serializable
internal data class ErrorDto(
    val message: String? = null,
    val msg: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val code: String? = null,
)

@Serializable
internal data class OtpRequest(val email: String, @SerialName("create_user") val createUser: Boolean = true)

@Serializable
internal data class VerifyRequest(val type: String = "email", val email: String, val token: String)

@Serializable
internal data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
internal data class UserDto(val id: String, val email: String? = null)

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    val user: UserDto? = null,
)

@Serializable
internal data class StoredSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
)

/**
 * Supabase Auth with email one-time codes (no passwords). The session lives in the app's private
 * database. Enable "Email OTP" in Supabase and include {{ .Token }} in the magic-link email template.
 */
class SupabaseAuthRepository(
    private val config: SupabaseConfig,
    private val http: HttpClient,
    private val store: KeyValueStore,
    private val clock: AppClock,
) : CloudAuthRepository {

    private val _session = MutableStateFlow<CloudSession?>(null)
    override val session: StateFlow<CloudSession?> = _session.asStateFlow()
    private val refreshMutex = Mutex()

    override val isAvailable: Boolean get() = config.isConfigured

    /** Loads the persisted session. Called once at startup. */
    suspend fun restore() {
        val raw = store.get(KEY_SESSION) ?: return
        val stored = runCatching { SupabaseJson.decodeFromString(StoredSession.serializer(), raw) }.getOrNull() ?: return
        _session.value = stored.toDomain()
    }

    override suspend fun requestCode(email: String): Result<Unit> = runCatching {
        check(isAvailable) { "Cloud isn't set up in this build" }
        val response = http.post("${config.baseUrl}/auth/v1/otp") {
            supabaseHeaders(config, null)
            contentType(ContentType.Application.Json)
            setBody(OtpRequest(email.trim().lowercase()))
        }
        if (!response.status.isSuccess()) throw CloudException(response.errorMessage(), response.status.value)
    }

    override suspend fun verifyCode(email: String, code: String): Result<CloudSession> = runCatching {
        check(isAvailable) { "Cloud isn't set up in this build" }
        val response = http.post("${config.baseUrl}/auth/v1/verify") {
            supabaseHeaders(config, null)
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(email = email.trim().lowercase(), token = code.filter { it.isDigit() }))
        }
        if (!response.status.isSuccess()) throw CloudException(response.errorMessage(), response.status.value)
        val token = response.body<TokenResponse>()
        saveToken(token, fallbackEmail = email.trim().lowercase())
    }

    override suspend fun validSession(): CloudSession? = refreshMutex.withLock {
        val current = _session.value ?: return null
        if (current.expiresAt - clock.now() > 60.seconds) return current
        val response = runCatching {
            http.post("${config.baseUrl}/auth/v1/token?grant_type=refresh_token") {
                supabaseHeaders(config, null)
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(current.refreshToken))
            }
        }.getOrNull() ?: return current // offline: keep using the old token, the call will 401 and retry later
        if (response.status.value in 400..499) {
            // Refresh token revoked or expired: sign out locally.
            clear()
            return null
        }
        if (!response.status.isSuccess()) return current
        val token = response.body<TokenResponse>()
        saveToken(token, fallbackEmail = current.email)
    }

    override suspend fun signOut() {
        val current = _session.value
        if (current != null) {
            runCatching {
                http.post("${config.baseUrl}/auth/v1/logout") { supabaseHeaders(config, current.accessToken) }
            }
        }
        clear()
    }

    private suspend fun saveToken(token: TokenResponse, fallbackEmail: String): CloudSession {
        val previous = _session.value
        val stored = StoredSession(
            userId = token.user?.id ?: previous?.userId ?: throw CloudException("Sign-in response had no user"),
            email = token.user?.email ?: fallbackEmail,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAtMillis = clock.now().toEpochMilliseconds() + token.expiresIn * 1000,
        )
        store.put(KEY_SESSION, SupabaseJson.encodeToString(StoredSession.serializer(), stored))
        return stored.toDomain().also { _session.value = it }
    }

    private suspend fun clear() {
        store.put(KEY_SESSION, null)
        _session.value = null
    }

    private fun StoredSession.toDomain() = CloudSession(
        userId = userId,
        email = email,
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = Instant.fromEpochMilliseconds(expiresAtMillis),
    )

    companion object {
        const val KEY_SESSION = "cloud.session"
    }
}
