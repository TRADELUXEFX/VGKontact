package com.vgkontact.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Refer and earn - opened from the dashboard's "Refer and earn" button
 * (formerly "Share App", which jumped straight to the Android share
 * sheet with no explanation). This screen explains the two ways a
 * referral pays before handing off to the same share sheet:
 *
 *   1. Free viewers, instantly - when someone joins using your code,
 *      their status viewers add to yours. No purchase needed. Backed
 *      by the existing get_my_referred_viewers_count RPC
 *      (SheetSync.fetchReferredViewersCount), same number already
 *      shown on the dashboard as "Referred viewers".
 *
 *   2. Cash commission - when a referral buys status viewers, a
 *      commission lands in the user's wallet. Backed by the existing
 *      get_my_wallet RPC (WalletSync.fetchWallet), same numbers
 *      already shown on WalletActivity.
 *
 * TODO: the exact commission percentage shown to the user (if this
 * screen ever states one explicitly) still needs final confirmation,
 * and whether the free-viewers reward extends to second-level
 * referrals the same way commission does is also still unconfirmed.
 * Neither is stated as a number anywhere in this screen yet - it only
 * describes the mechanic, not a rate - so both are safe to resolve
 * later without touching this file's copy.
 */
class ReferAndEarnActivity : BaseActivity() {

    private lateinit var backButton: View
    private lateinit var referredViewersText: TextView
    private lateinit var earningsText: TextView
    private lateinit var codeText: TextView
    private lateinit var shareButton: View
    private lateinit var loadingOverlay: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_refer_and_earn)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        backButton = findViewById(R.id.referBackButton)
        referredViewersText = findViewById(R.id.referReferredViewersText)
        earningsText = findViewById(R.id.referEarningsText)
        codeText = findViewById(R.id.referCodeText)
        shareButton = findViewById(R.id.referShareButton)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val myCode = UserPrefs.getWhatsapp(this) ?: "N/A"
        codeText.text = formatPhoneForDisplay(myCode)

        shareButton.setOnClickListener {
            copyCodeToClipboard(myCode)
            shareReferralLink(myCode)
        }

        loadStats()
    }

    override fun onResume() {
        super.onResume()
        loadStats()
    }

    /**
     * Populates the two header stats from the same two RPCs already used
     * elsewhere in the app - no new backend work needed. Referred viewers
     * mirrors the dashboard's existing stat; wallet earnings mirrors
     * WalletActivity's "Total earned". Each is independent, so one
     * failing doesn't block the other from painting.
     */
    private fun loadStats() {
        SheetSync.fetchReferredViewersCount(this) { count ->
            runOnUiThread {
                referredViewersText.text = (count ?: 0L).toString()
            }
        }

        WalletSync.fetchWallet(this) { wallet ->
            runOnUiThread {
                if (wallet != null) {
                    earningsText.text = formatNaira(wallet.totalEarned)
                } else {
                    earningsText.text = "—"
                }
            }
        }
    }

    private fun formatNaira(amount: Long): String {
        val formatted = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(amount)
        return "₦$formatted"
    }

    /**
     * Purely cosmetic grouping for a Nigerian phone number
     * (e.g. "08108709625" -> "0810 870 9625"). Falls back to the raw
     * value unchanged if it isn't an 11-digit number - covers a
     * username-based code cleanly with no special-casing needed.
     */
    private fun formatPhoneForDisplay(raw: String): String {
        if (raw.length != 11 || !raw.all { it.isDigit() }) return raw
        return "${raw.substring(0, 4)} ${raw.substring(4, 7)} ${raw.substring(7, 11)}"
    }

    private fun copyCodeToClipboard(code: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("VGKontact Referral Code", code)
        clipboard.setPrimaryClip(clip)
    }

    /**
     * Same share-sheet handoff MainMenuActivity used to fire directly -
     * this screen just explains the offer first. Kept identical to the
     * original shareReferralLink() (link, message, ?ref= param) so
     * nothing about the link itself changes for people already
     * receiving it.
     */
    private fun shareReferralLink(myCode: String) {
        if (myCode.isEmpty() || myCode == "N/A") {
            Toast.makeText(this, "Referral code unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        val link = "https://vgkontact.netlify.app?ref=$myCode"
        val message = "Join me on VGKontact! Download here: $link\n\nUse my code $myCode when you sign up."

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        startActivity(Intent.createChooser(shareIntent, "Share VGKontact"))
    }
}
