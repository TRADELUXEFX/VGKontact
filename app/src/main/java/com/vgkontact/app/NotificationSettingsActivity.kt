package com.vgkontact.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var hoursInputField: EditText
    private lateinit var saveFrequencyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        hoursInputField = findViewById(R.id.hoursInputField)
        saveFrequencyButton = findViewById(R.id.saveFrequencyButton)

        // Load current notification frequency hours
        val currentHours = UserPrefs.getNotificationFrequencyHours(this)
        hoursInputField.setText(currentHours.toString())

        saveFrequencyButton.setOnClickListener {
            val input = hoursInputField.text.toString().trim()
            
            when {
                input.isEmpty() -> {
                    Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
                }
                input.toIntOrNull() == null -> {
                    Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    val hours = input.toInt()
                    when {
                        hours < 1 -> {
                            Toast.makeText(this, "Hours must be at least 1", Toast.LENGTH_SHORT).show()
                            hoursInputField.setText("1")
                        }
                        hours > 24 -> {
                            Toast.makeText(this, "Maximum 24 hours allowed", Toast.LENGTH_SHORT).show()
                            hoursInputField.setText("24")
                        }
                        else -> {
                            UserPrefs.setNotificationFrequencyHours(this, hours)
                            SheetCheckWorker.schedule(this, hours)
                            Toast.makeText(this, "Notification frequency updated to $hours hours", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
            }
        }
    }
}
