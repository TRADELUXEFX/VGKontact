package com.vgkontact.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UserPrefs {
    private const val PREF_NAME = "vgkontact_prefs"
    private const val KEY_WHATSAPP = "whatsapp"
    private const val KEY_REFERRAL = "referral"
    private const val KEY_IS_REGISTERED = "is_registered"
    private const val KEY_DATE_REGISTERED = "date_registered"
    private const val KEY_NOTIFICATION_FREQUENCY_HOURS = "notification_frequency_hours"

    // Cached after first successful open so we don't re-touch the Keystore
    // (and re-risk the failure below) on every single call.
    @Volatile private var cachedPrefs: SharedPreferences? = null

    private fun buildPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // The Keystore-backed AES key behind EncryptedSharedPreferences can get
    // out of sync with the encrypted file itself - e.g. after "Clear cache"
    // on some OEMs (seen on Samsung), or if the Keystore entry is evicted/
    // regenerated for any reason. When that happens every read/write throws
    // (commonly AEADBadTagException/GeneralSecurityException), and since
    // this is called from the very first line of the app's launch path
    // (OnboardingActivity.onCreate -> isRegistered), an uncaught throw here
    // crashes on every single launch with no way back in.
    //
    // Recovery: if opening/using the encrypted file throws, delete the
    // corrupted prefs file and recreate it fresh. This does mean a user
    // hitting this bug loses their locally-stored registration state once
    // (they'll see onboarding again) - but that's recoverable, whereas an
    // unrecoverable launch crash is not.
    private fun getPrefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }

        return try {
            val prefs = buildPrefs(context)
            // Force a real read now (not just object construction) so a
            // corrupted file throws here, not later on some random screen.
            prefs.getBoolean(KEY_IS_REGISTERED, false)
            cachedPrefs = prefs
            prefs
        } catch (e: Exception) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
            context.deleteSharedPreferences(PREF_NAME)
            val fresh = buildPrefs(context)
            cachedPrefs = fresh
            fresh
        }
    }

    fun saveUser(context: Context, whatsapp: String, referral: String) {
        val dateRegistered = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        getPrefs(context).edit().apply {
            putString(KEY_WHATSAPP, whatsapp)
            putString(KEY_REFERRAL, referral)
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

    private const val KEY_CONTACT_COUNTER = "contact_counter"

    fun getContactCounter(context: Context): Int {
        return getPrefs(context).getInt(KEY_CONTACT_COUNTER, 0)
    }

    fun setContactCounter(context: Context, value: Int) {
        getPrefs(context).edit().putInt(KEY_CONTACT_COUNTER, value).apply()
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
}
