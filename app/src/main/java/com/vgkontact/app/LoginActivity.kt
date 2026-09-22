package com.vgkontact.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

/**
 * Reached from the onboarding screen's "Already have an account? Log in"
 * link, and (via Option A) automatically when a signup attempt comes back
 * NUMBER_ALREADY_REGISTERED - in both cases the user only has to enter
 * their WhatsApp number, never a password: the server treats THIS DEVICE's
 * Android ID as the credential.
 *
 * Calls rpc/login_check(p_whatsapp, p_android_id) and branches on its four
 * possible outcomes:
 *   MATCH               -> this really is that account's own device. Fetch
 *                          the registered name (reusing
 *                          SheetSync.fetchRegisteredName(), the same call
 *                          DeviceBlockedActivity's login button already
 *                          uses) and log straight into the dashboard.
 *   NUMBER_NOT_FOUND     -> this number was never registered - nothing to
 *                          log into. Shown inline, no navigation.
 *   ANDROID_ID_MISMATCH  -> the number exists but belongs to a different
 *                          device. Routed to DeviceBlockedActivity with
 *                          REASON_NUMBER, the exact same "contact customer
 *                          care" screen a blocked signup attempt already
 *                          shows, so there is only one such screen in the
 *                          app to maintain.
 *   BANNED               -> routed to BannedActivity, same as everywhere
 *                          else banned status surfaces.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var loginPhoneInput: TextInputEditText
    private lateinit var loginButton: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        loginPhoneInput = findViewById(R.id.loginPhoneInput)
        loginButton = findViewById(R.id.loginButton)
        progressBar = findViewById(R.id.progressBar)

        PhoneNumberFormatter.attachTo(loginPhoneInput)

        // Prefilled when arriving here from a NUMBER_ALREADY_REGISTERED
        // signup attempt (Option A) - the number the user just typed on
        // the onboarding form, formatted the same way the field displays
        // it as they type.
        intent.getStringExtra(EXTRA_PREFILL_WHATSAPP)?.let { raw ->
            loginPhoneInput.setText(PhoneNumberFormatter.format(raw))
            loginPhoneInput.setSelection(loginPhoneInput.text?.length ?: 0)
        }

        loginButton.setOnClickListener { onLoginClicked() }
    }

    private fun onLoginClicked() {
        val whatsapp = PhoneNumberFormatter.rawDigits(loginPhoneInput.text.toString())

        if (!isValidNigerianPhone(whatsapp)) {
            loginPhoneInput.error = "Enter a valid 11-digit Nigerian number"
            return
        }

        val androidId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )

        if (androidId.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't verify this device. Please restart the app and try again.", Toast.LENGTH_LONG).show()
            return
        }

        setLoading(true)

        SheetSync.loginCheck(whatsapp, androidId) { outcome ->
            runOnUiThread {
                when (outcome) {
                    SheetSync.LoginOutcome.MATCH -> {
                        // Loading state intentionally stays on through this
                        // second call - logInAsMatchedAccount() clears it
                        // itself once fetchRegisteredName() returns.
                        logInAsMatchedAccount(whatsapp)
                    }
                    SheetSync.LoginOutcome.NUMBER_NOT_FOUND -> {
                        setLoading(false)
                        loginPhoneInput.error = "No account found with this number"
                    }
                    SheetSync.LoginOutcome.ANDROID_ID_MISMATCH -> {
                        setLoading(false)
                        val intent = Intent(this, DeviceBlockedActivity::class.java)
                        intent.putExtra(DeviceBlockedActivity.EXTRA_REGISTERED_NUMBER, whatsapp)
                        intent.putExtra(DeviceBlockedActivity.EXTRA_ATTEMPTED_NUMBER, whatsapp)
                        intent.putExtra(DeviceBlockedActivity.EXTRA_REASON, DeviceBlockedActivity.REASON_NUMBER)
                        intent.putExtra(DeviceBlockedActivity.EXTRA_NEW_ANDROID_ID, androidId)
                        startActivity(intent)
                        finish()
                    }
                    SheetSync.LoginOutcome.BANNED -> {
                        setLoading(false)
                        val intent = Intent(this, BannedActivity::class.java)
                        intent.putExtra(BannedActivity.EXTRA_ATTEMPTED_NUMBER, whatsapp)
                        startActivity(intent)
                        finish()
                    }
                    null -> {
                        setLoading(false)
                        Toast.makeText(this, "Couldn't reach the server. Please check your connection and try again.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // Reuses the exact same restore-local-state + fetch-real-name pattern
    // DeviceBlockedActivity.logInAsExistingAccount() already uses, rather
    // than duplicating it - MATCH here is functionally the same situation
    // as tapping "Log in" from that screen's REASON_DEVICE case.
    private fun logInAsMatchedAccount(whatsapp: String) {
        SheetSync.fetchRegisteredName(this, whatsapp) { fetchedName ->
            runOnUiThread {
                setLoading(false)

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

    private fun setLoading(loading: Boolean) {
        loginButton.isEnabled = !loading
        loginButton.text = if (loading) "" else "Log In"
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    // Same validation used in OnboardingActivity/RecoverAccountActivity -
    // kept identical so a number accepted at signup is accepted here too.
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

    companion object {
        const val EXTRA_PREFILL_WHATSAPP = "prefill_whatsapp"
    }
}
