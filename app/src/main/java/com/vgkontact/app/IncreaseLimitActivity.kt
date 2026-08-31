package com.vgkontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

/**
 * Increase Contact Limit - merged entry point that replaces both
 * GrowYourViewsActivity (referral milestone campaigns) and
 * UpgradePlanActivity (key code redemption). Both screens existed to do
 * the same underlying thing - raise the user's contact limit - via two
 * different mechanisms, and both already duplicated the same limit-meter
 * fetch/render logic against SheetSync.fetchImportStats(). This activity
 * keeps that fetch in one place, shown in a header that's shared across
 * both tabs, so a claim or redemption on either tab is reflected
 * immediately without the user needing to leave the screen.
 *
 * Launched from MainMenuActivity's "Increase Contact Limit" button
 * (kontactGroupsButton) - the dashboard's separate "Grow Your Views"
 * button was removed since its content is now the Referral Rewards tab
 * here, not a second entry point. The Referral Rewards tab is selected
 * by default since referring friends doesn't require the user to
 * already have something (a code) in hand, unlike the key tab.
 */
class IncreaseLimitActivity : AppCompatActivity() {

    // Shared header (both tabs)
    private lateinit var limitCurrentText: TextView
    private lateinit var limitOfText: TextView
    private lateinit var limitMeterBar: ProgressBar
    private lateinit var limitPctText: TextView
    private lateinit var limitBreakdownBlock: LinearLayout
    private lateinit var limitBaseText: TextView
    private lateinit var limitBonusText: TextView

    // Tabs
    private lateinit var tabReferralButton: Button
    private lateinit var tabKeyButton: Button
    private lateinit var referralPanel: LinearLayout
    private lateinit var keyPanel: LinearLayout

    // Referral rewards panel
    private lateinit var shareInviteButton: Button
    private lateinit var campaignsProgressBar: ProgressBar
    private lateinit var campaignsEmptyText: TextView
    private lateinit var campaignCardsContainer: LinearLayout
    private lateinit var noCampaignsText: TextView

    // Redeem a key panel
    private lateinit var upgradeSubtitleText: TextView
    private lateinit var keyCodeInput: TextInputEditText
    private lateinit var redeemKeyButton: Button
    private lateinit var noCodeContactUsButton: Button
    private lateinit var redeemProgressBar: ProgressBar

