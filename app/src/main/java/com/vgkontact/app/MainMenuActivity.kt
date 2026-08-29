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
 * The "Increase Contact Limit" button (kontactGroupsButton) goes straight
 * to UpgradePlanActivity's generic code-redeem screen. There is no group
 * browser/picker in the app - which groups a code unlocks is decided
 * entirely by the admin server-side (keys.groups_unlock); the user never
 * sees or chooses a specific group ID.
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
    private lateinit var limitWarningText: TextView

    private var latestPermissionStatus: PermissionHealth.Status? = null
    // Guards against overlapping syncs - e.g. onResume firing again while an
    // earlier auto-sync is still in flight, or the user tapping the manual
    // Sync button while an auto-sync is already running in the background.
    private var isSyncing: Boolean = false

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
        limitWarningText = findViewById(R.id.limitWarningText)

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
            // This button is labeled "Increase Contact Limit" (see
            // activity_main_menu.xml, menu_increase_contact_limit) and should
            // take the user straight to the redeem-a-code screen, not the
            // group-browser screen.
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
        // Auto-sync every time this screen becomes visible, so newly added
        // kontacts (someone else registering elsewhere) show up without the
        // user needing to tap "Sync Kontact" themselves. Quiet by design -
        // no "checking..." toast, since this now fires on every app open/
        // return, not just an explicit user tap.
        autoSyncQuietly()
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

        // Same permanently-denied check as requestContactsPermission() -
        // once the user has denied this twice (or checked "Don't ask
        // again"), Android stops showing the popup entirely and silently
        // no-ops instead. Route to Settings in that case instead of a
        // dead-end tap.
        val permanentlyDenied =
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS) == false &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

        if (permanentlyDenied) {
            openAppSettings("Enable Notifications under Permissions, then come back")
            return
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
    private fun openAppSettings(instruction: String = "Enable Contacts under Permissions, then come back") {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            Toast.makeText(this, instruction, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open Settings - please enable it manually", Toast.LENGTH_LONG).show()
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
            limitWarningText.visibility = View.GONE
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

        // Proactive warning, not just a color change - tells the user in
        // words that they're about to run out, before a new kontact
        // actually fails to add. Same 80%/100% thresholds as the bar color,
        // so the wording always matches what the bar is showing.
        val remaining = (limit - current).coerceAtLeast(0L)
        when {
            pct >= 100 -> {
                limitWarningText.visibility = View.VISIBLE
                limitWarningText.text = "Limit reached - unlock more to keep adding kontacts"
                limitWarningText.setTextColor(ContextCompat.getColor(this, R.color.warning_red))
            }
            pct >= 80 -> {
                limitWarningText.visibility = View.VISIBLE
                val label = if (remaining == 1L) "spot" else "spots"
                limitWarningText.text = "Only $remaining $label left - unlock more before you run out"
                limitWarningText.setTextColor(ContextCompat.getColor(this, R.color.warning_amber))
            }
            else -> {
                limitWarningText.visibility = View.GONE
            }
        }

        // Notify (once) the moment the user actually crosses into the
        // warning/danger zone - not on every sync while already there,
        // otherwise this would repeat every single background/auto sync.
        // UserPrefs remembers the last state we notified about so this only
        // fires again if the user drops back under 80% (e.g. unlocks more)
        // and then climbs back up.
        val newZone = when {
            pct >= 100 -> "danger"
            pct >= 80 -> "warning"
            else -> "none"
        }
        val lastZone = UserPrefs.getLastLimitZoneNotified(this)
        if (newZone != lastZone) {
            UserPrefs.setLastLimitZoneNotified(this, newZone)
            if (newZone == "danger") {
                NotificationHelper.showLimitReachedNotification(this, current, limit)
            } else if (newZone == "warning") {
                NotificationHelper.showLimitWarningNotification(this, current, limit)
            }
        }
    }

    private fun startSync() {
        if (!checkContactsPermission()) {
            refreshPermissionHealth()
            Toast.makeText(this, "Contacts permission is required to sync", Toast.LENGTH_SHORT).show()
            return
        }
        if (isSyncing) {
            Toast.makeText(this, "Already syncing…", Toast.LENGTH_SHORT).show()
            return
        }
        isSyncing = true
        syncKontactButton.isEnabled = false
        val originalButtonText = syncKontactButton.text
        syncKontactButton.text = "Syncing..."
        Toast.makeText(this, "Checking for new Kontacts...", Toast.LENGTH_SHORT).show()
        NotificationHelper.showSyncStartedNotification(this)
        SheetSync.importAllContactsFromSheet(this) { submitted, failed, errorDetail ->
            runOnUiThread {
                isSyncing = false
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

    /**
     * Same underlying sync as startSync(), but silent - called automatically
     * every time the dashboard becomes visible (onResume), not from a user
     * tap. Skips the "Checking..." toast, the "No new numbers" toast, and
     * the sync-started notification, since those would fire constantly
     * (every app open/return) rather than in response to a deliberate
     * action. Only speaks up if it actually found something new to add, or
     * if something needs the user's attention (permission missing, no
     * internet) - otherwise it just quietly refreshes the numbers on screen.
     */
    private fun autoSyncQuietly() {
        if (!checkContactsPermission()) {
            // Don't nag on every resume - the permission banner already
            // covers this. Just skip the auto-sync silently.
            return
        }
        if (isSyncing) return
        isSyncing = true
        SheetSync.importAllContactsFromSheet(this) { submitted, failed, errorDetail ->
            runOnUiThread {
                isSyncing = false
                if (errorDetail == "NO_INTERNET") {
                    // Silent - no internet is common/expected background
                    // noise on a resume-triggered check, not worth a toast
                    // every time.
                    return@runOnUiThread
                }
                if (submitted > 0) {
                    val label = if (submitted == 1) "number" else "numbers"
                    Toast.makeText(this, "$submitted new $label added", Toast.LENGTH_LONG).show()
                    NotificationHelper.showSyncCompleteNotification(this, submitted, failed, errorDetail)
                    loadStats()
                }
                // submitted == 0 -> nothing new, stay quiet, no toast.
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
