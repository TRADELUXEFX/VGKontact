package com.vgkontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

class OnboardingActivity : AppCompatActivity() {

    private lateinit var whatsappInput: TextInputEditText
    private lateinit var referralInput: TextInputEditText
    private lateinit var continueButton: android.widget.Button
    private lateinit var progressBar: ProgressBar
    private lateinit var creditText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showPreviousCrashIfAny()

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
        creditText = findViewById(R.id.creditText)

        PhoneNumberFormatter.attachTo(whatsappInput)
        PhoneNumberFormatter.attachTo(referralInput)

        continueButton.setOnClickListener { onContinueClicked() }

        setupCreditLink()
    }

    // TEMPORARY DEBUG TOOL - shows the last crash (if any) as an on-screen
    // popup with a Copy button, then deletes the log so it only shows once.
    // Remove this whole function and its call in onCreate() once the crash
    // is fixed - it's not meant to ship.
    private fun showPreviousCrashIfAny() {
        val logFile = java.io.File(filesDir, CrashLoggingApplication.CRASH_LOG_FILENAME)
        if (!logFile.exists()) return

        val crashText = try {
            logFile.readText()
        } catch (e: Exception) {
            return
        }
        logFile.delete()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Last crash")
            .setMessage(crashText)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Crash log", crashText))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss", null)
            .setCancelable(false)
            .show()
    }

    private fun setupCreditLink() {
        val fullText = creditText.text.toString()
        val linkWord = "VGKontact"
        val startIndex = fullText.indexOf(linkWord)
        if (startIndex == -1) return

        val endIndex = startIndex + linkWord.length
        val spannable = SpannableString(fullText)

        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.vg_green)),
            startIndex,
            endIndex,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        spannable.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://vgkontact.netlify.app"))
                    startActivity(intent)
                }
            },
            startIndex,
            endIndex,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        creditText.text = spannable
        creditText.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun onContinueClicked() {
        val whatsapp = PhoneNumberFormatter.rawDigits(whatsappInput.text.toString())
        val referral = PhoneNumberFormatter.rawDigits(referralInput.text.toString())

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
                    val intent = Intent(this, PhoneVerificationActivity::class.java)
                    intent.putExtra(PhoneVerificationActivity.EXTRA_WHATSAPP, whatsapp)
                    startActivity(intent)
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
