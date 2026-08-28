package com.vgkontact.app

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Referral leaderboard. Shows every referrer's WhatsApp number next to how
 * many people they've referred (contacts.referral grouped/counted server-
 * side data, ranked highest referral count first). See
 * SheetSync.fetchReferralLeaderboard for how the count is derived.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var dayListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        window.statusBarColor = androidx.core.content.ContextCompat.getColor(this, R.color.vg_green)

        progressBar = findViewById(R.id.progressBar)
        emptyText = findViewById(R.id.emptyText)
        dayListContainer = findViewById(R.id.dayListContainer)

        loadReferralLeaderboard()
    }

    private fun loadReferralLeaderboard() {
        progressBar.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        dayListContainer.removeAllViews()

        SheetSync.fetchReferralLeaderboard(this) { list, error ->
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (list != null) {
                    if (list.isEmpty()) {
                        emptyText.visibility = View.VISIBLE
                        emptyText.text = "No referrals yet."
                        return@runOnUiThread
                    }
                    for (entry in list) {
                        val row = LinearLayout(this@HistoryActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, 14, 0, 14)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                        }

                        val numberView = TextView(this@HistoryActivity).apply {
                            text = entry.whatsapp
                            textSize = 14f
                            setTextColor(androidx.core.content.ContextCompat.getColor(this@HistoryActivity, R.color.vg_dark))
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val countView = TextView(this@HistoryActivity).apply {
                            text = entry.referralCount.toString()
                            textSize = 14f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(androidx.core.content.ContextCompat.getColor(this@HistoryActivity, R.color.vg_green))
                        }

                        row.addView(numberView)
                        row.addView(countView)
                        dayListContainer.addView(row)
                    }
                } else {
                    emptyText.visibility = View.VISIBLE
                    val message = if (error == "NO_INTERNET") {
                        "No internet connection. Check your connection and try again."
                    } else {
                        "Couldn't load referral history. Please try again."
                    }
                    emptyText.text = message
                    Toast.makeText(this@HistoryActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
