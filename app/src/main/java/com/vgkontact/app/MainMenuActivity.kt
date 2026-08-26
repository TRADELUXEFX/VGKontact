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

/**
 * Dashboard. All permission requests (contacts, notifications, battery) happen
 * once, up front, in PermissionSetupActivity before this screen is ever shown -
 * this activity does not request any of them on launch.
 *
 * The sync button still checks contacts permission before syncing, purely as a
 * fallback: if the user denied it during setup and grants it later via Settings,
 * or somehow lands here without having been through setup, tapping Sync asks for
 * it then instead of silently failing. If it's already granted (the normal case),
 * tapping Sync never shows a permission prompt - it just syncs.
 */
class MainMenuActivity : AppCompatActivity() {

    private lateinit var syncKontactButton: Button
    private lateinit var kontactHistoryButton: Button
    private lateinit var contactUsButton: Button
    private lateinit var phoneNumberText: TextView
    private lateinit var statsCard: LinearLayout
    private lateinit var statsProgressBar: ProgressBar
    private lateinit var statsContent: LinearLayout
    private lateinit var statsTotalText: TextView
    private lateinit var statsTodayText: TextView
    private lateinit var statsDatabaseTotalText: TextView
    private lateinit var statsAvailableText: TextView
    private lateinit var notificationIcon: ImageView
    private lateinit var profileIcon: ImageView
    private lateinit var planPreviewText: TextView
    private lateinit var upgradePlanButton: Button

    private val PERMISSION_REQUEST_CODE = 100
    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        // Setup notification channel
        NotificationHelper.createNotificationChannel(this)

        syncKontactButton = findViewById(R.id.syncKontactButton)
        kontactHistoryButton = findViewById(R.id.kontactHistoryButton)
        contactUsButton = findViewById(R.id.contactUsButton)
        phoneNumberText = findViewById(R.id.phoneNumberText)
        statsCard = findViewById(R.id.statsCard)
        statsProgressBar = findViewById(R.id.statsProgressBar)
        statsContent = findViewById(R.id.statsContent)
        statsTotalText = findViewById(R.id.statsTotalText)
        statsTodayText = findViewById(R.id.statsTodayText)
        statsDatabaseTotalText = findViewById(R.id.statsDatabaseTotalText)
        statsAvailableText = findViewById(R.id.statsAvailableText)
        notificationIcon = findViewById(R.id.notificationIcon)
        profileIcon = findViewById(R.id.profileIcon)
        planPreviewText = findViewById(R.id.planPreviewText)
        upgradePlanButton = findViewById(R.id.upgradePlanButton)

        phoneNumberText.text = UserPrefs.getWhatsapp(this) ?: "N/A"

        // Fetch plan from Supabase (defaults to FREE PLAN if row doesn't exist yet)
        planPreviewText.text = "FREE PLAN"
        SheetSync.fetchPlan(this) { plan ->
            runOnUiThread {
                val resolvedPlan = plan ?: "FREE"
                planPreviewText.text = "$resolvedPlan PLAN"
            }
        }

        upgradePlanButton.setOnClickListener {
            // TODO: point this at your actual upgrade/checkout flow
            startActivity(Intent(this, UpgradePlanActivity::class.java))
        }

        SheetCheckWorker.schedule(this)

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

        contactUsButton.setOnClickListener {
            openWhatsAppContactUs()
        }

        notificationIcon.setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }

        profileIcon.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers the case where permission state changed elsewhere (e.g. the user
        // granted contacts access via system Settings after denying it during
        // setup) - stats should reflect the real on-device numbers.
        loadStats()
    }

    private fun openWhatsAppContactUs() {
        val message = Uri.encode("Hi VG Kontact, I need help with...")
        val uri = Uri.parse("https://wa.me/$CONTACT_US_WHATSAPP_NUMBER?text=$message")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
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

        val todayCount = UserPrefs.getTodaySyncedCount(this)
        statsTodayText.text = if (todayCount > 0) {
            val label = if (todayCount == 1) "kontact" else "kontacts"
            "$todayCount $label synced today"
        } else {
            getString(R.string.stats_no_sync_today)
        }

        // TODO: once a FREE-plan import cap is decided, replace this with:
        // availableToImport = min(planLimit, stats.totalInDatabase) - stats.syncedToPhone
        // For now this shows the honest raw gap with no plan restriction applied.
        SheetSync.fetchImportStats(this) { stats ->
            runOnUiThread {
                if (stats != null) {
                    statsDatabaseTotalText.text = stats.totalInDatabase.toString()
                    statsAvailableText.text = stats.availableToImport.toString()
                }
            }
        }
    }

    private fun startSync() {
        syncKontactButton.isEnabled = false
        val originalButtonText = syncKontactButton.text
        syncKontactButton.text = "Syncing..."
        Toast.makeText(this, "Checking for new Kontacts...", Toast.LENGTH_SHORT).show()
        NotificationHelper.showSyncStartedNotification(this)
        SheetSync.importAllContactsFromSheet(this) { submitted, failed, errorDetail ->
            runOnUiThread {
                syncKontactButton.isEnabled = true
                syncKontactButton.text = originalButtonText
                if (errorDetail == "NO_INTERNET") {
                    Toast.makeText(this, "No internet connection", Toast.LENGTH_LONG).show()
                    NotificationHelper.showNoInternetNotification(this)
                    return@runOnUiThread
                }
                if (submitted == 0 && failed == 0) {
                    Toast.makeText(this, "No new numbers", Toast.LENGTH_LONG).show()
                } else if (submitted > 0 && failed == 0) {
                    val label = if (submitted == 1) "number" else "numbers"
                    Toast.makeText(this, "$submitted new $label added", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "$submitted new added, $failed failed - tap to retry", Toast.LENGTH_LONG).show()
                }
                NotificationHelper.showSyncCompleteNotification(this, submitted, failed, errorDetail)
                loadStats()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Permission was just granted, so the stats we last loaded (with
                // permission denied) are stale/generic. Refresh them before syncing
                // so the dashboard reflects the real on-device numbers right away.
                loadStats()
                startSync()
            } else {
                Toast.makeText(this, "Permission required to sync contacts", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
