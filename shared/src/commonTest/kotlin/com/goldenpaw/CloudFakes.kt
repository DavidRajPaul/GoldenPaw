package com.goldenpaw

import com.goldenpaw.data.remote.SupabaseConfig
import com.goldenpaw.data.remote.createSupabaseHttpClient
import com.goldenpaw.data.settings.KeyValues
import com.goldenpaw.domain.model.CloudSession
import com.goldenpaw.domain.repository.CloudAuthRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.UserSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant

class InMemoryKeyValues(initial: Map<String, String> = emptyMap()) : KeyValues {
    val values = MutableStateFlow(initial)
    override fun observe(): Flow<Map<String, String>> = values
    override suspend fun get(key: String): String? = values.value[key]
    override suspend fun put(key: String, value: String?) {
        values.value = if (value == null) values.value - key else values.value + (key to value)
    }
}

class FakeSettings(initial: UserSettings = UserSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<UserSettings> = state
    override suspend fun current(): UserSettings = state.value
    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        state.value = transform(state.value)
    }
    override suspend fun clear() {
        state.value = UserSettings()
    }
}

class FakeAuth(session: CloudSession? = null) : CloudAuthRepository {
    private val _session = MutableStateFlow(session)
    override val isAvailable: Boolean = true
    override val session: StateFlow<CloudSession?> = _session
    override suspend fun requestCode(email: String): Result<Unit> = Result.success(Unit)
    override suspend fun verifyCode(email: String, code: String): Result<CloudSession> = Result.failure(UnsupportedOperationException())
    override suspend fun validSession(): CloudSession? = _session.value
    override suspend fun signOut() {
        _session.value = null
    }
    fun signIn(userId: String, token: String = "token-$userId") {
        _session.value = CloudSession(userId, "$userId@example.com", token, "refresh", Instant.fromEpochMilliseconds(Long.MAX_VALUE / 2))
    }
}

/** A recorded request: path, Authorization header and JSON body. */
data class Recorded(val path: String, val authorization: String?, val body: String)

val TestCloud = SupabaseConfig(url = "https://test.supabase.co", anonKey = "anon-key")

/** An HttpClient over a MockEngine that records every request and answers with [reply]. */
class MockCloud(
    var reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respondJson("null") },
) {
    val requests = mutableListOf<Recorded>()
    val client: HttpClient = createSupabaseHttpClient(
        MockEngine { request ->
            requests += Recorded(
                path = request.url.encodedPath,
                authorization = request.headers[HttpHeaders.Authorization],
                body = request.body.toByteArray().decodeToString(),
            )
            reply(this, request)
        },
    )
}

fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
