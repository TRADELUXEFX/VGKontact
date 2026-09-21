package com.vgkontact.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Local (on-device) storage for the daily repost streak.
 *
 * Counting rule: one tap of "I've reposted today" = one counted repost
 * for that calendar day. Tapping again the same day does nothing.
 *
 * Streak rule: consecutive calendar days with a counted repost. If the
 * user misses a whole day, the streak resets to 0 the next time it is
 * read (see [getCurrentStreak]), so the number on screen is never stale.
 *
 * Uses its own SharedPreferences file rather than UserPrefs so this
 * feature stays fully self-contained and UserPrefs is untouched.
 *
 * ROLE WITH SUPABASE: the server (see RepostSync + repost_supabase.sql) is
 * the source of truth. This file is the on-device COPY - it lets the
 * screen show something instantly and keep working offline. When the
 * server answers, [saveServerStats] overwrites this copy. When the
 * server can't be reached, RepostActivity falls back to what's stored here.
 */
object RepostPrefs {

    private const val PREF_NAME = "vgkontact_repost_prefs"
    private const val KEY_LAST_DATE = "last_repost_date"       // yyyy-MM-dd of last counted repost
    private const val KEY_STREAK = "streak"                    // streak as of KEY_LAST_DATE
    private const val KEY_TOTAL = "total_reposts"
    private const val KEY_BEST = "best_streak"
    private const val KEY_HISTORY = "repost_dates"             // comma-separated yyyy-MM-dd, last 60 days
    private const val KEY_REACHED = "milestones_reached"       // comma-separated milestone day counts
    private const val KEY_PENDING = "pending_upload_date"      // yyyy-MM-dd of a repost the server hasn't confirmed

    /** Streak lengths that unlock a milestone. */
    val MILESTONES = listOf(3, 7, 14, 30)

    private const val HISTORY_LIMIT = 60

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun today(): String = fmt().format(Date())

    private fun yesterday(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        return fmt().format(cal.time)
    }

    fun hasRepostedToday(context: Context): Boolean =
        prefs(context).getString(KEY_LAST_DATE, null) == today()

    /**
     * Current live streak. If the last counted repost was neither today
     * nor yesterday, the streak has been broken and this returns 0
     * (the stored value is only rewritten on the next repost).
     */
    fun getCurrentStreak(context: Context): Int {
        val p = prefs(context)
        val last = p.getString(KEY_LAST_DATE, null) ?: return 0
        return if (last == today() || last == yesterday()) p.getInt(KEY_STREAK, 0) else 0
    }

    fun getTotalReposts(context: Context): Int = prefs(context).getInt(KEY_TOTAL, 0)

    fun getBestStreak(context: Context): Int = prefs(context).getInt(KEY_BEST, 0)

    /** Milestones (e.g. 3, 7) the user has hit at least once, ever. */
    fun getReachedMilestones(context: Context): Set<Int> =
        (prefs(context).getString(KEY_REACHED, "") ?: "")
            .split(",").mapNotNull { it.toIntOrNull() }.toSet()

    /** Dates (yyyy-MM-dd) with a counted repost, for the week dots. */
    fun getRepostDates(context: Context): Set<String> =
        (prefs(context).getString(KEY_HISTORY, "") ?: "")
            .split(",").filter { it.isNotBlank() }.toSet()

    /**
     * Records today's repost. Safe to call more than once a day.
     *
     * @return the milestone (e.g. 7) that this repost just unlocked, or
     * null if no new milestone was reached. Already-reached milestones
     * are never returned again, so the celebration only fires once.
     */
    fun recordRepostToday(context: Context): Int? {
        if (hasRepostedToday(context)) return null

        val p = prefs(context)
        val last = p.getString(KEY_LAST_DATE, null)
        val newStreak = if (last == yesterday()) p.getInt(KEY_STREAK, 0) + 1 else 1
        val newBest = maxOf(p.getInt(KEY_BEST, 0), newStreak)
        val todayStr = today()

        val history = (getRepostDates(context) + todayStr)
            .sorted().takeLast(HISTORY_LIMIT)

        val reached = getReachedMilestones(context)
        val newlyReached = MILESTONES.firstOrNull { it == newStreak && it !in reached }
        val updatedReached = if (newlyReached != null) reached + newlyReached else reached

        p.edit()
            .putString(KEY_LAST_DATE, todayStr)
            .putInt(KEY_STREAK, newStreak)
            .putInt(KEY_TOTAL, p.getInt(KEY_TOTAL, 0) + 1)
            .putInt(KEY_BEST, newBest)
            .putString(KEY_HISTORY, history.joinToString(","))
            .putString(KEY_REACHED, updatedReached.sorted().joinToString(","))
            .apply()

        return newlyReached
    }

    /**
     * Overwrites the local copy with numbers from the server.
     *
     * @param todayIfDone true when the server says today is already
     * counted, so [hasRepostedToday] agrees with it.
     * @param recentDates dates (yyyy-MM-dd) the server has for the last
     * 7 days; merged into local history for the week dots.
     * @return the milestone this update newly unlocked, if any (same
     * once-only rule as [recordRepostToday]).
     */
    fun saveServerStats(
        context: Context,
        streak: Int,
        total: Int,
        best: Int,
        todayIfDone: Boolean,
        recentDates: Set<String>
    ): Int? {
        val p = prefs(context)
        val reached = getReachedMilestones(context)
        // Any milestone at or below the server's BEST streak counts as
        // earned. The celebration only fires for the one this update
        // lands exactly on, and only once.
        val newlyReached = MILESTONES.firstOrNull { it == streak && it !in reached }
        val updatedReached = reached + MILESTONES.filter { it <= best }

        val history = (getRepostDates(context) + recentDates).sorted().takeLast(HISTORY_LIMIT)

        val edit = p.edit()
            .putInt(KEY_STREAK, streak)
            .putInt(KEY_TOTAL, total)
            .putInt(KEY_BEST, best)
            .putString(KEY_HISTORY, history.joinToString(","))
            .putString(KEY_REACHED, updatedReached.sorted().joinToString(","))
        if (todayIfDone) {
            edit.putString(KEY_LAST_DATE, today())
        } else if (hasPendingUploadToday(context)) {
            // The user tapped today but the server hasn't heard yet.
            // Keep today marked done; the caller retries the upload.
            // (Server numbers are still saved above; they'll catch up
            // once the retry lands.)
        } else if (p.getString(KEY_LAST_DATE, null) == today()) {
            // Local thinks today is done but the server says it isn't
            // (e.g. an earlier tap never reached the server). Trust the
            // server: step the marker back so the button is tappable and
            // the streak logic stays consistent.
            edit.putString(KEY_LAST_DATE, yesterday())
        }
        edit.apply()
        return newlyReached
    }

    /**
     * A repost counted on this phone that the server has NOT yet
     * confirmed (the tap happened offline, or the request failed).
     * Stored as the date it was tapped so it can't leak onto a later day.
     */
    fun markPendingUpload(context: Context) {
        prefs(context).edit().putString(KEY_PENDING, today()).apply()
    }

    fun clearPendingUpload(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).apply()
    }

    /** True only if there is an unconfirmed repost from TODAY. */
    fun hasPendingUploadToday(context: Context): Boolean =
        prefs(context).getString(KEY_PENDING, null) == today()

    /** The next milestone above [streak], or null once all are passed. */
    fun nextMilestone(streak: Int): Int? = MILESTONES.firstOrNull { it > streak }
}
