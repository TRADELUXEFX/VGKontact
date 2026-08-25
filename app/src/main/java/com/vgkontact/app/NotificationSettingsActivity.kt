package com.vgkontact.app

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var hoursValueText: TextView
    private lateinit var hoursSeekBar: SeekBar
    private lateinit var saveFrequencyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)

        hoursValueText = findViewById(R.id.hoursValueText)
        hoursSeekBar = findViewById(R.id.hoursSeekBar)
        saveFrequencyButton = findViewById(R.id.saveFrequencyButton)

        // SeekBar is 0-167, representing 1-168 hours (1 hour to 7 days)
        val currentHours = UserPrefs.getNotificationFrequencyHours(this)
        hoursSeekBar.progress = (currentHours - 1).coerceIn(0, 167)
        updateHoursLabel(currentHours)

        hoursSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateHoursLabel(progress + 1)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        saveFrequencyButton.setOnClickListener {
            val hours = hoursSeekBar.progress + 1
            UserPrefs.setNotificationFrequencyHours(this, hours)
            SheetCheckWorker.schedule(this, hours)
            Toast.makeText(this, "Notification frequency updated", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateHoursLabel(hours: Int) {
        val label = if (hours == 24) {
            "$hours hours (Daily)"
        } else if (hours == 1) {
            "$hours hour"
        } else {
            "$hours hours"
        }
        hoursValueText.text = label
    }
}
