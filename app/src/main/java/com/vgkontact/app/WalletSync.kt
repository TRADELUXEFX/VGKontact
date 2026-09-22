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
        val amount: Long,
        /** "PENDING", "APPROVED", or "REJECTED". Commissions are always "APPROVED". */
        val status: String
    )

    data class Wallet(
        val available: Long,
        val totalEarned: Long,
        val withdrawn: Long,
        val activity: List<Entry>
    )

    fun fetchWallet(context: Context, callback: (Wallet?) -> Unit) {        // applicationContext so a slow request can't keep a dead Activity alive
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
                                    amount = e.optLong("amount", 0L),
                                    status = e.optString("status", "APPROVED")
                                )
                            )
                        }
                    }

                    callback(
                        Wallet(
                            available = o.optLong("available", 0L),
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

    /**
     * Result of a withdrawal request. SUCCESS/REJECTED come from a real
     * server response (the RPC ran and returned an answer); NETWORK_ERROR
     * means we couldn't reach the server at all, so the caller shouldn't
     * treat it as "the bank details were wrong" - just "try again".
     */
    sealed class WithdrawResult {
        object Success : WithdrawResult()
        /** [message] is the server's reason, e.g. "Below minimum withdrawal". */
        data class Rejected(val message: String) : WithdrawResult()
        object NetworkError : WithdrawResult()
    }

    /**
     * Calls request_withdrawal(p_whatsapp, p_android_id, p_account_number,
     * p_bank_name, p_account_name, p_amount) - a backend RPC that:
     *  1. Re-validates the amount against the caller's real available
     *     balance server-side (never trust the client's number alone -
     *     the app's balance display could be stale or tampered with).
     *  2. Inserts a pending withdrawal record and reserves the amount,
     *     the same way an "amount": -5000 activity entry would show up
     *     under get_my_wallet's "activity" once a human approves it.
     *  3. Returns {"ok": true} on success, or {"ok": false, "message":
     *     "<reason>"} on a validation failure (below minimum, missing
     *     bank details, insufficient balance) - so the UI can show the
     *     server's exact reason rather than a generic error.
     *
     * This RPC does not exist yet - it needs to be added to Supabase
     * alongside get_my_wallet/redeem_key (see wallet_fix.sql). This
     * client-side call is written to match that shape so it's a drop-in
     * once the function exists.
     */
    fun requestWithdrawal(
        context: Context,
        accountNumber: String,
        bankName: String,
        accountName: String,
        callback: (WithdrawResult) -> Unit
    ) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val whatsapp = UserPrefs.getWhatsapp(appContext)
                val androidId = Settings.Secure.getString(
                    appContext.contentResolver, Settings.Secure.ANDROID_ID
                ) ?: ""
                if (whatsapp.isNullOrEmpty() || androidId.isBlank()) {
                    callback(WithdrawResult.NetworkError)
                    return@launch
                }

                val body = JSONObject()
                    .put("p_whatsapp", whatsapp)
                    .put("p_android_id", androidId)
                    .put("p_account_number", accountNumber)
                    .put("p_bank_name", bankName)
                    .put("p_account_name", accountName)

                val request = Request.Builder()
                    .url("$SUPABASE_URL/rest/v1/rpc/request_withdrawal")
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(JSON.toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.code !in 200..299) {
                        Log.w(TAG, "request_withdrawal failed: HTTP ${response.code} $text")
                        callback(WithdrawResult.NetworkError)
                        return@launch
                    }
                    // The RPC returns a single JSON object (not wrapped in
                    // an array), same convention PostgREST uses for a
                    // scalar/record-returning function called this way.
                    val o = JSONObject(text)
                    if (o.optBoolean("ok", false)) {
                        callback(WithdrawResult.Success)
                    } else {
                        callback(
                            WithdrawResult.Rejected(
                                o.optString("message", "Couldn't submit withdrawal")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestWithdrawal failed", e)
                callback(WithdrawResult.NetworkError)
            }
        }
    }
}
