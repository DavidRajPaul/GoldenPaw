# Store compliance forms: exact answers

These answers describe the app as built (see the privacy policy in `site/privacy.md`). If a feature
changes what leaves the device, update this file, the privacy policy and `PrivacyInfo.xcprivacy`
together.

## Google Play Console → Policy → App content

| Form | Answer |
|---|---|
| **Privacy policy** | `https://davidrajpaul.github.io/GoldenPaw/privacy/` (or your domain) |
| **Ads** | **No, my app does not contain ads.** (In-app promotions only advertise GoldenPaw Plus and ship off. If you ever enable a third-party ad network, change to Yes.) |
| **App access** | **All or some functionality is restricted** → add instructions: "No login is needed for the main app. The optional Care team feature signs in with a one-time code sent by email; reviewers can use any email address they can read. Plus can be tested with a license-tester account." |
| **Content rating** | Category: **All Other App Types**. Answer **No** to violence, sexuality, language, controlled substances, gambling. **Users can interact or exchange content: No** (care-team sharing is private to invited people, not public). **Shares user location: No.** **Digital purchases: Yes.** Expected rating: Everyone / 3+. |
| **Target audience** | **18 and over** only. Not designed for children. |
| **News app** | No |
| **COVID-19 / contact tracing** | No |
| **Data safety** | See below |
| **Government app** | No |
| **Financial features** | **My app doesn't provide any financial features** |
| **Health apps** | Declare that the app is **not** a human-health app: none of the human health categories apply. If the form insists on a category, choose the closest "other" option and describe: "Pet care record keeping (medicine reminders and notes for pets). No human health data." |
| **Account deletion** | Users can create an account: **Yes** (optional email sign-in). Delete account URL: `…/delete-account/`. In-app path: Settings → Delete my account. |
| **Photo and video permissions** | Not needed: the app uses the system Photo Picker (no READ_MEDIA permissions). |
| **Exact alarms** | The app uses `SCHEDULE_EXACT_ALARM` for medicine reminders (user-granted, no declaration form). If Play asks, the core use case is "reminders the user sets for a pet's medicines". |

### Data safety

**Does your app collect or share any of the required user data types?** Yes.
**Is all of the user data encrypted in transit?** Yes.
**Do you provide a way for users to request that their data is deleted?** Yes (in-app and by URL).

Nothing is **shared** (all transfers go to service providers acting for us, or are user-initiated
care-team sharing, which Play does not count as sharing).

| Data type | Collected | Optional? | Purposes | Ephemeral |
|---|---|---|---|---|
| Personal info → **Email address** | Yes | Optional (only for Care team) | App functionality, Account management | No |
| Personal info → **Name** (display name in a care team) | Yes | Optional | App functionality | No |
| App activity → **Other user-generated content** (shared pet care records, support messages) | Yes | Optional | App functionality | No |
| App info and performance → **Diagnostics** (app version, platform, reminder status in support messages) | Yes | Optional | App functionality | No |
| Device or other IDs → **Device or other IDs** (random install ID, push token) | Yes | Required | App functionality, Developer communications | No |
| Financial info → **Purchase history** (Plus status) | Yes | Optional | App functionality | No |

Not collected: location, contacts, photos/videos (they stay on the device), files and docs (scans
stay on the device), health info (pet data isn't the user's health), messages, web history, audio,
calendar, app interactions/analytics, crash logs.

## App Store Connect

### App Privacy (must match `iosApp/iosApp/PrivacyInfo.xcprivacy`)

**Do you or your third-party partners collect data from this app?** Yes. **Tracking:** No for every type.

| Data type | Linked to the user | Purposes |
|---|---|---|
| Contact Info → **Email Address** | Yes | App Functionality |
| Contact Info → **Name** | Yes | App Functionality |
| User Content → **Other User Content** | Yes | App Functionality |
| User Content → **Customer Support** | Yes | App Functionality |
| Identifiers → **Device ID** | Yes | App Functionality, Developer's Advertising or Marketing (opt-in offer notifications) |
| Purchases → **Purchase History** | Yes | App Functionality (add when subscriptions go live) |
| Diagnostics → **Other Diagnostic Data** | Yes | App Functionality |

### Age rating
All answers **None / No**, including "Unrestricted web access: No", "Gambling: No", "Medical or
treatment information: **Infrequent/Mild**" (medicine schedules and symptom notes for pets; choose
None if the form makes clear it means human medical info). Expected: 4+.

### App Review Information
- **Sign-in required:** No.
- **Notes:**
  ```
  GoldenPaw works fully without an account: add a pet from the first screen and everything is stored on the device.
  The optional Care team feature (Settings → Care team) signs in with a one-time code emailed to any address you can read.
  GoldenPaw Plus can be tested with a sandbox account: Settings → tap the "GoldenPaw Free" card to open the Plus screen.
  Medicine reminders are local notifications; allow notifications when prompted.
  The app keeps records only and does not give veterinary or medical advice.
  ```
- **Contact:** your name, phone, email.

### Export compliance
`ITSAppUsesNonExemptEncryption = NO` is already in Info.plist (HTTPS only), so no questions per build.

### Subscriptions (App Store Connect → Monetization)
Group **GoldenPaw Plus** → products `com.goldenpaw.plus.yearly`, `com.goldenpaw.plus.monthly`.
Each needs: reference name, duration, price, display name ("GoldenPaw Plus, yearly"), description
("Every pet, weekly summaries, cloud backup and deeper trends"), and a **review screenshot** of the
Plus screen. Introductory offer: Free, 1 week, new subscribers.
