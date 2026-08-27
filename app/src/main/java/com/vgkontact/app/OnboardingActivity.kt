package com.vgkontact.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class OnboardingActivity : AppCompatActivity() {

    private lateinit var slidesContainer: LinearLayout
    private lateinit var formContainer: LinearLayout

    private lateinit var slide1: LinearLayout
    private lateinit var slide2: LinearLayout
    private lateinit var slide3: LinearLayout
    private val slides by lazy { listOf(slide1, slide2, slide3) }

    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var dot3: View
    private val dots by lazy { listOf(dot1, dot2, dot3) }

    private lateinit var skipButton: TextView
    private lateinit var prevButton: FrameLayout
    private lateinit var nextButton: Button
    private lateinit var formBackButton: FrameLayout

    private lateinit var whatsappInput: TextInputEditText
    private lateinit var referralInput: TextInputEditText
    private lateinit var continueButton: Button
    private lateinit var progressBar: ProgressBar

    private var currentSlide = 0
    private val totalSlides = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (UserPrefs.isRegistered(this)) {
            // Already registered - route through the same gate the first-time
            // flow uses. PermissionSetupActivity skips itself immediately if
            // setup was already completed, so this is a no-op for returning users.
            startActivity(Intent(this, PermissionSetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)

        slidesContainer = findViewById(R.id.slidesContainer)
        formContainer = findViewById(R.id.formContainer)

        slide1 = findViewById(R.id.slide1)
        slide2 = findViewById(R.id.slide2)
        slide3 = findViewById(R.id.slide3)

        dot1 = findViewById(R.id.dot1)
        dot2 = findViewById(R.id.dot2)
        dot3 = findViewById(R.id.dot3)

        skipButton = findViewById(R.id.skipButton)
        prevButton = findViewById(R.id.prevButton)
        nextButton = findViewById(R.id.nextButton)
        formBackButton = findViewById(R.id.formBackButton)

        whatsappInput = findViewById(R.id.whatsappInput)
        referralInput = findViewById(R.id.referralInput)
        continueButton = findViewById(R.id.continueButton)
        progressBar = findViewById(R.id.progressBar)

        skipButton.setOnClickListener { showForm() }
        nextButton.setOnClickListener { onNextClicked() }
        prevButton.setOnClickListener { onPrevClicked() }
        formBackButton.setOnClickListener { hideForm() }

        continueButton.setOnClickListener { onContinueClicked() }

        updateSlideUi()
    }

    private fun onNextClicked() {
        if (currentSlide < totalSlides - 1) {
            currentSlide++
            updateSlideUi()
        } else {
            showForm()
        }
    }

    private fun onPrevClicked() {
        if (currentSlide > 0) {
            currentSlide--
            updateSlideUi()
        }
    }

    private fun updateSlideUi() {
        // Slide transitions
        slides.forEachIndexed { index, slide ->
            slide.visibility = if (index == currentSlide) View.VISIBLE else View.GONE
        }
        
        // Smooth dot animation
        dots.forEachIndexed { index, dot ->
            val isActive = index == currentSlide
            val targetAlpha = if (isActive) 1f else 0.4f
            
            dot.animate()
                .alpha(targetAlpha)
                .setDuration(300)
                .start()
            
            // Keep dot size constant (no width change)
            dot.setBackgroundResource(if (isActive) R.drawable.badge_dot else R.drawable.dot_inactive)
        }
        
        // Navigation state
        prevButton.visibility = if (currentSlide == 0) View.INVISIBLE else View.VISIBLE
        nextButton.text = if (currentSlide == totalSlides - 1) "Get started" else "Next"
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun showForm() {
        slidesContainer.visibility = View.GONE
        formContainer.visibility = View.VISIBLE
    }

    private fun hideForm() {
        formContainer.visibility = View.GONE
        slidesContainer.visibility = View.VISIBLE
    }

    private fun onContinueClicked() {
        val whatsapp = whatsappInput.text.toString().trim()
        val referral = referralInput.text.toString().trim()

        // Validate WhatsApp number
        if (!isValidNigerianPhone(whatsapp)) {
            whatsappInput.error = "Enter valid 11-digit Nigerian number"
            return
        }

        // Referral is optional, but if provided, must be valid
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
