package com.vgkontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
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

        findViewById<Button>(R.id.contactCareButton).setOnClickListener {
            openWhatsAppContactUs()
        }
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
