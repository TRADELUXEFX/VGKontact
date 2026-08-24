# VG ADDUP

Android source project (Kotlin). This is not a compiled .apk — open it in
Android Studio to build one yourself.

## What it does

1. On launch, asks for the user's **WhatsApp Number** and a **Referral
   WhatsApp Number** (with a "Pick from Contacts" button for the referral
   field — this is the only reason Contacts permission is requested).
2. Requests **Contacts** and **Notifications** permission.
3. Sends `whatsapp`, `referral`, and a `timestamp` to a Google Sheet.
4. Opens the Main Menu screen, showing the user's own number and two
   actions:
   - **Sync Kontact** — previews the first 10 Kontacts synced so far
     (contact icon + WhatsApp number).
   - **⚙ Settings** — opens the Settings screen (My Profile, Activity
     History, Settings, Contact Us).
5. Numbers are saved once during onboarding and are permanent — there is
   no edit/resync screen.

## Setup

### 1. Open the project
Open this folder in Android Studio ("Open" → select the `VGKontact` folder).
Android Studio will offer to generate the Gradle wrapper on first sync — accept it.

### 2. Wire up the Google Sheet
"Google Docs" isn't well suited to storing rows of data — this uses a
**Google Sheet** instead, which is what actually holds tabular data like
this. The Sheet is populated by a small Apps Script Web App:

1. Create a new Google Sheet. Add a header row: `WhatsApp Number | Referral Number | Timestamp`
2. In the Sheet: **Extensions → Apps Script**, paste in `apps-script/Code.gs`
   from this project.
3. **Deploy → New deployment → Web app**
   - Execute as: **Me**
   - Who has access: **Anyone**
4. Copy the deployment URL and paste it into `ENDPOINT_URL` in
   `app/src/main/java/com/vgkontact/app/SheetSync.kt`.

### 3. Build the APK
Build → Build Bundle(s) / APK(s) → Build APK(s).

## Notes

- Minimum SDK 24 (Android 7.0).
- The app icon is a placeholder vector — swap in your real logo via
  Image Asset Studio (right-click `res` → New → Image Asset).
- Permissions are requested but not required to continue — if a user denies
  Contacts, the referral field just has to be typed in manually.
