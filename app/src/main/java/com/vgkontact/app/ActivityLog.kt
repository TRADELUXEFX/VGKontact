package com.vgkontact.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backs the bell icon's activity feed (ActivityLogActivity). Stores a
 * capped, most-recent-first list of events as a single JSON array in
 * SharedPreferences - no separate table/DB needed for something this
 * small. Also tracks how many entries are unread so the bell can show a
 * red dot, cleared the moment the feed is opened.
 *
 * Event types map to the six things happening elsewhere in the app that
 * a user would actually want a history of: contact syncs, limit
 * warnings/reached, limit increases, permission issues, referral joins
 * (once server data supports it), and sync-frequency changes.
 */
object ActivityLog {

    enum class Type {
        CONTACT_SYNCED,
        LIMIT_WARNING,
        LIMIT_REACHED,
        LIMIT_INCREASED,
        REFERRAL_JOINED,
        PERMISSION_ISSUE,
        SYNC_FREQUENCY_CHANGED
    }

    private const val PREF_NAME = "vgkontact_activity_log"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_UNREAD_COUNT = "unread_count"
    private const val MAX_ENTRIES = 100

    data class Entry(
        val type: Type,
        val message: String,
        val timestamp: Long
    ) {
        fun displayTime(): String {
            return SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(timestamp))
        }
    }

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Records a new event and bumps the unread count. Safe to call from
     * any screen - callers don't need to know whether the feed is
     * currently open.
     */
    fun add(context: Context, type: Type, message: String) {
        val prefs = getPrefs(context)
        val array = readArray(prefs)

        val entry = JSONObject().apply {
            put("type", type.name)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
        }

        // Newest first - insert at index 0 rather than appending, so
        // getAll() never needs to reverse anything.
        val updated = JSONArray()
        updated.put(entry)
        for (i in 0 until array.length()) {
            updated.put(array.getJSONObject(i))
        }

        // Cap the list so this can't grow unbounded over the life of the
        // install - oldest entries fall off past MAX_ENTRIES.
        val trimmed = JSONArray()
        for (i in 0 until minOf(updated.length(), MAX_ENTRIES)) {
            trimmed.put(updated.getJSONObject(i))
        }

        prefs.edit()
            .putString(KEY_ENTRIES, trimmed.toString())
            .putInt(KEY_UNREAD_COUNT, getUnreadCount(context) + 1)
            .apply()
    }

    fun getAll(context: Context): List<Entry> {
        val array = readArray(getPrefs(context))
        val result = mutableListOf<Entry>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val type = try {
                Type.valueOf(obj.getString("type"))
            } catch (e: IllegalArgumentException) {
                continue // Unknown/old type - skip rather than crash.
            }
            result.add(Entry(type, obj.getString("message"), obj.getLong("timestamp")))
        }
        return result
    }

    fun getUnreadCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_UNREAD_COUNT, 0)
    }

    fun hasUnread(context: Context): Boolean {
        return getUnreadCount(context) > 0
    }

    fun markAllRead(context: Context) {
        getPrefs(context).edit().putInt(KEY_UNREAD_COUNT, 0).apply()
    }

    private fun readArray(prefs: android.content.SharedPreferences): JSONArray {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }
}
