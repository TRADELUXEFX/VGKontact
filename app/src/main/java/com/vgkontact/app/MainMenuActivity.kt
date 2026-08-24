package com.vgkontact.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainMenuActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 100

    private lateinit var cardSync: LinearLayout
    private lateinit var cardHistory: LinearLayout
    private lateinit var cardCommunity: LinearLayout
    private lateinit var cardProfile: LinearLayout

    private lateinit var tvWhatsappNumber: TextView
    private lateinit var tvReferralCode: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        cardSync = findViewById(R.id.cardSync)
        cardHistory = findViewById(R.id.cardHistory)
        cardCommunity = findViewById(R.id.cardCommunity)
        cardProfile = findViewById(R.id.cardProfile)

        tvWhatsappNumber = findViewById(R.id.tvWhatsappNumber)
        tvReferralCode = findViewById(R.id.tvReferralCode)

        tvWhatsappNumber.text = UserPrefs.getWhatsapp(this) ?: "N/A"
        tvReferralCode.text = UserPrefs.getReferral(this) ?: "N/A"

        cardSync.setOnClickListener {
            if (checkContactsPermission()) {
                startSync()
            } else {
                requestContactsPermission()
            }
        }

        cardHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        cardCommunity.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.whatsapp.com/"))
            startActivity(intent)
        }

        cardProfile.setOnClickListener {
            Toast.makeText(this, "Profile Settings", Toast.LENGTH_SHORT).show()
        }
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
