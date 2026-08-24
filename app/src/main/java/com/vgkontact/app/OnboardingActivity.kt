package com.vgkontact.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class OnboardingActivity : AppCompatActivity() {

    private lateinit var whatsappInput: TextInputEditText
    private lateinit var referralInput: TextInputEditText
    private lateinit var continueButton: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (UserPrefs.isRegistered(this)) {
            startActivity(Intent(this, MainMenuActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)

        whatsappInput = findViewById(R.id.whatsappInput)
        referralInput = findViewById(R.id.referralInput)
        continueButton = findViewById(R.id.continueButton)
        progressBar = findViewById(R.id.progressBar)

        continueButton.setOnClickListener {
            val whatsapp = whatsappInput.text.toString().trim()
            val referral = referralInput.text.toString().trim()

            // Validate WhatsApp number
            if (!isValidNigerianPhone(whatsapp)) {
                whatsappInput.error = "Enter valid 11-digit Nigerian number"
                return@setOnClickListener
            }

            // Referral is optional, but if provided, must be valid
            if (referral.isNotEmpty() && !isValidNigerianPhone(referral)) {
                referralInput.error = "Enter valid 11-digit Nigerian number"
                return@setOnClickListener
            }

            continueButton.isEnabled = false
            progressBar.visibility = android.view.View.VISIBLE

            SheetSync.submit(whatsapp, referral, this) { success, message ->
                runOnUiThread {
                    progressBar.visibility = android.view.View.GONE
                    continueButton.isEnabled = true
                    if (success) {
                        UserPrefs.saveUser(this, whatsapp, referral)
                        startActivity(Intent(this, MainMenuActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(this, message ?: "Submission failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun isValidNigerianPhone(phone: String): Boolean {
        if (phone.length != 11) return false
        if (!phone.startsWith("0")) return false
        if (!phone.all { it.isDigit() }) return false
        
        // Complete Nigerian telecom prefixes - all carriers
        val validPrefixes = listOf(
            // MTN
            "0803", "0806", "0810", "0813", "0814", "0816",
            "0703", "0704", "0706", "0707",
            "0906", "0913", "0916",
            
            // Airtel
            "0801", "0807", "0811", "0815",
            "0701", "0708", "0802", "0808", "0812",
            "0901", "0902", "0904", "0907", "0911", "0912",
            
            // Glo
            "0805",
            "0705",
            "0905", "0915",
            
            // 9mobile (T2)
            "0809", "0817", "0818",
            "0908", "0909",
            
            // Legacy/Other
            "0819" // Starcomms
        )
        
        return validPrefixes.any { phone.startsWith(it) }
    }
}
