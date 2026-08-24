package com.vgkontact.app

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class HistoryActivity : AppCompatActivity() {

    private var btnBack: View? = null
    private var progressBar: ProgressBar? = null
    private var historyContainer: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        btnBack = findViewById(R.id.btnBack) ?: findViewById(R.id.btn_back)
        progressBar = findViewById(R.id.progressBar) ?: findViewById(R.id.progress_bar)
        historyContainer = findViewById(R.id.historyContainer) ?: findViewById(R.id.history_container)

        btnBack?.setOnClickListener { finish() }

        loadHistory()
    }

    private fun loadHistory() {
        progressBar?.visibility = View.VISIBLE
        historyContainer?.removeAllViews()

        SheetSync.fetchHistory(this) { list, error ->
            runOnUiThread {
                progressBar?.visibility = View.GONE
                if (list != null) {
                    for (item in list) {
                        val textView = TextView(this@HistoryActivity).apply {
                            text = "${item.date}: ${item.count}"
                            textSize = 16f
                            setPadding(16, 16, 16, 16)
                        }
                        historyContainer?.addView(textView)
                    }
                } else {
                    Toast.makeText(this@HistoryActivity, error ?: "Failed to fetch history", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
