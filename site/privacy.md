---
layout: default
title: Privacy policy
permalink: /privacy/
description: What GoldenPaw stores, where, why, and how to delete it.
---

# Privacy policy

<p class="lede">GoldenPaw is built to keep your pet's care records on your own phone. This policy explains the few things that do leave it, and your choices.</p>

**Effective:** {{ site.effective_date }}<br>
**Who we are:** GoldenPaw is made by {{ site.owner_name }}, {{ site.postal_address }} ("we", "us"). For India's Digital Personal Data Protection Act, 2023 we are the *Data Fiduciary* for the personal data described here.<br>
**Contact:** [{{ site.contact_email }}](mailto:{{ site.contact_email }})

## The short version

- **Your pets' records stay on your device** unless you turn on a care team.
- **No advertising networks, no analytics or tracking tools, no selling of data.** Nothing is used to build an advertising profile of you.
- Using a care team needs an email address and copies your pets' care records to our database so your team can see them. You can delete your account in the app at any time.
- Photos and scanned documents **never** leave your device.

## 1. Data that stays on your device

Pet profiles, medicines and doses, check-ins, weights, symptoms, vet visits, vaccine records, scanned documents, photos, notes, reminders and app settings are stored in the app's private storage on your device. We cannot see them. They are removed when you use **Settings → Delete all data** or uninstall the app. Your phone's own backup (for example Google or iCloud backup) may include them, under that provider's terms.

## 2. Data that leaves your device, and why

| What | When | Why | Where it goes |
|---|---|---|---|
| **Email address** | Only if you sign in (needed for care teams) | To send you a one-time sign-in code and identify your account | Our database (Supabase); code emails sent through our email provider (Resend) |
| **Care-team records**: your display name and role, and for shared pets: name, species, breed, birth date, conditions, vet name and phone, medicines, doses given (and by whom), check-ins, weights, symptoms, vet visits and the notes on them | Only after you turn on sharing in **Care team** | So everyone in the team sees the same records and who gave each dose | Our database (Supabase). Visible only to members of that team |
| **Device record**: a random install ID created by the app, platform, push token, app version, language/region, time zone, your notification choices; linked to your account if signed in | When the app connects to our service | To deliver reminders-related and account notifications and, only if you opt in, offers | Our database (Supabase); notifications delivered by Google Firebase Cloud Messaging (Android) and Apple Push Notification service (iOS) |
| **Weekly summary facts**: pet name, species, age, conditions, medicine names, counts of doses/check-ins/symptoms, weight trend, the notes from that week and caregiver first names | Only when an AI-written weekly summary is requested | To write the summary | Sent through our server to Anthropic, which processes it to return the summary. Under Anthropic's commercial terms, API data is not used to train its models. If this isn't available, the app writes a summary on your device instead |
| **Support messages**: your message, topic, optional reply email, the install ID and app details (version, platform, Plus yes/no, reminder status, language, time zone) | Only when you send one from **Help & support** | To answer you | Our database (Supabase); a copy may be emailed to us. You can see exactly what's attached before sending |
| **Subscription status** | If you subscribe to GoldenPaw Plus | To unlock Plus on your devices and restore purchases | Your payment is handled entirely by Google Play or the App Store; we never see card details. Purchase status is processed by RevenueCat and stored in our database |
| **Promotion counts** | If in-app promotions are shown | To know which messages are useful | Only totals per message (shown / tapped / hidden). No install ID, account or device identifier is sent |

We do not collect your precise location, contacts, or any data from other apps.

## 3. Service providers

We use these processors, each bound by its own terms and acting on our instructions:

- **Supabase**: database and sign-in. Region: Mumbai, India (ap-south-1).
- **Google Firebase Cloud Messaging** and **Apple Push Notification service**: delivering notifications.
- **Resend**: sending sign-in code emails.
- **Anthropic**: writing weekly summaries (only when used).
- **RevenueCat**, **Google Play** and **Apple App Store**: subscriptions and payments.

Some providers may process data outside India. We only share what each needs to do its job.

## 4. How long we keep data

- Account and care-team data: until you delete your account (or the team owner deletes the team).
- Device records: until you delete your account, or the push token stops working.
- Support messages: as long as needed to help you, and no longer than 24 months.
- Data on your device: until you delete it or uninstall the app.

## 5. Your rights and choices

You can:

- **Delete your account**: **Settings → Delete my account**. This removes your sign-in, the teams you own with all their shared records, your membership in other teams and your devices. See [Delete your account or data]({{ '/delete-account/' | relative_url }}) for other ways.
- **Delete data on your device**: **Settings → Delete all data**.
- **Export your data**: **Settings → Export my data** (a JSON file).
- **Turn notifications on or off**: offers are off unless you turn them on; all types can be changed in **Settings**.
- **Access, correct or erase** your personal data, **withdraw consent**, or **nominate** someone to exercise your rights: email us. We may need to confirm it's you.

**Grievances.** Our grievance officer is {{ site.grievance_officer }}, [{{ site.contact_email }}](mailto:{{ site.contact_email }}). We aim to reply within 7 days and will resolve grievances within 90 days. If you're not satisfied, you may complain to the Data Protection Board of India.

## 6. Security

Data is encrypted in transit (HTTPS). Database access is restricted per user and per team with row-level security. No system is perfectly secure; if a personal-data breach affects you, we will tell you and the Data Protection Board as the law requires.

## 7. Children

GoldenPaw is meant for adults (18+) caring for pets. We don't knowingly collect personal data from children. If you believe a child has given us personal data, contact us and we will delete it.

## 8. Changes

If we change this policy, we'll update the date above, and tell you in the app if the change is significant.
