package com.vgkontact.app

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UserPrefs {
    private const val PREF_NAME = "vgkontact_prefs"
    private const val KEY_WHATSAPP = "whatsapp"
    private const val KEY_REFERRAL = "referral"
    private const val KEY_NAME = "name"
    private const val KEY_IS_REGISTERED = "is_registered"
    private const val KEY_DATE_REGISTERED = "date_registered"
    private const val KEY_NOTIFICATION_FREQUENCY_HOURS = "notification_frequency_hours"

    @Volatile private var cachedPrefs: SharedPreferences? = null

    // Plain (unencrypted) SharedPreferences. Previously this used
    // EncryptedSharedPreferences (androidx.security:security-crypto), which
    // is now deprecated and backed by Google Tink's native (JNI) crypto
    // code. On this app's very first launch path (OnboardingActivity.onCreate
    // -> isRegistered -> getPrefs, called before any UI is even shown), a
    // native-level failure inside that library kills the whole process
    // immediately - no Java exception, no crash dialog, nothing catchable
    // from Kotlin, matching a hard-to-diagnose silent crash seen on at
    // least one real device. Switching to plain SharedPreferences removes
    // Tink/Keystore from this path entirely. The data stored here (a
    // WhatsApp number) doesn't need Keystore-level encryption to justify
    // that risk.
    private fun buildPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        val prefs = buildPrefs(context)
        cachedPrefs = prefs
        return prefs
    }

    fun saveUser(context: Context, whatsapp: String, referral: String, name: String) {
        val dateRegistered = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        getPrefs(context).edit().apply {
            putString(KEY_WHATSAPP, whatsapp)
            putString(KEY_REFERRAL, referral)
            putString(KEY_NAME, name)
            putBoolean(KEY_IS_REGISTERED, true)
            putString(KEY_DATE_REGISTERED, dateRegistered)
            apply()
        }
    }

    fun isRegistered(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_REGISTERED, false)
    }

    fun getWhatsapp(context: Context): String? {
        return getPrefs(context).getString(KEY_WHATSAPP, null)
    }

    fun getReferral(context: Context): String? {
        return getPrefs(context).getString(KEY_REFERRAL, null)
    }

    fun getName(context: Context): String? {
        return getPrefs(context).getString(KEY_NAME, null)
    }

    fun getDateRegistered(context: Context): String? {
        return getPrefs(context).getString(KEY_DATE_REGISTERED, null)
    }

    private const val KEY_SYNCED_NUMBERS = "synced_numbers"

    fun getSyncedNumbers(context: Context): MutableSet<String> {
        return HashSet(getPrefs(context).getStringSet(KEY_SYNCED_NUMBERS, emptySet()) ?: emptySet())
    }

    fun addSyncedNumbers(context: Context, numbers: Set<String>) {
        val current = getSyncedNumbers(context)
        current.addAll(numbers)
        getPrefs(context).edit().putStringSet(KEY_SYNCED_NUMBERS, current).apply()
    }

    /**
     * Fully REPLACES the synced-numbers set, instead of only ever adding
     * to it. Needed so that a number can become "not synced" again after
     * its VG KONTACT contact is deleted from the phone - addSyncedNumbers()
     * alone can never un-mark a number once it's been added, even if the
     * contact backing it no longer exists.
     */
    fun setSyncedNumbers(context: Context, numbers: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_SYNCED_NUMBERS, numbers).apply()
    }

    private const val KEY_CONTACT_COUNTER = "contact_counter"

    fun getContactCounter(context: Context): Int {
        return getPrefs(context).getInt(KEY_CONTACT_COUNTER, 0)
    }

    fun setContactCounter(context: Context, value: Int) {
        getPrefs(context).edit().putInt(KEY_CONTACT_COUNTER, value).apply()
    }

    private const val KEY_LAST_KNOWN_GROUP_COUNT = "last_known_group_count"

    /**
     * The server-side total contact count across this user's groups, as of
     * the last time we checked. Used to decide whether a full sync is
     * actually worth running (see MainMenuActivity.autoSyncQuietly) -
     * -1 means "never checked yet", so the first check always proceeds.
     */
    fun getLastKnownGroupCount(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_KNOWN_GROUP_COUNT, -1L)
    }

    fun setLastKnownGroupCount(context: Context, value: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_KNOWN_GROUP_COUNT, value).apply()
    }

    private const val KEY_LAST_SYNC_DATE = "last_sync_date"
    private const val KEY_TODAY_SYNCED_COUNT = "today_synced_count"
    private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"

    /**
     * Call this right after a sync adds `newlyAddedCount` contacts.
     * Resets the counter to 0 first if the last recorded sync wasn't today.
     * Also stores the exact millisecond timestamp of this sync, so the UI
     * can show a real clock time (e.g. "Today, 22:55") rather than just a
     * date - see getLastSyncTimestamp / getLastSyncDisplayText below.
     */
    fun recordSyncedToday(context: Context, newlyAddedCount: Int) {
        if (newlyAddedCount <= 0) return
        val now = Date()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val prefs = getPrefs(context)
        val lastDate = prefs.getString(KEY_LAST_SYNC_DATE, null)
        val baseCount = if (lastDate == today) prefs.getInt(KEY_TODAY_SYNCED_COUNT, 0) else 0
        prefs.edit()
            .putString(KEY_LAST_SYNC_DATE, today)
            .putInt(KEY_TODAY_SYNCED_COUNT, baseCount + newlyAddedCount)
            .putLong(KEY_LAST_SYNC_TIMESTAMP, now.time)
            .apply()
    }

    /**
     * Returns the exact moment of the last successful sync, or null if no
     * sync has ever completed on this device.
     */
    fun getLastSyncTimestamp(context: Context): Long? {
        val value = getPrefs(context).getLong(KEY_LAST_SYNC_TIMESTAMP, -1L)
        return if (value == -1L) null else value
    }

    /**
     * Human-friendly "Last synced" text: "Today, 22:55", "Yesterday, 22:55",
     * or "Aug 26, 22:55" for anything older. Returns null if never synced,
     * so the caller can show its own empty-state message instead.
     */
    fun getLastSyncDisplayText(context: Context): String? {
        val timestamp = getLastSyncTimestamp(context) ?: return null

        val syncCal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val nowCal = java.util.Calendar.getInstance()
        val todayCal = java.util.Calendar.getInstance()
        val yesterdayCal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }

        val sameDay = { a: java.util.Calendar, b: java.util.Calendar ->
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
            a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        }

        val timeText = SimpleDateFormat("HH:mm", Locale.US).format(Date(timestamp))

        return when {
            sameDay(syncCal, todayCal) -> "Today, $timeText"
            sameDay(syncCal, yesterdayCal) -> "Yesterday, $timeText"
            else -> SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(timestamp))
        }
    }

    /**
     * Returns how many contacts were synced today, or 0 if nothing has been
     * synced today (including if the last sync was on a previous day).
     */
    fun getTodaySyncedCount(context: Context): Int {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val prefs = getPrefs(context)
        val lastDate = prefs.getString(KEY_LAST_SYNC_DATE, null)
        return if (lastDate == today) prefs.getInt(KEY_TODAY_SYNCED_COUNT, 0) else 0
    }

    fun getNotificationFrequencyHours(context: Context): Int {
        return getPrefs(context).getInt(KEY_NOTIFICATION_FREQUENCY_HOURS, 24)
    }

    fun setNotificationFrequencyHours(context: Context, hours: Int) {
        getPrefs(context).edit().putInt(KEY_NOTIFICATION_FREQUENCY_HOURS, hours).apply()
    }

    private const val KEY_PERMISSION_SETUP_DONE = "permission_setup_done"

    /**
     * True once the user has been through the one-time Contacts -> Notifications ->
     * Battery priming flow (regardless of whether each was granted or denied).
     * Used so PermissionSetupActivity only ever runs once, right after registration.
     */
    fun isPermissionSetupDone(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PERMISSION_SETUP_DONE, false)
    }

    fun setPermissionSetupDone(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_PERMISSION_SETUP_DONE, true).apply()
    }

    private const val KEY_WALKTHROUGH_DONE = "walkthrough_done"

    /**
     * True once the user has been through the one-time feature walkthrough
     * (Sync, Contact limit, Get viewers, Referrals) shown right after
     * PermissionSetupActivity, before the dashboard. Gates re-entry so
     * returning users skip straight to MainMenuActivity.
     */
    fun isWalkthroughDone(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_WALKTHROUGH_DONE, false)
    }

    fun setWalkthroughDone(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_WALKTHROUGH_DONE, true).apply()
    }

    private const val KEY_LAST_LIMIT_ZONE_NOTIFIED = "last_limit_zone_notified"

    /**
     * Remembers which contact-limit zone ("none", "warning" at 80%+, or
     * "danger" at 100%) the user was last notified about, so the proactive
     * limit warning/reached notification only fires once per crossing -
     * not on every single sync while already in that zone. Resets back to
     * "none" naturally once the user unlocks more contacts and the
     * percentage drops back under 80%, so a future re-crossing notifies
     * again.
     */
    fun getLastLimitZoneNotified(context: Context): String {
        return getPrefs(context).getString(KEY_LAST_LIMIT_ZONE_NOTIFIED, "none") ?: "none"
    }

    fun setLastLimitZoneNotified(context: Context, zone: String) {
        getPrefs(context).edit().putString(KEY_LAST_LIMIT_ZONE_NOTIFIED, zone).apply()
    }

    private const val KEY_SYNC_PAUSED = "sync_paused"

    /**
     * True once the user has tapped "Delete My Contacts" on the dashboard.
     * SheetCheckWorker checks this alongside the contacts-permission check
     * before every automatic sync - while true, no contacts are read or
     * sent anywhere, regardless of what permission state the OS reports.
     * This is enforced entirely on-device: it works offline and can't be
     * blocked by a failed network call, unlike a server-side flag would be.
     */
    fun isSyncPaused(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SYNC_PAUSED, false)
    }

    fun setSyncPaused(context: Context, paused: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SYNC_PAUSED, paused).apply()
    }

    private const val KEY_LAST_PERMISSION_SEVERITY_LOGGED = "last_permission_severity_logged"

    /**
     * Same "notify once per crossing" pattern as the limit-zone key above,
     * but for PermissionHealth.Severity. refreshPermissionHealth() runs on
     * every dashboard resume, so without this guard a permission issue
     * would get logged to ActivityLog on every single resume instead of
     * once when it's first detected - resets to "NONE" once fixed, so a
     * future re-occurrence logs again.
     */
    fun getLastPermissionSeverityLogged(context: Context): String {
        return getPrefs(context).getString(KEY_LAST_PERMISSION_SEVERITY_LOGGED, "NONE") ?: "NONE"
    }

    fun setLastPermissionSeverityLogged(context: Context, severity: String) {
        getPrefs(context).edit().putString(KEY_LAST_PERMISSION_SEVERITY_LOGGED, severity).apply()
    }
}
