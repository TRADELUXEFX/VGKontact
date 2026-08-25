package com.vgkontact.app

import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ProfileActivity : AppCompatActivity() {

    private lateinit var profileNumberText: TextView
    private lateinit var profileReferralText: TextView
    private lateinit var profileDateRegisteredText: TextView
    private lateinit var profilePlanText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        profileNumberText = findViewById(R.id.profileNumberText)
        profileReferralText = findViewById(R.id.profileReferralText)
        profileDateRegisteredText = findViewById(R.id.profileDateRegisteredText)
        profilePlanText = findViewById(R.id.profilePlanText)

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
    }
}
