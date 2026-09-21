package com.vgkontact.app

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Supabase calls for the Repost feature. Kept in its own file so the
 * (very large) SheetSync.kt is untouched: SheetSync's buildRequest() and
 * readAndroidId() are private, so this file carries small copies of the
 * same header setup instead.
 *
 * Every call sends p_whatsapp + p_android_id, exactly like SheetSync's
 * other RPCs, so the server can check the caller really owns that number
 * (see the VERIFY OWNER blocks in repost_supabase.sql).
 *
 * All callbacks fire on a background thread. Screens must hop to the UI
 * thread (runOnUiThread) before touching views.
 *
 * Callbacks receive null on any failure (offline, server error, not the
 * owner). Callers treat null as "couldn't reach the server" and fall back
 * to local data - see RepostActivity.
 */
object RepostSync {

    private const val TAG = "RepostSync"
    private const val JSON = "application/json; charset=utf-8"

    private val SUPABASE_URL = BuildConfig.SUPABASE_URL
    private val SUPABASE_ANON_KEY = BuildConfig.SUPABASE_ANON_KEY

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** The signed-in user's repost numbers, straight from the server. */
    data class Stats(
        val streak: Int,
        val totalReposts: Int,
        val bestStreak: Int,
        val doneToday: Boolean,
        /** Dates (yyyy-MM-dd) with a repost in the last 7 days. */
        val recentDates: Set<String>
    )

    /** One leaderboard row. [displayNumber] is already masked by the server. */
    data class BoardEntry(
        val rank: Int,
        val displayNumber: String,
        val totalReposts: Int,
        val streak: Int,
        val isMe: Boolean
    )

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Counts today's repost on the server. Safe to call twice: the
     * database allows only one row per user per day, so a double tap or
     * a retry never double counts.
     */
    fun recordRepost(context: Context, callback: (Stats?) -> Unit) {
        callRpc(context, "record_repost", extra = null) { arr ->
            if (arr == null || arr.length() == 0) {
                callback(null)
                return@callRpc
            }
            val o = arr.getJSONObject(0)
            callback(
                Stats(
                    streak = o.optInt("streak", 0),
                    totalReposts = o.optInt("total_reposts", 0),
                    bestStreak = o.optInt("best_streak", 0),
                    // record_repost always leaves today counted
                    doneToday = true,
                    // not returned by record_repost; the screen re-fetches
                    // via fetchMyStats right after to fill the week dots
                    recentDates = emptySet()
                )
            )
        }
    }

    fun fetchMyStats(context: Context, callback: (Stats?) -> Unit) {
        callRpc(context, "get_my_repost_stats", extra = null) { arr ->
            if (arr == null || arr.length() == 0) {
                callback(null)
                return@callRpc
            }
            val o = arr.getJSONObject(0)
            val dates = mutableSetOf<String>()
            val da = o.optJSONArray("recent_dates")
            if (da != null) for (i in 0 until da.length()) dates.add(da.getString(i))
            callback(
                Stats(
                    streak = o.optInt("streak", 0),
                    totalReposts = o.optInt("total_reposts", 0),
                    bestStreak = o.optInt("best_streak", 0),
                    doneToday = o.optBoolean("done_today", false),
                    recentDates = dates
                )
            )
        }
    }

    fun fetchLeaderboard(context: Context, limit: Int = 50, callback: (List<BoardEntry>?) -> Unit) {
        callRpc(context, "get_repost_leaderboard", extra = JSONObject().put("p_limit", limit)) { arr ->
            if (arr == null) {
                callback(null)
                return@callRpc
            }
            val list = ArrayList<BoardEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    BoardEntry(
                        rank = o.optInt("rank", i + 1),
                        displayNumber = o.optString("display_number", ""),
                        totalReposts = o.optInt("total_reposts", 0),
                        streak = o.optInt("streak", 0),
                        isMe = o.optBoolean("is_me", false)
                    )
                )
            }
            callback(list)
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun readAndroidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

    /**
     * Shared POST-an-RPC helper. Adds p_whatsapp + p_android_id, runs off
     * the main thread, and hands the parsed JSON array to [onResult]
     * (or null on any failure).
     */
    private fun callRpc(
        context: Context,
        function: String,
        extra: JSONObject?,
        onResult: (JSONArray?) -> Unit
    ) {
        // applicationContext so a long request can't keep a dead Activity alive
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val whatsapp = UserPrefs.getWhatsapp(appContext)
                val androidId = readAndroidId(appContext)
                if (whatsapp.isNullOrEmpty() || androidId.isBlank()) {
                    onResult(null)
                    return@launch
                }

                val body = JSONObject()
                    .put("p_whatsapp", whatsapp)
                    .put("p_android_id", androidId)
                extra?.keys()?.forEach { k -> body.put(k, extra.get(k)) }

                val request = Request.Builder()
                    .url("$SUPABASE_URL/rest/v1/rpc/$function")
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(JSON.toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        Log.w(TAG, "$function failed: HTTP ${response.code}")
                        onResult(null)
                        return@launch
                    }
                    val text = response.body?.string().orEmpty()
                    onResult(if (text.isBlank()) JSONArray() else JSONArray(text))
                }
            } catch (e: Exception) {
                Log.w(TAG, "$function failed", e)
                onResult(null)
            }
        }
    }
}
