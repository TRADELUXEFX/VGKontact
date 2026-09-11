package com.vgkontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

/**
 * Reachable proactively from the dashboard (unlike DeviceBlockedActivity,
 * which only appears automatically when a blocked signup attempt happens).
 * Lets a user who already knows they need help - lost their old phone,
 * switched numbers, etc - start the same recovery conversation without
 * first having to go through a failed signup.
 *
 * Recovery itself is still fully manual: this screen only collects the
 * number and hands off to a WhatsApp chat with customer care, exactly like
 * DeviceBlockedActivity.openWhatsAppContactUs() does. There is no
 * automatic account-recovery logic here or on the server - your team
 * handles the actual recovery by hand on the other end of that chat.
 */
class RecoverAccountActivity : AppCompatActivity() {

    // Kept identical to DeviceBlockedActivity/ProfileActivity/MainMenuActivity's
    // contact number so every "contact us" entry point in the app reaches
    // the same place.
    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    private lateinit var recoveryPhoneInput: TextInputEditText
    private lateinit var contactCareButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recover_account)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        recoveryPhoneInput = findViewById(R.id.recoveryPhoneInput)
        contactCareButton = findViewById(R.id.contactCareButton)

        PhoneNumberFormatter.attachTo(recoveryPhoneInput)

        contactCareButton.setOnClickListener { onContinueClicked() }
    }

    private fun onContinueClicked() {
        val phone = PhoneNumberFormatter.rawDigits(recoveryPhoneInput.text.toString())

        if (!isValidNigerianPhone(phone)) {
            recoveryPhoneInput.error = "Enter a valid 11-digit Nigerian number"
            return
        }

        openWhatsAppContactUs(phone)
    }

    private fun openWhatsAppContactUs(phone: String) {
        val text = "Hi VG Kontact, I need help recovering my account. My number is $phone"
        val message = Uri.encode(text)
        val uri = Uri.parse("https://wa.me/$CONTACT_US_WHATSAPP_NUMBER?text=$message")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    // Same validation used in OnboardingActivity - kept identical so a
    // number accepted at signup is accepted here too, and vice versa.
    private fun isValidNigerianPhone(phone: String): Boolean {
        if (phone.length != 11) return false
        if (!phone.startsWith("0")) return false
        if (!phone.all { it.isDigit() }) return false

        val validPrefixes = listOf(
            "0803", "0806", "0810", "0813", "0814", "0816",
            "0703", "0704", "0706", "0707",
            "0906", "0913", "0916",
            "0801", "0807", "0811", "0815",
            "0701", "0708", "0802", "0808", "0812",
            "0901", "0902", "0904", "0907", "0911", "0912",
            "0805",
            "0705",
            "0905", "0915",
            "0809", "0817", "0818",
            "0908", "0909",
            "0819"
        )

        return validPrefixes.any { phone.startsWith(it) }
    }
}
