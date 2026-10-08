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

---

## v1.2 additions (about 15 minutes)

**UX**

23. Cold-start the app: the splash glow blooms, the toe beans drop in one by one, the pad pops with a ripple and "GoldenPaw" rises letter by letter. With **Reduce motion** on it's a single fade.
24. Reset onboarding (Delete all data). Swipe the tutorial slowly: the background tint blends between pages, the indicator stretches, accents orbit the hero icon. On the last page, deny notifications **twice** (Android): the third tap on **Allow** opens the app's notification settings. Turn notifications on there and come back: the card flips to "Notifications are on" without restarting.
25. Switch tabs left and right: the pill indicator stretches towards the new tab and the screen slides in from that side; you feel a haptic tick.
26. Scroll Today: the header shrinks to 64 dp, the date fades, the greeting scales down and the pet avatar pops in. Let go half-way: it snaps open or closed.
27. iOS: Settings → Reminders health check shows the real notification status (deny in iOS Settings, come back: it shows a warning, and **Allow** opens Settings). macOS / Linux desktop: **Send test** shows a system notification.

**Vet & vaccine card scanning**

28. Pets → your pet → **Health records → Scan** (or Quick log → *Scan a vet or vaccine card*). Choose **Vaccine card**, tap **Scan with camera**. Deny the camera permission: a snackbar explains. Tap again and deny again (Android): the **Camera access is off** dialog offers *Open settings* and *Pick a photo instead*.
29. Allow the camera and scan a real vaccine card (2 pages). The scanner should find the edges and straighten each page.
30. On **Check the pages**: rotate a page, toggle **Enhance** on and off (it returns to the original), move a page, delete one, then **Add another page → Gallery**.
31. **Continue**: "Reading the card on your device…" then a summary of what was filled in. Check the vaccine rows (name, given, next due, batch), the clinic and the date; fix anything wrong, use **+1 year** on a row with no due date. Turn on *Add due dates as vet visits*.
32. **Save & create PDF**: the record opens. **Open PDF**: page 1 is the summary with the vaccine table and coloured status dots; the scans follow. **Send to vet** opens the share sheet.
33. A due date within 30 days shows the **Coming up** card on Today; an overdue one shows "has a vaccine overdue". The vet visit appears under Vet visits.
34. Insights → Vet report: a **Vaccinations & preventives** section lists the latest entry per vaccine.
35. Edit the record (pencil): change a date, **Edit pages** → rotate → Save changes. The PDF is regenerated.
36. Airplane mode: scanning and text reading still work (Android needs the ML Kit models downloaded once; they come with the install from Play).
37. Desktop: Health records → **Choose image files** (pick 2 JPGs) → review → details (no text reading on desktop) → Save → Open PDF.
38. Delete the record: pages and PDF are removed; Delete all data also removes every record.
