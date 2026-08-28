package com.vgkontact.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class ProfileActivity : AppCompatActivity() {

    private lateinit var profileNumberText: TextView
    private lateinit var profileReferralText: TextView
    private lateinit var profileDateRegisteredText: TextView
    private lateinit var profilePlanText: TextView
    private lateinit var upgradePlanButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        profileNumberText = findViewById(R.id.profileNumberText)
        profileReferralText = findViewById(R.id.profileReferralText)
        profileDateRegisteredText = findViewById(R.id.profileDateRegisteredText)
        profilePlanText = findViewById(R.id.profilePlanText)
        upgradePlanButton = findViewById(R.id.upgradePlanButton)

        profileNumberText.text = UserPrefs.getWhatsapp(this) ?: "N/A"
        val referral = UserPrefs.getReferral(this)
        profileReferralText.text = if (referral.isNullOrEmpty()) "None" else referral
        profileDateRegisteredText.text = UserPrefs.getDateRegistered(this) ?: "N/A"

        // Fetch plan from Supabase (falls back to FREE if not set / offline)
        profilePlanText.text = "FREE"
        SheetSync.fetchPlan(this) { plan ->
            runOnUiThread {
                profilePlanText.text = plan ?: "FREE"
            }
        }

        upgradePlanButton.setOnClickListener {
            // TODO: point this at your actual upgrade/checkout flow
            startActivity(Intent(this, UpgradePlanActivity::class.java))
        }
    }
}
