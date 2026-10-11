package com.goldenpaw.data.remote

import com.goldenpaw.config.AppConfig
import com.goldenpaw.core.newId
import com.goldenpaw.data.settings.KeyValues
import com.goldenpaw.domain.repository.CloudAuthRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.UserSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body of `gp_register_device(...)`. One row per app install (not per user). */
@Serializable
data class DeviceRegistration(
    @SerialName("p_install_id") val installId: String,
    @SerialName("p_platform") val platform: String,
    @SerialName("p_push_token") val pushToken: String?,
    @SerialName("p_app_version") val appVersion: String,
    @SerialName("p_app_build") val appBuild: Int,
    @SerialName("p_locale") val locale: String,
    @SerialName("p_timezone") val timezone: String,
    @SerialName("p_offers") val offers: Boolean,
    @SerialName("p_product") val product: Boolean,
    @SerialName("p_account") val account: Boolean,
)

/**
 * Keeps this install's row in the backend `devices` table current: push token, app version, time
 * zone and the owner's per-category choices. Works signed out (anonymous installs still get offers
 * and app news); once signed in the row is linked to the user so account notifications can reach
 * every device they use. Re-registers only when something actually changed.
 */
class PushRegistrationService(
    private val config: SupabaseConfig,
    private val http: HttpClient,
    private val store: KeyValues,
    private val auth: CloudAuthRepository,
    private val settings: SettingsRepository,
    private val timeZone: () -> String = { TimeZone.currentSystemDefault().id },
) {
    private val mutex = Mutex()

    val isAvailable: Boolean get() = config.isConfigured

    /** Stable random id for this install (survives sign-out; reset only by reinstalling). */
    suspend fun installId(): String = store.get(KEY_INSTALL_ID) ?: newId().also { store.put(KEY_INSTALL_ID, it) }

    /** Called by the platform when the OS hands out (or rotates) a push token. */
    suspend fun onNewToken(platform: String, token: String, locale: String) {
        store.put(KEY_TOKEN, token)
        store.put(KEY_PLATFORM, platform)
        store.put(KEY_LOCALE, locale)
        register()
    }

    /**
     * Re-registers whenever the notification choices or the signed-in user change. Call once from
     * an app-lifetime scope.
     */
    fun startIn(scope: CoroutineScope) {
        scope.launch {
            combine(
                settings.settings.map { Triple(it.offerNotifications, it.productNotifications, it.accountNotifications) },
                auth.session.map { it?.userId },
            ) { prefs, user -> prefs to user }
                .distinctUntilChanged()
                .collect { runCatching { register() } }
        }
    }

    /**
     * Sends the registration if it differs from the last one that succeeded (or [force]).
     * Returns true when a request was made and accepted.
     */
    suspend fun register(force: Boolean = false): Result<Boolean> = mutex.withLock {
        runCatching {
            if (!config.isConfigured) return@runCatching false
            val platform = store.get(KEY_PLATFORM) ?: return@runCatching false // no token yet
            val body = buildRegistration(
                installId = installId(),
                platform = platform,
                token = store.get(KEY_TOKEN),
                locale = store.get(KEY_LOCALE).orEmpty(),
                timezone = timeZone(),
                settings = settings.current(),
            )
            val session = auth.validSession()
            val fingerprint = SupabaseJson.encodeToString(DeviceRegistration.serializer(), body) + "|" + session?.userId.orEmpty()
            if (!force && fingerprint == store.get(KEY_FINGERPRINT)) return@runCatching false

            val response = http.post("${config.baseUrl}/rest/v1/rpc/gp_register_device") {
                supabaseHeaders(config, session?.accessToken)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (!response.status.isSuccess()) throw CloudException(response.errorMessage(), response.status.value)
            store.put(KEY_FINGERPRINT, fingerprint)
            true
        }
    }

    companion object {
        const val KEY_INSTALL_ID = "push.install_id"
        const val KEY_TOKEN = "push.token"
        const val KEY_PLATFORM = "push.platform"
        const val KEY_LOCALE = "push.locale"
        const val KEY_FINGERPRINT = "push.registered"

        fun buildRegistration(
            installId: String,
            platform: String,
            token: String?,
            locale: String,
            timezone: String,
            settings: UserSettings,
        ) = DeviceRegistration(
            installId = installId,
            platform = platform,
            pushToken = token?.takeIf { it.isNotBlank() },
            appVersion = AppConfig.VERSION_NAME,
            appBuild = AppConfig.VERSION_CODE,
            locale = locale,
            timezone = timezone,
            offers = settings.offerNotifications,
            product = settings.productNotifications,
            account = settings.accountNotifications,
        )
    }
}
