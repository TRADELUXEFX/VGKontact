package com.vgkontact.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Ongoing permission-health check, run every time MainMenuActivity resumes.
 *
 * PermissionSetupActivity only asks once, right after registration. This is
 * the follow-up: users can revoke any of the three permissions later from
 * system Settings (or an OEM battery manager can silently re-enable
 * optimization), so the dashboard needs to notice and surface it - not just
 * assume the initial grant still holds.
 *
 * Severity is split in two:
 *   - Contacts is BLOCKING: sync literally cannot work without it, so the
 *     Sync button gets disabled until it's granted again.
 *   - Notifications and battery are ADVISORY: sync still works, it's just
 *     less reliable/visible without them, so we warn but never block.
 */
object PermissionHealth {

    enum class Severity { NONE, ADVISORY, BLOCKING }

    data class Status(
        val contactsGranted: Boolean,
        val notificationsGranted: Boolean,
        val batteryExempted: Boolean
    ) {
        val severity: Severity
            get() = when {
                !contactsGranted -> Severity.BLOCKING
                !notificationsGranted || !batteryExempted -> Severity.ADVISORY
                else -> Severity.NONE
            }

        /**
         * The live 0-3 setup stage, used for tracking/reporting only (not
         * for gating any feature in the app itself):
         *   0 = nothing on
         *   1 = Contacts on (sync can work)
         *   2 = Contacts + Notifications on
         *   3 = all three on (Contacts + Notifications + Battery)
         *
         * This always reflects right now - it goes up AND down as
         * permissions are toggled, unlike the separate permanent
         * "first ever reached this stage" record kept in the database
         * (see SheetSync.reportSetupStage). The admin panel is expected to
         * use both: this live number to see where someone currently
         * stands, and the permanent first-reached timestamps to decide
         * anything reward-related, since those never move backwards.
         */
        val stage: Int
            get() = when {
                !contactsGranted -> 0
                !notificationsGranted -> 1
                !batteryExempted -> 2
                else -> 3
            }

        /** Short, user-facing summary of what's missing, worst issue first. */
        fun message(): String {
            if (!contactsGranted) {
                return "Contacts permission is off - Sync is disabled until it's allowed"
            }
            val missing = mutableListOf<String>()
            if (!notificationsGranted) missing.add("Notifications")
            if (!batteryExempted) missing.add("Background activity")
            return when (missing.size) {
                0 -> ""
                1 -> "${missing[0]} is off - some features may be less reliable"
                else -> "${missing.joinToString(" & ")} are off - some features may be less reliable"
            }
        }
    }

    fun check(context: Context): Status {
        val contacts = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // not required pre-13, treat as satisfied
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(context.packageName)

        return Status(contacts, notifications, battery)
    }

    /**
     * Deep-links to the most relevant system Settings screen for whichever
     * permission is currently the biggest problem (contacts first, since
     * that's the blocking one, then battery, then notifications).
     */
    fun openFixForWorstIssue(activity: Activity, status: Status) {
        when {
            !status.contactsGranted -> openAppDetailsSettings(activity)
            !status.batteryExempted -> openBatterySettings(activity)
            !status.notificationsGranted -> openAppNotificationSettings(activity)
            else -> openAppDetailsSettings(activity)
        }
    }

    private fun openAppDetailsSettings(activity: Activity) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${activity.packageName}")
            activity.startActivity(intent)
        } catch (e: Exception) {
            // Nothing else useful to fall back to here.
        }
    }

    private fun openBatterySettings(activity: Activity) {
        val instruction = "Allow background activity (battery), then come back"
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:${activity.packageName}")
            activity.startActivity(intent)
            android.widget.Toast.makeText(activity, instruction, android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                android.widget.Toast.makeText(activity, instruction, android.widget.Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                openAppDetailsSettings(activity)
            }
        }
    }

    private fun openAppNotificationSettings(activity: Activity) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName)
            activity.startActivity(intent)
            android.widget.Toast.makeText(activity, "Enable Notifications, then come back", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            openAppDetailsSettings(activity)
        }
    }
}
