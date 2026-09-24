package com.vgkontact.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class ProfileActivity : BaseActivity() {

    private lateinit var profileUsernameText: TextView
    private lateinit var profileUsernameCopyIcon: LinearLayout
    private lateinit var profileNumberText: TextView
    private lateinit var profileReferralNameText: TextView
    private lateinit var loadingOverlay: View
    private lateinit var profileReferralNumberBadge: TextView
    private lateinit var profileDateRegisteredText: TextView

    private lateinit var syncStatusBadge: LinearLayout
    private lateinit var syncStatusIcon: ImageView
    private lateinit var syncStatusText: TextView
    private lateinit var lastSyncedText: TextView

    private lateinit var verificationStatusBadge: TextView

    private lateinit var appVersionText: TextView

    private lateinit var profileContactUsButton: Button

    // Same WhatsApp support number the dashboard's Contact Us button uses
    // (see MainMenuActivity.CONTACT_US_WHATSAPP_NUMBER) - kept identical so
    // both entry points reach the same place.
    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        FloatingContactHelper.attach(this)
        // Profile is no longer a bottom-nav destination - it's reached via
        // the profile icon in the dashboard header instead - so no tab is
        // shown as active here.
        BottomNavHelper.setup(this, null)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        profileUsernameText = findViewById(R.id.profileUsernameText)
        profileUsernameCopyIcon = findViewById(R.id.profileUsernameCopyIcon)
        profileNumberText = findViewById(R.id.profileNumberText)
        profileReferralNameText = findViewById(R.id.profileReferralNameText)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        profileReferralNumberBadge = findViewById(R.id.profileReferralNumberBadge)
        profileDateRegisteredText = findViewById(R.id.profileDateRegisteredText)

        syncStatusBadge = findViewById(R.id.syncStatusBadge)
        syncStatusIcon = findViewById(R.id.syncStatusIcon)
        syncStatusText = findViewById(R.id.syncStatusText)
        lastSyncedText = findViewById(R.id.lastSyncedText)

        verificationStatusBadge = findViewById(R.id.verificationStatusBadge)

        appVersionText = findViewById(R.id.appVersionText)

        profileContactUsButton = findViewById(R.id.profileContactUsButton)

        val whatsapp = UserPrefs.getWhatsapp(this)

        // Username
        val name = UserPrefs.getName(this)
        profileUsernameText.text = if (name.isNullOrEmpty()) "N/A" else name

        // Copy username - lets the user share their own username with others
        // as a signup referral, same pattern as the referral-code copy below,
        // just a different label/clip/toast for this specific field.
        profileUsernameCopyIcon.setOnClickListener {
            val username = profileUsernameText.text?.toString().orEmpty()
            if (username.isNotBlank() && username != "N/A") {
                copyToClipboard(username, clipLabel = "VGKontact Username", toastMessage = "Username copied")
            }
        }

        // Number
        profileNumberText.text = whatsapp ?: "N/A"

        // Referred By - the upline's name goes in the main text, their
        // number in a separate pill badge (matches the Number+Verification
        // row above it: weight-1 text + trailing badge), rather than
        // parenthetical text inline with the name.
        //
        // Fetched live from the database rather than the local cache so
        // this can never drift out of sync with the real row - e.g. if
        // it's ever corrected or edited directly in Supabase later.
        //
        // The number badge is hidden (View.GONE, not just empty text)
        // whenever there's nothing meaningful to show in it - no referral
        // at all, or the name lookup hasn't resolved yet - rather than
        // showing an empty or half-loaded pill.
        // The white loading screen covers the whole profile until the
        // "Referred By" name is known, so the user never sees it filling
        // in after the screen has already opened. It is lifted from ONE
        // place (finishReferralLoad) reached by every outcome below -
        // no referral, name found, name lookup failed, or the request
        // itself failing - so it can never get stuck on white.
        profileReferralNameText.text = ""
        profileReferralNumberBadge.visibility = View.GONE

        // Show the remembered referrer instantly (no white screen) when we
        // already know it. First ever open has nothing saved, so it does the
        // server lookup below exactly as before and saves the result.
        val cachedReferrer = UserPrefs.getCachedReferrer(this)
        val noReferrerKnown = cachedReferrer == null && UserPrefs.hasNoReferrerConfirmed(this)
        val shownFromCache = cachedReferrer != null || noReferrerKnown
        if (cachedReferrer != null) {
            profileReferralNameText.text = cachedReferrer.second
            profileReferralNumberBadge.text = cachedReferrer.first
            profileReferralNumberBadge.visibility = View.VISIBLE
        } else if (noReferrerKnown) {
            profileReferralNameText.text = "None"
        } else {
            loadingOverlay.visibility = View.VISIBLE
        }

        // Saved name found: nothing to ask the server. Only the very first
        // open (nothing saved yet) does the lookup, and saves the result.
        if (!shownFromCache) SheetSync.fetchMyProfile(this) { referral, error ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (referral.isNullOrEmpty()) {
                    profileReferralNameText.text = "None"
                    // Only remember "none" when the server actually answered
                    // (no error) - a failed request must not be saved as "none".
                    if (error == null && !UserPrefs.getWhatsapp(this).isNullOrEmpty()) {
                        UserPrefs.setNoReferrerConfirmed(this)
                    }
                    finishReferralLoad()
                } else {
                    SheetSync.fetchNameForWhatsapp(referral) { name ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (!name.isNullOrBlank()) {
                                profileReferralNameText.text = name
                                profileReferralNumberBadge.text = referral
                                profileReferralNumberBadge.visibility = View.VISIBLE
                                UserPrefs.setCachedReferrerName(this, referral, name)
                            } else {
                                // Name lookup genuinely failed/returned nothing -
                                // fall back to the bare number. Badge stays hidden
                                // since showing the same number twice would be
                                // redundant.
                                profileReferralNameText.text = referral
                            }
                            finishReferralLoad()
                        }
                    }
                }
            }
        }

        // Date Registered
        profileDateRegisteredText.text = UserPrefs.getDateRegistered(this) ?: "N/A"

        // Sync Status - derived live, same source the dashboard banner uses,
        // so this never drifts out of sync with what the user sees there.
        bindSyncStatus()

        // Verification Status - mirrors the same three-permission check the
        // dashboard uses, so "Verified" here always means the same thing it
        // means everywhere else in the app.
        bindVerificationStatus()
        verificationStatusBadge.setOnClickListener {
            fixWorstPermissionIssue(PermissionHealth.check(this))
        }

        // App Version - pulled from BuildConfig so it can never go stale;
        // no manual string to remember to bump on release.
        appVersionText.text = "VGKontact v${BuildConfig.VERSION_NAME}"

        profileContactUsButton.setOnClickListener {
            openWhatsAppContactUs()
        }
    }

    /** Lifts the white loading screen once the "Referred By" row is filled in. */
    private fun finishReferralLoad() {
        loadingOverlay.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        // Permissions (and therefore verification/sync status) can change
        // while the user is away in system Settings, so refresh on return.
        bindSyncStatus()
        bindVerificationStatus()
    }

    private fun bindSyncStatus() {
        val contactsGranted = PermissionHealth.check(this).contactsGranted
        val todaySyncedCount = UserPrefs.getTodaySyncedCount(this)
        val lastSyncText = UserPrefs.getLastSyncDisplayText(this)

        if (contactsGranted && todaySyncedCount > 0) {
            syncStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.vg_green))
            syncStatusText.text = "Synced"
            syncStatusText.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
            lastSyncedText.text = if (lastSyncText != null) "Last synced: $lastSyncText" else "Last synced: Today"
        } else if (!contactsGranted) {
            syncStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.warning_red))
            syncStatusText.text = "Off"
            syncStatusText.setTextColor(ContextCompat.getColor(this, R.color.warning_red))
            lastSyncedText.text = "Contacts permission is off"
        } else {
            syncStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
            syncStatusText.text = "Idle"
            syncStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            lastSyncedText.text = if (lastSyncText != null) "Last synced: $lastSyncText" else "No contacts synced yet"
        }
    }

    private fun bindVerificationStatus() {
        val status = PermissionHealth.check(this)
        val isVerified = status.contactsGranted && status.notificationsGranted && status.batteryExempted

        // Same live stage report as MainMenuActivity - Profile is another
        // place the dashboard's permission state gets checked, so it needs
        // to report too, or the stage in the database could go stale
        // whenever a user only ever opens Profile and not the main
        // dashboard in a given session.
        SheetSync.reportSetupStage(this, status.stage)

        if (isVerified) {
            verificationStatusBadge.text = "Verified"
            verificationStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
            verificationStatusBadge.setBackgroundResource(R.drawable.badge_verified_background)
        } else {
            verificationStatusBadge.text = "Unverified"
            verificationStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.warning_red))
            verificationStatusBadge.setBackgroundResource(R.drawable.group_badge_background)
        }
    }

    private fun copyToClipboard(
        text: String,
        clipLabel: String = "VGKontact Referral Code",
        toastMessage: String = "Referral code copied"
    ) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(clipLabel, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
    }

    private fun openWhatsAppContactUs() {
        val message = Uri.encode("Hi VG Kontact, I need help with...")
        val uri = Uri.parse("https://wa.me/$CONTACT_US_WHATSAPP_NUMBER?text=$message")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Tapping the "Unverified" badge should fix whatever's actually wrong,
     * same as the dashboard's amber banner "FIX" button - not just relabel
     * itself. Mirrors MainMenuActivity.fixWorstPermissionIssue() exactly:
     * contacts and notifications get the native permission popup (falling
     * back to Settings only once permanently denied), battery always goes
     * to Settings since Android has no popup for that one.
     */
    private fun fixWorstPermissionIssue(status: PermissionHealth.Status) {
        when {
            !status.contactsGranted -> requestContactsPermission()
            !status.batteryExempted -> PermissionHealth.openFixForWorstIssue(this, status)
            !status.notificationsGranted -> requestNotificationPermission()
            else -> PermissionHealth.openFixForWorstIssue(this, status)
        }
    }

    private fun requestContactsPermission() {
        // Same permanently-denied check as MainMenuActivity - once denied
        // twice (or "Don't ask again"), Android stops showing the popup
        // and silently no-ops, so route to Settings instead of a dead tap.
        val permanentlyDenied =
            (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_CONTACTS) == false &&
             ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) ||
            (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_CONTACTS) == false &&
             ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)

        if (permanentlyDenied) {
            openAppSettings("Enable Contacts under Permissions, then come back")
            return
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS),
            PERMISSION_REQUEST_CODE
        )
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return // not applicable pre-Android 13
        }

        val permanentlyDenied =
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS) == false &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

        if (permanentlyDenied) {
            openAppSettings("Enable Notifications under Permissions, then come back")
            return
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE
        )
    }

    private fun openAppSettings(instruction: String) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            Toast.makeText(this, instruction, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open Settings - please enable it manually", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Whatever the result, just refresh the badge - bindVerificationStatus()
        // re-checks all three permissions live, so it naturally reflects
        // whether this specific grant/deny actually fixed anything.
        bindSyncStatus()
        bindVerificationStatus()
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 3001
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 3002
    }
}
