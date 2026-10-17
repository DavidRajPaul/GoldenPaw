# Release test plan (two phones)

Run before every store submission. Phone A is the owner, phone B is family or a sitter. Use fresh
installs. Mark each line ✅ / ❌ and send me any ❌ with the Support ID (Settings → Help & support).

## 1. Local, no account (phone A)
1. First launch: tour shows once; switching tabs afterwards shows **no** tips again.
2. Add a pet with a photo; add a daily medicine due in 3 minutes.
3. Allow notifications. Lock the phone. The reminder arrives on time; tap **Given** from the notification.
4. Reboot the phone; add another dose time 3 minutes ahead; it still fires.
5. Do a check-in, log a symptom with a photo, log a weight.
6. Scan a vaccine card (camera, then gallery). Fields pre-fill; save; open the PDF.
7. Settings → Reminders health check: everything green (Android: battery unrestricted).
8. Turn on **Reduce motion** (OS) → transitions become simple fades.
9. Airplane mode: everything above still works.

## 2. Care team (both phones, needs Supabase)
1. A: Care team → sign in with the emailed code → Turn on sharing → Invite as **Sitter** ending tomorrow.
2. B: sign in → **I have an invite code** → the pet appears.
3. A gives a dose; within a minute B sees "given by A". B tries to give the same dose → double-dose warning.
4. B (sitter) can't edit medicines.
5. A removes B → the pet disappears from B.

## 3. Push (needs Firebase / APNs)
Android: test with the **release** build (`./gradlew :composeApp:installRelease`): it uses the real
package name `com.goldenpaw.app` that's registered in Firebase. The debug build is `…app.debug`.
1. Settings → Notifications: turn **Offers** on.
2. Send a test campaign (SQL editor):
   ```sql
   insert into notification_campaigns (category, title, body, open, status, send_at)
   values ('product', 'Test', 'Hello from GoldenPaw', 'today', 'scheduled', now());
   ```
   Within ~1 minute (pg_cron) the notification arrives; tapping it opens Today.
3. Turn Offers off; send a campaign with `category = 'offers'` → it does **not** arrive.

## 4. Support (needs Supabase)
1. Help & support → Send us a message → it shows a ticket number.
2. The alert email arrives (if `RESEND_API_KEY` is set); **Reply** goes to the email you entered.

## 5. Plus (needs store accounts + RevenueCat)
1. Plus screen shows store prices. Buy yearly with a license/sandbox tester → Plus unlocks.
2. Reinstall → **Restore purchases** → Plus returns.
3. Cancel in the store → Plus stays until the period ends (sandbox periods are minutes).
4. Signed in: `select * from subscriptions;` shows your row with the right status.

## 6. Account & data
1. Settings → Export my data → JSON file opens.
2. Settings → Delete my account → signed out; the user is gone in Supabase → Authentication.
3. Settings → Delete all data → back to the first-run screen.
