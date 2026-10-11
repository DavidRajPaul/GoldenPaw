# GoldenPaw animation audit

Audit of the motion system in `composeApp/src/commonMain`, with the platform actuals that affect motion.
Checkout: `main` at `a48fd31` (after PR #4). Read-only audit: no application code, build scripts,
dependencies or settings were changed.

**Evidence labels used below**

| Label | Meaning |
|---|---|
| **Confirmed** | Read in the source at the cited file and line. |
| **Hypothesis** | Follows from the source but needs a runtime check (profiler, device, screen reader) before acting on it. |
| **Unverified** | A compatibility or API claim not checked by compiling against Kotlin 2.2.0 / CMP 1.9.0 here. Needs a spike. |

Line numbers refer to `a48fd31`. Paths are relative to `composeApp/src/commonMain/kotlin/com/goldenpaw/` unless they start with `composeApp/`, `gradle/` or `docs/`.

---

## 1. Executive summary

GoldenPaw already has a real motion system, not a collection of one-off effects:

* **Tokens** for M3 easing, durations and springs live in `ui/designsystem/Motion.kt` (`object Motion`).
* **Shared helpers** cover press scale, staggered list entrances, shimmer skeletons and an animated counter.
* **Navigation** has a custom `NavHost` with direction-aware shared-axis transitions, Android predictive back and a shared-element pet photo.
* **Reduced motion** is a single `LocalReduceMotion` composition local, fed by a user setting and the OS.
* **Performance** is mostly good: animated values are read in `graphicsLayer` or draw lambdas in about 80 % of the sites.
* **Dependencies**: no external animation library is used, and none is needed.

The gaps are consistency and guarantees rather than missing flair:

1. **Reduced motion is not complete (P0).** `Motion.kt` promises "everything honours LocalReduceMotion". In fact, 9 `AnimatedVisibility` sites and all 17 `Modifier.animateItem()` sites still slide, expand or reorder with motion. So do the bottom-bar entrance, two `AnimatedContent` sites and the paywall pager. On top of that, the OS setting is read once with `remember`, so iOS and Android don't react to a change while the app runs. Desktop never reports reduced motion.
2. **The same transition is written five times (P1)** with different offsets and timings: navigation push/pop/tab, Insights tabs, medication editor, pet editor and record scan. The reduced-motion fallback also varies: 0 ms, 120 ms or 150 ms.
3. **Some animations recompose every frame (P1)**: the memorial glow, the memorial grayscale fade and the new tour illustration. Infinite transitions also keep ticking while visually frozen under reduced motion (tour orbit, coach-mark pulse).
4. **State changes cut instead of transitioning (P1)**:
   * loading skeleton → Today content
   * check-in form → saved summary
   * every editor's blank loading frame
5. **Platform parity (P1)**:
   * iOS has no back-swipe at all. `PlatformBackHandler` on iOS is an empty stub.
   * Desktop has no keyboard back. The stub's comment points to an Escape handler in `main.kt` that doesn't exist.
6. **Duplicates (P2)**: there are two "orbit" illustrations and two pager indicators, written independently in onboarding and the new tour.

None of these need a new dependency. The only recommended addition is the **test-only** Compose UI test artifact from Compose Multiplatform itself (P0 for verification). It needs your approval, because the constraints rule out dependency changes in this pass.

---

## 2. Verified repository facts

| Claim | Result | Evidence |
|---|---|---|
| `composeApp` holds the shared Compose UI | Confirmed | 39 Kotlin files under `composeApp/src/commonMain` (13,232 lines), with Android/iOS/Desktop actuals in `androidMain`, `iosMain`, `desktopMain` |
| `shared` holds domain, data, reporting | Confirmed | `shared/src/commonMain/kotlin/com/goldenpaw/{domain,data,report}` |
| Targets Android, iOS, Desktop | Confirmed | `composeApp/build.gradle.kts` (`androidTarget`, `iosX64/iosArm64/iosSimulatorArm64`, `jvm("desktop")`) |
| Kotlin 2.2.0, CMP 1.9.0 | Confirmed | `gradle/libs.versions.toml` lines 8 and 10. Also jetbrainsLifecycle 2.9.4, Koin 4.1.1 |
| Animation dependencies | Confirmed: none besides Compose | `compose.animation`, `compose.foundation`, `compose.material3`; no Lottie, no third-party motion libraries |
| Experimental opt-ins | Confirmed | `composeApp/build.gradle.kts:35–39` opts in to `ExperimentalSharedTransitionApi`, `ExperimentalAnimationApi`, `ExperimentalFoundationApi`, `ExperimentalMaterial3Api` |
| Motion tokens / reduced motion | Confirmed | `ui/designsystem/Motion.kt:52`, `ui/designsystem/Theme.kt:63` (`LocalReduceMotion`) |
| Choreographed splash | Confirmed | `ui/designsystem/Choreography.kt:78` `ChoreographedSplash` |
| Floating nav bar | Confirmed | `ui/navigation/GpBottomBar.kt:59` `GpBottomBar` |
| Staggered entrances, shimmer, animated counter | Confirmed | `Motion.kt:133` `staggerIn`, `:150` `shimmer`, `:178` `AnimatedCounter` |
| Onboarding, coach marks, care rings, celebrations | Confirmed | `ui/onboarding/Onboarding.kt`, `ui/tour/CoachMarks.kt`, `ui/designsystem/Celebration.kt:108` `CareRingsView`, `:54` `CelebrationBurst` |
| README: tab switch is "fade through" | **Mismatch** | The `NavHost` KDoc says "fade through (fade + 92% scale-in)", but `Navigation.kt` `transitionFor` implements direction-aware shared axis X for `NavAction.TAB` |
| Desktop Escape navigates back | **Not implemented** | `composeApp/src/desktopMain/.../PlatformActuals.desktop.kt:71–79` says "Desktop uses the window's Escape key handler (see main.kt)". No key handling exists in `desktopMain/.../main.kt` (searched for `Escape`, `Key`, `onKey*`) |

---

## 3. Existing animation inventory

### 3.1 Motion foundation

| Symbol | File:line | What it does | Reduced motion |
|---|---|---|---|
| `Motion` easing tokens | `designsystem/Motion.kt:54–59` | M3 Emphasized / Decelerate / Accelerate, Standard family | n/a. Note: `Emphasized` and `Standard` are the same curve `(0.2, 0, 0, 1)` |
| `Motion` durations | `Motion.kt:62–74` | SHORT2…EXTRA_LONG2, legacy SHORT/MEDIUM/LONG aliases | n/a |
| `Motion` springs | `Motion.kt:77–84` | spatialFast/Default/Slow, effectsFast/Default, bouncy, gentleSpring, settleSpring | n/a |
| `Motion.emphasized/enter/exit` | `Motion.kt:86–88` | tween factories | n/a. **Unused** (no call sites found) |
| `motionOr(spec)` | `Motion.kt:94` | 120 ms tween under reduced motion | Yes. Used only in `Components.kt` and `Charts.kt` |
| `motionOrSnap(spec)` | `Motion.kt:99` | snap under reduced motion | Yes. **Unused** |
| `Modifier.pressScale` | `Motion.kt:106` | press → 0.97 scale, read in `graphicsLayer` | Yes. Used by `GpCard` (`Components.kt:104`) and `RecordScreens.kt:351` |
| `Modifier.staggerIn` + `StaggerState` | `Motion.kt:122–147` | fade + 24 dp rise, 45 ms/item, cap 8, plays once per key | Yes. Used in Today, Journal, CheckIn, CareTeam, Achievements |
| `Modifier.shimmer` / `SkeletonCard` | `Motion.kt:150`, `Components.kt:557` | gradient sweep in draw phase | Yes (static fill). Used in Today and Insights |
| `AnimatedCounter` | `Motion.kt:178` | vertical roll on number change | Yes. Used in `Achievements.kt:109` |
| `LocalReduceMotion` | `Theme.kt:63`, provided `Theme.kt:160` | composition local | Source of truth |
| `systemPrefersReducedMotion()` | expect `platform/Platform.kt:106`; actuals: Android `PlatformActuals.android.kt:194` (`ANIMATOR_DURATION_SCALE == 0`), iOS `PlatformActuals.ios.kt:137` (`UIAccessibilityIsReduceMotionEnabled()`), Desktop `PlatformActuals.desktop.kt:87` (`false`) | OS preference | See §6.1 |
| `rememberHaptics()` | expect `platform/Platform.kt:66` | confirm/tick haptics paired with motion | n/a. Used in Today, CheckIn, QuickLog, BottomBar, Achievements, Onboarding |

### 3.2 Shell and navigation

| Symbol | File:line | Implementation | Reduced motion |
|---|---|---|---|
| Splash overlay | `App.kt:138–149` | `AnimatedVisibility` exit `fadeOut` + `scaleOut(1.08)` over `ChoreographedSplash` | Yes (fade only) |
| `ChoreographedSplash` | `designsystem/Choreography.kt:78` | 8 `Animatable`s: glow, 4 toe springs, pad spring, ripple, per-letter word, tagline. All read in Canvas/`graphicsLayer` | Yes (single fade) |
| Bottom bar show/hide | `App.kt:222–225` | `slideInVertically`/`slideOutVertically` + fade | **No** |
| `GpBottomBar` liquid indicator | `navigation/GpBottomBar.kt:69–85, 103–114` | two `Animatable` edges, fast/slow springs, drawn in `drawBehind` | Yes (snap) |
| Tab icon pop | `GpBottomBar.kt:152` | `AnimatedContent` scaleIn spring + fade | Yes |
| Tab tint | `GpBottomBar.kt:123` | `animateColorAsState` tween 200 | Colour only (acceptable) |
| `NavHost` | `navigation/Navigation.kt:218` (`NavHost`), `:290` (`transitionFor`) | `SharedTransitionLayout` + `AnimatedContent` keyed on entry id. PUSH/POP shared axis X, TAB direction-aware axis X, REPLACE fade | Yes (150 ms fade) |
| Predictive back | `Navigation.kt` (`NavEntry.backProgress`, `graphicsLayer` in `NavHost`) | scale 0.9, edge shift, 28 dp corners; cancel spring | Yes (alpha only). Android only: `PlatformActuals.android.kt:145` uses `PredictiveBackHandler`; iOS and Desktop are empty stubs |
| `Modifier.sharedPetPhoto` | `Navigation.kt:194` | `sharedElement` key `pet-photo-$id` | Yes (disabled). Used at `pets/PetsScreens.kt:162` (list) and `:229` (detail) |
| Tour overlay | `App.kt:297` | fade in/out | Fade only |
| Coach-mark host | `tour/CoachMarks.kt:161` | 700 ms settle delay, fade, `Spotlight` | Partial, see §6 |

### 3.3 Design-system components with motion

| Symbol | File:line | Implementation | Reduced motion |
|---|---|---|---|
| `EmptyState` float | `Components.kt:152` | infinite ±4 dp float, `graphicsLayer` | Yes |
| `StepProgress` | `Components.kt:231` | `animateFloatAsState` + `motionOr` | Yes |
| `PetAvatar` ring | `Components.kt:279` | `animateColorAsState` read in `border()` (composition) | Colour only |
| `WellnessRing` | `Components.kt:355` | `Animatable` sweep 1100 ms + infinite "breathing" scale 2600 ms + colour | Yes |
| `EmojiScale` | `Components.kt:427` | spring scale (`motionOr`), bg colour, dim alpha | Scale yes; colour/alpha always |
| `SeverityPicker` | `Components.kt:495` | `animateColorAsState` | Colour only |
| `FadeInVisibility` | `Components.kt:537` | `AnimatedVisibility(fadeIn, fadeOut)` | Fade only. **Unused** |
| `MilestoneShimmer` | `Components.kt:563` | infinite 40-particle Canvas | Yes (not composed) |
| `CelebrationBurst` | `Celebration.kt:54` | 48 particles, one `Animatable`, Canvas | Yes (not composed) |
| `CareRingsView` | `Celebration.kt:108` | two `Animatable` spring fills | Yes (snap) |
| `BadgeMedallion` | `Celebration.kt:178` | static alpha | n/a |
| `AnimatedLineChart` | `designsystem/Charts.kt:60` | path trim 1200 ms, `drawWithCache` | Yes |
| `GoodDayCalendar` | `Charts.kt:172` | radial "bloom" `Animatable`, per-cell `graphicsLayer` | Yes |
| `ScoreBar`, `ContributionBar` | `Charts.kt:267, 299` | `animateFloatAsState` + `motionOr` | Yes |

### 3.4 Screen-level motion

| Symbol | File:line | Implementation | Reduced motion |
|---|---|---|---|
| `StickyTodayHeader` | `today/TodayScreen.kt:522` | M3 exit-until-collapsed scroll behaviour; elevation, background lerp, date fade, avatar pop with overshoot, all in `graphicsLayer`/`drawBehind` | Yes (no overshoot) |
| Today FAB | `TodayScreen.kt:183, 207` | `ExtendedFloatingActionButton(expanded = firstVisibleItemIndex == 0)` | M3 default |
| Hero check-in state | `TodayScreen.kt:656` | `AnimatedContent` with default spec | **No** (default includes scale) |
| `DoseCard` | `TodayScreen.kt:733–830` | bg colour tween, `animateContentSize`, attribution `AnimatedVisibility` (783), Given → check morph `AnimatedContent` (797) | Morph yes; size change and visibility **no** |
| Upcoming section | `TodayScreen.kt:904` | expand/shrink | **No** |
| Today list | `TodayScreen.kt:271–409` | `staggerIn` + `animateItem()` | stagger yes; `animateItem` **no** |
| Insights tabs | `insights/InsightsScreen.kt:140` | direction-aware axis X `AnimatedContent` | Yes |
| Med editor steps | `meds/MedsScreens.kt:297` | axis X `it/4` | Yes |
| Med schedule options | `MedsScreens.kt:390, 400, 437` | default `AnimatedVisibility` (expand + fade) | **No** |
| Pet editor steps | `pets/PetEditorScreen.kt:117` | axis X `it/4` (copy of med editor) | Yes |
| Memorial | `pets/PetsScreens.kt:389–397, 423` | grayscale `Animatable` 2600 ms; infinite candle glow read in composition | Yes |
| Record scan steps | `records/RecordScreens.kt:219` | axis X `it/5` / `it/8` | Yes |
| Record vaccines / OCR text | `RecordScreens.kt:562, 694` | expand/shrink | **No** |
| Care sharing stages | `care/CareTeamScreen.kt:280` | fade, 0 ms when reduced | Yes |
| OTP code field | `CareTeamScreen.kt:374` | default `AnimatedVisibility` | **No** |
| Check-in faces | `checkin/CheckIn.kt:233–234` | `EmojiScale` + `staggerIn` + haptic tick | Yes |
| Check-in celebration | `CheckIn.kt:302` | `CelebrationBurst` when score ≥ 49 | Yes |
| Badge dialog | `achievements/Achievements.kt:233–260` | medallion spring scale, burst, haptic | Yes |
| QuickLog feedback | `quicklog/QuickLog.kt:194` | `AnimatedContent` fade + `scaleIn(0.8)` | **No** |
| Paywall cards | `paywall/PaywallSheet.kt:102–128` | pager tilt, scale, parallax | Tilt/parallax yes; alpha always |
| Onboarding | `onboarding/Onboarding.kt:231–330, 438` | pager, `OrbitIllustration` (pop spring, infinite orbit and breath read in `graphicsLayer`), parallax, `PagerIndicator`, CTA `AnimatedContent` | Yes |
| App tour | `tour/AppTour.kt:131–330` | pager parallax, `OrbitIllustration` (internal), `PageDots`, CTA crossfade | Partial, see §6 |
| Coach marks | `tour/CoachMarks.kt:201–260` | offscreen-composited scrim with cut-out, infinite pulse ring, bubble `scaleIn` | Pulse hidden and bubble fades, but the transition still runs |

### 3.5 Sheets and dialogs (Material defaults)

`ModalBottomSheet`: `PaywallSheet.kt:84`, `QuickLog.kt:182`, `TodayScreen.kt:928` (`CaregiverSwitcher`).
`AlertDialog`: Today, Meds, Records, CareTeam (×3), Settings (×3), Achievements, `ui/common/Common.kt` (`ConfirmDialog`, `GpTimePickerDialog`), `ui/common/WeightDialog.kt`.
`DatePickerDialog`: `Common.kt:123`.
All use Material 3 built-in motion. No custom specs. Whether CMP 1.9 animates dialog entry identically on iOS and Desktop is **Unverified** (compare on device).

---

## 4. Screen-by-screen audit

Legend: ✅ present · ⚠ present but inconsistent or unsafe · ✗ absent (searched).

| Screen (route) | Existing motion | User-triggered transitions | Loading / empty / error / success | Gaps and opportunities | Risks |
|---|---|---|---|---|---|
| **Splash** (`App.kt`) | ✅ choreography, ✅ exit fade+scale | none | n/a | Holds about 1.7 s on every cold start even when data is ready (`App.kt:139`, `Choreography.kt:89–115`) | Perceived startup time (Hypothesis: measure). Per-letter `Text` nodes may be read letter by letter by screen readers; parent has a `contentDescription` but children aren't cleared (Hypothesis) |
| **Onboarding** | ✅ pager parallax, orbit, pop, indicator, CTA morph, haptics | swipe, Next/Skip, permission | permission card state | Duplicate of the tour's orbit and indicator (§5) | Infinite orbit only while composed (OK) |
| **Today** | ✅ sticky header, ✅ stagger, ✅ `animateItem`, ✅ dose morph, ✅ rings, ✅ milestone shimmer, ✅ FAB expand, ✅ snackbar undo | give/skip/undo dose, check in, expand upcoming, switch caregiver, quick log | loading: skeleton ✅; empty: `EmptyState` ✅; success: morph + haptic ✅; error: snackbar `TodayEvent.Message` ✅ | ✗ skeleton → content crossfade (hard cut, `TodayScreen.kt:214–221`); ✗ expand chevron rotation (`:902`); ✗ paw icon appear on closed rings (`Celebration.kt:144`); ⚠ hero `AnimatedContent` default spec (`:656`) | `WellnessRing` breathing and `EmptyState` float animate forever while visible (Hypothesis: battery and continuous frames) |
| **Journal** | ✅ stagger, ✅ `animateItem` | open entry, log symptom FAB | loading: blank (`Journal.kt:235`); empty ✅ | ✗ loading skeleton; FAB has no scroll-collapse (Today has one) | – |
| **Symptom editor** | ✗ | save, photo import | loading: `return@Scaffold` blank (`Journal.kt:499`); importing: spinner | ✗ content fade-in after load | – |
| **Insights** | ✅ tab axis X, ✅ line chart trim, ✅ calendar bloom, ✅ score bars, ✅ skeletons (weekly) | tab switch (tap only), refresh summary, add weight, build report | loading weekly: skeleton ✅; empty ✅; refreshing: spinner ✅; error: Unverified (not traced) | ✗ swipe between tabs (tap only); ⚠ tab content and `ScrollableTabRow` indicator move independently | `GoodDayCalendar` replays its bloom whenever the `days` instance changes (`Charts.kt:183`). Hypothesis: may replay on every flow emission |
| **Pets** | ✅ `animateItem`, ✅ shared photo → detail | open pet, add pet | empty ✅ | – | Shared element disabled under reduced motion ✅ |
| **Pet detail** | ✅ shared photo | records, visits, archive | – | ✗ sharedBounds for record/visit cards → detail (P2) | – |
| **Memorial** | ✅ grayscale fade, ✅ candle glow | – | – | – | **Confirmed** per-frame recomposition: `glow` is read with `by` in composition and fed to a `Brush` (`PetsScreens.kt:394–397`); `mono.value` read in composition (`:423`) |
| **Pet editor** | ✅ step axis X, ✅ `StepProgress` | next/back step, pick photo | loading blank (`PetEditorScreen.kt:114`) | Step transition duplicated (§5) | – |
| **Meds list** | ✅ `animateItem` | open, add | empty ✅ (gated on `!loading`) | ✗ loading state (blank until loaded) | – |
| **Med editor** | ✅ step axis X, ✅ `StepProgress`, ⚠ 3× `AnimatedVisibility` | schedule type, steps | loading blank (`MedsScreens.kt:288`) | Reduced motion missing on the 3 option panels | – |
| **Check-in** | ✅ faces scale, ✅ stagger, ✅ haptics, ✅ burst | pick faces, save | success: `SavedSummary` | ✗ form → summary is a hard cut (`CheckIn.kt:186–189`); bottom bar disappears abruptly (`:162`) | `EmojiScale` exposes the score only in `contentDescription`, with no selected state or role (`Components.kt:472`) |
| **Vet visit editor** | ✗ none (0 animation hits) | save | loading blank (`VetVisitEditorScreen.kt:71`) | content fade-in | – |
| **Record scan** | ✅ step axis X, ✅ press scale, ⚠ 2× expand | camera/gallery, review pages, details | loading blank (`RecordScreens.kt:217`); processing spinner ✅; errors via snackbar ✅ | Reduced motion on the expand panels; page reorder has no `animateItem` (Unverified, not traced) | – |
| **Record detail** | ✗ | share PDF | PDF preview loads async (Android) | fade-in of the preview bitmap when ready | – |
| **Care team** | ✅ stagger, ✅ `animateItem`, ✅ stage fade, ⚠ OTP field | sign in, invite, join, edit member | busy spinners ✅; snackbar messages ✅ | reduced motion on OTP reveal; reduced fallback is 0 ms here vs 120/150 ms elsewhere | – |
| **Achievements** | ✅ stagger, ✅ counter, ✅ `animateItem`, ✅ badge dialog | – | empty ✅ | – | – |
| **Settings / Reminders health** | ✗ (no motion APIs besides the coach-mark modifier) | toggles, dialogs, replay tour | snackbars ✅ | – (calm by design) | – |
| **Paywall sheet** | ✅ pager tilt/parallax/scale, M3 sheet | plan select, start trial | unlocked state swap | ✗ crossfade between plan list and "unlocked" content (`PaywallSheet.kt:138`); ⚠ plan selection colour/border jump without animation | – |
| **Quick log sheet** | ✅ feedback crossfade+scale, haptics | give dose, log symptom/weight | feedback chip | reduced motion on the feedback `scaleIn` | – |
| **App tour** | ✅ parallax, orbit, dots, CTA | swipe, Next, Skip, offer toggle | – | Duplicate of onboarding visuals | **Confirmed** per-frame recomposition: `angle`/`breath` read in composition (`AppTour.kt:258–272`); infinite transition still created under reduced motion |
| **Coach marks** | ✅ spotlight, pulse, bubble pop | Got it / tap scrim | – | – | Infinite pulse runs while reduced (drawing skipped only); `liveRegion` set ✅ |

---

## 5. Duplicated or inconsistent motion

| # | Finding | Where | Evidence | Recommendation |
|---|---|---|---|---|
| D1 | Five hand-written "shared axis X" transforms with different distances and timings | `Navigation.kt:290` `transitionFor` (push `it/4`, pop `it/10`, tab `it/6`/`it/8`); `InsightsScreen.kt:140` (`it/8`); `MedsScreens.kt:297` (`it/4`); `PetEditorScreen.kt:117` (`it/4`, byte-identical to meds); `RecordScreens.kt:219` (`it/5`/`it/8`) | Confirmed | One `Motion.sharedAxisX(forward, distance = Near/Far)` `ContentTransform` builder plus a `Motion.reducedFade()` |
| D2 | Reduced-motion fallback durations differ | 120 ms (`Motion.kt:95`, Insights, editors, scan, counter, onboarding), 150 ms (`NavHost`), 0 ms (`CareTeamScreen.kt:282`), default `fadeIn()` (CoachMarks bubble) | Confirmed | Single `Motion.REDUCED_FADE` token |
| D3 | Two orbit illustrations | `onboarding/Onboarding.kt:310` (private, reads values in `graphicsLayer` ✓) and `tour/AppTour.kt:256` (internal, reads in composition ✗) | Confirmed | Promote the onboarding one to `designsystem/Illustrations.kt`, delete the tour copy |
| D4 | Two pager indicators | `Onboarding.kt:438` `PagerIndicator` (offset-driven stretch) and `AppTour.kt:311` `PageDots` (state-driven `animateDpAsState`) | Confirmed | Keep the offset-driven one as `designsystem/PagerIndicator` |
| D5 | Helpers defined but unused | `Motion.emphasized/enter/exit`, `motionOrSnap`, `FadeInVisibility` | Confirmed (no call sites) | Use them in the new builders or delete |
| D6 | `Emphasized` and `Standard` easing are the same cubic | `Motion.kt:54, 57` | Confirmed | Keep (Compose has no path easing for M3 emphasized), but document it so nobody tunes one expecting a difference |
| D7 | `NavHost` KDoc describes tab "fade through"; code does axis X | `Navigation.kt` KDoc vs `transitionFor` | Confirmed | Fix the doc (P3) |
| D8 | Celebrations: two particle systems | `CelebrationBurst` (`Celebration.kt:54`) and `MilestoneShimmer` (`Components.kt:563`) | Confirmed. They differ on purpose (burst vs ambient) | Keep both, move `MilestoneShimmer` next to `CelebrationBurst` |

---

## 6. Accessibility and performance findings

### 6.1 Reduced motion

| ID | Finding | Evidence | Status |
|---|---|---|---|
| A1 | OS preference read once with `remember`. Toggling Reduce Motion (iOS) or Remove animations (Android) while the app runs has no effect until the composition is rebuilt | `PlatformActuals.ios.kt:137`, `PlatformActuals.android.kt:194–200` | Confirmed (code). Effect on device: Hypothesis |
| A2 | Desktop never reports reduced motion | `PlatformActuals.desktop.kt:87` returns `false`; the user setting in Settings still works | Confirmed. Querying macOS/Windows from the JVM is Unverified |
| A3 | Motion that ignores `LocalReduceMotion` (contradicts `Motion.kt:50`) | `AnimatedVisibility` with default or slide specs: `App.kt:222` (bottom bar slide), `TodayScreen.kt:783, 904`, `MedsScreens.kt:390, 400, 437`, `RecordScreens.kt:562, 694`, `CareTeamScreen.kt:374`. All 17 `Modifier.animateItem()` calls. `AnimatedContent` defaults at `TodayScreen.kt:656` and `QuickLog.kt:194`. `animateContentSize` in `DoseCard` (`TodayScreen.kt:756`) | Confirmed |
| A4 | Android: whether Compose already shortens animations when `ANIMATOR_DURATION_SCALE` is 0 | Not determinable from source | Unverified. Test on device with Developer options → Animator scale off |
| A5 | Infinite transitions created even when their output is ignored under reduced motion | `AppTour.kt:258` (orbit), `CoachMarks.kt:203` (pulse) | Confirmed |

### 6.2 Other accessibility

| ID | Finding | Evidence | Status |
|---|---|---|---|
| A6 | `EmojiScale` and `SeverityPicker` have no selected state or role; a screen reader hears "Appetite 3 of 5" but not which option is selected | `Components.kt:472, 513` (`contentDescription` only; no `selectable`, `Role.RadioButton` or `selectableGroup` anywhere in commonMain) | Confirmed |
| A7 | `SeverityPicker` targets are 44 dp (below the 48 dp minimum) | `Components.kt:509` `.size(44.dp)` | Confirmed |
| A8 | Splash title split into 9 `Text` nodes | `Choreography.kt:160–172` | Hypothesis: check TalkBack and VoiceOver |
| A9 | Loading skeletons have no semantics ("Loading") | `Components.kt:557` | Confirmed |
| A10 | iOS has no back gesture; Desktop has no Escape back | `PlatformActuals.ios.kt:120`, `PlatformActuals.desktop.kt:71` (empty stubs) | Confirmed |
| A11 | Infinite ambient motion with no stop (`WellnessRing` breathing, `EmptyState` float, memorial glow) can bother motion-sensitive users who haven't enabled reduced motion. WCAG 2.2.2 asks for a pause control for motion lasting over 5 s alongside other content | `Components.kt:152, 371`; `PetsScreens.kt:395` | Confirmed (code); judgement call |

### 6.3 Performance

| ID | Finding | Evidence | Status |
|---|---|---|---|
| F1 | Per-frame recomposition from reading animated values in composition | `PetsScreens.kt:394–397` (memorial glow → `Brush` in `Modifier.background`), `:423` (`mono.value` → `PetAvatar` → `ColorMatrix` `remember(monochrome)` rebuilt every frame), `AppTour.kt:271–272` | Confirmed |
| F2 | Colour animations read in composition (`by animateColorAsState` → `background`/`border`) | `EmojiScale` bg (`Components.kt:452`), `PetAvatar` ring (`:279`), `SeverityPicker` (`:506`), `DoseCard` container (`TodayScreen.kt:746`), bottom-bar tint (`GpBottomBar.kt:123`) | Confirmed. Cost is small and short (≤ 300 ms); fix only where cheap (`drawBehind`) |
| F3 | Always-on infinite animations on primary screens | `WellnessRing` breathing (Today hero, Insights, Check-in summary), `EmptyState` float | Confirmed that they run while composed. Frame and battery cost: Hypothesis (profile) |
| F4 | `animateContentSize` + `animateItem` + `staggerIn` on the same `DoseCard` | `TodayScreen.kt:382, 756` | Hypothesis: layout animation inside a lazy item during a placement animation; check for jank on a low-end Android device |
| F5 | Good practice already in place | splash, nav bar, charts, calendar, header, stagger, shimmer and coach marks read values in draw/`graphicsLayer` | Confirmed |
| F6 | Offscreen compositing layer for the coach-mark scrim | `CoachMarks.kt:218` (only while a tip is shown) | Confirmed. Acceptable, transient |

---

## 7. Recommended `commonMain` architecture

No new libraries. Everything below uses Compose APIs already on the classpath through `compose.animation`, `compose.foundation` and `compose.material3`.

```
ui/designsystem/
  Motion.kt            tokens (existing) + REDUCED_FADE, Distance.Near/Far
  MotionSpecs.kt       NEW: ContentTransform builders, all reduced-motion aware
                         sharedAxisX(forward, distance), fadeThrough(), containerFade(),
                         expandSection()/collapseSection() EnterTransition/ExitTransition
  MotionModifiers.kt   NEW (moved from Motion.kt): pressScale, staggerIn, shimmer,
                         gpAnimateItem() = animateItem with null specs when reduced,
                         ambient(): gates infinite animations (reduced motion, off-screen, cycle cap)
  ReducedMotion.kt     NEW: rememberReducedMotion() combining user setting + reactive OS signal
  Illustrations.kt     NEW: OrbitIllustration (from Onboarding), PagerIndicator (from Onboarding)
  Choreography.kt      unchanged
  Celebration.kt       + MilestoneShimmer moved here
```

Rules the code should enforce (each one is checkable in review):

1. Screens never call `fadeIn`/`slideIn*`/`scaleIn` directly. They use a `MotionSpecs` builder. (Grep check: no `slideInHorizontally` outside `designsystem/` and `navigation/`.)
2. Every `AnimatedVisibility`/`AnimatedContent` passes an explicit spec from `MotionSpecs`. No defaults.
3. Lazy items use `Modifier.gpAnimateItem()`, not `animateItem()`.
4. Animated values are read in `graphicsLayer`, `drawBehind`/`drawWithCache` or a lambda modifier. `by animate*AsState` in composition is reserved for colours with ≤ 300 ms tweens.
5. Infinite transitions are only created inside `if (!reduce)` and go through `ambient()`.
6. Reduced motion = no translation, scale or size animation. Opacity and colour changes ≤ 150 ms are allowed.

`MaterialTheme.motionScheme` (M3 Expressive) is **not** recommended now. Whether the material3 artifact shipped with CMP 1.9.0 exposes it as stable is **Unverified**, and `object Motion` already provides equivalent spring tokens.

---

## 8. Prioritised backlog

Impact: H/M/L for user-visible quality or correctness. Effort: S (< ½ day), M (1–2 days), L (> 2 days). Risk: chance of regressions.

| ID | Pri | Item | Impact | Effort | Risk | Depends on | Files to inspect / change |
|---|---|---|---|---|---|---|---|
| T0 | **P0** | Add Compose UI test harness (test-only `compose.uiTest` from the CMP Gradle plugin; **needs your approval**, no runtime dependency) and capture baseline screenshots on Desktop | H (verification) | S | L | approval | `composeApp/build.gradle.kts`, `composeApp/src/desktopTest/`, `.github/workflows/ci.yml` |
| R1 | **P0** | Reactive OS reduced-motion signal (iOS re-read on `UIApplicationDidBecomeActive`/accessibility notification; Android observe `Settings.Global` via `ContentObserver`); Desktop keeps the user setting | H | S–M | L | – | `platform/Platform.kt:106`, `PlatformActuals.android.kt:194`, `PlatformActuals.ios.kt:137`, `PlatformActuals.desktop.kt:87`, `App.kt:124` |
| R2 | **P0** | Make all motion honour reduced motion: 9 `AnimatedVisibility` sites, 17 `animateItem` sites, `animateContentSize` in `DoseCard`, `AnimatedContent` at `TodayScreen.kt:656` and `QuickLog.kt:194`, bottom bar `App.kt:222` | H | M | L | R3 builders (or inline) | files listed in §6.1 A3 |
| R3 | **P0** | Gate infinite transitions under reduced motion (create only when allowed) | M | S | L | – | `tour/AppTour.kt:258`, `tour/CoachMarks.kt:203` |
| M1 | **P1** | `MotionSpecs.kt`: `sharedAxisX`, `fadeThrough`, `expandSection`, `REDUCED_FADE`; migrate the 5 axis-X copies and unify reduced durations | M | M | M (visual tuning) | T0 baseline | `navigation/Navigation.kt` (`transitionFor`), `InsightsScreen.kt:140`, `MedsScreens.kt:297`, `PetEditorScreen.kt:117`, `RecordScreens.kt:219`, `CareTeamScreen.kt:280` |
| M2 | **P1** | Fix per-frame recomposition: memorial glow and grayscale, tour orbit | M | S | L | – | `pets/PetsScreens.kt:389–423`, `designsystem/Components.kt:270` (`PetAvatar`: accept `() -> Float` or apply the matrix in `graphicsLayer`/`drawWithContent`), `tour/AppTour.kt:256` |
| M3 | **P1** | Deduplicate `OrbitIllustration` and pager indicators into `Illustrations.kt` | L–M | S | L | M2 | `onboarding/Onboarding.kt:310, 438`, `tour/AppTour.kt:256, 311` |
| S1 | **P1** | Loading → content crossfade (Today skeleton cut) and skeletons for Journal, Meds list; fade-in after `return@Scaffold` loading in editors | M | M | L | M1 | `TodayScreen.kt:214–236`, `journal/Journal.kt:235, 499`, `MedsScreens.kt:115, 288`, `PetEditorScreen.kt:114`, `VetVisitEditorScreen.kt:71`, `RecordScreens.kt:217` |
| S2 | **P1** | Check-in form → saved summary transition (fade-through with the ring drawing in) and the bottom bar exiting with it | M | S | L | M1 | `checkin/CheckIn.kt:162, 186–189, 252` |
| X1 | **P1** | Selection semantics for `EmojiScale`/`SeverityPicker` (`selectable` + `Role.RadioButton` + `selectableGroup`); 48 dp targets; "Loading" semantics on skeletons | H (a11y) | S | L | – | `designsystem/Components.kt:427–520, 557` |
| N1 | **P1** | iOS back swipe and Desktop Escape. Spike first: compile a common `BackHandler`/`PredictiveBackHandler` (`androidx.compose.ui.backhandler`, added to CMP core in JetBrains/compose-multiplatform-core#1771) against CMP 1.9.0 on all three targets. If it works, replace the three `PlatformBackHandler` actuals; otherwise add an iOS edge-pan recognizer and a Desktop `onPreviewKeyEvent` Escape handler | H (iOS UX) | M | M | spike | `platform/Platform.kt:89`, the three `PlatformActuals.*.kt`, `navigation/Navigation.kt` (`NavHost`), `desktopMain/.../main.kt`, `iosMain/.../MainViewController.kt` |
| A11 | **P2** | Ambient animation policy: stop `WellnessRing` breathing and `EmptyState` float after a few cycles (or ≤ 5 s), pause when off-screen | M | S | L | M1 (`ambient()`) | `Components.kt:152, 371`, `PetsScreens.kt:395` |
| P1x | **P2** | Polish: upcoming chevron rotation, paw icon pop when care rings close, paywall unlocked crossfade and plan-selection colour animation, record PDF preview fade-in | M | S each | L | M1 | `TodayScreen.kt:902`, `Celebration.kt:144`, `PaywallSheet.kt:138–170`, `RecordScreens.kt` (detail) |
| P2x | **P2** | Insights: swipeable tabs via `HorizontalPager` synced with `ScrollableTabRow` (keeps axis-X feel, adds gesture) | M | M | M (gesture conflicts with charts) | M1 | `InsightsScreen.kt:98–160`, `designsystem/Charts.kt` |
| P3x | **P2** | Container transform: record/visit/med cards → detail via `sharedBounds` (same `SharedTransitionLayout` already used for pet photos) | M | M | M | M1, T0 | `Navigation.kt` (`LocalSharedScope`), `RecordScreens.kt` (`RecordRow`, `RecordDetailScreen`), `MedsScreens.kt` (`MedicationCard`) |
| P4x | **P2** | Splash: end as soon as `state.loaded` and the first beat has played (≈ 0.8 s), full choreography only on first launch | M | S | L | – | `App.kt:133–149`, `Choreography.kt:89–115` |
| P5x | **P2** | Verify `GoodDayCalendar` doesn't replay its bloom on unrelated emissions; key on a stable value (date range) | L | S | L | runtime check | `Charts.kt:183`, `insights/InsightsViewModel.kt` |
| P6x | **P3** | Docs: fix the `NavHost` KDoc (D7), note D6, delete unused helpers (D5) or adopt them | L | S | L | M1 | `Navigation.kt`, `Motion.kt`, `Components.kt:537` |
| P7x | **P3** | Splash title semantics (`clearAndSetSemantics`) if A8 is confirmed | L | S | L | screen-reader check | `Choreography.kt:120–182` |

---

## 9. Platform compatibility of the proposed APIs

| API / technique | Android | iOS | Desktop | Basis |
|---|---|---|---|---|
| `AnimatedContent`, `AnimatedVisibility`, `ContentTransform`, `togetherWith`, `SizeTransform` | ✅ | ✅ | ✅ | Already used in commonMain and compiled for all targets by CI (green on PR #4) |
| `Modifier.animateItem(fadeInSpec, placementSpec, fadeOutSpec)` with `null` specs | ✅ | ✅ | ✅ | `animateItem()` already compiles in commonMain; passing `null` specs is part of the same API in Compose foundation 1.7+. Unverified that CMP 1.9 exposes identical parameter names; confirm on first compile |
| `SharedTransitionLayout`, `sharedElement`, `sharedBounds` | ✅ | ✅ | ✅ | `sharedElement` already compiles and runs; `sharedBounds` is the same experimental API surface (opt-in present) |
| `HorizontalPager` | ✅ | ✅ | ✅ | Already used in 3 files |
| `graphicsLayer { compositingStrategy = Offscreen }` | ✅ | ✅ | ✅ | Already used (`CoachMarks.kt:218`) |
| Common `BackHandler`/`PredictiveBackHandler` (`androidx.compose.ui.backhandler`) | ✅ | Unverified (gesture recognizer in CMP core per PR #1771) | Unverified (Escape support not documented in that PR) | Spike required (N1). Newer androidx releases also move toward "navigation event" APIs, so pick whichever CMP 1.9 actually ships |
| Reactive reduced motion: Android `ContentObserver` on `Settings.Global` | ✅ | – | – | Platform API, androidMain only |
| Reactive reduced motion: iOS `UIAccessibilityReduceMotionStatusDidChangeNotification` | – | ✅ (UIKit, via K/N interop; may need a category import as with `registerForRemoteNotifications`) | – | iosMain only |
| `runComposeUiTest` (Compose Multiplatform UI test) | instrumented only | Unverified on `iosSimulatorArm64Test` | ✅ `desktopTest` | Needs the T0 test dependency |

---

## 10. Test plan

### 10.1 Automated

| Level | Target | What | How |
|---|---|---|---|
| Unit | `desktopTest` (runs in CI) | `MotionSpecs` returns fade-only transforms when reduced; `ambient()` gating; `bubblePosition` (exists); `Navigator.tabDirection` (exists in `NavigationTest`) | plain `kotlin.test` |
| Compose UI | `desktopTest` with `runComposeUiTest` (T0) | For each transition (push, pop, tab, step, sheet, expand): with `mainClock.autoAdvance = false`, advance half the duration and assert node bounds. **Reduced:** x/y and size unchanged, only alpha. **Normal:** translated. Tour/coach-mark: tips never show under the tour; one tip at a time; `markSeen` called | `onNodeWithTag(...).fetchSemanticsNode().boundsInRoot`, `mainClock.advanceTimeBy` |
| Screenshots | `desktopTest` | Golden images of key states (Today loaded, dose given, check-in summary, paywall, tour page 1, coach mark) in light/dark/reduced | `onRoot().captureToImage()` → PNG; upload as a CI artifact and diff against `docs/motion-baseline/` |
| Semantics | `desktopTest` | `EmojiScale` exposes `Role.RadioButton` and selected; skeleton exposes "Loading" | `assert(SemanticsMatcher.expectValue(...))` |
| Build | CI (existing) | Android APK, Desktop compile, iOS framework + simulator app | `.github/workflows/ci.yml` |
| iOS UI tests | `iosSimulatorArm64Test` | Same Compose UI tests if `runComposeUiTest` works on iOS in CMP 1.9 | Unverified: try in T0, else manual |

### 10.2 Manual / runtime (needs devices)

| Check | Android | iOS | Desktop |
|---|---|---|---|
| OS reduced motion toggled while the app is open (R1) | Developer options → Animator duration scale off; Accessibility → Remove animations | Settings → Accessibility → Motion → Reduce Motion | n/a (in-app setting) |
| Screen reader pass on Today, Check-in, Tour, Coach marks | TalkBack | VoiceOver | macOS VoiceOver (best effort) |
| Frame timing on Today (rings, breathing), Insights (charts), Memorial | Android Studio profiler or Macrobenchmark `FrameTimingMetric` on a low-end device | Xcode Instruments → Core Animation / Hitches | n/a |
| Back navigation | predictive back swipe, cancel mid-swipe | edge swipe (after N1) | Escape (after N1) |
| High refresh rate | 120 Hz device | ProMotion device | 120 Hz display |

---

## 11. Implementation sequence and acceptance criteria

| Phase | Scope | Acceptance criteria |
|---|---|---|
| **0. Harness** | T0 (after you approve the test dependency) | `desktopTest` runs at least one `runComposeUiTest`; baseline screenshots for 6 key screens × light/dark × reduced on/off are uploaded as a CI artifact; CI green on all 4 jobs |
| **1. Reduced motion** | R1, R2, R3 | Grep shows no `AnimatedVisibility`/`AnimatedContent` without an explicit spec and no bare `animateItem()`; UI tests prove zero translation/scale/size change under reduced motion for push, tab, step, expand, list insert; toggling the OS setting while the app runs takes effect on Android and iOS (manual); no infinite transition is created when reduced (unit test on `ambient()`) |
| **2. Motion architecture** | M1, M3, P6x | One definition of shared axis X used by all 5 former copies; one `REDUCED_FADE`; screenshots show no unintended visual change beyond the harmonised offsets (reviewed side by side); unused helpers removed or adopted; CI green |
| **3. Performance** | M2, A11 | Recomposition counts (Layout Inspector or composition tracing) show no per-frame recomposition on Memorial and Tour; ambient animations stop within 5 s; Today's 90th-percentile frame time not worse than baseline on the same device (measured, numbers in the report) |
| **4. State transitions & a11y** | S1, S2, X1, P7x | No hard cut between loading and content on Today, Journal, editors; check-in → summary transitions; TalkBack/VoiceOver announce selected face/severity; touch targets ≥ 48 dp; UI semantics tests pass |
| **5. Platform navigation parity** | N1 | Spike result documented; iOS edge-swipe pops one screen with the same visual as Android predictive back (or a native-feeling equivalent); Escape pops on Desktop; Android predictive back unchanged; all three tested manually and CI green |
| **6. Polish** | P1x, P2x, P3x, P4x, P5x | Each item behind the same reduced-motion rules; screenshots/recordings for each; cold start to interactive Today measured before/after P4x |

### Report required after each phase

As you asked, every phase ends with a report containing:

1. **Files modified and why** (path → reason).
2. **Reused vs new**: which existing helpers or tokens were used, and what was added.
3. **Android / iOS / Desktop status**: compiled (CI job), run (where), not run.
4. **Build and test results**, labelling each line as *actually run* (with the CI run link) or *not verified*.
5. **Reduced-motion and accessibility verification**: automated tests run plus manual checks done, with the device and OS version.
6. **Screenshots / recordings** of the major transitions. Desktop screenshots come from `captureToImage` in CI. Android/iOS recordings need a device or simulator run on your side or a macOS runner job; the report will say which.
7. **Remaining defects and follow-ups.**

> **Limits of this environment:** CI can build and run desktop tests and capture desktop screenshots. It
> can't drive TalkBack/VoiceOver, profile real devices or record Android/iOS screens without an
> emulator/simulator job. Those items will be marked "not verified" in phase reports unless you run
> them or approve adding emulator/simulator jobs.
