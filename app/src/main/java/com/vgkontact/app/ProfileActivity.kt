package com.vgkontact.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class ProfileActivity : AppCompatActivity() {

    private lateinit var profileNumberText: TextView
    private lateinit var profileReferralText: TextView
    private lateinit var profileDateRegisteredText: TextView

    private lateinit var syncStatusBadge: LinearLayout
    private lateinit var syncStatusIcon: ImageView
    private lateinit var syncStatusText: TextView
    private lateinit var lastSyncedText: TextView

    private lateinit var verificationStatusBadge: TextView

    private lateinit var appVersionText: TextView

    private lateinit var myReferralCodeText: TextView
    private lateinit var copyReferralCodeButton: Button
    private lateinit var profileContactUsButton: Button

    // Same WhatsApp support number the dashboard's Contact Us button uses
    // (see MainMenuActivity.CONTACT_US_WHATSAPP_NUMBER) - kept identical so
    // both entry points reach the same place.
    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        profileNumberText = findViewById(R.id.profileNumberText)
        profileReferralText = findViewById(R.id.profileReferralText)
        profileDateRegisteredText = findViewById(R.id.profileDateRegisteredText)

        syncStatusBadge = findViewById(R.id.syncStatusBadge)
        syncStatusIcon = findViewById(R.id.syncStatusIcon)
        syncStatusText = findViewById(R.id.syncStatusText)
        lastSyncedText = findViewById(R.id.lastSyncedText)

        verificationStatusBadge = findViewById(R.id.verificationStatusBadge)

        appVersionText = findViewById(R.id.appVersionText)

        myReferralCodeText = findViewById(R.id.myReferralCodeText)
        copyReferralCodeButton = findViewById(R.id.copyReferralCodeButton)
        profileContactUsButton = findViewById(R.id.profileContactUsButton)

        val whatsapp = UserPrefs.getWhatsapp(this)

        // Number
        profileNumberText.text = whatsapp ?: "N/A"

        // Referred By - the number that referred *this* user (if any)
        val referredBy = UserPrefs.getReferral(this)
        profileReferralText.text = if (referredBy.isNullOrEmpty()) "None" else referredBy

        // Date Registered
        profileDateRegisteredText.text = UserPrefs.getDateRegistered(this) ?: "N/A"

        // Sync Status - derived live, same source the dashboard banner uses,
        // so this never drifts out of sync with what the user sees there.
        bindSyncStatus()

        // Verification Status - mirrors the same three-permission check the
        // dashboard uses, so "Verified" here always means the same thing it
        // means everywhere else in the app.
        bindVerificationStatus()

        // App Version - pulled from BuildConfig so it can never go stale;
        // no manual string to remember to bump on release.
        appVersionText.text = "VGKontact v${BuildConfig.VERSION_NAME}"

        // My Referral Code - this is simply the user's own WhatsApp number.
        // Other people type this in during their own signup to credit this
        // user as the referrer. There is no separate generated code.
        val myCode = whatsapp ?: "N/A"
        myReferralCodeText.text = myCode
        copyReferralCodeButton.setOnClickListener {
            copyToClipboard(myCode)
        }

        profileContactUsButton.setOnClickListener {
            openWhatsAppContactUs()
        }
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

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("VGKontact Referral Code", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Referral code copied", Toast.LENGTH_SHORT).show()
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
}
