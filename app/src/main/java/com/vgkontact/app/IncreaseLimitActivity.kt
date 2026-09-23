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
import java.text.NumberFormat
import java.util.Locale

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

        // Selling packages for the "Buy Viewers" screen.
        private val PACKAGES = listOf(
            Package(viewers = 250, price = 10000),
            Package(viewers = 500, price = 20000),
            Package(viewers = 1000, price = 40000)
        )
    }

    private data class Package(val viewers: Int, val price: Int)

    private lateinit var tabPurchaseButton: Button
    private lateinit var tabUnlockButton: Button
    private lateinit var purchasePanel: LinearLayout
    private lateinit var unlockPanel: LinearLayout

    // Redeem a key panel
    private lateinit var upgradeSubtitleText: TextView
    private lateinit var keyCodeInput: EditText
    private lateinit var redeemKeyButton: Button
    private lateinit var noCodeContactUsButton: Button
    private lateinit var redeemProgressBar: ProgressBar

    // Package plan cards - three fixed packages (see PACKAGES above),
    // shown as tappable cards instead of a +/- stepper. Tapping a card
    // sets selectedPackageIndex and re-renders all three so exactly one
    // shows the "selected" tile background.
    private lateinit var packageCard250: LinearLayout
    private lateinit var packageCard500: LinearLayout
    private lateinit var packageCard1000: LinearLayout
    private lateinit var packagePrice250: TextView
    private lateinit var packagePrice500: TextView
    private lateinit var packagePrice1000: TextView
    private var selectedPackageIndex: Int = 2 // 1000 (best value) is the default pick

    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_increase_limit)
        FloatingContactHelper.attach(this)
        BottomNavHelper.setup(this, BottomNavHelper.Tab.UPGRADE)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        tabPurchaseButton = findViewById(R.id.tabPurchaseButton)
        tabUnlockButton = findViewById(R.id.tabUnlockButton)
        purchasePanel = findViewById(R.id.purchasePanel)
        unlockPanel = findViewById(R.id.unlockPanel)

        upgradeSubtitleText = findViewById(R.id.upgradeSubtitleText)
        keyCodeInput = findViewById(R.id.keyCodeInput)
        redeemKeyButton = findViewById(R.id.redeemKeyButton)
        noCodeContactUsButton = findViewById(R.id.noCodeContactUsButton)
        redeemProgressBar = findViewById(R.id.redeemProgressBar)

        packageCard250 = findViewById(R.id.packageCard250)
        packageCard500 = findViewById(R.id.packageCard500)
        packageCard1000 = findViewById(R.id.packageCard1000)
        packagePrice250 = findViewById(R.id.packagePrice250)
        packagePrice500 = findViewById(R.id.packagePrice500)
        packagePrice1000 = findViewById(R.id.packagePrice1000)

        upgradeSubtitleText.text = getString(R.string.upgrade_plan_coming_soon)

        redeemKeyButton.setOnClickListener { redeemKey() }
        noCodeContactUsButton.setOnClickListener { openWhatsAppForUnlockCode() }

        tabPurchaseButton.setOnClickListener { showPurchaseTab() }
        tabUnlockButton.setOnClickListener { showUnlockTab() }

        packageCard250.setOnClickListener { selectPackage(0) }
        packageCard500.setOnClickListener { selectPackage(1) }
        packageCard1000.setOnClickListener { selectPackage(2) }
        renderPackagePicker()
        showPurchaseTab()
    }

    // ==================== Tab switcher ====================
    // Same segmented-control pattern as HistoryActivity's
    // showMyReferralsTab()/showLeaderboardTab() - only one panel
    // visible at a time, toggled via the header tab buttons.

    private fun showPurchaseTab() {
        purchasePanel.visibility = View.VISIBLE
        unlockPanel.visibility = View.GONE
        tabPurchaseButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.white)
        tabPurchaseButton.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
        tabUnlockButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.transparent)
        tabUnlockButton.setTextColor(ContextCompat.getColor(this, R.color.white))
    }

    private fun showUnlockTab() {
        unlockPanel.visibility = View.VISIBLE
        purchasePanel.visibility = View.GONE
        tabUnlockButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.white)
        tabUnlockButton.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
        tabPurchaseButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.transparent)
        tabPurchaseButton.setTextColor(ContextCompat.getColor(this, R.color.white))
    }

    // ==================== Package plan cards ====================

    private fun selectPackage(index: Int) {
        selectedPackageIndex = index
        renderPackagePicker()
    }

    private fun renderPackagePicker() {
        val cards = listOf(packageCard250, packageCard500, packageCard1000)
        val prices = listOf(packagePrice250, packagePrice500, packagePrice1000)
        cards.forEachIndexed { index, card ->
            card.setBackgroundResource(
                if (index == selectedPackageIndex) R.drawable.freq_tile_selected_background
                else R.drawable.freq_tile_default_background
            )
        }
        prices.forEachIndexed { index, priceText ->
            priceText.text = "₦" + NumberFormat.getNumberInstance(Locale.US).format(PACKAGES[index].price)
        }
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
        val selected = PACKAGES[selectedPackageIndex]
        val formattedPrice = NumberFormat.getNumberInstance(Locale.US).format(selected.price)
        val messageText = "Hi VG Kontact, I'd like to purchase a code for ${selected.viewers} status viewers (₦$formattedPrice)."
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
