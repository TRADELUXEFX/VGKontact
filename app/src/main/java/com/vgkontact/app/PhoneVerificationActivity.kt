package com.vgkontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Shown right after onboarding submits the WhatsApp number.
 *
 * Flow:
 * 1. Generate a code via Termii's In-App Token API (SheetSync.generateOtp).
 * 2. Open WhatsApp with that code pre-filled, addressed to
 *    SheetSync.VERIFICATION_WHATSAPP_NUMBER, and ask the user to hit Send.
 * 3. User comes back to the app and taps "I've sent it" - we verify the
 *    pin against Termii (SheetSync.verifyOtp).
 *
 * This proves the user actually controls the WhatsApp number they signed
 * up with, since the code has to travel over a real WhatsApp message from
 * that number to be confirmed. Right now confirmation on the receiving end
 * is manual (check the WhatsApp inbox for VERIFICATION_WHATSAPP_NUMBER) -
 * Termii's verify call only confirms the code is correct and unexpired,
 * not that the WhatsApp message itself arrived.
 */
class PhoneVerificationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WHATSAPP = "extra_whatsapp"
    }

    private lateinit var whatsapp: String
    private var pinId: String? = null
    private var generatedCode: String? = null
    private var hasOpenedWhatsApp = false

    private lateinit var subtitleText: TextView
    private lateinit var codeText: TextView
    private lateinit var openWhatsAppButton: Button
    private lateinit var confirmSentButton: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_verification)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        whatsapp = intent.getStringExtra(EXTRA_WHATSAPP) ?: run {
            finish()
            return
        }

        subtitleText = findViewById(R.id.subtitleText)
        codeText = findViewById(R.id.otpInput) // repurposed as a read-only code display
        openWhatsAppButton = findViewById(R.id.verifyButton)
        confirmSentButton = findViewById(R.id.resendText)
        progressBar = findViewById(R.id.progressBar)

        codeText.isEnabled = false
        subtitleText.text = "Generating your verification code..."
        openWhatsAppButton.text = "Open WhatsApp"
        confirmSentButton.text = "I've sent it - Verify"
        confirmSentButton.isEnabled = false
        confirmSentButton.alpha = 0.5f

        openWhatsAppButton.setOnClickListener { onOpenWhatsAppClicked() }
        confirmSentButton.setOnClickListener { onConfirmSentClicked() }

        generateCode()
    }

    private fun generateCode() {
        setLoading(true)
        SheetSync.generateOtp(whatsapp) { success, pin, code, error ->
            runOnUiThread {
                setLoading(false)
                if (success && pin != null && code != null) {
                    pinId = pin
                    generatedCode = code
                    codeText.text = code
                    subtitleText.text = "Tap below to send this code to us on WhatsApp"
                    // confirmSentButton stays disabled here - it only turns
                    // on once the user has actually tapped Open WhatsApp,
                    // see onOpenWhatsAppClicked().
                } else {
                    subtitleText.text = "Couldn't generate a code"
                    Toast.makeText(this, error ?: "Please try again", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onOpenWhatsAppClicked() {
        val code = generatedCode ?: return
        val target = SheetSync.VERIFICATION_WHATSAPP_NUMBER
        val message = Uri.encode("My verification code is $code")
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$target?text=$message"))
            startActivity(intent)
            hasOpenedWhatsApp = true
            confirmSentButton.isEnabled = true
            confirmSentButton.alpha = 1f
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onConfirmSentClicked() {
        val pin = pinId ?: return
        val code = generatedCode ?: return
        setLoading(true)
        SheetSync.verifyOtp(pin, code) { success, message ->
            runOnUiThread {
                setLoading(false)
                if (success) {
                    startActivity(Intent(this, PermissionSetupActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, message ?: "Verification failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        openWhatsAppButton.isEnabled = !loading
        confirmSentButton.isEnabled = !loading && hasOpenedWhatsApp
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
