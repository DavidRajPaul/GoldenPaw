package com.goldenpaw

import com.goldenpaw.core.AppClock
import com.goldenpaw.data.ads.PromotionsService
import com.goldenpaw.domain.ads.AdContext
import com.goldenpaw.domain.ads.AdDecision
import com.goldenpaw.domain.ads.AdEngine
import com.goldenpaw.domain.ads.AdImpression
import com.goldenpaw.domain.ads.AdLedger
import com.goldenpaw.domain.ads.AdNetwork
import com.goldenpaw.domain.ads.AdPlacement
import com.goldenpaw.domain.ads.AdPolicy
import com.goldenpaw.domain.ads.AdsCatalog
import com.goldenpaw.domain.ads.Audience
import com.goldenpaw.domain.ads.HouseCreative
import com.goldenpaw.domain.ads.NoAdNetwork
import com.goldenpaw.domain.ads.SkipReason
import com.goldenpaw.domain.model.CheckIn
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val T0 = Instant.parse("2026-10-01T09:00:00Z")

/** An install id outside a 10% holdout (FNV bucket 94; checked in a test below). */
private const val INSTALL = "goldenpaw-install"

private fun plusCard(id: String = "plus", audience: Audience = Audience.FREE, weight: Int = 1, placements: List<String> = emptyList()) =
    HouseCreative(id = id, title = "Every week, written for you", audience = audience, weight = weight, placements = placements)

private val ON = AdPolicy(enabled = true, holdoutPercent = 10)

private fun catalog(policy: AdPolicy = ON, vararg creatives: HouseCreative = arrayOf(plusCard())) =
    AdsCatalog(version = 1, policy = policy, creatives = creatives.toList())

private fun context(
    now: Instant = T0 + 10.days,
    isPlus: Boolean = false,
    hardDay: Boolean = false,
    network: Boolean = false,
    install: String = INSTALL,
) = AdContext(now = now, installId = install, isPlus = isPlus, hardDayToday = hardDay, networkAvailable = network)

/** An established install: 10 days old, 5 sessions, nothing shown yet. */
private fun ledger(sessions: Int = 5) = AdLedger(installedAtMs = T0.toEpochMilliseconds(), sessions = sessions)

private fun decide(
    placement: AdPlacement = AdPlacement.INSIGHTS_FEED,
    ctx: AdContext = context(),
    led: AdLedger = ledger(),
    cat: AdsCatalog = catalog(),
) = AdEngine.decide(placement, ctx, led, cat, Random(7))

private fun AdDecision.reason() = (this as AdDecision.Skip).reason

class AdEngineTest {

    @Test
    fun theTestInstallIsOutsideTheDefaultHoldout() {
        assertFalse(AdEngine.isHoldout(INSTALL, 10))
    }

    @Test
    fun showsAHouseCardWhenEveryRuleAllows() {
        assertIs<AdDecision.ShowHouse>(decide())
    }

    @Test
    fun killSwitchIsOffByDefault() {
        assertFalse(AdPolicy().enabled)
        assertEquals(SkipReason.DISABLED, decide(cat = catalog(AdPolicy())).reason())
    }

    @Test
    fun onlyConfiguredPlacements() {
        val onlyInsights = ON.copy(placements = listOf(AdPlacement.INSIGHTS_FEED.wire))
        assertEquals(SkipReason.PLACEMENT_OFF, decide(AdPlacement.JOURNAL_FEED, cat = catalog(onlyInsights)).reason())
    }

    @Test
    fun newInstallsAndFewSessionsSeeNothing() {
        assertEquals(SkipReason.TOO_NEW, decide(ctx = context(now = T0 + 6.days)).reason())
        assertEquals(SkipReason.TOO_FEW_SESSIONS, decide(led = ledger(sessions = 2)).reason())
    }

    @Test
    fun quietOnAHardDay() {
        assertEquals(SkipReason.QUIET_DAY, decide(ctx = context(hardDay = true)).reason())
        assertIs<AdDecision.ShowHouse>(decide(ctx = context(hardDay = true), cat = catalog(ON.copy(quietOnHardDay = false))))
    }

    @Test
    fun hideStartsACooldown() {
        val now = T0 + 10.days
        val hidden = ledger().hidden(now - 71.hours)
        assertEquals(SkipReason.HIDDEN_RECENTLY, decide(ctx = context(now = now), led = hidden).reason())
        val longAgo = ledger().hidden(now - 73.hours)
        assertIs<AdDecision.ShowHouse>(decide(ctx = context(now = now), led = longAgo))
    }

