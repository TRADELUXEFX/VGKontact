package com.vgkontact.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainMenuActivity : AppCompatActivity() {

    private lateinit var syncKontactButton: Button
    private lateinit var kontactHistoryButton: Button
    private lateinit var phoneNumberText: TextView
    private lateinit var statsCard: LinearLayout
    private lateinit var statsProgressBar: ProgressBar
    private lateinit var statsContent: LinearLayout
    private lateinit var statsTotalText: TextView
    private lateinit var chatIcon: ImageView

    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        syncKontactButton = findViewById(R.id.syncKontactButton)
        kontactHistoryButton = findViewById(R.id.kontactHistoryButton)
        phoneNumberText = findViewById(R.id.phoneNumberText)
        statsCard = findViewById(R.id.statsCard)
        statsProgressBar = findViewById(R.id.statsProgressBar)
        statsContent = findViewById(R.id.statsContent)
        statsTotalText = findViewById(R.id.statsTotalText)
        chatIcon = findViewById(R.id.chatIcon)

        phoneNumberText.text = UserPrefs.getWhatsapp(this) ?: "N/A"

        syncKontactButton.setOnClickListener {
            if (checkContactsPermission()) {
                startSync()
            } else {
                requestContactsPermission()
            }
        }

        kontactHistoryButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        chatIcon.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.whatsapp.com/"))
            startActivity(intent)
        }

        loadStats()
    }

    private fun checkContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestContactsPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS),
            PERMISSION_REQUEST_CODE
        )
    }

    private fun loadStats() {
        statsCard.visibility = View.VISIBLE
        statsProgressBar.visibility = View.VISIBLE
        statsContent.visibility = View.GONE

        SheetSync.fetchHistory(this) { list, error ->
            runOnUiThread {
                statsProgressBar.visibility = View.GONE
                statsContent.visibility = View.VISIBLE
                if (list != null && list.isNotEmpty()) {
                    statsTotalText.text = list[0].count.toString()
                }
            }
        }
    }

    private fun startSync() {
        Toast.makeText(this, "Starting Sync...", Toast.LENGTH_SHORT).show()
        SheetSync.importAllContactsFromSheet(this) { submitted, failed ->
            runOnUiThread {
                Toast.makeText(this, "Synced: $submitted, Failed: $failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startSync()
            } else {
                Toast.makeText(this, "Permission required to sync contacts", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
