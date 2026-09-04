package com.vgkontact.app

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class DayCount(val date: String, val count: Int)

data class ReferralEntry(val whatsapp: String, val referralCount: Int)

data class ImportStats(
    val totalInDatabase: Int,
    val syncedToPhone: Int,
    val availableToImport: Int,
    val joinedGroupCount: Int = -1,
    val joinedGroupIds: List<Long> = emptyList(),
    val contactLimit: Long = -1L,
    val baseLimit: Long = -1L,
    val bonusLimit: Long = -1L
)

data class GroupSummary(
    val groupId: Long,
    val homeCount: Long,
    val extraCount: Long
)

data class CampaignStatus(
    val campaignId: Long,
    val campaignName: String,
    val referralsPerMilestone: Int,
    val slotsPerMilestone: Int,
    val triggerStage: Int,
    val repeats: Boolean,
    val milestonesClaimed: Int,
    val qualifyingReferrals: Int
) {
    val nextTarget: Int get() = (milestonesClaimed + 1) * referralsPerMilestone

    val readyToClaim: Boolean get() =
        (repeats || milestonesClaimed == 0) && qualifyingReferrals >= nextTarget

    val fullyClaimed: Boolean get() = !repeats && milestonesClaimed >= 1
}

data class GroupCap(
    val groupId: Long,
    val maxUsers: Long
)

object SheetSync {

    private val SUPABASE_URL = BuildConfig.SUPABASE_URL
    private val SUPABASE_ANON_KEY = BuildConfig.SUPABASE_ANON_KEY

    private const val MAX_RETRIES = 3
    private const val BASE_DELAY_MS = 1000L
    private const val JSON = "application/json"

    // Single shared OkHttpClient for the whole app process. OkHttp pools
    // and reuses its underlying connections automatically across calls
    // made through the same client instance - unlike the old code, which
    // opened (and never reused) a brand new HttpURLConnection for every
    // single request. Creating this once as an object-level val, instead
    // of per-call, is what makes that reuse actually happen.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Builds a Request against Supabase's REST endpoint. jsonBody == null
     * means a GET (no body); otherwise the given method is used with that
     * JSON string as the body. Same headers (apikey, Authorization,
     * Content-Type, and an optional Prefer) on every call, same as
     * openConnection() used to set on every HttpURLConnection before.
     */
    private fun buildRequest(path: String, method: String, jsonBody: String? = null, preferHeader: String? = null): Request {
        val builder = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/$path")
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $SUPABASE_ANON_KEY")
            .header("Content-Type", "application/json")
        if (preferHeader != null) {
            builder.header("Prefer", preferHeader)
        }
        if (jsonBody != null) {
            builder.method(method, jsonBody.toRequestBody(JSON.toMediaType()))
        } else {
            builder.method(method, null)
        }
        return builder.build()
    }

    private fun isRetryable(responseCode: Int?): Boolean {
        return responseCode == null || responseCode >= 500 || responseCode == 429
    }

    /**
     * Coroutine-friendly replacement for the old sleepBeforeRetry(), which
     * used Thread.sleep() and therefore blocked (parked) a whole OS thread
     * while waiting. delay() instead suspends only this coroutine, freeing
     * its thread to do other work in the meantime - same exponential
     * backoff timing as before (BASE_DELAY_MS * attempt number).
     */
    private suspend fun delayBeforeRetry(attempt: Int) {
        delay(BASE_DELAY_MS * (attempt + 1))
    }

    private const val GENERIC_ERROR = "Something went wrong. Please try again."

    /**
     * Reads a Response's body as a String, once, safely. OkHttp's response
     * body can only be read a single time and must be closed - this keeps
     * that rule in exactly one place instead of every call site.
     */
    private fun bodyString(response: Response): String {
        return response.body?.string() ?: ""
    }

    private fun readErrorBody(response: Response): String {
        val raw = try {
            val body = bodyString(response)
            if (body.isEmpty()) return GENERIC_ERROR

            // Supabase/PostgREST errors come back as JSON with a "message" field
            try {
                val obj = JSONObject(body)
                obj.optString("message").takeIf { it.isNotEmpty() }
                    ?: obj.optString("error_description").takeIf { it.isNotEmpty() }
                    ?: body
            } catch (e: Exception) {
                body
            }
        } catch (e: Exception) {
            return GENERIC_ERROR
        }
        return friendlyErrorMessage(raw)
    }

