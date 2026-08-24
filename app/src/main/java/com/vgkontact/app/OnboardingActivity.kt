package com.vgkontact.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class OnboardingActivity : AppCompatActivity() {

    private var etWhatsapp: EditText? = null
    private var etReferral: EditText? = null
    private var btnSubmit: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (UserPrefs.isRegistered(this)) {
            startActivity(Intent(this, MainMenuActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)

        etWhatsapp = findViewById(R.id.etWhatsapp) ?: findViewById(R.id.et_whatsapp)
        etReferral = findViewById(R.id.etReferral) ?: findViewById(R.id.et_referral)
        btnSubmit = findViewById(R.id.btnSubmit) ?: findViewById(R.id.btn_submit)

        btnSubmit?.setOnClickListener {
            val whatsapp = etWhatsapp?.text?.toString()?.trim() ?: ""
            val referral = etReferral?.text?.toString()?.trim() ?: ""

            if (whatsapp.isEmpty()) {
                etWhatsapp?.error = "WhatsApp number is required"
                return@setOnClickListener
            }

            btnSubmit?.isEnabled = false

            SheetSync.submit(whatsapp, referral, this) { success, message ->
                runOnUiThread {
                    btnSubmit?.isEnabled = true
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
