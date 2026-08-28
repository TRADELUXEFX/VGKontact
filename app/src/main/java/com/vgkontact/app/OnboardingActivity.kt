package com.vgkontact.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

class OnboardingActivity : AppCompatActivity() {

    private lateinit var whatsappInput: TextInputEditText
    private lateinit var referralInput: TextInputEditText
    private lateinit var continueButton: android.widget.Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (UserPrefs.isRegistered(this)) {
            startActivity(Intent(this, PermissionSetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        whatsappInput = findViewById(R.id.whatsappInput)
        referralInput = findViewById(R.id.referralInput)
        continueButton = findViewById(R.id.continueButton)
        progressBar = findViewById(R.id.progressBar)

        continueButton.setOnClickListener { onContinueClicked() }
    }

    private fun onContinueClicked() {
        val whatsapp = whatsappInput.text.toString().trim()
        val referral = referralInput.text.toString().trim()

        if (!isValidNigerianPhone(whatsapp)) {
            whatsappInput.error = "Enter valid 11-digit Nigerian number"
            return
        }

        if (referral.isNotEmpty() && !isValidNigerianPhone(referral)) {
            referralInput.error = "Enter valid 11-digit Nigerian number"
            return
        }

        continueButton.isEnabled = false
        continueButton.text = ""
        progressBar.visibility = View.VISIBLE

        SheetSync.submit(whatsapp, referral, this) { success, message ->
            runOnUiThread {
                progressBar.visibility = View.GONE
                continueButton.isEnabled = true
                continueButton.text = getString(R.string.btn_continue)
                if (success) {
                    UserPrefs.saveUser(this, whatsapp, referral)
                    startActivity(Intent(this, PermissionSetupActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, message ?: "Submission failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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
