# GoldenPaw customer support (solo-developer playbook)

**Do we need support?** Yes. GoldenPaw sells a subscription and sends offers, and that brings obligations:

- Google Play and the App Store require a working support contact.
- A monetized Play account shows a public contact address.
- India's DPDP Rules require a published grievance contact and a response within a fixed window.

None of this needs a helpdesk, a team or a phone line. The design goal is that **most people never need to write**, and **anyone who does write can be answered in one reply**.

## The funnel

| Layer | What it is | Cost | Where |
|---|---|---|---|
| 1. Self-serve | Bundled FAQ in **Settings → Help & support**. Answers link straight into the screen that fixes the problem (reminders health check, store subscriptions, care team, settings). Works offline. | ₹0 | `ui/support/Faq.kt`, `HelpScreen.kt` |
| 2. In-app message | A form with a topic chip and a message. The app attaches diagnostics, and the person can see exactly what is attached before sending. Messages go to `support_requests` in Supabase. Limit: 5 per install per day. | ₹0 (Supabase free tier) | `SupportService`, `0005_support.sql` |
| 3. Email | A `mailto:` link to `goldenpaw.supportEmail`. The subject and body come pre-filled with the topic, Support ID and diagnostics. | ₹0 to small (domain alias) | `SupportService.emailLink` |
| 4. Store channels | Refunds and cancellations go through the store (see below). The app links to the store's subscription page. | ₹0 | `subscriptionUrl()` |

### Why there is a Support ID

`XXXX-XXXX` is derived from the install id. It's shown in Help (with a copy button), and it's added to every message and email subject. Use it to find the install's push token row, its `support_requests` history and, once billing exists, the customer in RevenueCat if the Support ID or install id is used as the App User ID. It contains no personal data.

### What diagnostics contain

These fields are included: app version and build, platform, Support ID, Plus yes/no, signed-in yes/no, reminders-health OK, locale and time zone. **No pet data, health logs or email** (unless the person types one in).

## Billing questions: who does what

- **Google Play.** People can get a refund from Google themselves within about 48 hours of a purchase. After that, Google sends them to the developer. You can then issue a refund from Play Console → Order management.
- **Apple.** Only Apple issues refunds, at reportaproblem.apple.com. You can't, so point people there kindly. With RevenueCat or App Store Server Notifications you'll learn about the refund and the entitlement is revoked automatically.
- **"I paid but don't have Plus".** Almost always a different store account, or a stale cache. With RevenueCat: look up the customer by App User ID, check the entitlement, and grant a *promotional entitlement* if needed. That takes 30 seconds.
- **Goodwill.** A promotional entitlement (for example, one month of Plus) is the cheapest fix for a bad experience.

## Triage routine (about 10 minutes a day at launch)

1. **New ticket alert.** Add a Supabase *Database Webhook* on `insert` into `support_requests`. Point it at an Edge Function that posts to Telegram, Slack or email. Alternatively, check the table once a day in the Studio table view.
2. **Filter.** Use `where status = 'open' order by created_at`.
3. **Reply** by email if they left an address. Use canned replies (below). Set `status = 'waiting'` or `'closed'` and `answered_at = now()`. Put your own notes in `notes`.
4. **Promise.** Reply within 2 working days. The Help screen says "usually within two working days".

```sql
-- Open tickets, newest last
select id, created_at, topic, left(message, 120) as msg, contact_email, diagnostics->>'support_id' as sid
from support_requests where status = 'open' order by created_at;

-- Close one
update support_requests set status = 'closed', answered_at = now(), notes = 'Restored via promo entitlement' where id = 42;
```

### Canned replies (adapt per ticket)

- **Reminders not firing.** Send them to Settings → Reminders health check. On Android, also battery set to "Unrestricted" (OEM-specific steps).
- **Refund (Play, after 48 h).** "Done. You'll see it in 3–5 days." Refund in Order management.
- **Refund (Apple).** Link to reportaproblem.apple.com and offer a goodwill month if it fits.
- **Missing Plus.** Ask them to sign into the same store account and reopen the app. If that fails, grant a promotional entitlement.
- **Delete my data.** Settings → Delete my account (cloud) and Delete all data (device). Confirm when it's done.

## Scaling path (only when needed)

| Signal | Step |
|---|---|
| More than 5 tickets a day | Move email to a shared inbox (Help Scout or Crisp free tier) and save canned replies there. |
| The same question 3+ times | Add or rewrite an FAQ entry. That's the cheapest support there is. |
| Many languages | Translate the FAQ first, not the support channel. |
| More than 30 tickets a day | Think about part-time help. Until then the Supabase inbox + email setup is enough. |

Live chat and phone support are deliberately excluded. They don't scale for one person.

## Compliance checklist

- [ ] Support email on the Play listing, the App Store listing and the privacy policy.
- [ ] A privacy policy URL (set `goldenpaw.privacyUrl`). It names a grievance contact. Under the DPDP Rules, grievances are answered within 90 days, and there are breach-notification duties.
- [ ] Play monetized account: physical address (it is shown publicly).
- [ ] Account deletion inside the app (done: `gp_delete_my_account`) plus a web deletion link for Play's Data safety form.
- [ ] Offers only open inside the app, and the FAQ warns against card requests outside it (anti-phishing).

## Configuration

Set these in `local.properties` or as CI gradle properties. Both are blank by default, and the matching rows are hidden.

```
goldenpaw.supportEmail=support@yourdomain.com
goldenpaw.privacyUrl=https://yourdomain.com/privacy
```
