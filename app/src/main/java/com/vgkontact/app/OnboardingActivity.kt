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

    private lateinit var nameInput: TextInputEditText
    private lateinit var whatsappInput: TextInputEditText
    private lateinit var referralInput: TextInputEditText
    private lateinit var continueButton: android.widget.Button
    private lateinit var progressBar: ProgressBar
    private lateinit var creditText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (UserPrefs.isRegistered(this)) {
            startActivity(Intent(this, PermissionSetupActivity::class.java))
            finish()
            return
        }

        // The form always shows first - no on-open device check. The
        // database is the single source of truth for "one account per
        // device", enforced inside signup_and_assign_group() itself, so
        // there's no separate step here that a bad connection could skip.
        showOnboardingForm()
    }

    private fun showOnboardingForm() {
        setContentView(R.layout.activity_onboarding)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        nameInput = findViewById(R.id.nameInput)
        whatsappInput = findViewById(R.id.whatsappInput)
        referralInput = findViewById(R.id.referralInput)
        continueButton = findViewById(R.id.continueButton)
        progressBar = findViewById(R.id.progressBar)
        creditText = findViewById(R.id.creditText)

        PhoneNumberFormatter.attachTo(whatsappInput)
        // referralInput no longer gets the phone-digit formatter attached -
        // it now accepts either a name or a phone number, so it needs to
        // behave like a normal free-text field instead of being locked
        // into digit-only formatting.

        continueButton.setOnClickListener { onContinueClicked() }

        setupCreditLink()
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
        val name = nameInput.text.toString().trim()
        val whatsapp = PhoneNumberFormatter.rawDigits(whatsappInput.text.toString())
        val referralRaw = referralInput.text.toString().trim()

        if (name.isEmpty()) {
            nameInput.error = "Enter a username"
            return
        }

        if (name.any { it.isWhitespace() }) {
            nameInput.error = "No spaces allowed"
            return
        }

        if (!name.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
            nameInput.error = "Letters, numbers, _ and . only"
            return
        }

        if (!isValidNigerianPhone(whatsapp)) {
            whatsappInput.error = "Enter valid 11-digit Nigerian number"
            return
        }

        // Referral now accepts EITHER a phone number or a username (the
        // signup_and_assign_group() DB function resolves it against
        // both). If it looks like a phone-number attempt (has digits)
        // but isn't a valid Nigerian number, reject it early rather than
        // sending a malformed number to the server. A plain username
        // passes through untouched, but still can't contain spaces since
        // usernames never do.
        val referral = if (referralRaw.any { it.isDigit() }) {
            val referralDigits = PhoneNumberFormatter.rawDigits(referralRaw)
            if (!isValidNigerianPhone(referralDigits)) {
                referralInput.error = "Enter a valid number or username"
                return
            }
            referralDigits
        } else {
            if (referralRaw.any { it.isWhitespace() }) {
                referralInput.error = "Usernames have no spaces"
                return
            }
            referralRaw
        }

        // The database's one-account-per-device rule only works if
        // android_id is actually present - a blank id can't be matched
        // against anything, so it would slip straight past the unique
        // constraint. Blocking here, rather than letting it reach
        // Supabase, is what makes the rule airtight.
        //
        // Settings.Secure.ANDROID_ID has a known timing quirk on some
        // Android versions/devices: a read made very soon after process
        // start can transiently come back blank, then succeed moments
        // later with no other change. A single short retry absorbs that
        // window without weakening the "must have a real id" rule itself -
        // if both reads come back blank, this is treated as a genuine
        // failure, same as before. The retry's wait runs off the main
        // thread so a blank first read never freezes the UI.
        val firstAndroidId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )

        if (!firstAndroidId.isNullOrBlank()) {
            proceedWithSubmit(whatsapp, referral, name, firstAndroidId)
            return
        }

        continueButton.isEnabled = false
        continueButton.text = ""
        progressBar.visibility = View.VISIBLE

        Thread {
            Thread.sleep(150)
            val retryAndroidId = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            runOnUiThread {
                if (retryAndroidId.isNullOrBlank()) {
                    progressBar.visibility = View.GONE
                    continueButton.isEnabled = true
                    continueButton.text = getString(R.string.btn_continue)
                    startActivity(Intent(this, DeviceUnverifiedActivity::class.java))
                    finish()
                } else {
                    proceedWithSubmit(whatsapp, referral, name, retryAndroidId)
                }
            }
        }.start()
    }

    private fun proceedWithSubmit(whatsapp: String, referral: String, name: String, androidId: String) {
        continueButton.isEnabled = false
        continueButton.text = ""
        progressBar.visibility = View.VISIBLE

        // Tapping Submit is the only trigger for the block screen now.
        // signup_and_assign_group() refuses the insert outright when this
        // device has already registered, so the check happens inside the
        // same request that creates the account - not a separate step
        // that could fail independently of it.
        SheetSync.submit(whatsapp, referral, name, this, androidId) { success, message, registeredNumber ->
            runOnUiThread {
                progressBar.visibility = View.GONE
                continueButton.isEnabled = true
                continueButton.text = getString(R.string.btn_continue)
                when {
                    success -> {
                        UserPrefs.saveUser(this, whatsapp, referral, name)
                        startActivity(Intent(this, PermissionSetupActivity::class.java))
                        finish()
                    }
                    registeredNumber != null -> {
                        val intent = Intent(this, DeviceBlockedActivity::class.java)
                        intent.putExtra(DeviceBlockedActivity.EXTRA_REGISTERED_NUMBER, registeredNumber)
                        intent.putExtra(DeviceBlockedActivity.EXTRA_ATTEMPTED_NUMBER, whatsapp)
                        startActivity(intent)
                        finish()
                    }
                    message == "DEVICE_ID_UNAVAILABLE" -> {
                        Toast.makeText(this, "Couldn't verify this device. Please restart the app and try again.", Toast.LENGTH_LONG).show()
                    }
                    else -> {
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
