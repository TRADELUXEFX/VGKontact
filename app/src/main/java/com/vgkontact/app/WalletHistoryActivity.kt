package com.vgkontact.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.util.Locale

/**
 * Full wallet history - every commission and withdrawal entry, opened
 * from the "History" link next to "Recent activity" on WalletActivity.
 *
 * Reuses WalletSync.fetchWallet() (same call WalletActivity makes) and
 * item_wallet_activity.xml for each row, so this list always matches
 * what the Wallet screen itself shows - just without the wallet
 * screen's own recent-activity height limits.
 */
class WalletHistoryActivity : BaseActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var backButton: ImageView
    private lateinit var loadingOverlay: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet_history)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        listContainer = findViewById(R.id.walletHistoryListContainer)
        emptyText = findViewById(R.id.walletHistoryEmptyText)
        backButton = findViewById(R.id.walletHistoryBackButton)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        backButton.setOnClickListener { finish() }

        loadHistory()
    }

    private var loading = false

    private fun loadHistory() {
        if (loading) return
        loading = true
        loadingOverlay.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        listContainer.removeAllViews()
        WalletSync.fetchWallet(this) { wallet ->
            runOnUiThread {
                loading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                loadingOverlay.visibility = View.GONE
                if (wallet == null) {
                    emptyText.text = "Couldn't load history. Tap to retry"
                    emptyText.visibility = View.VISIBLE
                    emptyText.setOnClickListener { loadHistory() }
                    listContainer.removeAllViews()
                } else {
                    renderEntries(wallet.activity)
                }
            }
        }
    }

    private fun renderEntries(entries: List<WalletSync.Entry>) {
        listContainer.removeAllViews()

        if (entries.isEmpty()) {
            emptyText.text = "No wallet activity yet"
            emptyText.setOnClickListener(null)
            emptyText.visibility = View.VISIBLE
            return
        }
        emptyText.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        val green = ContextCompat.getColor(this, R.color.vg_green)
        val red = ContextCompat.getColor(this, R.color.vg_red_dark)

        entries.forEachIndexed { index, entry ->
            val row = inflater.inflate(R.layout.item_wallet_activity, listContainer, false)

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

            listContainer.addView(row)
        }
    }

    private fun naira(amount: Long): String =
        "₦" + NumberFormat.getNumberInstance(Locale.US).format(amount)
}
