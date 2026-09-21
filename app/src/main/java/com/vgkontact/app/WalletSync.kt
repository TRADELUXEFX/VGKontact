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
 * Supabase call for the Wallet screen. Own file (like RepostSync) so
 * SheetSync.kt stays untouched.
 *
 * Calls get_my_wallet(p_whatsapp, p_android_id) - see wallet_fix.sql.
 *
 * The callback fires on a background thread and receives null on ANY
 * failure (offline, server error, not the owner). Screens must hop to
 * the UI thread before touching views.
 */
object WalletSync {

    private const val TAG = "WalletSync"
    private const val JSON = "application/json; charset=utf-8"

    private val SUPABASE_URL = BuildConfig.SUPABASE_URL
    private val SUPABASE_ANON_KEY = BuildConfig.SUPABASE_ANON_KEY

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class Entry(
        /** "COMMISSION" or "WITHDRAWAL" */
        val kind: String,
        val title: String,
        val subtitle: String,
        /** Positive = money in, negative = money out. */
        val amount: Long
    )

    data class Wallet(
        val available: Long,
        val pending: Long,
        val totalEarned: Long,
        val withdrawn: Long,
        val activity: List<Entry>
    )

    fun fetchWallet(context: Context, callback: (Wallet?) -> Unit) {
        // applicationContext so a slow request can't keep a dead Activity alive
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val whatsapp = UserPrefs.getWhatsapp(appContext)
                val androidId = Settings.Secure.getString(
                    appContext.contentResolver, Settings.Secure.ANDROID_ID
                ) ?: ""
                if (whatsapp.isNullOrEmpty() || androidId.isBlank()) {
                    callback(null)
                    return@launch
                }

                val body = JSONObject()
                    .put("p_whatsapp", whatsapp)
                    .put("p_android_id", androidId)

                val request = Request.Builder()
                    .url("$SUPABASE_URL/rest/v1/rpc/get_my_wallet")
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(JSON.toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.code !in 200..299) {
                        Log.w(TAG, "get_my_wallet failed: HTTP ${response.code} $text")
                        callback(null)
                        return@launch
                    }
                    val arr = JSONArray(text)
                    if (arr.length() == 0) {
                        callback(null)
                        return@launch
                    }
                    val o = arr.getJSONObject(0)

                    val entries = ArrayList<Entry>()
                    val actArr = o.optJSONArray("activity")
                    if (actArr != null) {
                        for (i in 0 until actArr.length()) {
                            val e = actArr.getJSONObject(i)
                            entries.add(
                                Entry(
                                    kind = e.optString("kind", "COMMISSION"),
                                    title = e.optString("title", ""),
                                    subtitle = e.optString("subtitle", ""),
                                    amount = e.optLong("amount", 0L)
                                )
                            )
                        }
                    }

                    callback(
                        Wallet(
                            available = o.optLong("available", 0L),
                            pending = o.optLong("pending", 0L),
                            totalEarned = o.optLong("total_earned", 0L),
                            withdrawn = o.optLong("withdrawn", 0L),
                            activity = entries
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchWallet failed", e)
                callback(null)
            }
        }
    }
}
