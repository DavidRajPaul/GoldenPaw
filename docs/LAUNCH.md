# GoldenPaw launch checklist (what David does)

Everything on the code side is ready; these steps need your accounts. Secret values go only into
`supabase/secrets.env` (git-ignored), `local.properties` (git-ignored) or GitHub → Settings →
Secrets and variables → Actions — never into chat or a committed file (the repo is public).

## Part 1: do now (no domain or store account needed)

### 1. Supabase (≈ 30 min)
1. supabase.com → New project: name `goldenpaw`, region **Mumbai (ap-south-1)**, save the DB password.
2. Install the CLI and log in: `supabase login`.
3. In the repo folder run **`./scripts/setup-supabase.sh`**. It links the project, applies every
   migration, generates `PUSH_ADMIN_TOKEN` and `REVENUECAT_WEBHOOK_AUTH`, uploads secrets, deploys all
   four Edge Functions, writes `local.properties`, and (with the GitHub CLI) sets the CI secrets.
4. Do the two dashboard steps it prints at the end:
   - **A.** Paste the Vault SQL into the SQL editor (enables scheduled push + ticket alerts).
   - **B.** Paste `supabase/templates/sign-in-code.html` into **both** "Magic Link" and "Confirm
     signup" email templates (subject `Your GoldenPaw code: {{ .Token }}`).
5. **Testing with two people before your domain exists:** Supabase's built-in email only delivers
   to members of your Supabase organization. Invite the second tester's email under Organization →
   Team, and sign-in codes will reach them too.

### 2. Firebase, Android push (≈ 15 min)
1. console.firebase.google.com → Add project (Analytics off) → Add app → Android → `com.goldenpaw.app`.
2. From the downloaded `google-services.json` (keep it outside the repo) put into `local.properties`:
   ```properties
   firebase.projectId=<project_info.project_id>
   firebase.senderId=<project_info.project_number>
   firebase.androidAppId=<client[0].client_info.mobilesdk_app_id>
   firebase.apiKey=<client[0].api_key[0].current_key>
   ```
   and the same four as GitHub secrets `GOLDENPAW_FIREBASE_PROJECT_ID`, `_SENDER_ID`, `_ANDROID_APP_ID`, `_API_KEY`.
3. Project settings → Service accounts → Generate new private key → save the JSON outside the repo,
   put its path in `supabase/secrets.env` as `FCM_SERVICE_ACCOUNT_FILE=…`, re-run the setup script.
4. Test with the **release** build (`./gradlew :composeApp:installRelease`); see `docs/store/TEST_PLAN.md` §3.

### 3. Optional keys (add to `supabase/secrets.env`, re-run the script)
- **Ticket alerts:** resend.com account → API key → `RESEND_API_KEY`, and `SUPPORT_NOTIFY_EMAIL` =
  the email you signed up to Resend with (until your domain is verified that's the only allowed recipient).
- **AI weekly summaries:** console.anthropic.com → API key (add billing) → `ANTHROPIC_API_KEY`.

### 4. Android upload key (≈ 10 min, doesn't need the Play account yet)
1. Android Studio → Build → Generate Signed App Bundle → **Create new** keystore (validity 25+ years).
   Save the `.jks` and both passwords in your password manager; losing them means a support ticket with Google.
2. GitHub secrets: `ANDROID_KEYSTORE_BASE64` (`base64 -w0 upload.jks`, macOS `base64 -i upload.jks`),
   `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
3. Actions → **Release builds** → Run workflow → you get a signed `.aab` artifact (ready for Play later).

### 5. Website text (≈ 20 min)
Read `site/privacy.md`, `site/terms.md`, `site/delete-account.md`. Fill `site/_config.yml`: legal
name, the address you're comfortable publishing, grievance officer (you), effective date. The
contact email waits for your domain. (Publishing: Part 2, step 1.)

### 6. Things to start early
- **12 testers** for Play's closed test (14 days in a row): family, friends, colleagues with Android phones and a Gmail address.
- **Screenshots**: see `docs/store/LISTING.md` for the 7 screens to capture.
- **Decisions**: final prices (today yearly $39.99 with a 7-day trial, monthly $5.99; India prices?).

## Part 2: later (needs the domain or store accounts)

### 1. Domain and email
1. Buy the domain → Cloudflare DNS → Email Routing `support@` → your Gmail.
2. Resend → add and verify the domain → Gmail "Send mail as" `support@` via `smtp.resend.com:465`, user `resend`, password = API key.
3. Supabase → Authentication → SMTP Settings → custom SMTP (same Resend settings, sender `no-reply@<domain>`) → Rate Limits: emails/hour ≈ 30.
4. `site/_config.yml`: contact email (and, with a custom domain, `url`/`baseurl` + a `CNAME` file).
   GitHub → Settings → Pages → Source **GitHub Actions**; Variables → `PAGES_ENABLED=true`; Actions → Website → Run.
5. `gradle.properties`: `goldenpaw.supportEmail=support@<domain>` and `goldenpaw.privacyUrl=<site>/privacy/`.

### 2. Google Play Console
Register (Personal, $25, ID + device verification) → payments profile (bank, PAN) → create app →
upload the Release-builds `.aab` to **Internal testing** (accept Play App Signing) → License testing
(your Gmail) → App content forms (`docs/store/FORMS.md`) → listing (`docs/store/LISTING.md`) →
closed test with 12 testers for 14 days → apply for production.

### 3. Apple Developer
Enroll (Individual) → Paid Apps Agreement + bank + tax → App ID `com.goldenpaw.app` with Push →
APNs key (`APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_PRIVATE_KEY_FILE` in `secrets.env`, re-run the script) →
App Store Connect app record → API key (Admin) → GitHub secrets `ASC_KEY_ID`, `ASC_ISSUER_ID`,
`ASC_KEY_P8_BASE64` → send me the Team ID (I add the TestFlight job) → sandbox tester → TestFlight.

### 4. Subscriptions and RevenueCat
Play subscriptions `goldenpaw_plus_yearly` / `goldenpaw_plus_monthly` (7-day trial offer); Apple group
"GoldenPaw Plus" with `com.goldenpaw.plus.yearly` / `.monthly` (1-week free intro). RevenueCat project →
connect both stores → entitlement `plus`, offering `default` → `REVENUECAT_SECRET_KEY` into
`secrets.env` (re-run the script) → webhook URL `https://<ref>.supabase.co/functions/v1/revenuecat-webhook`
with Authorization = `REVENUECAT_WEBHOOK_AUTH` from `secrets.env` → send me the public SDK keys.

### 5. Launch
Supabase **Pro** ($25/month; free projects pause when idle) → run `docs/store/TEST_PLAN.md` →
submit iOS with its subscriptions → Play production at 20%, then 100%.
