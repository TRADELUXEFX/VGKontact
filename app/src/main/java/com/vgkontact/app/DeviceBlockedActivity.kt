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
 * Shown instead of the signup form for two different rejection reasons -
 * distinguished by EXTRA_REASON:
 *
 * REASON_DEVICE (default): this device (by Android ID) has already
 * registered an account before - whether or not the app's local data has
 * since been cleared. Local data being wiped only resets what this install
 * remembers; the device itself is still recognized server-side, so this
 * screen stands in for a "log in" step this app doesn't otherwise have.
 * The "Log in" button restores THIS device's own account, so it only makes
 * sense for this reason.
 *
 * REASON_NUMBER: a DIFFERENT device already holds the number this device
 * just tried. There's no account for this device to log into, so the
 * login button is hidden here - contacting customer care is the only
 * option, same as before.
 *
 * There is no way to get past this screen except contacting customer care -
 * intentionally, so clearing data can never be used to register a second
 * account on the same phone.
 */
class DeviceBlockedActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REGISTERED_NUMBER = "registered_number"
        const val EXTRA_ATTEMPTED_NUMBER = "attempted_number"
        const val EXTRA_REASON = "reason"
        const val REASON_DEVICE = "DEVICE"
        const val REASON_NUMBER = "NUMBER"
    }

    // Kept identical to ProfileActivity/MainMenuActivity's contact number
    // so every "contact us" entry point in the app reaches the same place.
    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    private var registeredNumber: String? = null
    private var attemptedNumber: String? = null
    private var reason: String = REASON_DEVICE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_blocked)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        registeredNumber = intent.getStringExtra(EXTRA_REGISTERED_NUMBER)
        attemptedNumber = intent.getStringExtra(EXTRA_ATTEMPTED_NUMBER)
        reason = intent.getStringExtra(EXTRA_REASON) ?: REASON_DEVICE

        findViewById<TextView>(R.id.registeredNumberText).text =
            registeredNumber?.takeIf { it.isNotBlank() } ?: "—"

        val loginButton = findViewById<Button>(R.id.loginButton)

        if (reason == REASON_NUMBER) {
            // A different device already holds this number - there's no
            // account on THIS device to log into, so no login button here,
            // only the copy and the contact-care fallback.
            findViewById<TextView>(R.id.headerLineOne).text = "This number is already"
            findViewById<TextView>(R.id.headerLineTwo).text = "Registered"
            findViewById<TextView>(R.id.registeredNumberLabel).text = "NUMBER YOU ENTERED"
            findViewById<TextView>(R.id.bodyText).text =
                "This number is already registered on a different device. Contact customer care to recover it."
            loginButton.visibility = android.view.View.GONE
        } else {
            loginButton.visibility = android.view.View.VISIBLE
            loginButton.setOnClickListener {
                logInAsExistingAccount(registeredNumber)
            }
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
    //
    // Fetches the real registered name from the server via
    // SheetSync.fetchRegisteredName() rather than saving a blank string -
    // previously this hardcoded name = "" unconditionally, which is why
    // returning users saw a blank/missing username after "logging in".
    private fun logInAsExistingAccount(whatsapp: String?) {
        if (whatsapp.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't find your account. Please contact customer care.", Toast.LENGTH_LONG).show()
            return
        }

        val loginButton = findViewById<Button>(R.id.loginButton)
        loginButton.isEnabled = false
        val originalButtonText = loginButton.text

        SheetSync.fetchRegisteredName(this, whatsapp) { fetchedName ->
            runOnUiThread {
                loginButton.isEnabled = true
                loginButton.text = originalButtonText

                if (fetchedName == null) {
                    Toast.makeText(
                        this,
                        "Couldn't retrieve your account details. Please check your connection or contact customer care.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }

                UserPrefs.saveUser(this, whatsapp, referral = "", name = fetchedName)
                startActivity(Intent(this, PermissionSetupActivity::class.java))
                finish()
            }
        }
    }

    private fun openWhatsAppContactUs() {
        val oldNumber = registeredNumber?.takeIf { it.isNotBlank() } ?: "—"
        val newNumber = attemptedNumber?.takeIf { it.isNotBlank() }

        val text = if (reason == REASON_NUMBER) {
            "Hi VG Kontact, I tried to sign up with $oldNumber but it says that number is already registered on another device. I need help recovering it."
        } else if (newNumber != null) {
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