    @Test
    fun sessionDailyAndGapCaps() {
        val now = T0 + 10.days
        fun shown(ago: Duration, session: Int) = AdImpression((now - ago).toEpochMilliseconds(), session, "insights_feed", "plus")

        // Already one this session.
        val thisSession = ledger(sessions = 5).record(shown(5.hours, session = 5))
        assertEquals(SkipReason.SESSION_CAP, decide(ctx = context(now = now), led = thisSession).reason())

        // Two in the last 24 h (earlier sessions).
        val twoToday = ledger(sessions = 5).record(shown(20.hours, 3)).record(shown(10.hours, 4))
        assertEquals(SkipReason.DAILY_CAP, decide(ctx = context(now = now), led = twoToday).reason())

        // One 3 h ago in an earlier session: under the 4 h gap.
        val recent = ledger(sessions = 5).record(shown(3.hours, 4))
        assertEquals(SkipReason.TOO_SOON, decide(ctx = context(now = now), led = recent).reason())

        // 5 h ago is fine.
        val older = ledger(sessions = 5).record(shown(5.hours, 4))
        assertIs<AdDecision.ShowHouse>(decide(ctx = context(now = now), led = older))
    }

    @Test
    fun plusSeesNoSalesAndNoAds() {
        // Free-audience cards (sales) never reach Plus...
        assertEquals(SkipReason.NO_CREATIVE, decide(ctx = context(isPlus = true)).reason())
        // ...but news written for everyone can.
        val news = plusCard(id = "news", audience = Audience.ALL)
        assertEquals("news", (decide(ctx = context(isPlus = true), cat = catalog(ON, plusCard(), news)) as AdDecision.ShowHouse).creative.id)
        // No network ads and no rewarded for Plus, even when a network is ready.
        val networky = catalog(ON.copy(networkPercent = 100))
        assertIs<AdDecision.ShowHouse>(decide(ctx = context(isPlus = true, network = true), cat = catalog(ON.copy(networkPercent = 100), news)))
        assertEquals(SkipReason.NO_CREATIVE, decide(ctx = context(isPlus = true, network = true), cat = networky).reason())
        val rewardOn = catalog(ON.copy(placements = ON.placements + AdPlacement.WEEKLY_SUMMARY_REWARD.wire))
        assertEquals(SkipReason.PLUS, decide(AdPlacement.WEEKLY_SUMMARY_REWARD, ctx = context(isPlus = true, network = true), cat = rewardOn).reason())
    }

    @Test
    fun holdoutIsStableAndRespected() {
        val ids = (0 until 2000).map { "install-$it" }
        val held = ids.count { AdEngine.isHoldout(it, 10) }
        assertTrue(held in 140..260, "about 10% held out, got $held")
        ids.take(50).forEach { assertEquals(AdEngine.isHoldout(it, 10), AdEngine.isHoldout(it, 10)) }
        val heldId = ids.first { AdEngine.isHoldout(it, 10) }
        assertEquals(SkipReason.HOLDOUT, decide(ctx = context(install = heldId)).reason())
        assertFalse(AdEngine.isHoldout("x", 0))
        assertTrue(AdEngine.isHoldout("x", 100))
    }

    @Test
    fun creativeFilteringAndRotation() {
        val now = T0 + 10.days
        val expired = plusCard(id = "old").copy(endsAtMs = (now - 1.days).toEpochMilliseconds())
        val journalOnly = plusCard(id = "journal", placements = listOf("journal_feed"))
        assertEquals(SkipReason.NO_CREATIVE, decide(ctx = context(now = now), cat = catalog(ON, expired, journalOnly)).reason())

        // Not the same card twice in a row when there is another one.
        val a = plusCard(id = "a")
        val b = plusCard(id = "b")
        val lastWasA = ledger(sessions = 5).record(AdImpression((now - 1.days).toEpochMilliseconds(), 2, "insights_feed", "a"))
        repeat(20) { seed ->
            val d = AdEngine.decide(AdPlacement.INSIGHTS_FEED, context(now = now), lastWasA, catalog(ON, a, b), Random(seed))
            assertEquals("b", (d as AdDecision.ShowHouse).creative.id)
        }
    }

