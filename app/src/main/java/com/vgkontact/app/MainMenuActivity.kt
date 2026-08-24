package com.vgkontact.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainMenuActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 100

    private var cardSync: View? = null
    private var cardHistory: View? = null
    private var cardCommunity: View? = null
    private var cardProfile: View? = null

    private var tvWhatsappNumber: TextView? = null
    private var tvReferralCode: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        cardSync = findViewById(R.id.cardSync) ?: findViewById(R.id.card_sync)
        cardHistory = findViewById(R.id.cardHistory) ?: findViewById(R.id.card_history)
        cardCommunity = findViewById(R.id.cardCommunity) ?: findViewById(R.id.card_community)
        cardProfile = findViewById(R.id.cardProfile) ?: findViewById(R.id.card_profile)

        tvWhatsappNumber = findViewById(R.id.tvWhatsappNumber) ?: findViewById(R.id.tv_whatsapp)
        tvReferralCode = findViewById(R.id.tvReferralCode) ?: findViewById(R.id.tv_referral)

        tvWhatsappNumber?.text = UserPrefs.getWhatsapp(this) ?: "N/A"
        tvReferralCode?.text = UserPrefs.getReferral(this) ?: "N/A"

        cardSync?.setOnClickListener {
            if (checkContactsPermission()) {
                startSync()
            } else {
                requestContactsPermission()
            }
        }

        cardHistory?.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        cardCommunity?.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.whatsapp.com/"))
            startActivity(intent)
        }

        cardProfile?.setOnClickListener {
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
