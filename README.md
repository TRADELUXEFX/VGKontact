# VG ADDUP

Android source project (Kotlin). This is not a compiled .apk — open it in
Android Studio to build one yourself.

## What it does

1. **Onboarding** — asks for the user's **name**, **WhatsApp Number**, and an
   optional **Referral WhatsApp Number** (with a "Pick from Contacts" button
   for the referral field). Signup and group assignment happen together via
   a Supabase RPC (`signup_and_assign_group`); one account per device is
   enforced server-side using the device's Android ID.
2. **Permission setup** — a one-time screen (right after registration) that
   requests **Contacts**, **Notifications**, and a battery-optimization
   exemption. Denying any of these doesn't block the user — a device with
   Contacts denied is marked **UNVERIFIED** instead of **VERIFIED**, shown as
   a badge on the main menu.
3. **Main menu / dashboard** — shows the user's VERIFIED/UNVERIFIED status,
   a contact-limit meter, and:
   - **Sync Kontact** — reads the user's group(s) from Supabase and adds any
     new contacts to the phone's address book (named `<name> VGK<n>`),
     automatically in the background roughly every 24 hours (configurable)
     via a WorkManager job (`SheetCheckWorker`), and on demand.
   - **Increase Contact Limit** — a merged screen for two ways to raise the
     per-device contact cap: sharing the user's referral link (tracked on
     a leaderboard), or redeeming an admin-issued key that unlocks
     additional contact groups.
   - **Activity Log** — a feed of sync events, limit warnings, and
     permission-health changes.
   - **Notification settings** — lets the user pick how often background
     sync checks run (1 / 6 / 12 / 24 hours).
   - **Profile** — shows the user's saved details; also where "Delete My
     Contacts" (removes every VGK-tagged contact from the phone and pauses
     syncing) and account recovery live.
4. All user-facing state is backed by [Supabase](https://supabase.com)
   (Postgres + PostgREST), not a Google Sheet — see Setup below.

## Setup

### 1. Open the project
Open this folder in Android Studio ("Open" → select the `VGKontact` folder).
Android Studio will offer to generate the Gradle wrapper on first sync — accept it.

### 2. Wire up Supabase
This app stores data in [Supabase](https://supabase.com) (Postgres), accessed
via its REST API. An earlier version of this project used a Google Sheet
plus an Apps Script Web App for this — that approach has been fully
retired and removed; all sync, signup, and referral logic now goes through
Supabase only.

1. Create a Supabase project and set up the `contacts` (and related) tables
   and RPC functions this app calls (see `SheetSync.kt` for the exact table
   names and RPC endpoints it expects, e.g. `signup_and_assign_group`,
   `redeem_key`, `record_sync_checkin`).
2. **Set Row Level Security (RLS) policies on every table** the anon key can
   reach. The Android app authenticates purely by passing values like
   `whatsapp` and `android_id` as request parameters — it does not use
   per-user Supabase auth sessions — so RLS (or equivalent checks inside
   your RPC functions) is the only thing standing between the public anon
   key and other users' data. Don't skip this step.
3. Add `SUPABASE_URL` and `SUPABASE_ANON_KEY` to a `local.properties` file
   in the project root (or as environment variables) — these are read by
   `app/build.gradle` into `BuildConfig` and consumed in
   `app/src/main/java/com/vgkontact/app/SheetSync.kt`.

### 3. Build the APK
Build → Build Bundle(s) / APK(s) → Build APK(s).

## Notes

- Minimum SDK 24 (Android 7.0).
- The app icon is a placeholder vector — swap in your real logo via
  Image Asset Studio (right-click `res` → New → Image Asset).
- Permissions are requested but not required to continue — if a user denies
  Contacts, the referral field just has to be typed in manually.