    @Test
    fun rewardedIsUserInitiatedNetworkOnlyAndCapped() {
        val r = AdPlacement.WEEKLY_SUMMARY_REWARD
        val withReward = ON.copy(placements = ON.placements + r.wire)
        // No network in this build: never available.
        assertEquals(SkipReason.NO_CREATIVE, decide(r, cat = catalog(withReward)).reason())
        assertIs<AdDecision.ShowNetwork>(decide(r, ctx = context(network = true), cat = catalog(withReward)))
        // Feed caps don't apply to an opt-in reward, but the daily reward cap does.
        val now = T0 + 10.days
        val rewardedToday = ledger().rewarded(now - 2.hours)
        assertEquals(SkipReason.REWARD_CAP, decide(r, ctx = context(now = now, network = true), led = rewardedToday, cat = catalog(withReward)).reason())
        // Still quiet on a hard day.
        assertEquals(SkipReason.QUIET_DAY, decide(r, ctx = context(network = true, hardDay = true), cat = catalog(withReward)).reason())
    }

    @Test
    fun networkShareOnlyWhenANetworkIsReady() {
        val allNetwork = catalog(ON.copy(networkPercent = 100))
        assertIs<AdDecision.ShowNetwork>(decide(ctx = context(network = true), cat = allNetwork))
        assertIs<AdDecision.ShowHouse>(decide(ctx = context(network = false), cat = allNetwork))
    }

    @Test
    fun ledgerKeepsOnlyRecentHistory() {
        val now = T0 + 10.days
        val l = ledger()
            .record(AdImpression((now - 3.days).toEpochMilliseconds(), 1, "insights_feed"))
            .record(AdImpression((now - 1.hours).toEpochMilliseconds(), 4, "insights_feed"))
            .rewarded(now - 5.days)
        val t = l.trimmed(now)
        assertEquals(1, t.impressions.size)
        assertTrue(t.rewards.isEmpty())
    }
}

private class FixedClock(var instant: Instant) : AppClock {
    override fun now(): Instant = instant
    override fun zone(): TimeZone = TimeZone.UTC
}

private class FakeCheckIns(vararg items: CheckIn) : CheckInRepository {
    val rows = MutableStateFlow(items.toList())
    override fun observeForPet(petId: String): Flow<List<CheckIn>> = rows.map { l -> l.filter { it.petId == petId } }
    override fun observeForDate(petId: String, date: LocalDate): Flow<CheckIn?> = rows.map { l -> l.firstOrNull { it.petId == petId && it.date == date } }
    override fun observeAll(): Flow<List<CheckIn>> = rows
    override suspend fun forPetBetween(petId: String, from: LocalDate, to: LocalDate): List<CheckIn> =
        rows.value.filter { it.petId == petId && it.date >= from && it.date <= to }
    override suspend fun upsert(checkIn: CheckIn) {
        rows.value = rows.value + checkIn
    }
}

private fun checkIn(day: LocalDate, score: Int) =
    CheckIn("pet:$day", "pet", day, score, score, score, score, score, score, score, "", T0)

class PromotionsServiceTest {

    private val enabledJson = """{"version":3,"policy":{"enabled":true,"holdout_percent":0,"min_days_since_install":0,"min_sessions":1},
        "creatives":[{"id":"plus","title":"Every week, written for you","cta":"See Plus","open":"paywall","audience":"free"}]}"""

    private fun service(
        clock: FixedClock = FixedClock(T0),
        store: InMemoryKeyValues = InMemoryKeyValues(),
        checkIns: FakeCheckIns = FakeCheckIns(),
        settings: FakeSettings = FakeSettings(),
        network: AdNetwork = NoAdNetwork,
        cloud: MockCloud = MockCloud { respondJson(enabledJson) },
    ) = PromotionsService(
        config = TestCloud, http = cloud.client, store = store, settings = settings, pets = FakePets(TestData.pet()),
        checkIns = checkIns, clock = clock, network = network, platform = "android", installId = { INSTALL },
    )

    @Test
    fun shipsDisabledUntilTheBackendTurnsItOn() = runTest {
        val s = service(cloud = MockCloud { respondJson("null", io.ktor.http.HttpStatusCode.InternalServerError) })
        s.startSession()
        assertEquals(SkipReason.DISABLED, (s.decide(AdPlacement.INSIGHTS_FEED) as AdDecision.Skip).reason)
    }

