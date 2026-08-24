package com.vgkontact.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class HistoryActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var historyContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        btnBack = findViewById(R.id.btnBack)
        progressBar = findViewById(R.id.progressBar)
        historyContainer = findViewById(R.id.historyContainer)

        btnBack.setOnClickListener { finish() }

        loadHistory()
    }

    private fun loadHistory() {
        progressBar.visibility = View.VISIBLE
        historyContainer.removeAllViews()

        SheetSync.fetchHistory(this) { list, error ->
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (list != null) {
                    for (item in list) {
                        val rowView = layoutInflater.inflate(R.layout.activity_history, historyContainer, false)
                        val tvDate = rowView.findViewById<TextView>(R.id.tvDate)
                        val tvCount = rowView.findViewById<TextView>(R.id.tvCount)
                        tvDate?.text = item.date
                        tvCount?.text = item.count.toString()
                        historyContainer.addView(rowView)
                    }
                } else {
                    Toast.makeText(this@HistoryActivity, error ?: "Failed to fetch history", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
