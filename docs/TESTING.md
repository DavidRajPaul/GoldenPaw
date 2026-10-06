# On-device test script

About 10 minutes on a real phone. Covers every MVP flow.

1. Go through onboarding, allow notifications and add a dog with a photo, an age of about 11 years, and Arthritis.
2. **Settings → Reminders health check:** grant Alarms & reminders and set battery to unrestricted. Send a test notification.
3. Add a med: "Carprofen", 25 mg, daily, with a time **2–3 minutes from now**, supply 30, refill alert at 29 days. Lock the phone and wait for the reminder, then tap **Given** in the notification. A refill notification should follow.
4. On **Today**, tap Given on another dose, then **Undo**. Try **Skip**.
5. Do a check-in (it should show the HHHHHMM breakdown). Backdate a few more check-ins using the Day picker to fill the calendar and trend.
6. Log 3 "Limping" symptoms on 3 different days. A pattern card should appear on Today and in the Journal.
7. Log weights in kg and lb (switch the unit in the dialog), then open **Insights → Weight**.
8. Open **Insights → Vet report**, generate it, check the preview, then share it to yourself.
9. Add a 2nd pet. The paywall should appear; start the trial.
10. Turn on **Reduce motion** and dark theme, then look around the app.
11. Move a pet to Memories and open the memorial.
12. Export JSON, then try Delete all data. The app restarts at onboarding.

---

## v1.1 additions (about 15 minutes, two phones if you can)

13. **Gamification:** give a dose and do a check-in. Today shows the rings closing and points rising; the first-dose and first-check-in badges celebrate once. Open **Settings → Care journey**. Turn on **gentle mode** (Streaks, levels and badges off) and check every counter disappears.
14. **Local care team:** Settings → Care team → **On this device**, add "Sam" as Family. On Today tap the caregiver chip, switch to Sam and give a dose. The dose shows "Sam"; switch back and tap Given on the same dose: the **already given** warning appears.
15. **Widget:** Settings → Add home-screen widget (or long-press the home screen). Tap **Given** on the widget; the app's Today updates.
16. **Quick log:** long-press the app icon → Quick log. Log a symptom chip and a weight.
17. **Vet visit:** Pets → your pet → Vet visits → Add for tomorrow. A reminder arrives the evening before.
18. **Weekly summary:** Insights → Weekly. Free shows a locked preview; after starting the Plus trial a summary appears with the "Not veterinary advice" label (on-device writer without Supabase).

With Supabase configured (see README):

19. Phone A: Care team → sign in with the email code → **Turn on sharing** → **Invite** as Sitter with an end date. Phone B: sign in → **I have an invite code**. Pets appear on B.
20. Give a dose on B; reopen the app on A. A shows "Given by <B's name>" and no reminder fires for it. B can't edit medications.
21. On A remove B from the team; after B syncs, the shared pets leave B's device.
22. With the Edge Function deployed, refresh the weekly summary: the source label reads "Written by AI".
