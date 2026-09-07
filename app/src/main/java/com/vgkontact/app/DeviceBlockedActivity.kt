package com.vgkontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Shown instead of the signup form when this device (by Android ID) has
 * already registered an account before - whether or not the app's local
 * data has since been cleared. Local data being wiped only resets what
 * this install remembers; the device itself is still recognized server-side,
 * so this screen stands in for a "log in" step this app doesn't otherwise have.
 *
 * There is no way to get past this screen except contacting customer care -
 * intentionally, so clearing data can never be used to register a second
 * account on the same phone.
 */
class DeviceBlockedActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REGISTERED_NUMBER = "registered_number"
        const val EXTRA_ATTEMPTED_NUMBER = "attempted_number"
    }

    // Kept identical to ProfileActivity/MainMenuActivity's contact number
    // so every "contact us" entry point in the app reaches the same place.
    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    private var registeredNumber: String? = null
    private var attemptedNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_blocked)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        registeredNumber = intent.getStringExtra(EXTRA_REGISTERED_NUMBER)
        attemptedNumber = intent.getStringExtra(EXTRA_ATTEMPTED_NUMBER)
        findViewById<TextView>(R.id.registeredNumberText).text =
            registeredNumber?.takeIf { it.isNotBlank() } ?: "—"

        findViewById<Button>(R.id.loginButton).setOnClickListener {
            logInAsExistingAccount(registeredNumber)
        }

        findViewById<Button>(R.id.contactCareButton).setOnClickListener {
            openWhatsAppContactUs()
        }
    }

    // "Logging in" here just means restoring this device's local registered
    // state from the record the database already has for it - there's no
    // separate account/session system in this app, so isRegistered() being
    // true is what makes every other screen treat this as a normal
    // returning user again.
    private fun logInAsExistingAccount(whatsapp: String?) {
        if (whatsapp.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't find your account. Please contact customer care.", Toast.LENGTH_LONG).show()
            return
        }
        UserPrefs.saveUser(this, whatsapp, referral = "")
        startActivity(Intent(this, PermissionSetupActivity::class.java))
        finish()
    }

    private fun openWhatsAppContactUs() {
        val oldNumber = registeredNumber?.takeIf { it.isNotBlank() } ?: "—"
        val newNumber = attemptedNumber?.takeIf { it.isNotBlank() }

        val text = if (newNumber != null) {
            "Hi VG Kontact, I need to change my number from $oldNumber to $newNumber"
        } else {
            "Hi VG Kontact, I need help with my device registration."
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
