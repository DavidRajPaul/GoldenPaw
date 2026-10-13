package com.goldenpaw.data.ads

import com.goldenpaw.config.AppConfig
import com.goldenpaw.core.AppClock
import com.goldenpaw.core.today
import com.goldenpaw.data.remote.CloudException
import com.goldenpaw.data.remote.SupabaseConfig
import com.goldenpaw.data.remote.SupabaseJson
import com.goldenpaw.data.remote.errorMessage
import com.goldenpaw.data.remote.supabaseHeaders
import com.goldenpaw.data.settings.KeyValues
import com.goldenpaw.domain.ads.AdContext
import com.goldenpaw.domain.ads.AdDecision
import com.goldenpaw.domain.ads.AdEngine
import com.goldenpaw.domain.ads.AdImpression
import com.goldenpaw.domain.ads.AdLedger
import com.goldenpaw.domain.ads.AdNetwork
import com.goldenpaw.domain.ads.AdPlacement
import com.goldenpaw.domain.ads.AdsCatalog
import com.goldenpaw.domain.model.DayQuality
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
internal data class AdsConfigRequest(
    @SerialName("p_platform") val platform: String,
    @SerialName("p_country") val country: String,
    @SerialName("p_app_build") val appBuild: Int,
)

@Serializable
internal data class AdEventRequest(
    @SerialName("p_creative") val creative: String,
    @SerialName("p_kind") val kind: String,
)

/**
 * Glue between the pure [AdEngine] and the app: loads the backend catalog (cached, like pricing),
 * keeps the on-device ledger, works out the context (Plus, hard day today) and records what was
 * shown. Only aggregate counts per house creative go to the backend; no user, device or install id.
 */
class PromotionsService(
    private val config: SupabaseConfig,
    private val http: HttpClient,
    private val store: KeyValues,
    private val settings: SettingsRepository,
    private val pets: PetRepository,
    private val checkIns: CheckInRepository,
    private val clock: AppClock,
    private val network: AdNetwork,
    private val platform: String,
    private val installId: suspend () -> String,
    private val country: () -> String = { "" },
    private val random: Random = Random.Default,
) {
    private val mutex = Mutex()
    private val _catalog = MutableStateFlow(AdsCatalog())
    val catalog: StateFlow<AdsCatalog> = _catalog.asStateFlow()

    /** Bumped whenever the ledger changes (e.g. after "Hide"), so on-screen slots re-check. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Feed placements already decided this session: a slot keeps its answer while the session lasts. */
    private val sessionDecisions = mutableMapOf<AdPlacement, AdDecision>()

    suspend fun restore() {
        store.get(KEY_CATALOG)?.let { decode(it) }?.let { _catalog.value = it }
    }

    suspend fun refresh(): Result<AdsCatalog> = runCatching {
        check(config.isConfigured) { "Cloud isn't set up in this build" }
        val response = http.post("${config.baseUrl}/rest/v1/rpc/gp_ads_config") {
            supabaseHeaders(config, null)
            contentType(ContentType.Application.Json)
            setBody(AdsConfigRequest(platform, country(), AppConfig.VERSION_CODE))
        }
        if (!response.status.isSuccess()) throw CloudException(response.errorMessage(), response.status.value)
        val raw = response.bodyAsText()
        val fresh = decode(raw) ?: throw CloudException("Unreadable ads config")
        mutex.withLock {
            if (fresh.version >= _catalog.value.version) {
                store.put(KEY_CATALOG, raw)
                _catalog.value = fresh
                sessionDecisions.clear()
            }
        }
        _catalog.value
    }

    /** Call once per app launch (a "session"). */
    suspend fun startSession() = mutex.withLock {
        sessionDecisions.clear()
        save(ledger().startSession())
    }

    /**
     * What [placement] may show now. Stable within a session for feed placements: asking again
     * returns the same answer, so a slot doesn't flicker or rotate while the owner scrolls.
     */
    suspend fun decide(placement: AdPlacement): AdDecision = mutex.withLock { decideLocked(placement) }

    private suspend fun decideLocked(placement: AdPlacement): AdDecision {
        if (!placement.rewarded) sessionDecisions[placement]?.let { return it }
        val decision = AdEngine.decide(placement, context(placement), ledger(), _catalog.value, random)
        if (!placement.rewarded) sessionDecisions[placement] = decision
        return decision
    }

    /** The slot actually rendered [decision] on screen. Counted once per placement per session. */
    suspend fun onShown(placement: AdPlacement, decision: AdDecision) = mutex.withLock {
        val creative = (decision as? AdDecision.ShowHouse)?.creative?.id
        val ledger = ledger()
        if (ledger.impressions.any { it.session == ledger.sessions && it.placement == placement.wire }) return@withLock
        save(ledger.record(AdImpression(clock.now().toEpochMilliseconds(), ledger.sessions, placement.wire, creative)), bump = false)
        creative?.let { report(it, "impression") }
    }

    suspend fun onClicked(decision: AdDecision) {
        (decision as? AdDecision.ShowHouse)?.creative?.id?.let { report(it, "click") }
    }

    /** "Hide": nothing anywhere for the cooldown, and this session's slots clear immediately. */
    suspend fun onHidden(decision: AdDecision) {
        mutex.withLock {
            sessionDecisions.clear()
            save(ledger().hidden(clock.now()))
        }
        (decision as? AdDecision.ShowHouse)?.creative?.id?.let { report(it, "hide") }
    }

    /** Runs a rewarded ad the owner asked for. True when it was watched to the end. */
    suspend fun watchRewarded(placement: AdPlacement): Boolean {
        require(placement.rewarded)
        if (decide(placement) !is AdDecision.ShowNetwork) return false
        val earned = network.showRewarded(placement)
        if (earned) mutex.withLock { save(ledger().rewarded(clock.now())) }
        return earned
    }

    private suspend fun context(placement: AdPlacement): AdContext {
        val s = settings.current()
        val today = clock.today()
        val hard = pets.observeActivePets().first().any { pet ->
            checkIns.forPetBetween(pet.id, today, today).any { it.dayQuality == DayQuality.HARD }
        }
        return AdContext(
            now = clock.now(),
            installId = installId(),
            isPlus = s.isPlus,
            hardDayToday = hard,
            networkAvailable = network.isReady(placement),
        )
    }

    private suspend fun ledger(): AdLedger =
        store.get(KEY_LEDGER)?.let { runCatching { SupabaseJson.decodeFromString(AdLedger.serializer(), it) }.getOrNull() }
            ?: AdLedger(installedAtMs = clock.now().toEpochMilliseconds()).also { save(it, bump = false) }

    private suspend fun save(ledger: AdLedger, bump: Boolean = true) {
        store.put(KEY_LEDGER, SupabaseJson.encodeToString(AdLedger.serializer(), ledger.trimmed(clock.now())))
        if (bump) _revision.value++
    }

    private suspend fun report(creative: String, kind: String) {
        if (!config.isConfigured) return
        runCatching {
            http.post("${config.baseUrl}/rest/v1/rpc/gp_record_ad_event") {
                supabaseHeaders(config, null)
                contentType(ContentType.Application.Json)
                setBody(AdEventRequest(creative, kind))
            }
        }
    }

    companion object {
        const val KEY_CATALOG = "ads.catalog"
        const val KEY_LEDGER = "ads.ledger"

        fun decode(raw: String): AdsCatalog? = runCatching { SupabaseJson.decodeFromString(AdsCatalog.serializer(), raw) }.getOrNull()
    }
}
