package com.vgkontact.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
 *
 * Groups UI (joined-groups summary + join-more-groups action) has moved off
 * this dashboard entirely into GroupsActivity - this screen now only holds
 * a single "Kontact Groups" button as the entry point to that screen.
 */
class MainMenuActivity : AppCompatActivity() {

    private lateinit var syncKontactButton: Button
    private lateinit var kontactGroupsButton: Button
    private lateinit var referralHistoryButton: Button
    private lateinit var contactUsButton: Button
    private lateinit var phoneNumberText: TextView
    private lateinit var statsCard: LinearLayout
    private lateinit var statsProgressBar: ProgressBar
    private lateinit var statsContent: LinearLayout
    private lateinit var statsTodayText: TextView
    private lateinit var notificationIcon: ImageView
    private lateinit var profileIcon: ImageView
    private lateinit var planPreviewText: TextView
    private lateinit var permissionWarningBanner: LinearLayout
    private lateinit var permissionWarningText: TextView

    // Contact limit meter (replaces the old database-total / available tiles)
    private lateinit var limitCurrentText: TextView
    private lateinit var limitOfText: TextView
    private lateinit var limitMeterBar: ProgressBar
    private lateinit var limitPctText: TextView

    private var latestPermissionStatus: PermissionHealth.Status? = null

    private val PERMISSION_REQUEST_CODE = 100
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 101
    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        // Setup notification channel
        NotificationHelper.createNotificationChannel(this)

        syncKontactButton = findViewById(R.id.syncKontactButton)
        kontactGroupsButton = findViewById(R.id.kontactGroupsButton)
        referralHistoryButton = findViewById(R.id.referralHistoryButton)
        contactUsButton = findViewById(R.id.contactUsButton)
        phoneNumberText = findViewById(R.id.phoneNumberText)
        statsCard = findViewById(R.id.statsCard)
        statsProgressBar = findViewById(R.id.statsProgressBar)
        statsContent = findViewById(R.id.statsContent)
        statsTodayText = findViewById(R.id.statsTodayText)
        notificationIcon = findViewById(R.id.notificationIcon)
        profileIcon = findViewById(R.id.profileIcon)
        planPreviewText = findViewById(R.id.planPreviewText)
        permissionWarningBanner = findViewById(R.id.permissionWarningBanner)
        permissionWarningText = findViewById(R.id.permissionWarningText)

        limitCurrentText = findViewById(R.id.limitCurrentText)
        limitOfText = findViewById(R.id.limitOfText)
        limitMeterBar = findViewById(R.id.limitMeterBar)
        limitPctText = findViewById(R.id.limitPctText)

        permissionWarningBanner.setOnClickListener {
            latestPermissionStatus?.let { status ->
                fixWorstPermissionIssue(status)
            }
        }

        phoneNumberText.text = UserPrefs.getWhatsapp(this) ?: "N/A"

        // VERIFIED/UNVERIFIED is no longer a stored flag we fetch once - it's
        // derived live from PermissionHealth.check() every time the dashboard
        // is visible (see refreshPermissionHealth/onResume), so it can never
        // go stale whichever way the user flips a permission.

        kontactGroupsButton.setOnClickListener {
            startActivity(Intent(this, GroupsActivity::class.java))
        }

        SheetCheckWorker.schedule(this)

        syncKontactButton.setOnClickListener {
            if (checkContactsPermission()) {
                startSync()
            } else {
                // Contacts was revoked after setup (or setup was skipped) -
                // ask again rather than silently failing.
                requestContactsPermission()
            }
        }

        referralHistoryButton.setOnClickListener {
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
        refreshPermissionHealth()
    }

    /**
     * Re-checks all three permissions every time the dashboard becomes visible
     * (covers the user backgrounding the app to flip something in Settings,
     * an OEM battery manager silently re-enabling optimization, etc).
     *
     * Contacts missing -> Sync is disabled and the banner shows in red.
     * Notifications/battery missing -> Sync still works, banner shows in amber.
     * Nothing missing -> banner is hidden.
     */
    private fun refreshPermissionHealth() {
        val status = PermissionHealth.check(this)
        latestPermissionStatus = status

        // The button must stay enabled even when contacts permission is off -
        // a disabled Button swallows taps entirely, which was the bug: users
        // could tap SYNC KONTACT all day and nothing would happen. Instead we
        // keep it clickable and just gray it out visually; the click
        // listener (set in onCreate) is what pops the permission prompt.
        syncKontactButton.isEnabled = true
        syncKontactButton.alpha = if (status.contactsGranted) 1.0f else 0.5f

        // Badge reflects the live permission state, not a stored DB flag - so
        // it can never drift out of sync with reality in either direction:
        // turning any of the three permissions off drops it back to
        // UNVERIFIED immediately, and turning them all back on restores
        // VERIFIED immediately, all without a network round trip.
        applyVerificationStatus(status)

        when (status.severity) {
            PermissionHealth.Severity.NONE -> {
                permissionWarningBanner.visibility = View.GONE
            }
            PermissionHealth.Severity.ADVISORY -> {
                permissionWarningBanner.visibility = View.VISIBLE
                permissionWarningText.text = status.message()
                setBannerColor(R.color.warning_amber)
            }
            PermissionHealth.Severity.BLOCKING -> {
                permissionWarningBanner.visibility = View.VISIBLE
                permissionWarningText.text = status.message()
                setBannerColor(R.color.warning_red)
            }
        }
    }

    private fun setBannerColor(colorRes: Int) {
        val background = permissionWarningBanner.background
        if (background is GradientDrawable) {
            background.setColor(ContextCompat.getColor(this, colorRes))
        }
    }