    // Campaigns are only fetched once, the first time the Referral Rewards
    // tab is shown - not re-fetched every time the user switches back to
    // it, since nothing about them changes just from tab-switching.
    private var campaignsLoaded = false

    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_increase_limit)
        BottomNavHelper.setup(this, BottomNavHelper.Tab.UPGRADE)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        limitCurrentText = findViewById(R.id.limitCurrentText)
        limitOfText = findViewById(R.id.limitOfText)
        limitMeterBar = findViewById(R.id.limitMeterBar)
        limitPctText = findViewById(R.id.limitPctText)
        limitBreakdownBlock = findViewById(R.id.limitBreakdownBlock)
        limitBaseText = findViewById(R.id.limitBaseText)
        limitBonusText = findViewById(R.id.limitBonusText)

        tabReferralButton = findViewById(R.id.tabReferralButton)
        tabKeyButton = findViewById(R.id.tabKeyButton)
        referralPanel = findViewById(R.id.referralPanel)
        keyPanel = findViewById(R.id.keyPanel)

        shareInviteButton = findViewById(R.id.shareInviteButton)
        campaignsProgressBar = findViewById(R.id.campaignsProgressBar)
        campaignsEmptyText = findViewById(R.id.campaignsEmptyText)
        campaignCardsContainer = findViewById(R.id.campaignCardsContainer)
        noCampaignsText = findViewById(R.id.noCampaignsText)

        upgradeSubtitleText = findViewById(R.id.upgradeSubtitleText)
        keyCodeInput = findViewById(R.id.keyCodeInput)
        redeemKeyButton = findViewById(R.id.redeemKeyButton)
        noCodeContactUsButton = findViewById(R.id.noCodeContactUsButton)
        redeemProgressBar = findViewById(R.id.redeemProgressBar)

        upgradeSubtitleText.text = getString(R.string.upgrade_plan_coming_soon)

        tabReferralButton.setOnClickListener { showReferralTab() }
        tabKeyButton.setOnClickListener { showKeyTab() }

        shareInviteButton.setOnClickListener { shareInviteLink() }

        redeemKeyButton.setOnClickListener { redeemKey() }
        noCodeContactUsButton.setOnClickListener { openWhatsAppForUnlockCode() }

        loadLimitMeter()

        // XML no longer hardcodes which tab looks active/inactive - both
        // buttons start visually neutral, and this call is what actually
        // applies the "Referral rewards selected" styling on first render.
        // Without this, the screen's initial look depended on whatever
        // was left in the XML defaults, which could drift out of sync
        // with what showReferralTab()/showKeyTab() consider "inactive".
        // showReferralTab() also triggers the first loadCampaigns() call
        // (guarded by campaignsLoaded), so it isn't called separately here.
        showReferralTab()
    }

    private fun showReferralTab() {
        referralPanel.visibility = View.VISIBLE
        keyPanel.visibility = View.GONE
        tabReferralButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.white)
        tabReferralButton.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
        tabKeyButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.transparent)
        tabKeyButton.setTextColor(ContextCompat.getColor(this, R.color.text_muted))

        if (!campaignsLoaded) {
            loadCampaigns()
        }
    }

    private fun showKeyTab() {
        referralPanel.visibility = View.GONE
        keyPanel.visibility = View.VISIBLE
        tabKeyButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.white)
        tabKeyButton.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
        tabReferralButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.transparent)
        tabReferralButton.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
    }

    /**
     * Loads the shared limit header, reusing the same fetchImportStats
     * call the dashboard uses, including the baseLimit/bonusLimit split
     * for the breakdown block - so this screen's numbers always match the
     * dashboard exactly, same convention as both original screens.
     */
    private fun loadLimitMeter() {
        SheetSync.fetchImportStats(this) { stats ->
            runOnUiThread {
                if (stats == null || stats.contactLimit < 0L) {
                    limitCurrentText.text = "--"
                    limitOfText.text = "/ --"
                    limitPctText.text = getString(R.string.limit_meter_unknown)
                    limitBreakdownBlock.visibility = View.GONE
                    return@runOnUiThread
                }
                limitCurrentText.text = stats.syncedToPhone.toString()
                limitOfText.text = "/ ${stats.contactLimit}"

                if (stats.baseLimit >= 0L && stats.bonusLimit > 0L) {
                    limitBreakdownBlock.visibility = View.VISIBLE
                    limitBaseText.text = stats.baseLimit.toString()
                    limitBonusText.text = "+${stats.bonusLimit}"
                } else {
                    limitBreakdownBlock.visibility = View.GONE
                }

                val pct = if (stats.contactLimit <= 0L) 0
                    else ((stats.syncedToPhone.toLong() * 100) / stats.contactLimit).toInt().coerceIn(0, 100)
                limitMeterBar.progress = pct
                limitPctText.text = "$pct% used"
            }
        }
    }

    // ==================== Referral rewards ====================

    private fun loadCampaigns() {
        campaignsProgressBar.visibility = View.VISIBLE
        campaignsEmptyText.visibility = View.GONE
        campaignCardsContainer.removeAllViews()
        noCampaignsText.visibility = View.GONE

        SheetSync.fetchMyCampaignStatus(this) { list, error ->
            runOnUiThread {
                campaignsProgressBar.visibility = View.GONE
                if (list == null) {
                    val message = if (error == "NO_INTERNET") {
                        "No internet connection. Check your connection and try again."
                    } else {
                        "Couldn't load rewards right now."
                    }
                    campaignsEmptyText.visibility = View.VISIBLE
                    campaignsEmptyText.text = message
                    return@runOnUiThread
                }
                campaignsLoaded = true
                if (list.isEmpty()) {
                    noCampaignsText.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                renderCampaignCards(list)
            }
        }
    }

    private fun renderCampaignCards(campaigns: List<CampaignStatus>) {
        campaignCardsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (campaign in campaigns) {
            val card = inflater.inflate(R.layout.item_campaign_card, campaignCardsContainer, false)

            val descriptionText = card.findViewById<TextView>(R.id.campaignDescriptionText)
            val rewardBadge = card.findViewById<TextView>(R.id.campaignRewardBadge)
            val progressCountText = card.findViewById<TextView>(R.id.campaignProgressCountText)
            val progressLabelText = card.findViewById<TextView>(R.id.campaignProgressLabelText)
            val progressBarView = card.findViewById<ProgressBar>(R.id.campaignProgressBar)
            val actionButton = card.findViewById<Button>(R.id.campaignClaimButton)

            val perMilestone = campaign.referralsPerMilestone
            val target = campaign.nextTarget
            descriptionText.text = "Refer $perMilestone friends"
            rewardBadge.text = "+${campaign.slotsPerMilestone} viewers"

            progressCountText.text = "${campaign.qualifyingReferrals}"
            progressLabelText.text = "of $target friends registered"
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
     * Same real invite link and message pattern as
     * MainMenuActivity.shareReferralLink() (https://vgkontact.netlify.app,
     * not a Play Store link) so the user isn't shown two different invite
     * links depending on which screen they share from.
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

    // ==================== Redeem a key ====================

    private fun redeemKey() {
        // Codes are generated as VGK-XXXX-XXXX (uppercase) by the admin
        // panel, but redeem_key() does a case-sensitive match - so
        // normalize whatever the user typed to uppercase here rather
        // than requiring them to match case exactly.
        val code = keyCodeInput.text.toString().trim().uppercase()
        if (code.isEmpty()) {
            Toast.makeText(this, getString(R.string.key_redeem_empty), Toast.LENGTH_SHORT).show()
            return
        }

        if (!SheetSync.isOnline(this)) {
            Toast.makeText(this, getString(R.string.key_redeem_error), Toast.LENGTH_SHORT).show()
            return
        }

        setRedeemLoading(true)
        SheetSync.redeemKey(this, code) { unlockedGroups ->
            runOnUiThread {
                setRedeemLoading(false)
                if (unlockedGroups != null && unlockedGroups.isNotEmpty()) {
                    keyCodeInput.text?.clear()
                    // Newly unlocked groups just raised contactLimit
                    // server-side - refresh the shared header immediately
                    // so the effect is visible without leaving this screen.
                    loadLimitMeter()
                    // Pull in the newly-unlocked group's contacts right
                    // away, instead of making the user go back to the
                    // dashboard and tap Sync manually.
                    syncAfterRedeem()
                } else {
                    Toast.makeText(this, getString(R.string.key_redeem_invalid), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openWhatsAppForUnlockCode() {
        val messageText = "Hi VG Kontact, I don't have an unlock code yet and would like to join more groups."
        val message = Uri.encode(messageText)
        val uri = Uri.parse("https://wa.me/$CONTACT_US_WHATSAPP_NUMBER?text=$message")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setRedeemLoading(loading: Boolean) {
        redeemKeyButton.isEnabled = !loading
        redeemProgressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    /**
     * Called right after a successful key redemption. Pulls in the newly
     * unlocked group's contacts immediately, same as before, instead of
     * leaving the user's synced count stale until they go back to the
     * dashboard and tap Sync manually. If contacts permission isn't
     * granted, this silently does nothing - the user can still sync
     * manually from the dashboard, so this is never worse than before.
     */
    private fun syncAfterRedeem() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.WRITE_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_CONTACTS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return

        SheetSync.importAllContactsFromSheet(this) { submitted, failed, errorDetail ->
            runOnUiThread {
                loadLimitMeter()
                if (errorDetail == null && submitted > 0) {
                    val label = if (submitted == 1) "contact" else "contacts"
                    Toast.makeText(this, "$submitted $label added", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
