# Market research, round 2 (October 2026)

Goal: after shipping care teams, the widget, weekly summaries and the multiplatform build, find what the best pet-care and habit apps do to keep people coming back every day, and pick what fits a calm senior-pet app.

## What competitors do

| Finding | Source | What we did |
|---|---|---|
| Shared-care apps win on real-time sync, attribution ("who fed / who medicated"), double-dose warnings, unlimited members, widgets and streaks. | [Pawlo: best shared pet care apps for couples, 2026](https://getpawlo.app/blog/best-shared-pet-care-apps-couples-2026) | Care team with roles, attribution everywhere, double-dose guard in app, notification and widget; team contributions. |
| Family feeds with likes and comments keep households engaged. | [DogLog](https://doglogapp.com) | Shared care log (feed) on the Care team screen. Reactions are on the roadmap. |
| Senior-dog apps add structured assessments (HCPI pain, DISHAAL cognition, HHHHHMM) and vet-visit tracking. | [Grey (App Store)](https://apps.apple.com/app/id6768031238) | Vet visits with reminders. HCPI and cognitive checks are next on the roadmap (HHHHHMM already shipped). |
| Pet journals split into memory-first and tracker-first; the best do both. | [Eleven April: best pet journal apps](https://elevenapril.com/blog/best-pet-journal-apps) | "On this day" memories surface past good days next to the tracker. |
| Roundups of 2026 pet apps highlight reminders, records, shared access and expense tracking. | [Petnoter](https://petnoter.com/best-pet-care-apps/), [Petiogo](https://www.petiogo.com/blog/best-pet-care-apps-2026), [AI Money Vault](https://aimoneyvault.app/resources/articles/best-pet-expense-tracker-apps), [Newsweek Readers' Choice](https://www.newsweek.com/readerschoice/best-pet-app-2026) | Vet expense tracking added to the roadmap. |

## Habit design: what works without guilt

| Principle | Source | Implementation |
|---|---|---|
| Streaks work best capped and weekly, with **earned rest days**; long streaks create loss aversion and burnout, so "graduate" them around 30 days. | [Yu-kai Chou: streak design](https://yukaichou.com/gamification-analysis/streak-design-gamification-motivation-burnout/) | Care streak with 0–3 rest days per week (default 2) that bridge gaps automatically; graduates to a permanent badge at 30 days and is shown more quietly. |
| No-punishment self-care apps keep users longer than guilt-driven ones. | [Slate on Finch, Sept 2026](https://slate.com) | Missed days cost nothing but the count; copy never scolds; **gentle mode** turns all counters off. |
| Daily goals that reset (rings) beat ever-growing counters. | Apple Fitness rings pattern | Daily care rings (doses + check-in) and a capped weekly check-in goal of 5. |
| Variable, meaningful rewards. | Habit research above | 16 badges in three tiers, celebration burst + haptic once per badge, levels from "New caretaker" upwards. |

## Shipped from this round

1. Care points, levels and daily rings (derived from the log; undo removes points).
2. Care streak with earned rest days and 30-day graduation.
3. 16 badges with one-time celebrations and an Achievements ("Care journey") screen.
4. Team contributions and the shared care log.
5. "On this day" memories (1 month, 6 months, 1 year ago).
6. Vet visits with day-before reminders.
7. Streak-aware check-in nudge copy and a Sunday weekly recap notification.

## Next candidates

- Reactions on teammates' entries; push when a teammate gives a dose.
- HCPI (pain) and CCDR (cognition) assessments for senior dogs and cats.
- Vet expense tracking with per-pet totals in the vet report.
- iOS home-screen widget (WidgetKit) and Live Activity for a dose window.