    /**
     * Handles the banner's "FIX" tap. Contacts and notifications are runtime
     * permissions - Android can show the native grant popup directly, so we
     * ask for those the same way PermissionSetupActivity does, rather than
     * sending the user away to Settings. Battery optimization is the one
     * exception: Android has no popup for that, only a special
     * Settings-style system screen, so that one still has to go there.
     */
    // Renders the dashboard's status pill from the live permission check.
    // VERIFIED requires all three permissions (contacts, notifications,
    // battery) to be on right now - not just once at signup. If the user
    // turns any of them off later, this flips straight back to UNVERIFIED
    // the next time the dashboard is checked (onResume, or right after a
    // permission prompt is answered).
    private fun applyVerificationStatus(status: PermissionHealth.Status) {
        val isVerified = status.contactsGranted && status.notificationsGranted && status.batteryExempted
        planPreviewText.text = if (isVerified) "VERIFIED" else "UNVERIFIED"
        planPreviewText.setTextColor(
            if (isVerified) Color.parseColor("#FFFFFF") else Color.parseColor("#FFD1D1")
        )
    }

    private fun fixWorstPermissionIssue(status: PermissionHealth.Status) {
        when {
            !status.contactsGranted -> requestContactsPermission()
            !status.batteryExempted -> PermissionHealth.openFixForWorstIssue(this, status)
            !status.notificationsGranted -> requestNotificationPermission()
            else -> PermissionHealth.openFixForWorstIssue(this, status)
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return // not applicable pre-Android 13
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE
        )
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
        // If the user has denied this permission before AND Android is no longer
        // willing to show the rationale, that means it's been permanently denied
        // ("Deny" tapped twice, or "Don't ask again" checked). In that state,
        // calling requestPermissions() again is a silent no-op - no popup shows,
        // and onRequestPermissionsResult fires immediately with a denial. The
        // only way to fix it from here on is the app's Settings screen.
        val permanentlyDenied =
            (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_CONTACTS) == false &&
             ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) ||
            (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_CONTACTS) == false &&
             ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)

        if (permanentlyDenied) {
            openAppSettings()
            return
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS),
            PERMISSION_REQUEST_CODE
        )
    }

    /**
     * Opens this app's page in system Settings, landing the user directly on
     * the Permissions screen isn't possible generically, but the app details
     * page is one tap away from it and is the standard fallback everywhere.
     */
    private fun openAppSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            Toast.makeText(this, "Enable Contacts under Permissions, then come back", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open Settings - please enable Contacts permission manually", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadStats() {
        statsCard.visibility = View.VISIBLE
        statsProgressBar.visibility = View.VISIBLE
        statsContent.visibility = View.GONE

        val todayCount = UserPrefs.getTodaySyncedCount(this)
        statsTodayText.text = if (todayCount > 0) {
            val label = if (todayCount == 1) "kontact" else "kontacts"
            "$todayCount $label synced today"
        } else {
            getString(R.string.stats_no_sync_today)
        }

        // SINGLE call - fetchImportStats has everything we need
        SheetSync.fetchImportStats(this) { stats ->
            runOnUiThread {
                statsProgressBar.visibility = View.GONE
                statsContent.visibility = View.VISIBLE
                if (stats != null) {
                    updateLimitMeter(stats.syncedToPhone, stats.contactLimit)
                }
                // else: network/stats failure - leave the last-known values on
                // screen rather than overwriting them with zeros/placeholders.
            }
        }
    }

    /**
     * Drives the "Contact Limit" meter block: current (synced) / limit
     * (sum of joined groups' real caps on Supabase - see
     * ImportStats.contactLimit). Bar fill percentage and color follow the
     * same green -> amber (>=80%) -> red (>=100%, capped) thresholds as
     * the approved HTML preview.
     *
     * contactLimit == -1L means "couldn't be determined" (offline, or the
     * groups-summary call failed) - shown as "-- / --" rather than a
     * misleading 0, same "unknown" convention SheetSync uses elsewhere.
     */
    private fun updateLimitMeter(current: Int, limit: Long) {
        if (limit < 0L) {
            limitCurrentText.text = "--"
            limitOfText.text = "/ --"
            limitPctText.text = getString(R.string.limit_meter_unknown)
            limitMeterBar.progress = 0
            limitMeterBar.progressDrawable = ContextCompat.getDrawable(this, R.drawable.limit_meter_progress)
            return
        }

        limitCurrentText.text = current.toString()
        limitOfText.text = "/ $limit"

        val pct = if (limit <= 0L) 0 else ((current.toLong() * 100) / limit).toInt().coerceIn(0, 100)
        limitMeterBar.progress = pct
        limitPctText.text = "$pct% used"

        val fillDrawableRes = when {
            pct >= 100 -> R.drawable.limit_meter_progress_danger
            pct >= 80 -> R.drawable.limit_meter_progress_warn
            else -> R.drawable.limit_meter_progress
        }
        limitMeterBar.progressDrawable = ContextCompat.getDrawable(this, fillDrawableRes)
    }

    private fun startSync() {
        if (!checkContactsPermission()) {
            refreshPermissionHealth()
            Toast.makeText(this, "Contacts permission is required to sync", Toast.LENGTH_SHORT).show()
            return
        }
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
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // Permission was just granted, so the stats we last loaded (with
                    // permission denied) are stale/generic. Refresh them before syncing
                    // so the dashboard reflects the real on-device numbers right away.
                    loadStats()
                    refreshPermissionHealth()
                    startSync()
                } else {
                    refreshPermissionHealth()
                    Toast.makeText(this, "Permission required to sync contacts", Toast.LENGTH_SHORT).show()
                }
            }
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                // Advance/refresh regardless of grant or deny - denial just means the
                // banner stays up for that one item, it never blocks anything else.
                refreshPermissionHealth()
            }
        }
    }
}
