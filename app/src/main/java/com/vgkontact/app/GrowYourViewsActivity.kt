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
 * "Grow your views" - repeating referral milestone screen. Entered
 * from a dashboard button (see MainMenuActivity wiring). Shows:
 *   1. The user's real current contact limit (same numbers/meter style
 *      as the dashboard, via SheetSync.fetchImportStats)
 *   2. A share button for their invite link
 *   3. One card per active campaign (SheetSync.fetchMyCampaignStatus),
 *      each showing live progress like "3 of 5 friends" and a button
 *      that's enabled once the next milestone is reached.
 *
 * FINAL DESIGN (agreed): campaigns are self-serve and repeating.
 * There is ONE plain reusable code per campaign (not locked to any
 * phone number). The app computes live progress itself from
 * qualifying_referrals vs nextTarget - no admin lookup per user.
 * Tapping "Unlock reward" calls SheetSync.claimCampaignMilestone(),
 * which re-checks eligibility server-side and redeems the shared
 * code. On success we show a simple inline success state (no code
 * text ever shown on screen, since the code is shared/reusable and
 * displaying it would just be something to screenshot and pass
 * around), then the card resets to count toward the next milestone.
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

        SheetSync.fetchMyCampaignStatus(this) { list, error ->
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
     * Builds one card per active campaign via item_campaign_card.xml.
     * Shows real live progress ("3 of 5 friends") using
     * qualifyingReferrals / nextTarget from CampaignStatus. The button
     * is enabled ("Unlock reward") once readyToClaim is true, disabled
     * ("Keep referring") otherwise, or a permanent "Claimed" state for
     * a non-repeating campaign that's already been used.
     */
    private fun renderCampaignCards(campaigns: List<CampaignStatus>) {
        campaignCardsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (campaign in campaigns) {
            val card = inflater.inflate(R.layout.item_campaign_card, campaignCardsContainer, false)

            val descriptionText = card.findViewById<TextView>(R.id.campaignDescriptionText)
            val progressCountText = card.findViewById<TextView>(R.id.campaignProgressCountText)
            val progressBarView = card.findViewById<ProgressBar>(R.id.campaignProgressBar)
            val actionButton = card.findViewById<Button>(R.id.campaignClaimButton)

            descriptionText.text = "Refer friends to unlock more WhatsApp status viewers"
            progressCountText.visibility = View.VISIBLE
            progressBarView.visibility = View.VISIBLE

            val target = campaign.nextTarget
            progressCountText.text = "${campaign.qualifyingReferrals} of $target friends"
            progressBarView.max = target
            progressBarView.progress = campaign.qualifyingReferrals.coerceAtMost(target)

            when {
                campaign.fullyClaimed -> {
                    actionButton.isEnabled = false
                    actionButton.alpha = 0.5f
                    actionButton.text = "Claimed"
                    actionButton.setOnClickListener(null)
                }
                campaign.readyToClaim -> {
                    actionButton.isEnabled = true
                    actionButton.alpha = 1f
                    actionButton.text = "Unlock reward"
                    actionButton.setOnClickListener { claimMilestone(campaign, actionButton) }
                }
                else -> {
                    actionButton.isEnabled = false
                    actionButton.alpha = 0.5f
                    actionButton.text = "Keep referring"
                    actionButton.setOnClickListener(null)
                }
            }

            campaignCardsContainer.addView(card)
        }
    }

    /**
     * Taps "Unlock reward" -> claimCampaignMilestone() re-checks
     * eligibility and redeems the campaign's shared code server-side.
     * On success: simple success toast (no code shown on screen - see
     * class doc), then reload so the card resets toward the next
     * milestone. On failure: generic error, nothing changes.
     */
    private fun claimMilestone(campaign: CampaignStatus, actionButton: Button) {
        if (!SheetSync.isOnline(this)) {
            Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show()
            return
        }
        actionButton.isEnabled = false
        actionButton.text = "Unlocking..."

        SheetSync.claimCampaignMilestone(this, campaign.campaignId) { unlockedGroups ->
            runOnUiThread {
                if (unlockedGroups != null && unlockedGroups.isNotEmpty()) {
                    Toast.makeText(this, "Reward unlocked! Your extra status viewer slots are now active.", Toast.LENGTH_LONG).show()
                    loadLimitMeter()
                    loadCampaigns()
                } else {
                    Toast.makeText(this, "Couldn't unlock right now. Please try again.", Toast.LENGTH_LONG).show()
                    actionButton.isEnabled = true
                    actionButton.text = "Unlock reward"
                }
            }
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
