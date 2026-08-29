package com.vgkontact.app

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var frequencySummaryText: TextView
    private lateinit var saveFrequencyButton: Button

    private lateinit var tile1Hour: LinearLayout
    private lateinit var tile6Hours: LinearLayout
    private lateinit var tile12Hours: LinearLayout
    private lateinit var tile24Hours: LinearLayout

    private var selectedHours: Int = 24

    private val presetTiles by lazy {
        mapOf(
            1 to tile1Hour,
            6 to tile6Hours,
            12 to tile12Hours,
            24 to tile24Hours
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        frequencySummaryText = findViewById(R.id.frequencySummaryText)
        saveFrequencyButton = findViewById(R.id.saveFrequencyButton)

        tile1Hour = findViewById(R.id.tile1Hour)
        tile6Hours = findViewById(R.id.tile6Hours)
        tile12Hours = findViewById(R.id.tile12Hours)
        tile24Hours = findViewById(R.id.tile24Hours)

        // Load current notification frequency hours, snapped to nearest preset
        val currentHours = UserPrefs.getNotificationFrequencyHours(this)
        selectedHours = nearestPreset(currentHours)

        presetTiles.forEach { (hours, tile) ->
            tile.setOnClickListener { selectHours(hours) }
        }

        updateSelectionUi()

        saveFrequencyButton.setOnClickListener {
            UserPrefs.setNotificationFrequencyHours(this, selectedHours)
            SheetCheckWorker.schedule(this, selectedHours)
            Toast.makeText(this, "Notification frequency updated to $selectedHours hours", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun nearestPreset(hours: Int): Int {
        val presets = presetTilesKeys
        return presets.minByOrNull { kotlin.math.abs(it - hours) } ?: 24
    }

    private val presetTilesKeys = listOf(1, 6, 12, 24)

    private fun selectHours(hours: Int) {
        selectedHours = hours
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        presetTiles.forEach { (hours, tile) ->
            val isSelected = hours == selectedHours
            tile.setBackgroundResource(
                if (isSelected) R.drawable.freq_tile_selected_background
                else R.drawable.freq_tile_default_background
            )

            val valueColor = if (isSelected) R.color.vg_green_dark else R.color.vg_dark
            val labelColor = if (isSelected) R.color.vg_green_dark else R.color.text_muted

            for (i in 0 until tile.childCount) {
                val child = tile.getChildAt(i)
                if (child is TextView) {
                    val colorRes = if (i == 0) valueColor else labelColor
                    child.setTextColor(ContextCompat.getColor(this, colorRes))
                }
            }
        }

        val label = if (selectedHours == 1) "hour" else "hours"
        frequencySummaryText.text = "Notifications sent every $selectedHours $label"
    }
}
