<div align="center">

# 🐾 GoldenPaw

### Senior pet care companion for Android, iOS and Desktop

*Every good day, remembered. Every hard day, easier.*

![Platforms](https://img.shields.io/badge/platforms-Android%20·%20iOS%20·%20Desktop-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin%20Multiplatform-2.1-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Compose%20Multiplatform-1.8-4285F4?logo=jetpackcompose&logoColor=white)
![Architecture](https://img.shields.io/badge/architecture-Clean%20%2B%20MVI-C8862A)
![Offline first](https://img.shields.io/badge/offline-first-6F8F6A)
![Status](https://img.shields.io/badge/status-v1.2%20beta-B5654A)

</div>

---

GoldenPaw helps owners of **aging and chronically ill dogs and cats** handle the daily work of care: complex medication schedules, mobility and pain tracking, a recognised quality-of-life score, a symptom journal and a **one-tap, vet-ready PDF report**. Version 1.1 added **shared care** for families and sitters, a **home-screen widget**, **weekly summaries**, **gentle gamification**, and runs on **Android, iOS and Desktop** from one Kotlin codebase. Version 1.2 adds **vet and vaccine card scanning** that turns a paper card into a tidy PDF and a proper entry with due-date reminders, plus a redesigned, more animated UI.

## Contents

- [What's new in 1.2](#-whats-new-in-12)
- [What's new in 1.1](#-whats-new-in-11)
- [Features](#-features)
- [Architecture](#-architecture)
- [Getting started](#-getting-started)
- [Turning on sharing and AI summaries (Supabase)](#-turning-on-sharing-and-ai-summaries-supabase)
- [Project structure](#-project-structure)
- [Roadmap](#-roadmap)
- [Disclaimer](#-disclaimer)

---

## 🆕 What's new in 1.2

| Area | What you get |
|---|---|
| 📷 **Vet & vaccine card scanning** | **Scan with camera** (permission asked only when you tap it) or **choose from gallery** (Photo Picker / PHPicker, no library permission). Android uses Google's ML Kit document scanner and iOS uses VisionKit: edge detection, perspective correction and multi-page capture. Review pages: rotate, **Enhance** (grey, higher contrast for faded stamps), reorder, delete, add more. |
| 🔤 **On-device reading** | ML Kit (Android) and Vision (iOS) read the card **on the phone**: nothing is uploaded and there's no per-scan cost. A parser rebuilds table rows and pre-fills vaccines (incl. Indian brands like Megavac, Canigen, Rabisin, Defensor), given and next-due dates, batch numbers, clinic and vet. You confirm every field. |
| 📄 **Proper PDF** | A4 PDF with a summary page (pet, clinic, vet, vaccine table with status dots, notes) followed by every scanned page. Send it to any vet or kennel from the record screen. |
| 💉 **Due dates that act** | Vaccines and deworming due within 30 days (or overdue) show on **Today**; one switch adds them as vet visits with the evening-before reminder. The vet report now includes a **Vaccinations & preventives** section. |
| ✨ **UX** | Choreographed splash, orbiting tutorial with live permission status, floating "liquid" bottom bar, collapsing sticky Today header, Material icons instead of emoji, and permission fixes on all three platforms. |

## 🆕 What's new in 1.1

| Area | What you get |
|---|---|
| 🤝 **Care team** | Households with **Owner / Family / Sitter** roles, invite codes, sitter access that ends on a date, a shared care log, and **"who gave the dose"** on every entry. A **double-dose guard** warns before anyone gives a dose someone else already gave (app, notification and widget). People who share one phone can add themselves on the device and switch with one tap. |
| 📱 **Widget & quick-log** | Glance home-screen widget with today's doses and a one-tap **Given** button, check-in and quick-log shortcuts. A quick-log sheet logs doses, symptoms and weight in two taps. App shortcuts (long-press the icon). |
| ✨ **Weekly summaries** (Plus) | A short, kind recap of the week written by Claude (through a Supabase Edge Function), with highlights, things to keep an eye on and questions for your vet. Clearly labelled **"Not veterinary advice"**. Works offline with an on-device writer when the AI isn't available. Free users still get the weekly numbers. |
| 🌱 **Gentle gamification** | Care points and 7 levels, daily care rings, a care streak with **earned rest days** (it never resets for one missed day), 16 badges with celebrations, "On this day" memories and team contributions. One switch turns it all off (**gentle mode**). |
| 🩺 **Vet visits** | Appointments with reminders the evening before, notes and a "done" tick. |
| 🎞 **Motion** | Material 3 motion tokens, shared-axis navigation, fade-through tabs, **predictive back**, shared-element pet photo, spring-based press feedback, staggered list entrances, skeleton loading, rolling counters. Everything respects **Reduce motion** (in-app or OS). |
| 🧩 **Kotlin Multiplatform** | Shared domain, data (Room KMP, Ktor) and PDF report; Compose Multiplatform UI; Android, iOS and Desktop apps. |

## ✨ Features

- **💊 Medication manager:** daily, every-N-days, specific weekdays, tapering and as-needed schedules; supply countdown and refill alerts; read-only for sitters.
- **🔔 Reliable reminders:** exact alarms with Given / Snooze / Skip actions on Android, pre-scheduled local notifications on iOS, tray notifications on Desktop, plus a reminders health check.
- **🌤 Daily check-in:** 30-second HHHHHMM quality-of-life check-in, good-day calendar and 30-day trend.
- **📖 Symptom journal** with photos, severity, tags and pattern detection.
- **⚖️ Weight tracking** stored in kg, shown in your unit.
- **🗂 Health records:** scan vet cards, vaccine cards, prescriptions and lab reports into PDFs with structured entries and vaccine due dates.
- **📄 Vet-ready PDF** made on the device (Android uses the system PDF engine; iOS and Desktop use a built-in pure-Kotlin PDF writer).
- **🐕 Pet profiles, memories** and a quiet memorial space.
- **🔒 Private by default:** everything lives on the device until you turn on sharing.

## 🏛 Architecture

```
composeApp (UI)                              shared (no UI)
┌───────────────────────────────┐            ┌─────────────────────────────────────┐
│ Compose Multiplatform screens │            │ domain/  models · logic · use cases │
│ ViewModels (StateFlow, MVI)   │ ─────────▶ │          repository interfaces       │
│ Navigator + motion system     │            │ data/    Room KMP · Supabase (Ktor)  │
│ androidMain / iosMain /       │            │          settings · export           │
│ desktopMain platform services │            │ report/  vet PDF layout + canvases   │
└───────────────────────────────┘            └─────────────────────────────────────┘
```

- **Clean architecture:** UI → use cases → repository interfaces; data implements them. Platform pieces (reminders, files, PDF canvas, share sheet) sit behind interfaces wired with **Koin**.
- **Offline-first sync:** every row is UUID-keyed and marked pending locally; sync pushes pending rows, then pulls by a per-household cursor on the server's `synced_at`. Last write wins on `updated_at`; dose events are append-only; check-ins use a deterministic id per pet per day.
- **Security:** Postgres row-level security per household, role checks in triggers (sitters can't change pets or meds, only remaining supply), invites via `SECURITY DEFINER` RPCs. The Anthropic key lives only on the server.
- **Gamification is derived**, not stored: points, streaks and badges are computed from the care log, so undoing a dose undoes its points.

## 🚀 Getting started

**Prerequisites:** JDK 17, Android Studio (Ladybug or newer) with the Kotlin Multiplatform plugin; Xcode 16 and [XcodeGen](https://github.com/yonaskolb/XcodeGen) for iOS.

```bash
./gradlew :composeApp:installDebug          # Android (device or emulator)
./gradlew :composeApp:run                   # Desktop
./gradlew :shared:desktopTest               # shared logic tests
cd iosApp && xcodegen generate && open iosApp.xcodeproj   # iOS, then Run
```

CI (`.github/workflows/ci.yml`) runs the shared tests, builds the debug APK (downloadable as an artifact), compiles Desktop, and builds the iOS framework on `main` or on demand.

Without Supabase keys the app is **fully local**: everything works except syncing between phones and AI-written summaries (the on-device summary is used instead).

## ☁️ Turning on sharing and AI summaries (Supabase)

1. Create a Supabase project. In **Authentication → Email templates → Magic link**, include `{{ .Token }}` so users receive a 6-digit code.
2. Apply the schema: `supabase link --project-ref <ref>` then `supabase db push` (or paste `supabase/migrations/0001_goldenpaw.sql` into the SQL editor).
3. AI summaries: `supabase secrets set ANTHROPIC_API_KEY=sk-ant-...` then `supabase functions deploy weekly-summary`. Optional `ANTHROPIC_MODEL` (defaults to `claude-haiku-4-5-20251001`).
4. Give the app your keys in `local.properties` (never commit them):
   ```properties
   supabase.url=https://<ref>.supabase.co
   supabase.anonKey=<anon key>
   ```
   or set `GOLDENPAW_SUPABASE_URL` / `GOLDENPAW_SUPABASE_ANON_KEY` (also as GitHub secrets for CI builds).

## 🗂 Project structure

```
shared/                  domain, data, report (commonMain) + android/ios/desktop actuals, tests
composeApp/              Compose Multiplatform UI
  src/commonMain         screens, ViewModels, navigation, design system, DI
  src/androidMain        MainActivity, alarms + notifications, Glance widget, shortcuts
  src/iosMain            MainViewController, UNUserNotificationCenter reminders
  src/desktopMain        main.kt, tray reminders
iosApp/                  XcodeGen project + SwiftUI host
supabase/                SQL migration (RLS, triggers, invite RPCs) + weekly-summary Edge Function
docs/                    TESTING.md, MARKET_RESEARCH_2.md
```

## 🗺 Roadmap

- [x] MVP: pets, medications and reminders, check-ins and QoL, weight, symptom journal, vet PDF, offline-first
- [x] Unit tests for the schedule engine, QoL, summaries, gamification
- [x] Supabase backend: email-code auth, RLS, push/pull sync
- [x] Caregiver sharing (family and sitter roles, "who gave the dose")
- [x] Home-screen widget and quick-log
- [x] AI weekly journal summaries (Plus, with a "not veterinary advice" disclaimer)
- [x] Kotlin Multiplatform: shared domain and data, Compose Multiplatform UI, iOS and Desktop targets
- [x] Gentle gamification and vet visits
- [x] Vet / vaccine card scanning with on-device text recognition, PDF records and vaccine due dates
- [ ] Billing (RevenueCat) for GoldenPaw Plus
- [ ] Photo and record sync (Supabase Storage), realtime updates, push notifications when a teammate gives a dose
- [ ] Vet expense tracking, HCPI pain and cognitive (CCDR) assessments, iOS widget
- [ ] Baseline Profile, crash reporting, analytics

See [docs/MARKET_RESEARCH_2.md](docs/MARKET_RESEARCH_2.md) for the research behind 1.1.

## ⚠️ Disclaimer

GoldenPaw is an informational tool for pet owners. It does **not** diagnose conditions and is **not** a substitute for professional veterinary advice. Weekly summaries only describe what was logged. If you're worried about your pet, contact your veterinarian or an emergency clinic.

---

<div align="center">

Built with ❤️ for every senior pet by **[David Raj Paul](https://github.com/DavidRajPaul)**

© 2026 David Raj Paul. All rights reserved.

</div>
