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
 * Always generic: the user never sees or picks a specific group ID. Which
 * groups a code unlocks is decided entirely by the admin server-side
 * (keys.groups_unlock) - the app just shows "enter your code" and doesn't
 * expose the grouping logic to the user.
 */
class UpgradePlanActivity : AppCompatActivity() {

    private lateinit var upgradeTitleText: TextView
    private lateinit var upgradeSubtitleText: TextView
    private lateinit var keyCodeInput: TextInputEditText
    private lateinit var redeemKeyButton: Button
    private lateinit var noCodeContactUsButton: Button
    private lateinit var redeemProgressBar: ProgressBar
    private lateinit var currentLimitNumberText: TextView
    private lateinit var currentLimitOfText: TextView

    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

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

        upgradeTitleText.text = getString(R.string.title_upgrade_plan)
        upgradeSubtitleText.text = getString(R.string.upgrade_plan_coming_soon)

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

    private fun setLoading(loading: Boolean) {
        redeemKeyButton.isEnabled = !loading
        redeemProgressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
