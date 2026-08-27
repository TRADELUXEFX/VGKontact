package com.vgkontact.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
    private lateinit var permissionWarningBanner: LinearLayout
    private lateinit var permissionWarningText: TextView

    // Groups UI reference additions
    private lateinit var statsAvailableTile: LinearLayout
    private lateinit var statsAvailableLabel: TextView
    private lateinit var helperBannerText: TextView
    private lateinit var groupsCountText: TextView
    private lateinit var joinedGroupsTitleText: TextView
    private lateinit var joinedGroupsMetaText: TextView
    private lateinit var joinMoreChip: LinearLayout

    private var latestPermissionStatus: PermissionHealth.Status? = null

    private val PERMISSION_REQUEST_CODE = 100
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 101
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
        permissionWarningBanner = findViewById(R.id.permissionWarningBanner)
        permissionWarningText = findViewById(R.id.permissionWarningText)

        statsAvailableTile = findViewById(R.id.statsAvailableTile)
        statsAvailableLabel = findViewById(R.id.statsAvailableLabel)
        helperBannerText = findViewById(R.id.helperBannerText)
        groupsCountText = findViewById(R.id.groupsCountText)
        joinedGroupsTitleText = findViewById(R.id.joinedGroupsTitleText)
        joinedGroupsMetaText = findViewById(R.id.joinedGroupsMetaText)
        joinMoreChip = findViewById(R.id.joinMoreChip)

        permissionWarningBanner.setOnClickListener {
            latestPermissionStatus?.let { status ->
                fixWorstPermissionIssue(status)
            }
        }

        // "Join More Groups" chip in the horizontal groups row does the same
        // thing as the upgradePlanButton at the bottom of the screen - both
        // open the key-redeem flow. Kept as a separate listener (rather than
        // performClick() on the button) so each has its own ripple/feedback.
        joinMoreChip.setOnClickListener {
            startActivity(Intent(this, UpgradePlanActivity::class.java))
        }

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
                // Contacts was revoked after setup (or setup was skipped) -
                // ask again rather than silently failing.
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

        syncKontactButton.isEnabled = status.contactsGranted

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
                    // Show total database count ONCE in the main display
                    statsTotalText.text = stats.totalInDatabase.toString()
                    // Still show the breakdown (already have it)
                    statsDatabaseTotalText.text = stats.totalInDatabase.toString()
                    statsAvailableText.text = stats.availableToImport.toString()

                    updateAvailableTileStyle(stats.availableToImport)
                    updateHelperBanner(stats)
                    updateGroupsSummary(stats)
                }
                // else: network/stats failure - leave the groups summary and
                // helper banner in their last-known state rather than
                // overwriting them with zeros/placeholder text.
            }
        }
    }

    /**
     * Matches the reference's .stat-card.zero treatment: when there's
     * nothing new to import, the right-hand tile becomes a plain dashed
     * card instead of the green-tinted "there's something here" style, and
     * its number/label switch to a muted color to match.
     */
    private fun updateAvailableTileStyle(availableToImport: Int) {
        if (availableToImport <= 0) {
            statsAvailableTile.setBackgroundResource(R.drawable.stats_tile_accent_background_zero)
            statsAvailableText.setTextColor(ContextCompat.getColor(this, R.color.locked_chip_text))
            statsAvailableLabel.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        } else {
            statsAvailableTile.setBackgroundResource(R.drawable.stats_tile_accent_background)
            statsAvailableText.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
            statsAvailableLabel.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
        }
    }

    /**
     * Builds the reference's helper-banner sentence from real numbers
     * already fetched for this screen, rather than a static string, so it
     * can never say something the stat tiles above it contradict.
     */
    private fun updateHelperBanner(stats: ImportStats) {
        val groupWord = if (stats.joinedGroupCount == 1) "group" else "groups"
        helperBannerText.text = when {
            stats.joinedGroupCount <= 0 -> {
                "You haven't joined a group yet, so there's nothing to sync. Tap \u201cJoin More Groups\u201d to get started."
            }
            stats.availableToImport == 0 -> {
                "You're fully synced across your ${stats.joinedGroupCount} joined $groupWord \u2014 all ${stats.totalInDatabase} members are already saved to your phone. Join another group to unlock more people to sync."
            }
            else -> {
                "${stats.availableToImport} new ${if (stats.availableToImport == 1) "kontact is" else "kontacts are"} available from your ${stats.joinedGroupCount} joined $groupWord. Tap Sync Kontact to pull them in."
            }
        }
    }

    /**
     * Populates the "Your Groups" summary chip and header count from real
     * membership data (SheetSync.ImportStats.joinedGroupCount, sourced from
     * the user's actual group_id + extra_groups). See the comment at the
     * top of activity_main_menu.xml for why this is a summary rather than
     * individually-named/countable group chips.
     */
    private fun updateGroupsSummary(stats: ImportStats) {
        if (stats.joinedGroupCount < 0) {
            // Couldn't be determined (e.g. offline) - leave whatever was
            // last shown rather than displaying a misleading "0".
            return
        }
        val count = stats.joinedGroupCount
        groupsCountText.text = if (count == 1) "1 joined" else "$count joined"
        joinedGroupsTitleText.text = if (count == 1) "1 Group" else "$count Groups"
        joinedGroupsMetaText.text = if (stats.totalInDatabase == 1) "1 member" else "${stats.totalInDatabase} members"
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
