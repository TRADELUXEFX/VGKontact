package com.vgkontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Shown instead of the signup form when this WhatsApp number OR this
 * device's android_id belongs to an account marked banned server-side
 * (see signup_and_assign_group()'s explicit ban check). Mirrors
 * DeviceBlockedActivity's structure - a dedicated full screen, not a
 * dialog, with no way to get past it except contacting customer care.
 */
class BannedActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ATTEMPTED_NUMBER = "attempted_number"
    }

    // Kept identical to DeviceBlockedActivity/ProfileActivity/MainMenuActivity's
    // contact number so every "contact us" entry point in the app reaches
    // the same place.
    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    private var attemptedNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_banned)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_red)

        attemptedNumber = intent.getStringExtra(EXTRA_ATTEMPTED_NUMBER)
            ?: UserPrefs.getWhatsapp(this)

        val accountNumber = attemptedNumber?.takeIf { it.isNotBlank() }
        if (accountNumber != null) {
            findViewById<TextView>(R.id.bannedNumberText)?.text = accountNumber
            findViewById<View>(R.id.bannedAccountSection)?.visibility = View.VISIBLE
        }

        // Show the specific reason when the server has one; otherwise the
        // generic list from the layout stays. The last known reason is
        // cached so it also shows offline.
        val reasonText = findViewById<TextView>(R.id.bannedReasonText)
        val defaultReasonText = reasonText?.text?.toString() ?: ""
        applyBanReason(reasonText, defaultReasonText, UserPrefs.getBanReason(this))
        SheetSync.fetchBanReason(applicationContext, attemptedNumber) { reason ->
            if (reason != null) {
                UserPrefs.setBanReason(applicationContext, reason)
                runOnUiThread { applyBanReason(reasonText, defaultReasonText, reason) }
            }
        }

        // Remove the contacts this app added to the phone. Sync stops for a
        // banned user before it reaches its normal clean-up step, so they'd
        // otherwise stay forever. Safe to repeat: does nothing if there are
        // none, and does nothing without contacts permission (e.g. a
        // banned number at signup, before any permission was granted).
        Thread {
            try {
                SheetSync.deleteAllSyncedContacts(applicationContext)
            } catch (e: Exception) {
                // No permission or provider error - nothing more to do here.
            }
        }.start()

        findViewById<Button>(R.id.contactCareButton).setOnClickListener {
            openWhatsAppContactUs()
        }
    }

    private fun applyBanReason(view: TextView?, defaultText: String, code: String?) {
        if (view == null) return
        val line = when (code) {
            "multiple_accounts" -> "Using more than one account"
            "deleted_contacts" -> "Deleting the contacts from your phone after getting them"
            "scam" -> "Scamming or misusing the app"
            else -> null
        }
        view.text = line ?: defaultText
    }

    override fun onResume() {
        super.onResume()
        // If support lifted the ban, let the user back in without a reinstall.
        if (UserPrefs.isRegistered(this)) {
            SheetSync.checkBanStatus(this) { banned ->
                if (banned == false) {
                    runOnUiThread {
                        startActivity(
                            Intent(this, MainMenuActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                        finish()
                    }
                }
            }
        }
    }

    // A banned user has nowhere to go back to - leave the app instead of
    // popping back into the dashboard.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishAffinity()
    }

    private fun openWhatsAppContactUs() {
        val number = attemptedNumber?.takeIf { it.isNotBlank() }
        val text = if (number != null) {
            "Hi VG Kontact, my account ($number) has been banned. I'd like to appeal this."
        } else {
            "Hi VG Kontact, my account has been banned. I'd like to appeal this."
        }
        val message = Uri.encode(text)
        val uri = Uri.parse("https://wa.me/$CONTACT_US_WHATSAPP_NUMBER?text=$message")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
