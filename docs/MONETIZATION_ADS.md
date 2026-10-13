# GoldenPaw: calm monetization and ads

Market and technical research, the strategy that follows from it, and how the in-app "calm promotions
engine" enforces that strategy in code. Researched 13 October 2026. Figures are cited, and anything
not backed by a source is labelled as an assumption.

---

## 1. The short answer

* **Subscriptions stay the business.** Ads are a small add-on for people who will never pay. They
  must never cost us a subscriber or a caregiver's trust.
* **GoldenPaw's users are in an emotional situation** (an old or sick pet, sometimes a memorial).
  The places where an ad would earn the most (app open, after logging a dose, after a hard check-in)
  are exactly the places where it would do the most harm. Those places are permanently ad-free.
* **Formats:** no interstitials, no app-open ads, no banners, no video autoplay. Only:
  1. **House promotions**: our own cards (Plus, seasonal offers, new features), served from Supabase.
  2. **Native "Sponsored" cards**: one calm card in browse surfaces (Insights, Journal), clearly labelled.
  3. **Rewarded, user-initiated unlocks**: "Watch a short video to read this week's summary", for free
     users only and only if they tap it.
* **Hard limits in code:** for example, nothing in the first 7 days, at most 1 per session, 2 per
  day, 4 hours apart, a 3-day cooldown after "Hide", nothing on a day with a hard check-in, and a 10%
  holdout group to prove ads don't hurt retention. Every number can be tuned from the backend, and
  there's a kill switch.
* **Launch plan:** ship the engine now with **house promotions only** (no ad SDK, no tracking, no
  consent prompts needed). Add a third-party network (AdMob, possibly InMobi for India) only once
  daily actives reach roughly 20–30k, and then mainly in markets where subscriptions convert poorly.

---

## 2. Market research

### 2.1 How subscription apps make money

