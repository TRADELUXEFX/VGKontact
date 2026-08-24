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

            if (whatsapp.isEmpty()) {
                whatsappInput.error = "WhatsApp number is required"
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
}
