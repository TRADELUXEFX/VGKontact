package com.vgkontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Shown instead of the signup form when this device did not return a
 * usable android_id. The one-account-per-device rule depends on that id
 * being present - a blank id can't be matched against anything, so
 * letting a blank-id signup through would silently defeat the rule for
 * that device. This screen stops the signup here and gives the person a
 * way to reach customer care instead of dead-ending on a repeating toast.
 */
class DeviceUnverifiedActivity : AppCompatActivity() {

    // Kept identical to DeviceBlockedActivity/ProfileActivity/MainMenuActivity's
    // contact number so every "contact us" entry point in the app reaches the
    // same place.
    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_unverified)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        findViewById<Button>(R.id.contactCareButton).setOnClickListener {
            openWhatsAppContactUs()
        }

        findViewById<Button>(R.id.tryAgainButton).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }
    }

    private fun openWhatsAppContactUs() {
        val message = Uri.encode("Hi VG Kontact, I'm trying to sign up but my device isn't being verified.")
        val uri = Uri.parse("https://wa.me/$CONTACT_US_WHATSAPP_NUMBER?text=$message")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