| Finding | Source |
|---|---|
| About 90% of users never convert to a subscription | RevenueCat, cited in [Hybrid monetization techniques](https://www.revenuecat.com/blog/growth/hybrid-monetization-techniques) |
| 35% of apps already mix subscriptions with consumables or lifetime purchases; trials of 17–32 days convert best (45.7%); 30% of annual subscribers cancel in the first month | [RevenueCat State of Subscription Apps 2025, via Subscription Insider](https://subscriptioninsider.com/article-type/news/revenuecats-state-of-subscription-apps-2025-report-ais-dominance-retention-challenges-and-the-shift-away-from-pure-subscriptions) |
| Subscriptions plus ads typically adds **10–30%** revenue; ads can also be tiny (Spotify's free tier is under 5% of revenue) | [RevenueCat](https://www.revenuecat.com/blog/growth/hybrid-monetization-techniques), [Growth Gems](https://growthgems.substack.com/p/should-you-also-monetize-your-subscription) |
| Ads become worthwhile at roughly **30–50k DAU**; rewarded eCPM in India and Brazil is around **$2** and banners are close to worthless there; rewarded is the least harmful format | [Growth Gems](https://growthgems.substack.com/p/should-you-also-monetize-your-subscription) |
| Duolingo makes over 80% of revenue from subscriptions with under 10% paying, and uses ad breaks to promote its own premium plan | [Growth Gems](https://growthgems.substack.com/p/should-you-also-monetize-your-subscription), [RevenueCat](https://www.revenuecat.com/blog/growth/hybrid-monetization-techniques) |

**Takeaway:** ads are worth adding late, at scale, and only for people who won't pay. Rewarded and
self-promotion are the formats with the best value-to-annoyance ratio.

### 2.2 What makes ads annoying

A study of 36,309 app reviews that complain about ads
([Gui et al., arXiv 2008.12112](https://ar5iv.arxiv.org/html/2008.12112)) found:

| Complaint | Share of classified ad reviews |
|---|---|
| Too many ads | 45.5% |
| Display frequency | 25.0% |
| Pop-up ads | 13.5% (median rating 1★) |
| Timing / duration | 12.1% |
| Ad content | 8.8% |
| Ads covering controls (position) | 6.7% |
| Non-skippable | 3.8% |

**70.5%** of complaints are about **how many ads appear and how often**, not which ad it is. A
2014 survey found 67% of UK users aged 16–24 called in-app ads annoying
([Research Live](https://www.research-live.com/article/news/two-thirds-of-young-uk-app-users-turned-off-by-ads/id/4012016)).
That survey is old, but its direction matches the review study.

**Design implication:** frequency caps and "no pop-ups" matter more than which network we use. The
engine's caps are its most important feature.

### 2.3 GoldenPaw's audience and market

* India is a core market: about 100 million pets (30 million in urban homes), a pet-care market of
  US$3.6B in 2024, projected to grow at about 20% a year
  ([India Briefing](https://www.india-briefing.com/news/indias-pet-care-economy-2025-an-overview-37234.html)).
  Subscription conversion is typically lower in markets like India; this is where a careful ad tier
  could earn something from people who won't subscribe.
* Owners of senior and chronically ill pets are under stress and sometimes grieving. Trust is the
  product (see `docs/MARKET_RESEARCH.md`). *Assumption:* one badly timed ad after a hard check-in
  costs more in reviews and churn than months of ad revenue from that user. The engine is built
  around this.
* Natural sponsors (pet insurance, senior pet food, supplements) come close to medical advice.
  Any sponsored content must be clearly labelled, must not appear inside care tasks, and is reviewed
  by us before it runs (house ads) or blocked by category (network ads).

### 2.4 Competitors

Most pet-health apps either charge for premium features or are free and funded by vet clinics. We
didn't find a reliable source on ad usage in the leading pet-health trackers. *Assumption:* the
category is mostly ad-free, which makes calm ads (or none) a way to stand out rather than a gap to
fill. Check this by installing the top 5 trackers in India and the US before turning on network ads.

---

## 3. Technical and policy research

### 3.1 Google Play ads policy

From [Play Console Help: Ads](https://support.google.com/googleplay/android-developer/answer/9857753?hl=en):

* **No unexpected full-screen interstitials**, especially "when the user has chosen to do something
  else". They must be closable within 15 s, and none may appear before the app's loading screen.
* Interstitials that keep appearing after user actions, distracting from what the user is doing, are banned.
* Ads must not **imitate app UI or system notifications**. They must be easy to dismiss and never
  placed where users normally tap.
* Ads only inside the app; **no lock-screen ads**.
* **Rewarded ads the user opts into are exempt** from the interstitial rules.

### 3.2 Native ad rules (AdMob)

From [AdMob native ads policy](https://support.google.com/admob/answer/6329638?hl=en):

* Show an **"Ad" / "Sponsored" badge** (at least 15 px) plus the AdChoices overlay.
* Title, badge and call-to-action are required. Title text can't be cut off before 25 characters, body text before 90.
* The ad must be **clearly separate from app content**, must not look like navigation, and its background must not be clickable.

### 3.3 Consent and tracking

* **EEA, UK and Switzerland:** personalized ads need a Google-certified CMP using IAB TCF (EEA/UK
  since 16 Jan 2024, CH since 31 Jul 2024). Non-personalized or "limited" ads may still serve without it
  ([AdMob: consent management requirements](https://support.google.com/admob/answer/13554116?hl=en)).
  Google's UMP SDK provides this message for apps; check its certification status at integration time.
* **iOS ATT:** the average opt-in rate is about **35%** (Q2 2025,
  [Adjust](https://www.adjust.com/blog/att-opt-in-rates-2025)). **Decision:** GoldenPaw never shows
  the ATT prompt. It requests non-personalized ads only on every platform. We lose some eCPM and gain
  trust, no extra system prompt, and simpler privacy labels.
* **Pet health data never reaches an ad network.** No custom targeting keys, no content mapping URLs.
  The engine decides *whether* to show an ad from local signals; the network only learns that a
  placement is asking for an ad.

### 3.4 Content controls

AdMob's Blocking controls cover general and sensitive categories and a maximum ad content rating
([AdMob blocking controls](https://support.google.com/admob/answer/3150235?hl=en)). At integration:
block gambling, dating, alcohol, "get rich quick", politics and religion, and anything implying
veterinary or medical claims where the category exists. Set the content rating to **G**.

### 3.5 Integration options for Kotlin Multiplatform

| Option | Formats | Notes |
|---|---|---|
| Native SDKs behind our own `AdNetwork` interface (AdMob Android SDK + iOS SDK via SPM) | All, including native | Full control. Native ads rendered by platform views inside Compose (`AndroidView` / `UIKitView`). **Recommended** |
| [Basic-Ads](https://klibs.io/project/LexiLabs-App/basic-ads) (MIT) | Banner, interstitial, rewarded | No native ads; built against a newer Kotlin than ours (2.2.0), so check compatibility |
| Other KMP wrappers ([admob-compose-multiplatform](https://klibs.io/project/Meet-Miyani/admob-compose-multiplatform), [MultiAds](https://klibs.io/project/mobile-development-group/MultiAds)) | Varies | Unverified for CMP 1.9.0 |

eCPM ranges vary widely by source. A mediation vendor quotes AdMob rewarded at about $8–15 in
tier-1 markets and $0.80–1.50 in India, with InMobi higher in India
([CAS.AI](https://cas.ai/?p=11066)). This is vendor marketing and should be checked against your own data.

### 3.6 Rough economics (assumptions, check before acting)

Free-tier ad revenue per DAU per day ≈ impressions/DAU × fill × eCPM / 1000.
With our caps (about 0.6 impressions per DAU per day after eligibility), 80% fill and $1.5 blended
eCPM (India-heavy): 0.6 × 0.8 × 1.5 / 1000 ≈ **$0.0007 per DAU per day**, about **$21 per day at 30k
DAU**. That's meaningful only at scale, and much less than one extra yearly subscriber per day.
Hence: **subscriptions first, house promotions now, network ads later and gated.**

---

## 4. Strategy

### 4.1 Principles

1. **Care first.** No ad, promotion or paywall in a care task, a reminder, onboarding, the tour or a memorial.
2. **The owner is in control.** Every card has "Hide", and hiding is respected (cooldown). Plus removes all ads.
3. **Few and far between.** Strict caps, enforced in one place.
4. **Honest.** Clearly labelled "Sponsored" / "From GoldenPaw". No fake UI, no countdown traps, no ads in notifications.
5. **Prove it.** A holdout group never sees ads. If they retain better than everyone else, ads go off.

### 4.2 Where things can appear

| Surface | House promotion | Native sponsored | Rewarded unlock |
|---|---|---|---|
| Today, meds, check-in, quick log, dose reminders | ✗ never | ✗ never | ✗ never |
| Onboarding, tour, coach marks, splash, app open | ✗ | ✗ | ✗ |
| Memorial, archived pets, records/scanning, care team | ✗ | ✗ | ✗ |
| Notifications (local or push) | ✗ ads. Offers only via the opt-in "Offers" push category | ✗ | ✗ |
| Insights (bottom of the Weekly tab) | ✓ | ✓ | – |
| Journal (after the 5th entry) | ✓ | ✓ | – |
| Locked weekly summary (free users) | – | – | ✓ only on tap |

### 4.3 Frequency and timing rules (defaults; tunable from the backend)

| Rule | Default |
|---|---|
| Days since install before anything shows | 7 |
| Sessions before anything shows | 3 |
| Per session | 1 |
| Per day | 2 |
| Minimum gap | 4 hours |
| After "Hide" | 72 hours of nothing |
| Rewarded unlocks | 1 per day |
| Quiet day | No promotions on a day with a hard check-in (quality-of-life score below the acceptable threshold) |
| Holdout | 10% of installs (stable hash) never see promotions |
| Kill switch | `ads.enabled = false` (default until turned on in the backend) |
| Plus | No ads at all; house promotions limited to non-sales news |

### 4.4 In-app promotion strategy (Plus)

* The paywall stays **soft**: it opens only when someone taps a Plus feature, or from an opt-in offer push.
* At most **one house promotion card** per placement at a time, rotated by weight, never the same card two sessions in a row.
* Rewarded unlock is a **"taste of Plus"**: one weekly summary. Afterwards we show "Get every week with Plus" (one line, no pop-up).
* Seasonal offers (Diwali, Christmas) come from `promo_offers` and reach people as a house card *or* an opt-in push, never both on the same day.

---

## 5. How the engine enforces this

```
shared/commonMain/.../domain/ads/
  AdModels.kt     AdPlacement, AdPolicy (all tunables), HouseCreative, AdDecision, SkipReason
  AdEngine.kt     pure decision function: (placement, context, ledger, policy) → Show | Skip(reason)
  AdLedger.kt     on-device impression/hide/reward history (no network, no user id)
  AdsRepository   backend config + house creatives (gp_ads_config), cached like pricing
composeApp/commonMain/.../ui/ads/
  PromoSlot.kt    the only composable that can show a promotion; asks the engine, renders, records
  RewardedUnlock  "Watch a short video" button, hidden unless a network can serve rewarded
supabase/migrations/0003_ads.sql
  ad_config, ad_house_creatives, ad_daily_stats (aggregate counts only), gp_ads_config(), gp_record_ad_event()
```

* **One decision function**: every rule in 4.3 is a branch in `AdEngine.decide`, with a named
  `SkipReason`, covered by unit tests.
* **Placements are an enum**: there is no `AdPlacement.TODAY` etc., so care surfaces *can't* request a promotion.
* **Network-agnostic**: `AdNetwork` interface with `NoAdNetwork` as the default. Until an SDK is added,
  only house creatives ever render.
* **Privacy**: the ledger stays on the device. Stats sent to the backend are aggregate per creative per
  day (no user, device or install id).

---

## 6. Rollout

| Stage | When | What |
|---|---|---|
| 0 | Now | Engine + house promotions shipped **disabled**; turn on with `ad_config.enabled = true` for house cards only |
| 1 | After launch, ≥ 2k DAU | House promotions for Plus and features; watch hide rate (< 5%) and retention against the holdout |
| 2 | ≥ 20–30k DAU, India-heavy | AdMob (non-personalized) native + rewarded behind `AdNetwork`; UMP for EEA/UK/CH; category blocks; rating G |
| 3 | If India eCPM disappoints | Add InMobi via mediation; compare on our own geo data |

**Guardrails (automatic switch-off criteria):** D30 retention of exposed users ≥ 2 percentage points
below the holdout, Plus conversion down ≥ 10% against the holdout, hide rate ≥ 8%, or any 1★ review
trend mentioning ads.

---

## 7. What David needs to decide or do

1. Approve the defaults in 4.3 (or change them in `ad_config`).
2. Write the first house creatives (Plus, feature news) in `ad_house_creatives`.
3. At stage 2: create an AdMob account and app IDs (Android and iOS), set blocking controls, configure
   UMP messages, and update the privacy policy, Play Data Safety and App Store privacy labels.
