package com.vgkontact.app

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.util.Locale

/**
 * Wallet screen - referral commission balance, Withdraw button and a
 * Recent activity list.
 *
 * DATA: everything shown comes from [loadWallet], which currently returns
 * SAMPLE numbers. The database is wired up later - when that happens,
 * replace the body of [loadWallet] (or call [render] from a network
 * callback) and nothing else in this file or the layout needs to change.
 */
class WalletActivity : BaseActivity() {

    // ---- Data model (swap loadWallet() for real data later) -----------

    private enum class Kind { COMMISSION, WITHDRAWAL }

    private data class WalletEntry(
        val kind: Kind,
        val title: String,
        val subtitle: String,
        /** Positive for money in, negative for money out. */
        val amount: Long
    )

    private data class WalletData(
        val available: Long,
        val pending: Long,
        val totalEarned: Long,
        val withdrawn: Long,
        val activity: List<WalletEntry>
    )

    private lateinit var balanceText: TextView
    private lateinit var pendingText: TextView
    private lateinit var totalEarnedText: TextView
    private lateinit var withdrawnText: TextView
    private lateinit var withdrawButton: View
    private lateinit var withdrawLabel: TextView
    private lateinit var activityList: LinearLayout
    private lateinit var emptyState: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        FloatingContactHelper.attach(this)
        BottomNavHelper.setup(this, BottomNavHelper.Tab.WALLET)

        balanceText = findViewById(R.id.walletBalanceText)
        pendingText = findViewById(R.id.walletPendingText)
        totalEarnedText = findViewById(R.id.walletTotalEarnedText)
        withdrawnText = findViewById(R.id.walletWithdrawnText)
        withdrawButton = findViewById(R.id.walletWithdrawButton)
        withdrawLabel = findViewById(R.id.walletWithdrawLabel)
        activityList = findViewById(R.id.walletActivityList)
        emptyState = findViewById(R.id.walletEmptyState)

        render(loadWallet())
    }

    /** SAMPLE DATA - replace with the real database call later. */
    private fun loadWallet(): WalletData = WalletData(
        available = 4_250,
        pending = 750,
        totalEarned = 12_500,
        withdrawn = 7_500,
        activity = listOf(
            WalletEntry(Kind.COMMISSION, "Commission from Chidi", "Bought 500 viewers", 250),
            WalletEntry(Kind.COMMISSION, "Commission from Amaka", "Bought 1,000 viewers", 500),
            WalletEntry(Kind.WITHDRAWAL, "Withdrawal to Opay", "Paid", -3_000)
        )
    )

    private fun render(data: WalletData) {
        balanceText.text = naira(data.available)
        pendingText.text = "${naira(data.pending)} pending"
        totalEarnedText.text = naira(data.totalEarned)
        withdrawnText.text = naira(data.withdrawn)

        // Withdraw is only usable once the balance reaches the minimum.
        val canWithdraw = data.available >= MIN_WITHDRAWAL
        withdrawButton.setBackgroundResource(
            if (canWithdraw) R.drawable.wallet_withdraw_button
            else R.drawable.wallet_withdraw_button_disabled
        )
        withdrawButton.setOnClickListener {
            if (canWithdraw) {
                // Real withdrawal request comes with the database work.
                Toast.makeText(this, "Withdrawals are coming soon", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Minimum withdrawal is ${naira(MIN_WITHDRAWAL)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        renderActivity(data.activity)
    }

    private fun renderActivity(entries: List<WalletEntry>) {
        activityList.removeAllViews()

        if (entries.isEmpty()) {
            activityList.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            return
        }
        activityList.visibility = View.VISIBLE
        emptyState.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        val green = ContextCompat.getColor(this, R.color.vg_green)
        val red = ContextCompat.getColor(this, R.color.vg_red_dark)

        entries.forEachIndexed { index, entry ->
            val row = inflater.inflate(R.layout.item_wallet_activity, activityList, false)

            // No divider above the first row.
            row.findViewById<View>(R.id.walletRowDivider).visibility =
                if (index == 0) View.GONE else View.VISIBLE

            val isIn = entry.amount >= 0
            row.findViewById<ImageView>(R.id.walletRowIcon).apply {
                setImageResource(if (isIn) R.drawable.ic_arrow_in else R.drawable.ic_arrow_out)
                setBackgroundResource(if (isIn) R.drawable.wallet_chip_in else R.drawable.wallet_chip_out)
                setColorFilter(if (isIn) green else red)
            }
            row.findViewById<TextView>(R.id.walletRowTitle).text = entry.title
            row.findViewById<TextView>(R.id.walletRowSubtitle).text = entry.subtitle
            row.findViewById<TextView>(R.id.walletRowAmount).apply {
                val sign = if (isIn) "+" else "-"
                text = "$sign${naira(kotlin.math.abs(entry.amount))}"
                setTextColor(if (isIn) green else red)
            }

            activityList.addView(row)
        }
    }

    private fun naira(amount: Long): String =
        "₦" + NumberFormat.getNumberInstance(Locale.US).format(amount)

    companion object {
        private const val MIN_WITHDRAWAL = 1_000L
    }
}
