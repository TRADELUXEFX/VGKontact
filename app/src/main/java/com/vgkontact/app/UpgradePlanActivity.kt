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
import android.widget.EditText

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
    private lateinit var keyCodeInput: EditText
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
            // Codes are generated as VGK-XXXX-XXXX (uppercase) by the admin
            // panel, but redeem_key() does a case-sensitive match - so
            // normalize whatever the user typed to uppercase here rather
            // than requiring them to match case exactly.
            val code = keyCodeInput.text.toString().trim().uppercase()
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
                        keyCodeInput.text?.clear()
                        // Newly unlocked groups just raised contactLimit
                        // server-side - refresh the reminder card so the
                        // user sees their new, higher limit immediately
                        // without leaving this screen.
                        loadCurrentLimit()
                        // Pull in the newly-unlocked group's contacts right
                        // away, instead of making the user go back to the
                        // dashboard and tap Sync manually. The only success
                        // feedback the user sees is the "X contacts added"
                        // toast from this sync, not a separate "unlocked N
                        // groups" message - the user doesn't need to know
                        // about groups at all.
                        syncAfterRedeem()
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

    /**
     * Called right after a successful key redemption. Pulls in the newly
     * unlocked group's contacts immediately, instead of leaving the user's
     * synced count stale until they go back to the dashboard and tap Sync
     * manually. If contacts permission isn't granted, this silently does
     * nothing - the user can still sync manually from the dashboard like
     * before, so this is never worse than the old behavior, just better
     * when permission is already in place (the common case).
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
                // Refresh the limit card again now that syncedToPhone has
                // moved too, not just contactLimit from the redeem itself.
                loadCurrentLimit()
                if (errorDetail == null && submitted > 0) {
                    val label = if (submitted == 1) "contact" else "contacts"
                    Toast.makeText(this, "$submitted $label added", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
