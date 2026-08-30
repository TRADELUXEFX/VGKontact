package com.vgkontact.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * "Grow your views" - referral milestone screen. Entered from a new
 * dashboard button (see MainMenuActivity wiring notes in the handoff
 * doc). Shows:
 *   1. The user's real current contact limit (same numbers/meter style
 *      as the dashboard, via SheetSync.fetchImportStats)
 *   2. A share button for their invite link
 *   3. One card per active campaign (SheetSync.fetchCampaignProgress),
 *      each in plain language - no mention of "campaign", "group", or
 *      "stage" anywhere in the UI text. A card's Claim button is only
 *      enabled once that campaign's isEligibleToClaim is true.
 *
 * Tapping Claim calls SheetSync.claimCampaignReward, which hits
 * claim_campaign_reward() on Supabase - a server-side function that
 * re-checks eligibility itself, so this is safe to wire directly to a
 * button tap with no admin approval step. On success, this screen
 * reloads both the limit meter and the campaign list, so the user sees
 * their new, higher limit immediately.
 */
class GrowYourViewsActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var limitCurrentText: TextView
    private lateinit var limitOfText: TextView
    private lateinit var limitMeterBar: ProgressBar
    private lateinit var limitPctText: TextView
    private lateinit var shareInviteButton: Button
    private lateinit var campaignCardsContainer: android.widget.LinearLayout
    private lateinit var noCampaignsText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grow_your_views)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        progressBar = findViewById(R.id.progressBar)
        emptyText = findViewById(R.id.emptyText)
        limitCurrentText = findViewById(R.id.limitCurrentText)
        limitOfText = findViewById(R.id.limitOfText)
        limitMeterBar = findViewById(R.id.limitMeterBar)
        limitPctText = findViewById(R.id.limitPctText)
        shareInviteButton = findViewById(R.id.shareInviteButton)
        campaignCardsContainer = findViewById(R.id.campaignCardsContainer)
        noCampaignsText = findViewById(R.id.noCampaignsText)

        shareInviteButton.setOnClickListener { shareInviteLink() }

        loadLimitMeter()
        loadCampaigns()
    }

    /**
     * Reloads just the limit meter, reusing the same fetchImportStats
     * call the dashboard already uses - so the number here always
     * matches the dashboard exactly, no separate/duplicated calculation.
     */
    private fun loadLimitMeter() {
        SheetSync.fetchImportStats(this) { stats ->
            runOnUiThread {
                if (stats == null || stats.contactLimit < 0L) {
                    limitCurrentText.text = "--"
                    limitOfText.text = "/ --"
                    limitPctText.text = "Unable to load"
                    return@runOnUiThread
                }
                limitCurrentText.text = stats.syncedToPhone.toString()
                limitOfText.text = "/ ${stats.contactLimit}"
                val pct = if (stats.contactLimit <= 0L) 0
                    else ((stats.syncedToPhone.toLong() * 100) / stats.contactLimit).toInt().coerceIn(0, 100)
                limitMeterBar.progress = pct
                limitPctText.text = "$pct% used"
            }
        }
    }

    private fun loadCampaigns() {
        progressBar.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        campaignCardsContainer.removeAllViews()
        noCampaignsText.visibility = View.GONE

        SheetSync.fetchCampaignProgress(this) { list, error ->
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (list == null) {
                    val message = if (error == "NO_INTERNET") {
                        "No internet connection. Check your connection and try again."
                    } else {
                        "Couldn't load rewards right now."
                    }
                    emptyText.visibility = View.VISIBLE
                    emptyText.text = message
                    return@runOnUiThread
                }
                if (list.isEmpty()) {
                    noCampaignsText.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                renderCampaignCards(list)
            }
        }
    }

    /**
     * Builds one card per campaign via item_campaign_card.xml, entirely
     * in plain language - "friends" and "status viewers", never
     * "campaign", "group", or "stage". A campaign the user already
     * claimed is skipped, since there's nothing left to show them.
     */
    private fun renderCampaignCards(campaigns: List<CampaignProgress>) {
        campaignCardsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        var visibleCount = 0

        for (campaign in campaigns) {
            if (campaign.alreadyClaimed) continue
            visibleCount++

            val card = inflater.inflate(R.layout.item_campaign_card, campaignCardsContainer, false)

            val descriptionText = card.findViewById<TextView>(R.id.campaignDescriptionText)
            val progressCountText = card.findViewById<TextView>(R.id.campaignProgressCountText)
            val progressBarView = card.findViewById<ProgressBar>(R.id.campaignProgressBar)
            val claimButton = card.findViewById<Button>(R.id.campaignClaimButton)

            // rewardGroupId's size (max_users) is what the user actually
            // gets - but that number lives on the groups table, not on
            // CampaignProgress. Until that's threaded through, show the
            // referral requirement plainly and let the claim result speak
            // for itself via the limit meter jumping after a successful
            // claim. See handoff notes for wiring rewardGroupId -> its
            // real max_users if you want the exact number shown here too.
            descriptionText.text = "Get ${campaign.requiredReferrals} friends to register on this " +
                "app and unlock more WhatsApp status viewers"

            val current = campaign.qualifyingReferrals.coerceAtMost(campaign.requiredReferrals)
            progressCountText.text = "$current of ${campaign.requiredReferrals}"
            val pct = if (campaign.requiredReferrals <= 0) 0
                else ((current * 100) / campaign.requiredReferrals).coerceIn(0, 100)
            progressBarView.progress = pct

            if (campaign.isEligibleToClaim) {
                claimButton.isEnabled = true
                claimButton.text = getString(R.string.btn_claim_views)
                claimButton.alpha = 1f
            } else {
                claimButton.isEnabled = false
                claimButton.alpha = 0.5f
                val remaining = (campaign.requiredReferrals - campaign.qualifyingReferrals).coerceAtLeast(0)
                claimButton.text = "Refer $remaining more to claim views"
            }

            claimButton.setOnClickListener {
                claimButton.isEnabled = false
                claimButton.text = "Claiming..."
                SheetSync.claimCampaignReward(this, campaign.campaignId) { unlockedGroupId, error ->
                    runOnUiThread {
                        if (unlockedGroupId != null) {
                            Toast.makeText(this, "Views unlocked!", Toast.LENGTH_SHORT).show()
                            loadLimitMeter()
                            loadCampaigns()
                        } else {
                            Toast.makeText(
                                this,
                                "Couldn't claim right now - please try again",
                                Toast.LENGTH_SHORT
                            ).show()
                            claimButton.isEnabled = true
                            claimButton.text = getString(R.string.btn_claim_views)
                        }
                    }
                }
            }

            campaignCardsContainer.addView(card)
        }

        if (visibleCount == 0) {
            noCampaignsText.visibility = View.VISIBLE
        }
    }

    /**
     * Opens the system share sheet with the user's real invite link -
     * same link and message pattern as MainMenuActivity.shareReferralLink()
     * (https://vgkontact.netlify.app?ref=..., not a Play Store link - this
     * app isn't distributed via the Play Store). Kept consistent so the
     * user isn't shown two different invite links depending on which
     * screen they share from.
     */
    private fun shareInviteLink() {
        val myCode = UserPrefs.getWhatsapp(this)
        if (myCode.isNullOrEmpty()) {
            Toast.makeText(this, "Referral code unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        val link = "https://vgkontact.netlify.app?ref=$myCode"
        val message = "Get more WhatsApp status views with VGKontact! " +
            "Download here: $link\n\nUse my code $myCode when you sign up."

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        startActivity(Intent.createChooser(shareIntent, "Share invite"))
    }
}
