package com.vgkontact.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

    private lateinit var totalKontactsText: TextView

    private lateinit var verificationStatusBadge: TextView

    private lateinit var appVersionText: TextView

    private lateinit var myReferralCodeText: TextView
    private lateinit var copyReferralCodeButton: Button

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

        totalKontactsText = findViewById(R.id.totalKontactsText)

        verificationStatusBadge = findViewById(R.id.verificationStatusBadge)

        appVersionText = findViewById(R.id.appVersionText)

        myReferralCodeText = findViewById(R.id.myReferralCodeText)
        copyReferralCodeButton = findViewById(R.id.copyReferralCodeButton)

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

        // Total Kontacts Registered
        totalKontactsText.text = UserPrefs.getContactCounter(this).toString()

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

        if (contactsGranted && todaySyncedCount > 0) {
            syncStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.vg_green))
            syncStatusText.text = "Synced"
            syncStatusText.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
            lastSyncedText.text = "Last synced: Today"
        } else if (!contactsGranted) {
            syncStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.warning_red))
            syncStatusText.text = "Off"
            syncStatusText.setTextColor(ContextCompat.getColor(this, R.color.warning_red))
            lastSyncedText.text = "Contacts permission is off"
        } else {
            syncStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
            syncStatusText.text = "Idle"
            syncStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            lastSyncedText.text = "No contacts synced yet today"
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
}
