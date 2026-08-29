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
import com.google.android.material.textfield.TextInputEditText

/**
 * Key redemption / "unlock" screen. User types in a code (given to them by
 * the admin); on success, the unlocked groups get merged into their
 * extra_groups server side (see redeem_key() Postgres function /
 * SheetSync.redeemKey()), and the next Sync on the main menu will pull
 * contacts from those groups too.
 *
 * Can be opened two ways:
 *  - Generic, from the "Join Kontact Groups" button on GroupsActivity - no
 *    extra passed, shows the default "Unlock More Kontacts" copy.
 *  - Targeted, by tapping a specific group row on GroupsActivity - passes
 *    EXTRA_TARGET_GROUP_ID, which swaps the title/subtitle to reference
 *    that group by number and personalizes the "don't have a code"
 *    WhatsApp message with the group number too.
 */
class UpgradePlanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET_GROUP_ID = "extra_target_group_id"
    }

    private lateinit var upgradeTitleText: TextView
    private lateinit var upgradeSubtitleText: TextView
    private lateinit var keyCodeInput: TextInputEditText
    private lateinit var redeemKeyButton: Button
    private lateinit var noCodeContactUsButton: Button
    private lateinit var redeemProgressBar: ProgressBar
    private lateinit var currentLimitNumberText: TextView
    private lateinit var currentLimitOfText: TextView

    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    private var targetGroupId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upgrade_plan)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        upgradeTitleText = findViewById(R.id.upgradeTitleText)
        upgradeSubtitleText = findViewById(R.id.upgradeSubtitleText)
        keyCodeInput = findViewById(R.id.keyCodeInput)
        redeemKeyButton = findViewById(R.id.redeemKeyButton)
        noCodeContactUsButton = findViewById(R.id.noCodeContactUsButton)
        redeemProgressBar = findViewById(R.id.redeemProgressBar)
        currentLimitNumberText = findViewById(R.id.currentLimitNumberText)
        currentLimitOfText = findViewById(R.id.currentLimitOfText)

        loadCurrentLimit()

        val incomingId = intent.getLongExtra(EXTRA_TARGET_GROUP_ID, -1L)
        targetGroupId = if (incomingId > 0) incomingId else null

        applyTargetGroupCopy()

        redeemKeyButton.setOnClickListener {
            val code = keyCodeInput.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(this, getString(R.string.key_redeem_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!SheetSync.isOnline(this)) {
                Toast.makeText(this, getString(R.string.key_redeem_error), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            setLoading(true)
            SheetSync.redeemKey(this, code) { unlockedGroups ->
                runOnUiThread {
                    setLoading(false)
                    if (unlockedGroups != null && unlockedGroups.isNotEmpty()) {
                        Toast.makeText(
                            this,
                            getString(R.string.key_redeem_success, unlockedGroups.size),
                            Toast.LENGTH_LONG
                        ).show()
                        keyCodeInput.text?.clear()
                        // Newly unlocked groups just raised contactLimit
                        // server-side - refresh the reminder card so the
                        // user sees their new, higher limit immediately
                        // without leaving this screen.
                        loadCurrentLimit()
                    } else {
                        Toast.makeText(this, getString(R.string.key_redeem_invalid), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        noCodeContactUsButton.setOnClickListener {
            openWhatsAppForUnlockCode()
        }
    }

    /**
     * Loads and displays the "Your Current Limit" reminder card at the top
     * of this screen: syncedToPhone / contactLimit, same numbers and same
     * source (SheetSync.fetchImportStats) as the dashboard meter. Called
     * once on open, and again right after a successful key redemption
     * since that changes contactLimit server-side immediately.
     */
    private fun loadCurrentLimit() {
        SheetSync.fetchImportStats(this) { stats ->
            runOnUiThread {
                if (stats == null || stats.contactLimit < 0L) {
                    currentLimitNumberText.text = "--"
                    currentLimitOfText.text = getString(R.string.limit_meter_unknown)
                } else {
                    currentLimitNumberText.text = stats.syncedToPhone.toString()
                    currentLimitOfText.text = "/ ${stats.contactLimit} contacts"
                }
            }
        }
    }

    private fun applyTargetGroupCopy() {
        val groupId = targetGroupId
        if (groupId == null) {
            upgradeTitleText.text = getString(R.string.title_upgrade_plan)
            upgradeSubtitleText.text = getString(R.string.upgrade_plan_coming_soon)
        } else {
            upgradeTitleText.text = getString(R.string.title_upgrade_plan_for_group, groupId)
            upgradeSubtitleText.text = getString(R.string.upgrade_plan_coming_soon_for_group, groupId)
        }
    }

    private fun openWhatsAppForUnlockCode() {
        val groupId = targetGroupId
        val messageText = if (groupId == null) {
            "Hi VG Kontact, I don't have an unlock code yet and would like to join more groups."
        } else {
            "Hi VG Kontact, I don't have an unlock code yet and would like to join Group $groupId kontacts"
        }
        val message = Uri.encode(messageText)
        val uri = Uri.parse("https://wa.me/$CONTACT_US_WHATSAPP_NUMBER?text=$message")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setLoading(loading: Boolean) {
        redeemKeyButton.isEnabled = !loading
        redeemProgressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
