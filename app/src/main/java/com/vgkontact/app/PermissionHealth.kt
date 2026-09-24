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
 *   - Notifications, battery, and OEM autostart are ADVISORY: sync still
 *     works, it's just less reliable/visible without them, so we warn but
 *     never block.
 *
 * OEM autostart: on top of stock Android's battery optimization (which
 * isIgnoringBatteryOptimizations() checks and which the Settings dialog
 * covers), several manufacturers ship their OWN separate background-app
 * killer that stock Android has no API to query or request an exemption
 * from - isIgnoringBatteryOptimizations() can report true (exempted) while
 * the OEM's own manager still kills the app anyway. This is a common cause
 * of "sync just stopped happening" on these devices specifically, so it's
 * surfaced as its own advisory check with a deep-link to that
 * manufacturer's autostart settings screen where one is known.
 *
 * These OEM screens are NOT a stable public Android API - manufacturers
 * change or remove them across OS versions without notice, unlike the
 * stock intents used elsewhere in this file. So this check degrades
 * gracefully: if the device is a known OEM, show the advisory with a
 * best-effort deep-link (falling back to the app's details page if that
 * specific screen doesn't exist on this build); if the device isn't one of
 * the known OEMs, don't show anything - most devices don't need this.
 */
object PermissionHealth {

    enum class Severity { NONE, ADVISORY, BLOCKING }

    /** Manufacturers with a known separate autostart/background-lock manager. */
    private val KNOWN_AGGRESSIVE_OEMS = setOf(
        "xiaomi", "redmi", "poco",       // MIUI/HyperOS
        "huawei", "honor",               // EMUI/HarmonyOS/Magic UI
        "oppo", "realme", "oneplus",     // ColorOS (OnePlus merged onto ColorOS)
        "vivo", "iqoo",                  // OriginOS/FuntouchOS
        "tecno", "infinix", "itel"       // Transsion brands, HiOS/XOS - common in Nigeria
    )

    private fun isKnownAggressiveOem(): Boolean =
        Build.MANUFACTURER.lowercase() in KNOWN_AGGRESSIVE_OEMS

    data class Status(
        val contactsGranted: Boolean,
        val notificationsGranted: Boolean,
        val batteryExempted: Boolean,
        /**
         * True when either this isn't a known-aggressive OEM (nothing to
         * check), or it is one and the user has already been sent to that
         * OEM's autostart screen at least once (see
         * UserPrefs.hasSeenOemAutostartPrompt). We can't read the OEM's
         * autostart toggle back programmatically - no stock API exists for
         * it - so unlike the other three fields this isn't a live
         * yes/no of the actual setting, it's "have we at least prompted
         * for it." Kept false only for known-aggressive OEMs who haven't
         * been prompted yet, so the advisory shows once per device and
         * doesn't nag forever after they've been sent there.
         */
        val oemAutostartLikelyOk: Boolean
    ) {
        val severity: Severity
            get() = when {
                !contactsGranted -> Severity.BLOCKING
                !notificationsGranted || !batteryExempted || !oemAutostartLikelyOk -> Severity.ADVISORY
                else -> Severity.NONE
            }

        /**
         * The live setup stage, used for tracking/reporting only (not for
         * gating any feature in the app itself). Reports EVERY permission
         * that is currently on, not just the furthest one reached in a
         * fixed order - so any combination is representable, not only the
         * ones that happen to match a strict Contacts-then-Notifications-
         * then-Battery ladder.
         *
         * Format: comma-separated labels, one per permission that is
         * currently on, always in this fixed order:
         *   1 = Contacts
         *   2 = Notifications
         *   3 = Battery
         * e.g. "1,3" means Contacts and Battery are on, Notifications is
         * not. "0" means none are on.
         *
         * This always reflects right now - it goes up AND down as
         * permissions are toggled, unlike the separate permanent
         * "first ever reached this stage" record kept in the database
         * (see SheetSync.reportSetupStage). The admin panel is expected to
         * use both: this live value to see where someone currently
         * stands, and the permanent first-reached timestamps to decide
         * anything reward-related, since those never move backwards.
         *
         * NOTE: oemAutostartLikelyOk is deliberately NOT folded into this
         * string. This format is a fixed 3-slot protocol that something
         * downstream (the admin panel, going by the doc above) already
         * parses - silently adding a 4th label risks breaking whatever
         * reads it without warning. If OEM-autostart tracking needs to
         * reach the backend/admin panel, that should be a deliberate,
         * separate decision (e.g. its own reported field), not smuggled
         * into this string.
         */
        val stage: String
            get() {
                val labels = listOfNotNull(
                    "1".takeIf { contactsGranted },
                    "2".takeIf { notificationsGranted },
                    "3".takeIf { batteryExempted }
                )
                return if (labels.isEmpty()) "0" else labels.joinToString(",")
            }

        /** Short, user-facing summary of what's missing, worst issue first. */
        fun message(): String {
            if (!contactsGranted) {
                return "Contacts permission is off - Sync is disabled until it's allowed"
            }
            val missing = mutableListOf<String>()
            if (!notificationsGranted) missing.add("Notifications")
            if (!batteryExempted) missing.add("Background activity")
            if (!oemAutostartLikelyOk) missing.add("Autostart")
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

        // See the Status.oemAutostartLikelyOk doc comment: true for every
        // device except a known-aggressive OEM that hasn't been prompted
        // yet, since there's no real setting we can read back directly.
        val oemAutostartLikelyOk = !isKnownAggressiveOem() || UserPrefs.hasSeenOemAutostartPrompt(context)

        return Status(contacts, notifications, battery, oemAutostartLikelyOk)
    }

    /**
     * Deep-links to the most relevant system Settings screen for whichever
     * permission is currently the biggest problem (contacts first, since
     * that's the blocking one, then battery, then notifications, then OEM
     * autostart last - it's the least certain fix of the four, since we
     * can't confirm the OEM screen exists or that toggling it worked).
     */
    fun openFixForWorstIssue(activity: Activity, status: Status) {
        when {
            !status.contactsGranted -> openAppDetailsSettings(activity)
            !status.batteryExempted -> openBatterySettings(activity)
            !status.notificationsGranted -> openAppNotificationSettings(activity)
            !status.oemAutostartLikelyOk -> openOemAutostartSettings(activity)
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

    /**
     * Best-effort deep-link to this OEM's autostart/background-lock
     * screen. These are undocumented, OEM-specific activities - not a
     * stable public API - so exact package/class names vary by device
     * and OS version and can change or vanish entirely without notice.
     * Each one is tried in turn; the first that resolves is launched.
     * If none resolve (unknown model/OS version combination, or an
     * unlisted OEM), falls back to the app's own details page, which at
     * minimum lets the user find their way there manually.
     *
     * Marks hasSeenOemAutostartPrompt regardless of which branch fires,
     * including the fallback - once we've made a genuine attempt to get
     * the user to the right place, Status stops re-surfacing this
     * advisory every time the dashboard resumes. See the field's doc
     * comment on Status for why this can't instead be verified directly.
     */
    private fun openOemAutostartSettings(activity: Activity) {
        UserPrefs.setSeenOemAutostartPrompt(activity, true)

        val manufacturer = Build.MANUFACTURER.lowercase()
        val candidates: List<Intent> = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> listOf(
                Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity"))
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> listOf(
                Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
                Intent().setComponent(android.content.ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
            )
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
                Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                Intent().setComponent(android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
            )
            manufacturer.contains("tecno") || manufacturer.contains("infinix") || manufacturer.contains("itel") -> listOf(
                Intent().setComponent(android.content.ComponentName("com.transsion.phonemanager", "com.itel.autobootmanager.activity.AutoBootMgrActivity")),
                Intent().setComponent(android.content.ComponentName("com.transsion.phonemanager", "com.transsion.phonemanager.module.security.autoboot.AutoBootManagerActivity"))
            )
            else -> emptyList()
        }

        val instruction = "Allow VGKontact to auto-start / run in background, then come back"
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
                android.widget.Toast.makeText(activity, instruction, android.widget.Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                // This specific screen doesn't exist on this device/OS
                // version - try the next candidate for this OEM.
            }
        }

        // Nothing resolved - unknown model or OS version. Fall back to the
        // app details page; not a direct fix, but the best available.
        openAppDetailsSettings(activity)
    }
}
