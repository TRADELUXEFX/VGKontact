package com.vgkontact.app

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class HistoryActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var totalText: TextView
    private lateinit var emptyText: TextView
    private lateinit var dayListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        progressBar = findViewById(R.id.progressBar)
        totalText = findViewById(R.id.totalText)
        emptyText = findViewById(R.id.emptyText)
        dayListContainer = findViewById(R.id.dayListContainer)

        loadHistory()
    }

    private fun formatDate(isoDate: String): String {
        return try {
            val zonedDateTime = ZonedDateTime.parse(isoDate)
            zonedDateTime.format(DateTimeFormatter.ofPattern("MMM dd, yyyy"))
        } catch (e: Exception) {
            isoDate
        }
    }

    private fun loadHistory() {
        progressBar.visibility = View.VISIBLE
        dayListContainer.removeAllViews()

        SheetSync.fetchHistory(this) { list, error ->
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (list != null) {
                    totalText.text = "Total Kontacts: ${if (list.isNotEmpty()) list[0].count else 0}"
                    for (item in list.drop(1)) {
                        val row = LinearLayout(this@HistoryActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, 14, 0, 14)
                            val params = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            layoutParams = params
                        }

                        val dateView = TextView(this@HistoryActivity).apply {
                            text = formatDate(item.date)
                            textSize = 14f
                            setTextColor(androidx.core.content.ContextCompat.getColor(this@HistoryActivity, R.color.vg_dark))
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val countView = TextView(this@HistoryActivity).apply {
                            text = item.count.toString()
                            textSize = 14f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(androidx.core.content.ContextCompat.getColor(this@HistoryActivity, R.color.vg_green))
                        }

                        row.addView(dateView)
                        row.addView(countView)
                        dayListContainer.addView(row)
                    }
                } else {
                    emptyText.visibility = View.VISIBLE
                    emptyText.text = "Couldn't load history. Check your connection and try again."
                    Toast.makeText(this@HistoryActivity, "Couldn't load history", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
