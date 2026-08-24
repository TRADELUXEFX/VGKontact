package com.vgkontact.app

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vgkontact.app.databinding.ActivityHistoryBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadHistory()
    }

    private fun loadHistory() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        binding.totalText.text = ""
        binding.dayListContainer.removeAllViews()

        lifecycleScope.launch {
            val result = SheetSync.fetchHistory()
            binding.progressBar.visibility = View.GONE

            result.onSuccess { summary ->
                binding.totalText.text = getString(R.string.history_total_prefix) + summary.total

                if (summary.days.isEmpty()) {
                    binding.emptyText.text = getString(R.string.history_empty)
                    binding.emptyText.visibility = View.VISIBLE
                } else {
                    summary.days.forEach { day ->
                        binding.dayListContainer.addView(buildDayRow(day))
                        binding.dayListContainer.addView(dividerView())
                    }
                }
            }.onFailure {
                binding.totalText.text = ""
                binding.emptyText.text = getString(R.string.history_load_failed)
                binding.emptyText.visibility = View.VISIBLE
            }
        }
    }

    private fun buildDayRow(day: DayCount): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, (14 * density).toInt(), 0, (14 * density).toInt())
        }

        val label = TextView(this).apply {
            text = dayLabel(day.date)
            textSize = 15f
            setTextColor(getColor(R.color.vg_dark))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val count = TextView(this).apply {
            text = "${day.count} added"
            textSize = 15f
            setTextColor(getColor(R.color.vg_green))
        }

        row.addView(label)
        row.addView(count)
        return row
    }

    private fun dividerView(): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(getColor(R.color.gray_light))
        }

    private fun dayLabel(isoDate: String): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = sdf.format(Date())
        val yesterday = sdf.format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        )
        return when (isoDate) {
            today -> "Today"
            yesterday -> "Yesterday"
            else -> isoDate
        }
    }
}
