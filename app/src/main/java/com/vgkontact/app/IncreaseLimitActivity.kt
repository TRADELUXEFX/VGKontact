package com.vgkontact.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.widget.EditText

/**
 * Increase Contact Limit - key code redemption screen. Used to also
 * host a "By tasks" / referral rewards tab (paid campaign listing),
 * which has been removed entirely per request; this screen now only
 * shows the key redemption panel, unconditionally.
 *
 * EXTRA_INITIAL_TAB/TAB_KEY/TAB_REFERRAL are kept as no-op constants
 * since MainMenuActivity and BottomNavHelper still pass them when
 * launching this activity - harmless now that there's only one panel.
 */
class IncreaseLimitActivity : BaseActivity() {

    companion object {
        // No longer change behavior (only one panel remains) - kept so
        // existing callers (MainMenuActivity, BottomNavHelper) that pass
        // these extras don't need to change.
        const val EXTRA_INITIAL_TAB = "initial_tab"
        const val TAB_KEY = "key"
        const val TAB_REFERRAL = "referral"

        // Selling rate for the contact amount picker: ₦250 per 250
        // contacts. Both sides move together so the picker only ever
        // lands on amounts we actually sell.
        private const val STEP_CONTACTS = 250
        private const val RATE_PER_STEP = 250
    }

    private lateinit var keyPanel: LinearLayout

    // Redeem a key panel
    private lateinit var upgradeSubtitleText: TextView
    private lateinit var keyCodeInput: EditText
    private lateinit var redeemKeyButton: Button
    private lateinit var noCodeContactUsButton: Button
    private lateinit var redeemProgressBar: ProgressBar

    // Contact amount picker - lets the user pick how many contacts they
    // want before purchasing a code. STEP_CONTACTS/RATE_PER_STEP define
    // the fixed selling rate (₦250 per 250 contacts); the stepper only
    // moves in whole steps so the total always lines up with a purchasable
    // amount, without exposing that "unit" framing in the UI copy.
    private lateinit var contactsMinusButton: Button
    private lateinit var contactsPlusButton: Button
    private lateinit var contactsAmountText: TextView
    private lateinit var contactsTotalPriceText: TextView
    private var selectedContacts: Int = STEP_CONTACTS

    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_increase_limit)
        FloatingContactHelper.attach(this)
        BottomNavHelper.setup(this, BottomNavHelper.Tab.UPGRADE)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        keyPanel = findViewById(R.id.keyPanel)

        upgradeSubtitleText = findViewById(R.id.upgradeSubtitleText)
        keyCodeInput = findViewById(R.id.keyCodeInput)
        redeemKeyButton = findViewById(R.id.redeemKeyButton)
        noCodeContactUsButton = findViewById(R.id.noCodeContactUsButton)
        redeemProgressBar = findViewById(R.id.redeemProgressBar)

        contactsMinusButton = findViewById(R.id.contactsMinusButton)
        contactsPlusButton = findViewById(R.id.contactsPlusButton)
        contactsAmountText = findViewById(R.id.contactsAmountText)
        contactsTotalPriceText = findViewById(R.id.contactsTotalPriceText)

        upgradeSubtitleText.text = getString(R.string.upgrade_plan_coming_soon)

        redeemKeyButton.setOnClickListener { redeemKey() }
        noCodeContactUsButton.setOnClickListener { openWhatsAppForUnlockCode() }

        contactsMinusButton.setOnClickListener {
            if (selectedContacts > STEP_CONTACTS) {
                selectedContacts -= STEP_CONTACTS
                renderContactsPicker()
            }
        }
        contactsPlusButton.setOnClickListener {
            selectedContacts += STEP_CONTACTS
            renderContactsPicker()
        }
        renderContactsPicker()
    }

    // ==================== Contact amount picker ====================

    private fun renderContactsPicker() {
        contactsAmountText.text = "$selectedContacts contacts"
        val total = (selectedContacts / STEP_CONTACTS) * RATE_PER_STEP
        contactsTotalPriceText.text = "₦$total"
        contactsMinusButton.isEnabled = selectedContacts > STEP_CONTACTS
        contactsMinusButton.alpha = if (contactsMinusButton.isEnabled) 1f else 0.4f
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
                    // User-facing language never mentions "groups" - that's
                    // internal database structure. Externally this is always
                    // framed as unlocked contact capacity. The actual
                    // contact count/toast is reported by syncAfterRedeem()
                    // once the sync completes, so we don't show a second,
                    // separate popup here with a different (group) number.
                    ActivityLog.add(
                        this,
                        ActivityLog.Type.LIMIT_INCREASED,
                        "Contact limit increased via key redemption"
                    )
                    syncAfterRedeem()
                } else {
                    Toast.makeText(this, getString(R.string.key_redeem_invalid), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openWhatsAppForUnlockCode() {
        val total = (selectedContacts / STEP_CONTACTS) * RATE_PER_STEP
        val messageText = "Hi VG Kontact, I'd like to purchase a code for $selectedContacts contacts (₦$total)."
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
                if (errorDetail == null && submitted > 0) {
                    val label = if (submitted == 1) "contact was" else "contacts were"
                    Toast.makeText(this, "$submitted $label unlocked with your key", Toast.LENGTH_LONG).show()
                    NotificationHelper.showKeyRedeemedNotification(this, submitted)
                } else if (errorDetail == null) {
                    Toast.makeText(this, "Contact limit increased with your key", Toast.LENGTH_LONG).show()
                    NotificationHelper.showKeyRedeemedNotification(this, 0)
                }
            }
        }
    }
}
