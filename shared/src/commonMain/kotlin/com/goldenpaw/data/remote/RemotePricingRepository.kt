package com.goldenpaw.data.remote

import com.goldenpaw.config.AppConfig
import com.goldenpaw.data.settings.KeyValues
import com.goldenpaw.domain.model.PricingCatalog
import com.goldenpaw.domain.repository.PricingRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PricingRequest(
    @SerialName("p_platform") val platform: String,
    @SerialName("p_country") val country: String,
    @SerialName("p_app_version") val appVersion: Int,
)

/**
 * Reads `gp_pricing_catalog()` (anonymous access, no sign-in needed) and caches the JSON so the
 * paywall shows the last known prices offline. Edit prices, plans and offers in the database; no
 * app release required. The server picks plans for the platform/country and hides expired offers.
 */
class RemotePricingRepository(
    private val config: SupabaseConfig,
    private val http: HttpClient,
    private val store: KeyValues,
    private val platform: String,
    private val country: () -> String = { "" },
    private val appVersion: Int = AppConfig.VERSION_CODE,
) : PricingRepository {

    private val _catalog = MutableStateFlow(PricingCatalog.DEFAULT)
    override val catalog: StateFlow<PricingCatalog> = _catalog.asStateFlow()

    override suspend fun restore() {
        val raw = store.get(KEY_CACHE) ?: return
        decode(raw)?.takeIf { it.version >= _catalog.value.version }?.let { _catalog.value = it }
    }

    override suspend fun refresh(): Result<PricingCatalog> = runCatching {
        check(config.isConfigured) { "Cloud isn't set up in this build" }
        val response = http.post("${config.baseUrl}/rest/v1/rpc/gp_pricing_catalog") {
            supabaseHeaders(config, null)
            contentType(ContentType.Application.Json)
            setBody(PricingRequest(platform = platform, country = country(), appVersion = appVersion))
        }
        if (!response.status.isSuccess()) throw CloudException(response.errorMessage(), response.status.value)
        val raw = response.bodyAsText()
        val fresh = decode(raw) ?: throw CloudException("Unreadable pricing catalog")
        // Never go backwards: a stale cache/CDN response must not undo a newer price change.
        if (fresh.version >= _catalog.value.version) {
            store.put(KEY_CACHE, raw)
            _catalog.value = fresh
        }
        _catalog.value
    }

    companion object {
        const val KEY_CACHE = "pricing.catalog"

        fun decode(raw: String): PricingCatalog? =
            runCatching { SupabaseJson.decodeFromString(PricingCatalog.serializer(), raw) }.getOrNull()
    }
}