    @Test
    fun oneCardPerSessionStableWhileScrollingAndHideClearsIt() = runTest {
        val clock = FixedClock(T0)
        val s = service(clock = clock)
        s.refresh().getOrThrow()
        s.startSession()
        val first = s.decide(AdPlacement.INSIGHTS_FEED)
        assertIs<AdDecision.ShowHouse>(first)
        assertEquals(first, s.decide(AdPlacement.INSIGHTS_FEED)) // stable within the session
        s.onShown(AdPlacement.INSIGHTS_FEED, first)
        s.onShown(AdPlacement.INSIGHTS_FEED, first) // counted once
        // A second placement in the same session is capped (1 per session).
        assertEquals(SkipReason.SESSION_CAP, (s.decide(AdPlacement.JOURNAL_FEED) as AdDecision.Skip).reason)

        s.onHidden(first)
        assertEquals(SkipReason.HIDDEN_RECENTLY, (s.decide(AdPlacement.INSIGHTS_FEED) as AdDecision.Skip).reason)

        // Next sessions after the cooldown: allowed again.
        clock.instant = T0 + 4.days
        s.startSession()
        assertIs<AdDecision.ShowHouse>(s.decide(AdPlacement.INSIGHTS_FEED))
    }

    @Test
    fun aHardCheckInTodayKeepsEverythingQuiet() = runTest {
        val today = LocalDate(2026, 10, 1)
        val s = service(checkIns = FakeCheckIns(checkIn(today, score = 1)))
        s.refresh().getOrThrow()
        s.startSession()
        assertEquals(SkipReason.QUIET_DAY, (s.decide(AdPlacement.INSIGHTS_FEED) as AdDecision.Skip).reason)
    }

    @Test
    fun aGoodCheckInDoesNotSilence() = runTest {
        val today = LocalDate(2026, 10, 1)
        val s = service(checkIns = FakeCheckIns(checkIn(today, score = 5)))
        s.refresh().getOrThrow()
        s.startSession()
        assertIs<AdDecision.ShowHouse>(s.decide(AdPlacement.INSIGHTS_FEED))
    }

    @Test
    fun catalogIsCachedForOfflineStarts() = runTest {
        val store = InMemoryKeyValues()
        service(store = store).refresh().getOrThrow()
        val offline = service(store = store, cloud = MockCloud { respondJson("null", io.ktor.http.HttpStatusCode.ServiceUnavailable) })
        offline.restore()
        assertTrue(offline.catalog.value.policy.enabled)
        assertEquals(3, offline.catalog.value.version)
    }

    @Test
    fun onlyAggregateEventsWithNoIdentifiersReachTheBackend() = runTest {
        val cloud = MockCloud { respondJson(enabledJson) }
        val s = service(cloud = cloud)
        s.refresh().getOrThrow()
        s.startSession()
        val d = s.decide(AdPlacement.INSIGHTS_FEED)
        s.onShown(AdPlacement.INSIGHTS_FEED, d)
        s.onClicked(d)
        val events = cloud.requests.filter { it.path.endsWith("gp_record_ad_event") }
        assertEquals(2, events.size)
        events.forEach {
            assertFalse(it.body.contains(INSTALL), it.body)
            assertTrue(it.body.contains("\"p_creative\":\"plus\""), it.body)
        }
        assertEquals("Bearer anon-key", events.first().authorization) // no user token either
    }

    @Test
    fun rewardedNeedsANetworkAndRecordsTheReward() = runTest {
        val withReward = enabledJson.replace("\"min_sessions\":1", "\"min_sessions\":1,\"placements\":[\"weekly_summary_reward\"]")
        assertFalse(service(cloud = MockCloud { respondJson(withReward) }).run {
            refresh().getOrThrow(); startSession(); watchRewarded(AdPlacement.WEEKLY_SUMMARY_REWARD)
        })
        val network = object : AdNetwork {
            var shown = 0
            override fun isReady(placement: AdPlacement) = true
            override suspend fun showRewarded(placement: AdPlacement): Boolean { shown++; return true }
        }
        val s = service(cloud = MockCloud { respondJson(withReward) }, network = network)
        s.refresh().getOrThrow()
        s.startSession()
        assertTrue(s.watchRewarded(AdPlacement.WEEKLY_SUMMARY_REWARD))
        assertFalse(s.watchRewarded(AdPlacement.WEEKLY_SUMMARY_REWARD)) // 1 per day
        assertEquals(1, network.shown)
    }

    @Test
    fun plusIsReadFromSettings() = runTest {
        val s = service(settings = FakeSettings(UserSettings(isPlus = true)))
        s.refresh().getOrThrow()
        s.startSession()
        assertEquals(SkipReason.NO_CREATIVE, (s.decide(AdPlacement.INSIGHTS_FEED) as AdDecision.Skip).reason)
    }
}
