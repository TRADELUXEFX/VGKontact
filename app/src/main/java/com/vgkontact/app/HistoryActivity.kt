package com.vgkontact.app

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

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

    private fun loadHistory() {
        progressBar.visibility = View.VISIBLE
        dayListContainer.removeAllViews()

        SheetSync.fetchHistory(this) { list, error ->
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (list != null) {
                    totalText.text = "Total Kontacts: ${if (list.isNotEmpty()) list[0].count else 0}"
                    for (item in list.drop(1)) {
                        val textView = TextView(this@HistoryActivity).apply {
                            text = "${item.date}: ${item.count}"
                            textSize = 16f
                            setPadding(16, 16, 16, 16)
                        }
                        dayListContainer.addView(textView)
                    }
                } else {
                    emptyText.visibility = View.VISIBLE
                    emptyText.text = error ?: "Failed to fetch history"
                    Toast.makeText(this@HistoryActivity, error ?: "Failed to fetch history", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