    /**
     * Converts raw backend/Postgres error text into plain, user-facing language.
     * Nothing from Supabase/PostgREST (constraint names, SQL wording, error codes)
     * should ever reach the UI directly.
     */
    private fun friendlyErrorMessage(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("duplicate key") || lower.contains("unique constraint") || lower.contains("already exists") ->
                "This WhatsApp number is already registered."
            lower.contains("timeout") || lower.contains("timed out") ->
                "The request timed out. Please check your connection and try again."
            lower.contains("network") || lower.contains("unable to resolve host") || lower.contains("connection") ->
                "No internet connection. Please try again."
            lower.contains("jwt") || lower.contains("unauthor") || lower.contains("permission denied") ->
                "You're not authorized to do this. Please restart the app and try again."
            lower.contains("not-null") || lower.contains("null value") ->
                "Please fill in all required fields."
            else -> GENERIC_ERROR
        }
    }

    // TODO: fill in your Termii API key (Termii dashboard -> Settings -> API Token).
    private const val TERMII_API_KEY = "tlv_5tV5_cXTPFwkZO-6qWEd41B1C1UlK8SHFtNHsLkntpg"
    private const val TERMII_BASE_URL = "https://api.ng.termii.com"

    /**
     * The WhatsApp number that OTP codes get sent TO. Users are asked to
     * open WhatsApp and send their code to this number so we can confirm
     * they actually control the number they signed up with. Update this
     * once you've picked a number to use for verification.
     */
    const val VERIFICATION_WHATSAPP_NUMBER = "2349110321143"

    /**
     * Converts an 11-digit local Nigerian number (e.g. 08031234567) into
     * international format (2348031234567, no leading +) expected by Termii.
     */
    private fun toIntl(nigerianLocal: String): String {
        val digits = nigerianLocal.filter { it.isDigit() }
        return if (digits.startsWith("0") && digits.length == 11) {
            "234" + digits.substring(1)
        } else {
            digits
        }
    }

    /**
     * Generates a one-time code via Termii's In-App Token API
     * (POST /api/sms/otp/generate). This does NOT send any SMS or WhatsApp
     * message itself - it only creates and stores the pin server-side and
     * hands back a pin_id (to verify against later) and the code itself,
     * which the app is responsible for delivering. Because there's no SMS
     * sending involved, no Sender ID / CAC registration is required.
     *
     * callback receives (success, pinId, code, errorMessage).
     */
    fun generateOtp(whatsapp: String, callback: (Boolean, String?, String?, String?) -> Unit) {
        runOnIoThread {
            try {
                val json = JSONObject()
                json.put("api_key", TERMII_API_KEY)
                json.put("phone_number", toIntl(whatsapp))
                json.put("pin_type", "NUMERIC")
                json.put("pin_attempts", 3)
                json.put("pin_time_to_live", 10) // minutes
                json.put("pin_length", 6)
                val request = Request.Builder()
                    .url("$TERMII_BASE_URL/api/sms/otp/generate")
                    .header("Content-Type", "application/json")
                    .post(json.toString().toRequestBody(JSON.toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = bodyString(response)
                    if (response.code in 200..299) {
                        val obj = JSONObject(body)
                        val pinId = obj.optString("pin_id")
                        val code = obj.optString("otp")
                        if (pinId.isNotBlank() && code.isNotBlank()) {
                            callback(true, pinId, code, null)
                        } else {
                            callback(false, null, null, "Couldn't generate code. Please try again.")
                        }
                    } else {
                        callback(false, null, null, "Couldn't generate code. Please try again.")
                    }
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "generateOtp threw exception", e)
                callback(false, null, null, "No internet connection. Please try again.")
            }
        }
    }

    /**
     * Verifies the code the user says they sent, against Termii
     * (POST /api/sms/otp/verify). pinId is the one returned by generateOtp.
     */
    fun verifyOtp(pinId: String, code: String, callback: ((Boolean, String?) -> Unit)? = null) {
        runOnIoThread {
            try {
                val json = JSONObject()
                json.put("api_key", TERMII_API_KEY)
                json.put("pin_id", pinId)
                json.put("pin", code)
                val request = Request.Builder()
                    .url("$TERMII_BASE_URL/api/sms/otp/verify")
                    .header("Content-Type", "application/json")
                    .post(json.toString().toRequestBody(JSON.toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = bodyString(response)
                    if (response.code in 200..299) {
                        val obj = JSONObject(body)
                        val verified = obj.optString("verified", "false").equals("true", ignoreCase = true)
                        if (verified) {
                            callback?.invoke(true, null)
                        } else {
                            callback?.invoke(false, "Incorrect or expired code. Please try again.")
                        }
                    } else {
                        callback?.invoke(false, "Incorrect or expired code. Please try again.")
                    }
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "verifyOtp threw exception", e)
                callback?.invoke(false, "No internet connection. Please try again.")
            }
        }
    }

    /**
     * Signs up a new contact AND assigns them a group in a single network
     * call, via the signup_and_assign_group() Postgres function. Both
     * steps happen inside one database transaction, so there's nothing to
     * wait on and no extra round trip.
     */
    fun submit(whatsapp: String, referral: String = "", context: Context? = null, callback: ((Boolean, String?) -> Unit)? = null) {
        runOnIoThread {
            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val hasContactsPermission = context?.let {
                        ContextCompat.checkSelfPermission(it, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(it, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
                    } ?: false

                    val json = JSONObject()
                    json.put("p_whatsapp", whatsapp)
                    json.put("p_referral", referral)
                    json.put("p_plan", if (hasContactsPermission) "VERIFIED" else "UNVERIFIED")

                    val request = buildRequest("rpc/signup_and_assign_group", "POST", json.toString())
                    httpClient.newCall(request).execute().use { response ->
                        val responseCode = response.code

                        if (responseCode in 200..299) {
                            val body = bodyString(response)

                            val (contactId, groupId) = try {
                                val arr = JSONArray(body)
                                if (arr.length() > 0) {
                                    val row = arr.getJSONObject(0)
                                    Pair(row.optLong("id", -1L), row.optLong("group_id", -1L))
                                } else {
                                    Pair(-1L, -1L)
                                }
                            } catch (e: Exception) {
                                Log.e("SheetSync", "submit: failed to parse signup_and_assign_group response: $body", e)
                                Pair(-1L, -1L)
                            }

                            if (contactId <= 0 || groupId <= 0) {
                                val debugInfo = "id=$contactId group=$groupId resp=${body.take(150)}"
                                callback?.invoke(false, "Signed up, but couldn't join a group. [$debugInfo]")
                                return@runOnIoThread
                            }

                            callback?.invoke(true, null)
                            return@runOnIoThread
                        } else if (!isRetryable(responseCode)) {
                            val errorText = readErrorBody(response)
                            callback?.invoke(false, errorText)
                            return@runOnIoThread
                        }
                        Log.w("SheetSync", "submit attempt ${attempt + 1} failed with code $responseCode, retrying...")
                    }
                } catch (e: Exception) {
                    Log.w("SheetSync", "submit attempt ${attempt + 1} threw exception, retrying...", e)
                }

                if (attempt < MAX_RETRIES - 1) {
                    delayBeforeRetry(attempt)
                }
            }
            callback?.invoke(false, "Failed after $MAX_RETRIES attempts")
        }
    }

    fun fetchHistory(context: Context? = null, callback: ((List<DayCount>?, String?) -> Unit)? = null) {
        runOnIoThread {
            try {
                val request = buildRequest("contacts?select=created_at", "GET")
                httpClient.newCall(request).execute().use { response ->
                    if (response.code in 200..299) {
                        val body = bodyString(response)
                        val arr = JSONArray(body)
                        callback?.invoke(listOf(DayCount("all", arr.length())), null)
                    } else {
                        val errorText = readErrorBody(response)
                        callback?.invoke(null, errorText)
                    }
                }
            } catch (e: java.io.IOException) {
                Log.w("SheetSync", "fetchHistory failed - network error", e)
                callback?.invoke(null, "NO_INTERNET")
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchHistory failed", e)
                callback?.invoke(null, "Couldn't load history right now")
            }
        }
    }

    /**
     * Referral leaderboard: for every contact row, "referral" holds the
     * WhatsApp number of the person who referred them. Grouping by that
     * column and counting rows gives each referrer's total number of
     * referrals. Sorted descending so the top referrer appears first.
     */
    fun fetchReferralLeaderboard(context: Context? = null, callback: ((List<ReferralEntry>?, String?) -> Unit)? = null) {
        runOnIoThread {
            try {
                val request = buildRequest("contacts?select=referral&referral=not.is.null", "GET")
                httpClient.newCall(request).execute().use { response ->
                    if (response.code in 200..299) {
                        val body = bodyString(response)
                        val arr = JSONArray(body)
                        val counts = LinkedHashMap<String, Int>()
                        for (i in 0 until arr.length()) {
                            val referral = arr.getJSONObject(i).optString("referral").trim()
                            if (referral.isEmpty()) continue
                            counts[referral] = (counts[referral] ?: 0) + 1
                        }
                        val leaderboard = counts.entries
                            .map { ReferralEntry(it.key, it.value) }
                            .sortedByDescending { it.referralCount }
                        callback?.invoke(leaderboard, null)
                    } else {
                        val errorText = readErrorBody(response)
                        callback?.invoke(null, errorText)
                    }
                }
            } catch (e: java.io.IOException) {
                Log.w("SheetSync", "fetchReferralLeaderboard failed - network error", e)
                callback?.invoke(null, "NO_INTERNET")
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchReferralLeaderboard failed", e)
                callback?.invoke(null, "Couldn't load referral history right now")
            }
        }
    }

    /**
     * Fetches this user's live progress on every active campaign from
     * campaign_progress_live, filtered to rows where referrer_whatsapp
     * is this user.
     */
    fun fetchMyCampaignStatus(context: Context, callback: (List<CampaignStatus>?, String?) -> Unit) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback(null, "No user registered yet")
                    return@runOnIoThread
                }

                val encoded = URLEncoder.encode(whatsapp, "UTF-8")
                val request = buildRequest(
                    "campaign_progress_live?referrer_whatsapp=eq.$encoded" +
                        "&select=campaign_id,campaign_name,referrals_per_milestone,slots_per_milestone,trigger_stage,repeats,milestones_claimed,qualifying_referrals",
                    "GET"
                )
                httpClient.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        val errorText = readErrorBody(response)
                        callback(null, errorText)
                        return@runOnIoThread
                    }
                    val body = bodyString(response)
                    val arr = JSONArray(body)
                    val results = mutableListOf<CampaignStatus>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        results.add(
                            CampaignStatus(
                                campaignId = obj.optLong("campaign_id"),
                                campaignName = obj.optString("campaign_name"),
                                referralsPerMilestone = obj.optInt("referrals_per_milestone"),
                                slotsPerMilestone = obj.optInt("slots_per_milestone"),
                                triggerStage = obj.optInt("trigger_stage"),
                                repeats = obj.optBoolean("repeats"),
                                milestonesClaimed = obj.optInt("milestones_claimed"),
                                qualifyingReferrals = obj.optInt("qualifying_referrals")
                            )
                        )
                    }
                    callback(results, null)
                }
            } catch (e: java.io.IOException) {
                Log.w("SheetSync", "fetchMyCampaignStatus failed - network error", e)
                callback(null, "NO_INTERNET")
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchMyCampaignStatus failed", e)
                callback(null, "Couldn't load campaigns right now")
            }
        }
    }

    /**
     * Called when the user taps "Unlock reward" on a card that's
     * readyToClaim. Calls the claim_campaign_milestone() RPC.
     */
    fun claimCampaignMilestone(context: Context, campaignId: Long, callback: (List<Long>?) -> Unit) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback(null)
                    return@runOnIoThread
                }

                val body = JSONObject()
                body.put("p_campaign_id", campaignId)
                body.put("p_whatsapp", whatsapp)
                val request = buildRequest("rpc/claim_campaign_milestone", "POST", body.toString())

                httpClient.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        callback(null)
                        return@runOnIoThread
                    }

                    val trimmed = bodyString(response).trim()
                    if (trimmed == "null" || trimmed.isEmpty()) {
                        callback(null)
                        return@runOnIoThread
                    }
                    val arr = JSONArray(trimmed)
                    val unlocked = ArrayList<Long>()
                    for (i in 0 until arr.length()) {
                        unlocked.add(arr.getLong(i))
                    }
                    callback(unlocked)
                }
            } catch (e: Exception) {
                Log.e("SheetSync", "claimCampaignMilestone failed", e)
                callback(null)
            }
        }
    }

    /**
     * Cheap check: asks the server for a single number (total contacts
     * across this user's groups) via get_my_group_contact_count(), instead
     * of downloading the full contact list. Used to decide whether a full
     * sync is actually worth running - see MainMenuActivity.autoSyncQuietly.
     */
    fun fetchGroupContactCount(context: Context, callback: (Long?) -> Unit) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback(null)
                    return@runOnIoThread
                }

                val body = JSONObject()
                body.put("p_whatsapp", whatsapp)
                val request = buildRequest("rpc/get_my_group_contact_count", "POST", body.toString())

                httpClient.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        callback(null)
                        return@runOnIoThread
                    }
                    val trimmed = bodyString(response).trim()
                    val count = trimmed.toLongOrNull()
                    callback(count)
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchGroupContactCount failed", e)
                callback(null)
            }
        }
    }

    fun fetchPlan(context: Context, callback: (String?) -> Unit) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback(null)
                    return@runOnIoThread
                }
                val encoded = URLEncoder.encode(whatsapp, "UTF-8")
                val request = buildRequest("contacts?whatsapp=eq.$encoded&select=plan", "GET")
                httpClient.newCall(request).execute().use { response ->
                    if (response.code in 200..299) {
                        val body = bodyString(response)
                        val arr = JSONArray(body)
                        if (arr.length() > 0) {
                            val plan = arr.getJSONObject(0).optString("plan", "FREE")
                            callback(if (plan.isEmpty()) "FREE" else plan)
                        } else {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchPlan failed", e)
                callback(null)
            }
        }
    }

    /**
     * Updates the current user's `plan` column to reflect whether they actually
     * granted contacts permission during PermissionSetupActivity.
     */
    fun updateVerificationStatus(context: Context, verified: Boolean, callback: ((Boolean) -> Unit)? = null) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback?.invoke(false)
                    return@runOnIoThread
                }
                val encoded = URLEncoder.encode(whatsapp, "UTF-8")

                val json = JSONObject()
                json.put("plan", if (verified) "VERIFIED" else "UNVERIFIED")

                val request = buildRequest(
                    "contacts?whatsapp=eq.$encoded", "PATCH", json.toString(),
                    preferHeader = "return=representation"
                )

                httpClient.newCall(request).execute().use { response ->
                    if (response.code in 200..299) {
                        val body = bodyString(response)
                        val arr = JSONArray(body)
                        if (arr.length() == 0) {
                            Log.w("SheetSync", "updateVerificationStatus: 0 rows updated for whatsapp=$whatsapp - check RLS UPDATE policy on contacts table")
                            callback?.invoke(false)
                        } else {
                            callback?.invoke(true)
                        }
                    } else {
                        val errorBody = readErrorBody(response)
                        Log.w("SheetSync", "updateVerificationStatus failed with code ${response.code}: $errorBody")
                        callback?.invoke(false)
                    }
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "updateVerificationStatus failed", e)
                callback?.invoke(false)
            }
        }
    }

    /**
     * Reports this user's live 0-3 setup stage and stamps the first-reached
     * timestamp columns as needed. See PermissionHealth.Status.stage.
     */
    fun reportSetupStage(context: Context, stage: Int, callback: ((Boolean) -> Unit)? = null) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback?.invoke(false)
                    return@runOnIoThread
                }
                val encoded = URLEncoder.encode(whatsapp, "UTF-8")

                val json = JSONObject()
                json.put("setup_stage", stage)

                val nowIso = java.time.Instant.now().toString()

                val request = buildRequest("contacts?whatsapp=eq.$encoded", "PATCH", json.toString())
                val responseCode = httpClient.newCall(request).execute().use { it.code }

                if (responseCode !in 200..299) {
                    Log.w("SheetSync", "reportSetupStage: live stage update failed with code $responseCode")
                    callback?.invoke(false)
                    return@runOnIoThread
                }

                if (stage >= 1) stampFirstReachedIfNull(encoded, "first_reached_stage_1_at", nowIso)
                if (stage >= 2) stampFirstReachedIfNull(encoded, "first_reached_stage_2_at", nowIso)
                if (stage >= 3) stampFirstReachedIfNull(encoded, "first_reached_stage_3_at", nowIso)

                callback?.invoke(true)
            } catch (e: Exception) {
                Log.w("SheetSync", "reportSetupStage failed", e)
                callback?.invoke(false)
            }
        }
    }

    /**
     * Stamps a single "first reached stage N" column with the given
     * timestamp, but only for rows where that column is still null.
     */
    private fun stampFirstReachedIfNull(encodedWhatsapp: String, column: String, nowIso: String) {
        try {
            val json = JSONObject()
            json.put(column, nowIso)
            val request = buildRequest("contacts?whatsapp=eq.$encodedWhatsapp&$column=is.null", "PATCH", json.toString())
            httpClient.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    val errorBody = readErrorBody(response)
                    Log.w("SheetSync", "stampFirstReachedIfNull($column) failed with code ${response.code}: $errorBody")
                }
            }
        } catch (e: Exception) {
            Log.w("SheetSync", "stampFirstReachedIfNull($column) failed", e)
        }
    }

    /**
     * Returns the breakdown shown on the main dashboard (see ImportStats
     * doc comment for field meanings).
     */
    fun fetchImportStats(context: Context, callback: (ImportStats?) -> Unit) {
        runOnIoThread {
            val contacts = fetchAllContacts(context)
            if (contacts == null) {
                callback(null)
                return@runOnIoThread
            }
            val totalInDatabase = contacts.count { it.first.isNotEmpty() }

            val knownSynced = UserPrefs.getSyncedNumbers(context).map { normalizePhone(it) }.toSet()
            val onDevice = if (checkContactsPermission(context))
                getDevicePhoneNumbers(context).map { normalizePhone(it) }.toSet()
            else emptySet()
            val syncedToPhone = contacts.count {
                it.first.isNotEmpty() &&
                    normalizePhone(it.first).let { n -> knownSynced.contains(n) || onDevice.contains(n) }
            }

            val availableToImport = (totalInDatabase - syncedToPhone).coerceAtLeast(0)

            val groupsSplit = fetchMyGroupsSplit(context)
            val joinedGroupIds = groupsSplit?.let { (home, extra) ->
                (listOfNotNull(home) + extra).sorted()
            } ?: emptyList()
            val joinedGroupCount = if (groupsSplit != null) joinedGroupIds.size else -1

            val allGroupCaps = if (groupsSplit != null) fetchAllGroupCapsSync() else null
            val (baseLimit, bonusLimit) = if (groupsSplit == null || allGroupCaps == null) {
                Pair(-1L, -1L)
            } else {
                val capsById = allGroupCaps.associateBy { it.groupId }
                val (homeGroup, extraGroups) = groupsSplit
                val base = homeGroup?.let {
                    capsById[it]?.maxUsers ?: run {
                        Log.w("SheetSync", "home group $it has no matching cap in allGroupCaps")
                        0L
                    }
                } ?: 0L
                val bonus = extraGroups.sumOf {
                    capsById[it]?.maxUsers ?: run {
                        Log.w("SheetSync", "extra group $it has no matching cap in allGroupCaps")
                        0L
                    }
                }
                Pair(base, bonus)
            }
            val contactLimit = if (baseLimit < 0L || bonusLimit < 0L) -1L else baseLimit + bonusLimit

            callback(ImportStats(totalInDatabase, syncedToPhone, availableToImport, joinedGroupCount, joinedGroupIds, contactLimit, baseLimit, bonusLimit))
        }
    }

    /**
     * Fetches every group that exists (not just the current user's own),
     * via the get_all_groups_summary() Postgres function.
     */
    fun fetchAllGroupsSummary(callback: (List<GroupSummary>?) -> Unit) {
        runOnIoThread {
            callback(fetchAllGroupsSummarySync())
        }
    }

    /**
     * Synchronous core of fetchAllGroupsSummary() above - callable inline
     * from other already-backgrounded code in this file (e.g.
     * fetchImportStats()) without nesting another background dispatch.
     */
    private fun fetchAllGroupsSummarySync(): List<GroupSummary>? {
        try {
            val request = buildRequest("rpc/get_all_groups_summary", "POST", "{}")
            httpClient.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    return null
                }
                val body = bodyString(response)
                val arr = JSONArray(body)
                val result = ArrayList<GroupSummary>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    result.add(
                        GroupSummary(
                            groupId = obj.getLong("group_id"),
                            homeCount = obj.optLong("home_count", 0L),
                            extraCount = obj.optLong("extra_count", 0L)
                        )
                    )
                }
                return result.sortedBy { it.groupId }
            }
        } catch (e: Exception) {
            Log.e("SheetSync", "fetchAllGroupsSummarySync failed", e)
            return null
        }
    }

    /**
     * Fetches every group's real capacity (groups.max_users) directly from
     * the groups table on Supabase.
     */
    private fun fetchAllGroupCapsSync(): List<GroupCap>? {
        try {
            val request = buildRequest("groups?select=group_id,max_users", "GET")
            httpClient.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    return null
                }
                val body = bodyString(response)
                val arr = JSONArray(body)
                val result = ArrayList<GroupCap>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    result.add(
                        GroupCap(
                            groupId = obj.getLong("group_id"),
                            maxUsers = obj.optLong("max_users", 0L)
                        )
                    )
                }
                return result
            }
        } catch (e: Exception) {
            Log.e("SheetSync", "fetchAllGroupCapsSync failed", e)
            return null
        }
    }

    /**
     * Normalizes a Nigerian phone number for comparison purposes only.
     */
    private fun normalizePhone(raw: String): String {
        var digits = raw.filter { it.isDigit() }
        if (digits.startsWith("234")) {
            digits = digits.removePrefix("234")
        } else if (digits.startsWith("0")) {
            digits = digits.removePrefix("0")
        }
        return digits
    }

    private fun checkContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Phone numbers belonging ONLY to contacts this app itself created (i.e. named
     * "VG KONTACT <number>").
     */
    private fun getDevicePhoneNumbers(context: Context): Set<String> {
        val numbers = HashSet<String>()
        val pattern = Regex("^VG KONTACT (\\d+)$")

        // Pushing the "VG KONTACT%" filter into the query's selection args
        // means the Contacts provider only returns matching rows, instead
        // of every contact on the device being pulled into the app and
        // filtered here one by one.
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("VG KONTACT%"),
            null
        )
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)?.trim() ?: continue
                if (!pattern.matches(name)) continue

                val num = it.getString(numIndex)
                if (!num.isNullOrEmpty()) numbers.add(num)
            }
        }
        return numbers
    }

    /**
     * Looks up the current user's own group_id + extra_groups, keeping
     * them separate (needed by fetchImportStats() for baseLimit/bonusLimit).
     */
    private fun fetchMyGroupsSplit(context: Context): Pair<Long?, List<Long>>? {
        val whatsapp = UserPrefs.getWhatsapp(context) ?: return null
        try {
            val encoded = URLEncoder.encode(whatsapp, "UTF-8")
            val request = buildRequest("contacts?whatsapp=eq.$encoded&select=group_id,extra_groups", "GET")
            httpClient.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    return null
                }
                val body = bodyString(response)
                val arr = JSONArray(body)
                if (arr.length() == 0) return null
                val obj = arr.getJSONObject(0)

                val homeGroup = obj.optLong("group_id", -1L).let { if (it > 0) it else null }
                val extra = ArrayList<Long>()
                obj.optJSONArray("extra_groups")?.let {
                    for (i in 0 until it.length()) extra.add(it.getLong(i))
                }
                return Pair(homeGroup, extra)
            }
        } catch (e: Exception) {
            Log.e("SheetSync", "fetchMyGroupsSplit failed", e)
            return null
        }
    }

    private fun fetchMyGroups(context: Context): List<Long>? {
        val whatsapp = UserPrefs.getWhatsapp(context) ?: return null
        try {
            val encoded = URLEncoder.encode(whatsapp, "UTF-8")
            val request = buildRequest("contacts?whatsapp=eq.$encoded&select=group_id,extra_groups", "GET")
            httpClient.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    return null
                }
                val body = bodyString(response)
                val arr = JSONArray(body)
                if (arr.length() == 0) return null
                val obj = arr.getJSONObject(0)

                val groups = ArrayList<Long>()
                val homeGroup = obj.optLong("group_id", -1L)
                if (homeGroup > 0) groups.add(homeGroup)

                val extra = obj.optJSONArray("extra_groups")
                if (extra != null) {
                    for (i in 0 until extra.length()) {
                        groups.add(extra.getLong(i))
                    }
                }
                return if (groups.isEmpty()) null else groups
            }
        } catch (e: Exception) {
            Log.e("SheetSync", "fetchMyGroups failed", e)
            return null
        }
    }

    /**
     * Redeems a key code for the current user via the redeem_key() RPC.
     */
    fun redeemKey(context: Context, code: String, callback: (List<Long>?) -> Unit) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback(null)
                    return@runOnIoThread
                }

                // Single round trip: redeem_key() now looks up the contact
                // by whatsapp number itself (see updated SQL function),
                // instead of the app fetching the contact id first and
                // then calling redeem_key() as a second request.
                val body = JSONObject()
                body.put("p_code", code)
                body.put("p_whatsapp", whatsapp)
                val rpcRequest = buildRequest("rpc/redeem_key", "POST", body.toString())

                httpClient.newCall(rpcRequest).execute().use { response ->
                    if (response.code !in 200..299) {
                        callback(null)
                        return@runOnIoThread
                    }

                    val trimmed = bodyString(response).trim()
                    if (trimmed == "null" || trimmed.isEmpty()) {
                        callback(null)
                        return@runOnIoThread
                    }
                    val arr = JSONArray(trimmed)
                    val unlocked = ArrayList<Long>()
                    for (i in 0 until arr.length()) {
                        unlocked.add(arr.getLong(i))
                    }
                    callback(unlocked)
                }
            } catch (e: Exception) {
                Log.e("SheetSync", "redeemKey failed", e)
                callback(null)
            }
        }
    }

    private fun fetchAllContacts(context: Context? = null): List<Pair<String, String>>? {
        val groupFilter = if (context != null) {
            val groups = fetchMyGroups(context)
            if (groups.isNullOrEmpty()) {
                return emptyList()
            }
            "&group_id=in.(${groups.joinToString(",")})"
        } else {
            ""
        }

        for (attempt in 0 until MAX_RETRIES) {
            try {
                val request = buildRequest("contacts?select=whatsapp,referral$groupFilter", "GET")
                httpClient.newCall(request).execute().use { response ->
                    val responseCode = response.code
                    if (responseCode in 200..299) {
                        val body = bodyString(response)
                        val arr = JSONArray(body)
                        val result = ArrayList<Pair<String, String>>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            result.add(Pair(obj.optString("whatsapp"), obj.optString("referral")))
                        }
                        return result
                    } else {
                        if (!isRetryable(responseCode)) {
                            return null
                        }
                        Log.w("SheetSync", "fetchAllContacts attempt ${attempt + 1} failed with code $responseCode, retrying...")
                    }
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchAllContacts attempt ${attempt + 1} threw exception, retrying...", e)
            }

            if (attempt < MAX_RETRIES - 1) {
                // fetchAllContacts is called both from coroutine contexts
                // (importAllContactsFromSheetSuspend) and from plain
                // runOnIoThread contexts. runBlocking here lets the same
                // suspend-based delay be reused from either caller.
                runBlocking { delayBeforeRetry(attempt) }
            }
        }
        Log.e("SheetSync", "fetchAllContacts failed after $MAX_RETRIES attempts")
        return null
    }

    fun checkForNewNumbersSync(context: Context): Int {
        val contacts = fetchAllContacts(context) ?: return 0
        val alreadySynced = UserPrefs.getSyncedNumbers(context).map { normalizePhone(it) }.toSet()
        var newCount = 0
        for ((phone, _) in contacts) {
            if (phone.isNotEmpty() && !alreadySynced.contains(normalizePhone(phone))) {
                newCount++
            }
        }
        return newCount
    }

    /**
     * Single query against the Phone table (already includes each
     * contact's display name), instead of one query to list contacts
     * plus a second phone-lookup query per matching contact.
     */
    private fun reconcileFromExistingContacts(context: Context) {
        var maxFound = UserPrefs.getContactCounter(context)
        val existingPhones = HashSet<String>()
        val pattern = Regex("^VG KONTACT (\\d+)$")

        // Same filtering-in-the-query approach as getDevicePhoneNumbers()
        // above - only "VG KONTACT*" rows come back from the provider.
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("VG KONTACT%"),
            null
        )
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)?.trim() ?: continue
                if (!pattern.matches(name)) continue

                val num = pattern.find(name)?.groupValues?.get(1)?.toIntOrNull()
                if (num != null && num > maxFound) {
                    maxFound = num
                }

                val phone = it.getString(numIndex)
                if (!phone.isNullOrEmpty()) existingPhones.add(phone)
            }
        }

        if (maxFound > UserPrefs.getContactCounter(context)) {
            UserPrefs.setContactCounter(context, maxFound)
        }
        if (existingPhones.isNotEmpty()) {
            UserPrefs.addSyncedNumbers(context, existingPhones)
        }
    }

    suspend fun importAllContactsFromSheetSuspend(context: Context): Triple<Int, Int, String?> {
        return withContext(Dispatchers.Default) {
            var submitted = 0
            var failed = 0
            var errorDetail: String? = null

            if (!isOnline(context)) {
                return@withContext Triple(0, 0, "NO_INTERNET")
            }

            reconcileFromExistingContacts(context)
            var contactCount = UserPrefs.getContactCounter(context)
            val newlySynced = HashSet<String>()

            try {
                val contacts = fetchAllContacts(context)
                if (contacts == null) {
                    return@withContext Triple(0, 1, "Failed to fetch contacts from server")
                }

                val alreadySynced = UserPrefs.getSyncedNumbers(context).map { normalizePhone(it) }.toSet()
                val toAdd = ArrayList<Pair<String, String>>()
                for ((phone, _) in contacts) {
                    if (phone.isEmpty() || alreadySynced.contains(normalizePhone(phone))) {
                        continue
                    }
                    contactCount++
                    toAdd.add(Pair("VG KONTACT $contactCount", phone))
                }

                if (toAdd.isNotEmpty()) {
                    val (ok, fail) = addContactsBatched(context, toAdd)
                    submitted = ok
                    failed = fail
                    if (fail == 0) {
                        newlySynced.addAll(toAdd.map { it.second })
                    } else {
                        errorDetail = "$fail contact(s) failed to save locally"
                    }
                }
                if (newlySynced.isNotEmpty()) {
                    UserPrefs.addSyncedNumbers(context, newlySynced)
                    UserPrefs.setContactCounter(context, contactCount)
                    UserPrefs.recordSyncedToday(context, newlySynced.size)
                }
            } catch (e: Exception) {
                Log.e("SheetSync", "Error importing contacts", e)
                failed++
                errorDetail = e.message ?: e.javaClass.simpleName
            }
            Triple(submitted, failed, errorDetail)
        }
    }

    fun importAllContactsFromSheet(context: Context, callback: ((Int, Int, String?) -> Unit)? = null) {
        runOnIoThread {
            var submitted = 0
            var failed = 0
            var errorDetail: String? = null

            if (!isOnline(context)) {
                callback?.invoke(0, 0, "NO_INTERNET")
                return@runOnIoThread
            }

            reconcileFromExistingContacts(context)
            var contactCount = UserPrefs.getContactCounter(context)
            val newlySynced = HashSet<String>()

            try {
                val contacts = fetchAllContacts(context)
                if (contacts == null) {
                    callback?.invoke(0, 1, "Failed to fetch contacts from server")
                    return@runOnIoThread
                }

                val alreadySynced = UserPrefs.getSyncedNumbers(context).map { normalizePhone(it) }.toSet()
                val toAdd = ArrayList<Pair<String, String>>()
                for ((phone, _) in contacts) {
                    if (phone.isEmpty() || alreadySynced.contains(normalizePhone(phone))) {
                        continue
                    }
                    contactCount++
                    toAdd.add(Pair("VG KONTACT $contactCount", phone))
                }

                if (toAdd.isNotEmpty()) {
                    val (ok, fail) = addContactsBatched(context, toAdd)
                    submitted = ok
                    failed = fail
                    if (fail == 0) {
                        newlySynced.addAll(toAdd.map { it.second })
                    } else {
                        errorDetail = "$fail contact(s) failed to save locally"
                    }
                }
                if (newlySynced.isNotEmpty()) {
                    UserPrefs.addSyncedNumbers(context, newlySynced)
                    UserPrefs.setContactCounter(context, contactCount)
                    UserPrefs.recordSyncedToday(context, newlySynced.size)
                }
            } catch (e: Exception) {
                Log.e("SheetSync", "Error importing contacts", e)
                failed++
                errorDetail = e.message ?: e.javaClass.simpleName
            }
            callback?.invoke(submitted, failed, errorDetail)
        }
    }

    /**
     * Builds the ContentProviderOperations for ONE contact (insert + name +
     * phone), to be combined with other contacts' ops into a single
     * applyBatch() call.
     */
    private fun buildContactOps(name: String, phone: String, insertIndex: Int): List<ContentProviderOperation> {
        return listOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, insertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, insertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        )
    }

    /**
     * Writes many contacts to the phone's contact list in batches of 50,
     * instead of one applyBatch() call per contact.
     */
    private fun addContactsBatched(context: Context, contactsToAdd: List<Pair<String, String>>): Pair<Int, Int> {
        var submitted = 0
        var failed = 0
        val chunkSize = 50

        for (chunk in contactsToAdd.chunked(chunkSize)) {
            try {
                val ops = ArrayList<ContentProviderOperation>()
                for (i in chunk.indices) {
                    val (name, phone) = chunk[i]
                    ops.addAll(buildContactOps(name, phone, insertIndex = i * 3))
                }
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                submitted += chunk.size
            } catch (e: Exception) {
                Log.w("SheetSync", "addContactsBatched: batch of ${chunk.size} failed, retrying individually", e)
                for ((name, phone) in chunk) {
                    val (ok, _) = addSingleContactDetailed(context, name, phone)
                    if (ok) submitted++ else failed++
                }
            }
        }
        return Pair(submitted, failed)
    }

    private fun addSingleContactDetailed(context: Context, name: String, phone: String): Pair<Boolean, String?> {
        return try {
            val ops = buildContactOps(name, phone, insertIndex = 0)
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Runs [block] on a coroutine dispatched to Dispatchers.IO's shared
     * thread pool. This replaces the old kotlin.concurrent.thread { }
     * pattern, which spun up a brand new OS thread from scratch on every
     * single call with no reuse. Dispatchers.IO maintains a shared,
     * reusable pool sized for blocking I/O work, so repeated calls (e.g.
     * several screens fetching data close together) share threads instead
     * of each paying full thread-creation cost. Every public function in
     * this file still has the exact same callback-based shape as before -
     * only what runs the work in the background changed, so no calling
     * Activity needs to change.
     */
    private fun runOnIoThread(block: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            block()
        }
    }
}
