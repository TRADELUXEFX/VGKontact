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
import org.json.JSONTokener
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

data class MyReferral(val whatsapp: String, val createdAt: String, val level: Int = 1)

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

data class PaidCampaign(
    val id: Long,
    val name: String,
    val rate: String,
    val requirements: String?,
    val destinationUrl: String
)

data class GroupCap(
    val groupId: Long,
    val maxUsers: Long
)

data class AppUpdateInfo(
    val updateAvailable: Boolean,
    val latestVersionCode: Int,
    val latestVersionName: String,
    val minSupportedVersionCode: Int,
    val downloadUrl: String? = null
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
    // Launches `block` on the IO dispatcher without blocking the caller.
    // Callers use `return@runOnIoThread` inside `block` for early exit, so
    // this just needs to accept a suspend lambda and hand it to a coroutine.
    private fun runOnIoThread(block: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            block()
        }
    }

    private suspend fun delayBeforeRetry(attempt: Int) {
        delay(BASE_DELAY_MS * (attempt + 1))
    }

    /** Thrown by a withRetry block to stop retrying immediately (e.g. a non-retryable HTTP response), instead of exhausting all attempts pointlessly. */
    private class NonRetryableFailure : Exception()

    /**
     * Shared retry loop: calls [block] up to MAX_RETRIES times, waiting
     * (via delayBeforeRetry) between attempts. [block] returns:
     *   - a non-null T on success -> withRetry returns it immediately
     *   - null when the failure is retryable -> loop tries again
     * [block] can throw NonRetryableFailure to stop immediately (e.g. on
     * a 4xx response that will never succeed by retrying) - any other
     * exception is treated as an unexpected-but-possibly-transient error
     * and just triggers another attempt, same as before. Returns null if
     * every attempt is exhausted, or if a NonRetryableFailure is thrown.
     *
     * This replaces the "for (attempt in 0 until MAX_RETRIES) { ... }"
     * loop that used to be copy-pasted separately inside submit() and
     * fetchAllContacts() - same behavior, one place to change it.
     */
    private suspend fun <T> withRetry(block: suspend (attempt: Int) -> T?): T? {
        for (attempt in 0 until MAX_RETRIES) {
            try {
                val result = block(attempt)
                if (result != null) return result
            } catch (e: NonRetryableFailure) {
                return null
            } catch (e: Exception) {
                Log.w("SheetSync", "withRetry: attempt ${attempt + 1} threw exception", e)
            }
            if (attempt < MAX_RETRIES - 1) {
                delayBeforeRetry(attempt)
            }
        }
        return null
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

    private const val DEVICE_ALREADY_REGISTERED_MARKER = "DEVICE_ALREADY_REGISTERED:"
    // Distinct from DEVICE_ALREADY_REGISTERED_MARKER: this fires when a NEW
    // device (new android_id) tries a whatsapp number that's already on a
    // different device's account, rather than this same device re-signing-up.
    // signup_and_assign_group() used to raise DEVICE_ALREADY_REGISTERED for
    // both cases, which made them indistinguishable here.
    private const val NUMBER_ALREADY_REGISTERED_MARKER = "NUMBER_ALREADY_REGISTERED:"
    private const val BANNED_MARKER = "BANNED:"

    private fun isBannedStatus(status: String?): Boolean {
        val v = status?.trim()?.lowercase() ?: return false
        return v == "banned" || v == "suspended" || v == "blocked" || v.contains("banned")
    }

    /**
     * Extracts the raw Postgres error message from a response body, before
     * any friendly-text conversion happens. Needed because
     * signup_and_assign_group() raises a specific DEVICE_ALREADY_REGISTERED:<number>
     * marker that submit() must detect on its own terms - once the message
     * passes through friendlyErrorMessage() that marker is gone for good.
     */
    private fun rawErrorMessage(response: Response): String? {
        return try {
            val body = bodyString(response)
            if (body.isEmpty()) return null
            try {
                val obj = JSONObject(body)
                obj.optString("message").takeIf { it.isNotEmpty() }
                    ?: obj.optString("error_description").takeIf { it.isNotEmpty() }
                    ?: body
            } catch (e: Exception) {
                body
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readErrorBody(response: Response): String {
        val raw = rawErrorMessage(response) ?: return GENERIC_ERROR
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

    /**
     * Signs up a new contact AND assigns them a group in a single network
     * call, via the signup_and_assign_group() Postgres function. Both
     * steps happen inside one database transaction, so there's nothing to
     * wait on and no extra round trip.
     *
     * The database is the single source of truth for the "one account per
     * device" rule: signup_and_assign_group() raises
     * DEVICE_ALREADY_REGISTERED:<number> when this device has already
     * registered, and that failure happens inside the very same insert
     * attempt - there's no separate check that can fail independently of
     * the submission itself. callback's third value carries that number
     * when this happens, or null otherwise.
     */
    /**
     * Calls the check_app_version() Postgres function with this build's
     * version code, and reports back whether a newer approved release
     * exists, and whether this version has fallen below the minimum
     * supported version. Returns null on network/parse failure so callers
     * can just skip showing anything rather than show stale/wrong info.
     */
    fun checkAppVersion(currentVersionCode: Int, callback: (AppUpdateInfo?) -> Unit) {
        runOnIoThread {
            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val json = JSONObject()
                    json.put("p_current_version_code", currentVersionCode)
                    val request = buildRequest("rpc/check_app_version", "POST", json.toString())
                    httpClient.newCall(request).execute().use { response ->
                        val responseCode = response.code

                        if (responseCode in 200..299) {
                            val body = bodyString(response)
                            val info = try {
                                val row = when (val parsed = JSONTokener(body).nextValue()) {
                                    is JSONArray -> if (parsed.length() > 0) parsed.getJSONObject(0) else null
                                    is JSONObject -> parsed
                                    else -> null
                                }
                                row?.let {
                                    AppUpdateInfo(
                                        updateAvailable = it.optBoolean("update_available", false),
                                        latestVersionCode = it.optInt("latest_version_code", currentVersionCode),
                                        latestVersionName = it.optString("latest_version_name", ""),
                                        minSupportedVersionCode = it.optInt("min_supported_version_code", 0),
                                        downloadUrl = it.optString("download_url", "").ifBlank { null }
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e("SheetSync", "checkAppVersion: failed to parse response: $body", e)
                                null
                            }
                            callback(info)
                            return@runOnIoThread
                        } else if (!isRetryable(responseCode)) {
                            Log.w("SheetSync", "checkAppVersion: non-retryable response code $responseCode")
                            callback(null)
                            return@runOnIoThread
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SheetSync", "checkAppVersion: attempt $attempt failed", e)
                }
                delayBeforeRetry(attempt)
            }
            callback(null)
        }
    }

    /** Carries submit()'s four callback values through withRetry, since withRetry needs a single return value rather than a direct callback invocation per branch. */
    private data class SubmitOutcome(val success: Boolean, val error: String?, val existingWhatsapp: String?, val savedReferral: String?)

    fun submit(whatsapp: String, referral: String = "", name: String, context: Context? = null, androidId: String, callback: ((Boolean, String?, String?, String?) -> Unit)? = null) {
        runOnIoThread {
            // androidId is now read exactly once, at the moment the user
            // taps Submit in OnboardingActivity, and passed straight in here.
            // It used to be re-read independently inside this function too -
            // a second Settings.Secure lookup that could come back blank even
            // when the first one (on click) had just succeeded. That let
            // signups reach Supabase with no device id: the row saved with
            // android_id NULL, this device could register again and again
            // untracked, and later - once a read finally did succeed and
            // matched an old row - the user would land on DeviceBlockedActivity
            // out of nowhere. Reading once, on click, and reusing that same
            // value all the way through removes the second unreliable read
            // entirely.
            if (androidId.isBlank()) {
                callback?.invoke(false, "DEVICE_ID_UNAVAILABLE", null, null)
                return@runOnIoThread
            }

            val outcome = withRetry { attempt ->
                val hasContactsPermission = context?.let {
                    ContextCompat.checkSelfPermission(it, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(it, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
                } ?: false

                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                json.put("p_referral", referral)
                json.put("p_plan", if (hasContactsPermission) "VERIFIED" else "UNVERIFIED")
                json.put("p_name", name)
                json.put("p_android_id", androidId)
                val request = buildRequest("rpc/signup_and_assign_group", "POST", json.toString())
                httpClient.newCall(request).execute().use { response ->
                    val responseCode = response.code

                    if (responseCode in 200..299) {
                        val body = bodyString(response)

                        val (contactId, groupId, savedReferral) = try {
                            val arr = JSONArray(body)
                            if (arr.length() > 0) {
                                val row = arr.getJSONObject(0)
                                val savedReferral = if (row.isNull("referral")) {
                                    null
                                } else {
                                    row.optString("referral", "").ifBlank { null }
                                }
                                Triple(row.optLong("id", -1L), row.optLong("group_id", -1L), savedReferral)
                            } else {
                                Triple(-1L, -1L, null)
                            }
                        } catch (e: Exception) {
                            Log.e("SheetSync", "submit: failed to parse signup_and_assign_group response: $body", e)
                            Triple(-1L, -1L, null)
                        }

                        if (contactId <= 0 || groupId <= 0) {
                            val debugInfo = "id=$contactId group=$groupId resp=${body.take(150)}"
                            SubmitOutcome(false, "Signed up, but couldn't join a group. [$debugInfo]", null, null)
                        } else {
                            SubmitOutcome(true, null, null, savedReferral)
                        }
                    } else if (!isRetryable(responseCode)) {
                        val rawMessage = rawErrorMessage(response)
                        if (rawMessage != null && rawMessage.contains(BANNED_MARKER)) {
                            // Surfaced through the existing error string slot
                            // (like DEVICE_ID_UNAVAILABLE above) rather than
                            // adding a new SubmitOutcome field - keeps this
                            // callback's shape unchanged for every other caller.
                            SubmitOutcome(false, "BANNED", null, null)
                        } else if (rawMessage != null && rawMessage.contains(NUMBER_ALREADY_REGISTERED_MARKER)) {
                            // A different device already holds this number.
                            // Log the attempt (this device's android_id
                            // against the taken number) as a plain, separate
                            // insert - independent of the RPC call above, so
                            // there's no transaction for it to be rolled
                            // back with, and no dblink needed.
                            val attemptedWhatsapp = rawMessage
                                .substringAfter(NUMBER_ALREADY_REGISTERED_MARKER)
                                .trim()
                                .ifBlank { null }
                            if (attemptedWhatsapp != null) {
                                logRecoveryRequest(attemptedWhatsapp, androidId)
                            }
                            // error slot carries a marker (same pattern as
                            // "BANNED" above) so OnboardingActivity can tell
                            // this apart from the same-device case even
                            // though both currently route to
                            // DeviceBlockedActivity.
                            SubmitOutcome(false, "NUMBER_ALREADY_REGISTERED", attemptedWhatsapp, null)
                        } else if (rawMessage != null && rawMessage.contains(DEVICE_ALREADY_REGISTERED_MARKER)) {
                            val existingWhatsapp = rawMessage
                                .substringAfter(DEVICE_ALREADY_REGISTERED_MARKER)
                                .trim()
                                .ifBlank { null }
                            SubmitOutcome(false, null, existingWhatsapp, null)
                        } else {
                            val errorText = rawMessage?.let { friendlyErrorMessage(it) } ?: GENERIC_ERROR
                            SubmitOutcome(false, errorText, null, null)
                        }
                    } else {
                        Log.w("SheetSync", "submit attempt ${attempt + 1} failed with code $responseCode, retrying...")
                        null
                    }
                }
            }

            if (outcome != null) {
                callback?.invoke(outcome.success, outcome.error, outcome.existingWhatsapp, outcome.savedReferral)
            } else {
                callback?.invoke(false, "Failed after $MAX_RETRIES attempts", null, null)
            }
        }
    }

    /**
     * Result of calling rpc/login_check: exactly one of the four outcomes
     * the login_check() Postgres function returns, or null on a
     * network/parse failure that exhausted all retries.
     */
    enum class LoginOutcome {
        MATCH,
        NUMBER_NOT_FOUND,
        ANDROID_ID_MISMATCH,
        BANNED
    }

    /**
     * Calls rpc/login_check(p_whatsapp, p_android_id) and maps its plain-text
     * return value onto LoginOutcome. Same retry loop shape as every other
     * RPC call here (withRetry + isRetryable), so a flaky connection doesn't
     * surface as a false NUMBER_NOT_FOUND.
     *
     * Returns null only when every retry attempt failed outright (network
     * down, unexpected server error) - callers should show a generic
     * "couldn't reach the server" message in that case, not one of the four
     * real outcomes.
     */
    fun loginCheck(whatsapp: String, androidId: String, callback: (LoginOutcome?) -> Unit) {
        runOnIoThread {
            val outcome = withRetry { attempt ->
                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                json.put("p_android_id", androidId)
                val request = buildRequest("rpc/login_check", "POST", json.toString())
                httpClient.newCall(request).execute().use { response ->
                    val responseCode = response.code
                    if (responseCode in 200..299) {
                        val body = bodyString(response)
                        val raw = try {
                            JSONTokener(body).nextValue() as? String
                        } catch (e: Exception) {
                            Log.e("SheetSync", "loginCheck: failed to parse response: $body", e)
                            null
                        }
                        when (raw) {
                            "MATCH" -> LoginOutcome.MATCH
                            "NUMBER_NOT_FOUND" -> LoginOutcome.NUMBER_NOT_FOUND
                            "ANDROID_ID_MISMATCH" -> LoginOutcome.ANDROID_ID_MISMATCH
                            "BANNED" -> LoginOutcome.BANNED
                            else -> {
                                Log.e("SheetSync", "loginCheck: unrecognized outcome: $raw")
                                // Non-null so withRetry doesn't burn retries on
                                // a response the server will never change its
                                // mind about; caller treats null the same as
                                // an unrecognized value would need to anyway.
                                throw NonRetryableFailure()
                            }
                        }
                    } else if (!isRetryable(responseCode)) {
                        Log.w("SheetSync", "loginCheck: non-retryable response code $responseCode")
                        throw NonRetryableFailure()
                    } else {
                        Log.w("SheetSync", "loginCheck attempt ${attempt + 1} failed with code $responseCode, retrying...")
                        null
                    }
                }
            }
            callback(outcome)
        }
    }

    /**
     * Logs a duplicate-number signup attempt into recovery_requests, so it
     * shows up on the admin dashboard for manual follow-up. A plain REST
     * insert, entirely separate from the signup_and_assign_group() RPC
     * call that just failed - there's no shared transaction, so nothing
     * here can be rolled back by that failure, and no dblink or second
     * database connection is needed.
     *
     * One row per attempt (not an upsert) - if the same number is tried
     * from several devices, or the same device tries more than once,
     * each attempt gets its own row so the full history is visible on
     * the dashboard rather than only the most recent attempt.
     *
     * Fire-and-forget: runs on the IO thread, doesn't call back to the
     * caller, and a failure here only means this one log entry is
     * missing - it must never block or affect the rejection screen the
     * user is already being sent to.
     */
    private fun logRecoveryRequest(whatsapp: String, androidId: String) {
        runOnIoThread {
            try {
                val json = JSONObject()
                json.put("whatsapp", whatsapp)
                json.put("captured_android_id", androidId)
                val request = buildRequest("recovery_requests", "POST", json.toString())
                httpClient.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        Log.w("SheetSync", "logRecoveryRequest: failed with code ${response.code}: ${bodyString(response)}")
                    }
                }
            } catch (e: Exception) {
                Log.e("SheetSync", "logRecoveryRequest: failed to log attempt for $whatsapp", e)
            }
        }
    }

    fun fetchHistory(context: Context? = null, callback: ((List<DayCount>?, String?) -> Unit)? = null) {
        runOnIoThread {
            try {
                val request = buildRequest("contacts_public?select=created_at", "GET")
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
     * Checks whether this device (by Android ID) has already registered an
     * account, before the signup form is even shown. Returns the WhatsApp
     * number of the first account created on this device, or null if this
     * is a new device (or the check itself failed - fails open so a
     * network hiccup never locks a genuine new user out of signing up).
     */
    /**
     * Fetches this device's own contact row live from the database -
     * currently just the referral column. Profile screen uses this instead
     * of the locally cached UserPrefs value so it can never drift out of
     * sync with whatever the database actually holds, even if the row
     * gets edited directly (e.g. by an admin) after signup.
     */
    fun fetchMyProfile(context: Context, callback: (referral: String?, error: String?) -> Unit) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback(null, null)
                    return@runOnIoThread
                }
                val encodedWhatsapp = URLEncoder.encode(whatsapp, "UTF-8")
                val request = buildRequest(
                    "contacts_public?select=referral&whatsapp=eq.$encodedWhatsapp&limit=1",
                    "GET"
                )
                httpClient.newCall(request).execute().use { response ->
                    if (response.code in 200..299) {
                        val body = bodyString(response)
                        val arr = JSONArray(body)
                        if (arr.length() > 0) {
                            val row = arr.getJSONObject(0)
                            // org.json's optString only falls back to the
                            // default when the key is missing - if the DB
                            // column is a genuine SQL NULL, optString
                            // returns the literal string "null" instead,
                            // which isBlank()/ifBlank never catches. Check
                            // isNull() explicitly first so a real null
                            // referral becomes null here, not the word
                            // "null" showing up on the profile screen.
                            val referral = if (row.isNull("referral")) {
                                null
                            } else {
                                row.optString("referral", "").ifBlank { null }
                            }
                            callback(referral, null)
                        } else {
                            callback(null, null)
                        }
                    } else {
                        val errorText = readErrorBody(response)
                        callback(null, errorText)
                    }
                }
            } catch (e: java.io.IOException) {
                Log.w("SheetSync", "fetchMyProfile failed - network error", e)
                callback(null, "NO_INTERNET")
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchMyProfile failed", e)
                callback(null, "Couldn't load profile right now")
            }
        }
    }
    /**
     * Fetches the referral leaderboard: for each contact row, the
     * WhatsApp number of the person who referred them. Grouping by that
     * column and counting rows gives each referrer's total number of
     * referrals. Sorted descending so the top referrer appears first.
     */
    fun fetchReferralLeaderboard(context: Context? = null, callback: ((List<ReferralEntry>?, String?) -> Unit)? = null) {
        runOnIoThread {
            try {
                val request = buildRequest("contacts_public?select=referral&referral=not.is.null", "GET")
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
     * Fetches the contacts this user has personally referred (level 1,
     * contacts whose referral column equals this device's own WhatsApp
     * number) AND the contacts referred by THOSE referrals in turn
     * (level 2 - this user's downline's downline, same reach as
     * addSecondLevelReferralContactsSuspend's contact-adding logic).
     * Both are merged into one newest-first list for HistoryActivity's
     * "My referrals" tab, tagged by [MyReferral.level] so the UI can
     * badge each row appropriately.
     *
     * Two separate REST calls rather than the get_second_level_referrals
     * RPC used elsewhere in this file - that RPC only returns
     * whatsapp+name, not created_at, which this list needs for its
     * "time ago" display and newest-first ordering. Querying
     * contacts_public directly for both levels keeps the same shape
     * (whatsapp, created_at) for each, so they merge into one list
     * without a mismatched-fields workaround.
     */
    fun fetchMyReferrals(context: Context, callback: (List<MyReferral>?, String?) -> Unit) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback(emptyList(), null)
                    return@runOnIoThread
                }
                val encodedWhatsapp = URLEncoder.encode(whatsapp, "UTF-8")

                val directRequest = buildRequest(
                    "contacts_public?select=whatsapp,created_at&referral=eq.$encodedWhatsapp&order=created_at.desc",
                    "GET"
                )
                val direct = httpClient.newCall(directRequest).execute().use { response ->
                    if (response.code !in 200..299) return@use null
                    val arr = JSONArray(bodyString(response))
                    (0 until arr.length()).map {
                        val obj = arr.getJSONObject(it)
                        MyReferral(obj.optString("whatsapp"), obj.optString("created_at"), level = 1)
                    }
                }
                if (direct == null) {
                    callback(null, "Failed to fetch your referrals from server")
                    return@runOnIoThread
                }

                // Second level: anyone referred by one of this user's own
                // direct referrals. Empty when there are no direct
                // referrals yet, or none of them have referred anyone of
                // their own yet - not an error, so this stays a plain
                // empty list rather than falling back to null/failure.
                val secondLevel = if (direct.isEmpty()) {
                    emptyList()
                } else {
                    val directNumbers = direct.joinToString(",") {
                        URLEncoder.encode(it.whatsapp, "UTF-8")
                    }
                    val secondLevelRequest = buildRequest(
                        "contacts_public?select=whatsapp,created_at&referral=in.($directNumbers)",
                        "GET"
                    )
                    httpClient.newCall(secondLevelRequest).execute().use { response ->
                        if (response.code !in 200..299) {
                            // A failed second-level lookup shouldn't blank
                            // out an otherwise-successful direct-referrals
                            // fetch - just show level 1 alone this time.
                            Log.w("SheetSync", "fetchMyReferrals: second-level lookup failed with code ${response.code}")
                            emptyList()
                        } else {
                            val arr = JSONArray(bodyString(response))
                            (0 until arr.length()).map {
                                val obj = arr.getJSONObject(it)
                                MyReferral(obj.optString("whatsapp"), obj.optString("created_at"), level = 2)
                            }
                        }
                    }
                }

                val merged = (direct + secondLevel).sortedByDescending { it.createdAt }
                callback(merged, null)
            } catch (e: java.io.IOException) {
                Log.w("SheetSync", "fetchMyReferrals failed - network error", e)
                callback(null, "NO_INTERNET")
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchMyReferrals failed", e)
                callback(null, "Couldn't load your referrals right now")
            }
        }
    }

    /**
     * Fetches the DIRECT referrals of an arbitrary WhatsApp number (not
     * necessarily this device's own) - the same first REST call
     * fetchMyReferrals makes for the logged-in user, factored out so the
     * "My referrals" drill-in screen can reuse it for any referral tapped
     * into, at any depth. Always level = 1 relative to [whatsapp], since
     * the caller tracks depth via the breadcrumb stack, not this data.
     */
    fun fetchReferralsFor(whatsapp: String, callback: (List<MyReferral>?, String?) -> Unit) {
        runOnIoThread {
            try {
                val encoded = URLEncoder.encode(whatsapp, "UTF-8")
                val request = buildRequest(
                    "contacts_public?select=whatsapp,created_at&referral=eq.$encoded&order=created_at.desc",
                    "GET"
                )
                httpClient.newCall(request).execute().use { response ->
                    if (response.code in 200..299) {
                        val arr = JSONArray(bodyString(response))
                        val list = (0 until arr.length()).map {
                            val obj = arr.getJSONObject(it)
                            MyReferral(obj.optString("whatsapp"), obj.optString("created_at"), level = 1)
                        }
                        callback(list, null)
                    } else {
                        callback(null, "Couldn't load referrals right now")
                    }
                }
            } catch (e: java.io.IOException) {
                Log.w("SheetSync", "fetchReferralsFor failed - network error", e)
                callback(null, "NO_INTERNET")
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchReferralsFor failed", e)
                callback(null, "Couldn't load referrals right now")
            }
        }
    }

    /**
     * Given a list of WhatsApp numbers (e.g. one visible page of rows),
     * returns how many people each one has referred - one batched
     * request rather than one per row, same counting approach as
     * fetchReferralLeaderboard but scoped to just [numbers] via an
     * "in.(...)" filter instead of pulling the whole referral column.
     */
    fun fetchReferralCountsFor(numbers: List<String>, callback: (Map<String, Int>?, String?) -> Unit) {
        if (numbers.isEmpty()) {
            callback(emptyMap(), null)
            return
        }
        runOnIoThread {
            try {
                val encodedList = numbers.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
                val request = buildRequest(
                    "contacts_public?select=referral&referral=in.($encodedList)",
                    "GET"
                )
                httpClient.newCall(request).execute().use { response ->
                    if (response.code in 200..299) {
                        val arr = JSONArray(bodyString(response))
                        val counts = LinkedHashMap<String, Int>()
                        for (i in 0 until arr.length()) {
                            val referral = arr.getJSONObject(i).optString("referral").trim()
                            if (referral.isEmpty()) continue
                            counts[referral] = (counts[referral] ?: 0) + 1
                        }
                        callback(counts, null)
                    } else {
                        callback(null, "Couldn't load referral counts right now")
                    }
                }
            } catch (e: java.io.IOException) {
                Log.w("SheetSync", "fetchReferralCountsFor failed - network error", e)
                callback(null, "NO_INTERNET")
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchReferralCountsFor failed", e)
                callback(null, "Couldn't load referral counts right now")
            }
        }
    }

    /**
     * Adds this user's SECOND-LEVEL referrals as phone contacts - i.e.
     * the people referred by this user's own direct referrals (their
     * "downline's downline"). This is additive on top of the normal
     * referral flow: a user's direct referrals are added as contacts by
     * the existing sync pipeline already (see addContactsBatched calls
     * from importAllContactsFromSheet*), and this covers exactly one
     * layer beyond that - not the direct referrals themselves, and not
     * anything deeper than one extra layer.
     *
     * A plain suspend function - same shape as
     * importAllContactsFromSheetSuspend above - rather than a
     * callback, so both the background worker (which is already a
     * coroutine) and the callback-based Activity call sites can use it
     * directly, the Activity ones via the small wrapper below.
     *
     * The actual "who is second-level" lookup is one call to the
     * get_second_level_referrals RPC (see accompanying SQL) - the join
     * that used to be two separate contacts_public fetches, matched by
     * hand in Kotlin, now happens server-side in a single round trip.
     *
     * Named/numbered the same "[name] VGK[number]" way as normal group
     * contacts (unlike addUplineContact's deliberately unmarked
     * "[name] Upline" contacts above) - these are ordinary discoverable
     * contacts, not a special case that needs hiding from
     * removeStaleVgkContacts/getDevicePhoneNumbers, so there's no reason
     * to opt them out of that existing cleanup/dedupe machinery.
     *
     * Safe to call repeatedly (every login, every background check) -
     * gated by UserPrefs.getSyncedNumbers the same way the normal
     * import path is, so an already-added second-level contact is never
     * re-added or duplicated on a later run, and a NEW second-level
     * referral that appears later (e.g. this user's direct referral
     * gains a referral of their own next week) gets picked up the next
     * time this runs.
     */
    suspend fun addSecondLevelReferralContactsSuspend(context: Context): Pair<Int, Int> {
        try {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return Pair(0, 0)

            val whatsapp = UserPrefs.getWhatsapp(context)
            if (whatsapp.isNullOrEmpty()) return Pair(0, 0)

            val json = JSONObject()
            json.put("p_whatsapp", whatsapp)
            val request = buildRequest("rpc/get_second_level_referrals", "POST", json.toString())
            val secondLevel = httpClient.newCall(request).execute().use { response ->
                if (response.code !in 200..299) return@use null
                val arr = JSONArray(bodyString(response))
                (0 until arr.length()).map {
                    val obj = arr.getJSONObject(it)
                    Pair(obj.optString("whatsapp"), obj.optString("name"))
                }
            } ?: return Pair(0, 0)
            // Empty result just means no direct referrals yet, or none of
            // them have referrals of their own yet - not an error.
            if (secondLevel.isEmpty()) return Pair(0, 0)

            val alreadySynced = UserPrefs.getSyncedNumbers(context).map { normalizePhone(it) }.toSet()
            val numbersInUse = reconcileFromExistingContacts(context).toMutableSet()
            val toAdd = ArrayList<Pair<String, String>>()
            for ((phone, name) in secondLevel) {
                if (phone.isEmpty() || name.isEmpty() || alreadySynced.contains(normalizePhone(phone))) {
                    continue
                }
                val nextNumber = lowestFreeNumber(numbersInUse)
                numbersInUse.add(nextNumber)
                toAdd.add(Pair("$name VGK$nextNumber", phone))
            }
            if (toAdd.isEmpty()) return Pair(0, 0)

            val (ok, fail) = addContactsBatched(context, toAdd)
            if (ok > 0) {
                UserPrefs.addSyncedNumbers(context, toAdd.take(ok).map { it.second }.toSet())
            }
            return Pair(ok, fail)
        } catch (e: Exception) {
            Log.w("SheetSync", "addSecondLevelReferralContactsSuspend failed", e)
            return Pair(0, 0)
        }
    }

    /**
     * Callback wrapper around addSecondLevelReferralContactsSuspend for
     * the Activity call sites (PermissionSetupActivity), which aren't
     * coroutines themselves - same runOnIoThread + runBlocking bridge
     * every other suspend->callback wrapper in this file already uses,
     * not a one-off pattern invented for this function.
     */
    fun addSecondLevelReferralContacts(context: Context, callback: ((submitted: Int, failed: Int) -> Unit)? = null) {
        runOnIoThread {
            val (ok, fail) = runBlocking { addSecondLevelReferralContactsSuspend(context) }
            callback?.invoke(ok, fail)
        }
    }

    /**
     * Fetches all active paid campaigns from paid_campaigns. These are
     * pure listing data - name, rate, requirements, and a destination
     * link (WhatsApp group or DM) - managed entirely from the admin
     * panel. Nothing here is tracked or claimed in-app; tapping a
     * campaign's button just opens destinationUrl.
     */
    fun fetchPaidCampaigns(context: Context, callback: (List<PaidCampaign>?, String?) -> Unit) {
        runOnIoThread {
            try {
                val request = buildRequest(
                    "paid_campaigns?active=eq.true" +
                        "&select=id,name,rate,requirements,destination_url" +
                        "&order=created_at.desc",
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
                    val results = mutableListOf<PaidCampaign>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        results.add(
                            PaidCampaign(
                                id = obj.optLong("id"),
                                name = obj.optString("name"),
                                rate = obj.optString("rate"),
                                requirements = if (obj.isNull("requirements")) null else obj.optString("requirements"),
                                destinationUrl = obj.optString("destination_url")
                            )
                        )
                    }
                    callback(results, null)
                }
            } catch (e: java.io.IOException) {
                Log.w("SheetSync", "fetchPaidCampaigns failed - network error", e)
                callback(null, "NO_INTERNET")
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchPaidCampaigns failed", e)
                callback(null, "Couldn't load campaigns right now")
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

    /**
     * Asks the server why this number/device was banned (get_ban_reason RPC).
     * Callback gets null on any failure (offline, error), "" when the ban has
     * no reason set, or the reason code (multiple_accounts, deleted_contacts,
     * scam, other).
     */
    fun fetchBanReason(context: Context, whatsapp: String?, callback: (String?) -> Unit) {
        runOnIoThread {
            try {
                val json = JSONObject()
                json.put("p_whatsapp", whatsapp ?: "")
                json.put("p_android_id", readAndroidId(context))
                val request = buildRequest("rpc/get_ban_reason", "POST", json.toString())
                httpClient.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        callback(null)
                        return@runOnIoThread
                    }
                    val raw = bodyString(response).trim()
                    val reason = if (raw == "null") "" else raw.removeSurrounding("\"")
                    callback(reason)
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchBanReason failed", e)
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
                val androidId = readAndroidId(context)
                if (androidId.isBlank()) {
                    callback(null)
                    return@runOnIoThread
                }
                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                json.put("p_android_id", androidId)
                // Now goes through the get_my_plan() RPC (SECURITY DEFINER)
                // instead of a direct GET filtered by whatsapp+android_id -
                // the base contacts table has RLS enabled with no SELECT
                // policy now (the old "Allow anon read"/"Allow select for
                // anon" policies were both unrestricted, qual: true, so
                // they were dropped). plan isn't exposed via the
                // contacts_public view either since it's not something
                // other users should ever see, only the row's own device.
                val request = buildRequest("rpc/get_my_plan", "POST", json.toString())
                httpClient.newCall(request).execute().use { response ->
                    if (response.code in 200..299) {
                        val body = bodyString(response)
                        // get_my_plan returns a bare JSON string (RPC scalar
                        // return), e.g. "FREE" including the quotes - not a
                        // row array like the old PostgREST table GET did.
                        val plan = try {
                            JSONTokener(body).nextValue() as? String
                        } catch (e: Exception) {
                            null
                        }
                        callback(if (plan.isNullOrEmpty()) "FREE" else plan)
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

    /** Simple current/max pair for a single viewers row on the dashboard. */
    data class ViewerCount(val current: Long, val max: Long)

    /**
     * FREE VIEWERS row - current members of the user's home group only,
     * vs that group's cap, via get_my_free_viewers(). Deliberately does
     * NOT include extra_groups (purchased capacity) - that's a separate
     * row, see fetchPurchasedViewers below - so the two numbers never
     * double-count the same people.
     */
    fun fetchFreeViewers(context: Context, callback: (ViewerCount?) -> Unit) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback(null)
                    return@runOnIoThread
                }
                val androidId = readAndroidId(context)
                if (androidId.isBlank()) {
                    callback(null)
                    return@runOnIoThread
                }
                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                json.put("p_android_id", androidId)
                val request = buildRequest("rpc/get_my_free_viewers", "POST", json.toString())
                httpClient.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        callback(null)
                        return@runOnIoThread
                    }
                    val arr = JSONArray(bodyString(response))
                    if (arr.length() == 0) {
                        callback(null)
                        return@runOnIoThread
                    }
                    val obj = arr.getJSONObject(0)
                    callback(ViewerCount(obj.optLong("current_count", 0L), obj.optLong("max_users", 0L)))
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchFreeViewers failed", e)
                callback(null)
            }
        }
    }

    /**
     * PURCHASED VIEWERS row - current members across all of the user's
     * extra_groups (unlocked via redeem_key), vs their combined cap, via
     * get_my_purchased_viewers(). Home group is intentionally excluded -
     * see fetchFreeViewers above.
     */
    fun fetchPurchasedViewers(context: Context, callback: (ViewerCount?) -> Unit) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback(null)
                    return@runOnIoThread
                }
                val androidId = readAndroidId(context)
                if (androidId.isBlank()) {
                    callback(null)
                    return@runOnIoThread
                }
                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                json.put("p_android_id", androidId)
                val request = buildRequest("rpc/get_my_purchased_viewers", "POST", json.toString())
                httpClient.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        callback(null)
                        return@runOnIoThread
                    }
                    val arr = JSONArray(bodyString(response))
                    if (arr.length() == 0) {
                        callback(null)
                        return@runOnIoThread
                    }
                    val obj = arr.getJSONObject(0)
                    callback(ViewerCount(obj.optLong("current_count", 0L), obj.optLong("max_users", 0L)))
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchPurchasedViewers failed", e)
                callback(null)
            }
        }
    }

    /**
     * REFERRED VIEWERS row - first-level referrals plus second-level
     * (people referred by people this user referred), combined into one
     * number via get_my_referred_viewers_count(). Only needs whatsapp,
     * not android_id, since referral chains are keyed off whatsapp
     * numbers, same as get_referral_leaderboard/get_second_level_referrals.
     */
    fun fetchReferredViewersCount(context: Context, callback: (Long?) -> Unit) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback(null)
                    return@runOnIoThread
                }
                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                val request = buildRequest("rpc/get_my_referred_viewers_count", "POST", json.toString())
                httpClient.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        callback(null)
                        return@runOnIoThread
                    }
                    val trimmed = bodyString(response).trim()
                    callback(trimmed.toLongOrNull())
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchReferredViewersCount failed", e)
                callback(null)
            }
        }
    }

    /**
     * Looks up this device's registered name from the server, for the
     * DeviceBlockedActivity "log in" flow - restoring local state for a
     * device the server already recognizes needs the real saved name,
     * not a blank string, since there's no fresh signup form to type it
     * into at that point. Mirrors fetchPlan's shape exactly: same RPC
     * call pattern, same get_my_plan-style scalar-string response via
     * the new get_my_name() SECURITY DEFINER function, matched by the
     * same p_whatsapp + p_android_id pair every other per-device RPC uses.
     *
     * whatsapp is passed in explicitly rather than read from UserPrefs
     * (unlike fetchPlan) because this runs from DeviceBlockedActivity,
     * before UserPrefs.saveUser() has been called for this device -
     * there's nothing in local storage yet to read it from.
     */
    fun fetchRegisteredName(context: Context, whatsapp: String, callback: (String?) -> Unit) {
        runOnIoThread {
            try {
                if (whatsapp.isBlank()) {
                    callback(null)
                    return@runOnIoThread
                }
                val androidId = readAndroidId(context)
                if (androidId.isBlank()) {
                    callback(null)
                    return@runOnIoThread
                }
                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                json.put("p_android_id", androidId)
                val request = buildRequest("rpc/get_my_name", "POST", json.toString())
                httpClient.newCall(request).execute().use { response ->
                    if (response.code in 200..299) {
                        val body = bodyString(response)
                        // Same bare-JSON-string scalar shape as get_my_plan
                        // above - a plain quoted string, not a row array.
                        val name = try {
                            JSONTokener(body).nextValue() as? String
                        } catch (e: Exception) {
                            null
                        }
                        callback(name?.takeIf { it.isNotBlank() })
                    } else {
                        callback(null)
                    }
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchRegisteredName failed", e)
                callback(null)
            }
        }
    }

    /**
     * Saves this user's upline (the referrer whose code they entered at
     * signup, i.e. UserPrefs.getReferral()) AND that upline's own upline
     * (one hop further up the referral chain, via get_upline_of_upline -
     * see accompanying SQL) as real phone contacts - called once, right
     * after Contacts permission is granted in PermissionSetupActivity,
     * alongside the normal first sync.
     *
     * This is the mirror of addSecondLevelReferralContactsSuspend's
     * one-hop-down reach: that function gives a user their downline's
     * downline; this one gives a new signup their upline's upline. Same
     * referral chain, same one-extra-hop rule, walked from opposite
     * ends - so if C signs up under B who was referred by A, A gains C
     * (via the second-level function) and C gains A (via this
     * function), in addition to the direct A<->B and B<->C links that
     * already existed.
     *
     * Unlike fetchRegisteredName above (which looks up the CURRENT
     * device's own name, matched by its own android_id via the
     * get_my_name RPC), the direct-upline lookup here looks up a
     * DIFFERENT device's name - the referrer's - so it can't use that
     * RPC's matching logic. Instead it queries contacts_public directly
     * for the row whose whatsapp equals the referral number, same query
     * shape fetchMyReferrals already uses elsewhere in this file, just
     * filtering on whatsapp instead of referral.
     *
     * Both contacts are labeled plain "[Name] Upline" - deliberately
     * WITHOUT the "VGK" marker or a numeric suffix that every other
     * app-created contact carries (see buildContactOps/
     * getDevicePhoneNumbers/removeStaleVgkContacts above), so they read
     * as normal, clean contact names instead of exposing internal
     * bookkeeping. This is a deliberate trade-off: because they have no
     * "VGK" marker, these contacts are invisible to every VGK-pattern-
     * based function in this file - reconcileFromExistingContacts/
     * getDevicePhoneNumbers won't find them, and removeStaleVgkContacts
     * can't accidentally delete them either (they were never in that
     * cleanup's target set to begin with, per that function's own
     * comments, so this doesn't newly expose them to anything).
     * Duplicate-prevention for these contacts instead relies entirely
     * on UserPrefs.getSyncedNumbers/addSyncedNumbers below, keyed by
     * phone number rather than by scanning contact names - a separate,
     * already-existing mechanism, not something this function has to
     * invent.
     *
     * Each half (direct upline, upline-of-upline) is independent - a
     * failure or absence of one doesn't block the other. Silently skips
     * the whole function (no callback param at all - fire and forget,
     * same as how PermissionSetupActivity already fires the normal
     * contacts sync without blocking the UI on its result) when there's
     * no referral on this account at all (most users won't have one) or
     * contacts permission isn't actually granted (defensive check -
     * callers should only invoke this once permission is confirmed, but
     * this makes the function safe standalone too). A failed upline
     * save is never worth interrupting or delaying the rest of
     * onboarding for - same reasoning as the normal sync's silent
     * submitted==0 case.
     */
    /**
     * Looks up the display name for a given whatsapp number via
     * contacts_public (select=name&whatsapp=eq.<number>) - the same
     * query shape fetchMyReferrals already uses elsewhere in this file,
     * just filtering on whatsapp instead of referral. Shared by
     * addUplineContact (looking up the current user's upline) and
     * ProfileActivity's "Referred By" display (same lookup, different
     * caller/thread context), so both draw from one query instead of
     * two copies that could drift.
     *
     * Synchronous/blocking - callers already on a background thread
     * (like addUplineContact, itself inside runOnIoThread) can call this
     * directly; callers on the main thread (like an Activity) should
     * wrap it in runOnIoThread themselves, same as every other network
     * call in this file.
     */
    private fun fetchNameForWhatsappBlocking(whatsapp: String): String? {
        val encoded = URLEncoder.encode(whatsapp, "UTF-8")
        val request = buildRequest(
            "contacts_public?select=name&whatsapp=eq.$encoded&limit=1",
            "GET"
        )
        return httpClient.newCall(request).execute().use { response ->
            if (response.code !in 200..299) return@use null
            val arr = JSONArray(bodyString(response))
            if (arr.length() == 0) return@use null
            val obj = arr.getJSONObject(0)
            if (obj.isNull("name")) null else obj.optString("name", "").ifBlank { null }
        }
    }

    /**
     * Callback wrapper around fetchNameForWhatsappBlocking for callers on
     * the main thread (e.g. an Activity's onCreate) that can't block.
     * Runs the lookup on a background thread via runOnIoThread and hands
     * the result back through callback - caller is responsible for
     * hopping back to the main thread if updating UI, same convention as
     * fetchRegisteredName/fetchPlan/every other callback-based function
     * in this file.
     */
    fun fetchNameForWhatsapp(whatsapp: String, callback: (String?) -> Unit) {
        runOnIoThread {
            val name = try {
                fetchNameForWhatsappBlocking(whatsapp)
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchNameForWhatsapp failed", e)
                null
            }
            callback(name)
        }
    }

    fun addUplineContact(context: Context) {
        runOnIoThread {
            try {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.WRITE_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.READ_CONTACTS
                    ) == PackageManager.PERMISSION_GRANTED
                if (!hasPermission) return@runOnIoThread

                val referralNumber = UserPrefs.getReferral(context)?.takeIf { it.isNotBlank() }
                    ?: return@runOnIoThread

                val alreadySynced = UserPrefs.getSyncedNumbers(context).map { normalizePhone(it) }.toSet()
                val newlySynced = HashSet<String>()

                // Direct upline - unchanged from before. Recorded via
                // addSyncedNumbers IMMEDIATELY after it succeeds, not
                // batched with the grand-upline save at the end of the
                // function - if the grand-upline lookup below throws
                // (network error, RPC not deployed yet, etc.), that
                // exception must not un-record a direct-upline contact
                // that was already successfully written to the phone,
                // or the next run would try to add it again and create
                // a duplicate.
                if (!alreadySynced.contains(normalizePhone(referralNumber))) {
                    val uplineName = fetchNameForWhatsappBlocking(referralNumber)
                    if (uplineName != null) {
                        // No VGK marker or numeric suffix - see the class
                        // doc above for why this is a deliberate, safe
                        // trade-off.
                        val (ok, _) = addSingleContactDetailed(context, "$uplineName Upline", referralNumber)
                        if (ok) {
                            newlySynced.add(referralNumber)
                            UserPrefs.addSyncedNumbers(context, setOf(referralNumber))
                        }
                    }
                }

                // Upline's own upline - one hop further up, via
                // get_upline_of_upline (see accompanying SQL). This is
                // the mirror of addSecondLevelReferralContactsSuspend's
                // one-hop-down reach: just as a user gains their
                // downline's downline, a new downline here gains their
                // upline's upline - same referral chain, walked one
                // extra hop from each end. Wrapped in its own try/catch
                // so a failure here (network error, or this RPC not
                // having been deployed to the database yet) can never
                // undo or block the direct-upline save above, which has
                // already completed and been recorded by this point.
                val currentlySynced = alreadySynced + newlySynced.map { normalizePhone(it) }
                try {
                    val grandUplineJson = JSONObject()
                    grandUplineJson.put("p_upline_whatsapp", referralNumber)
                    val grandUplineRequest = buildRequest("rpc/get_upline_of_upline", "POST", grandUplineJson.toString())
                    val grandUpline = httpClient.newCall(grandUplineRequest).execute().use { response ->
                        if (response.code !in 200..299) return@use null
                        val arr = JSONArray(bodyString(response))
                        if (arr.length() == 0) return@use null
                        val obj = arr.getJSONObject(0)
                        Pair(obj.optString("whatsapp"), obj.optString("name"))
                    }
                    if (grandUpline != null) {
                        val (grandUplineNumber, grandUplineName) = grandUpline
                        if (grandUplineNumber.isNotBlank() && grandUplineName.isNotBlank() &&
                            !currentlySynced.contains(normalizePhone(grandUplineNumber))
                        ) {
                            val (ok, _) = addSingleContactDetailed(context, "$grandUplineName Upline", grandUplineNumber)
                            if (ok) {
                                newlySynced.add(grandUplineNumber)
                                UserPrefs.addSyncedNumbers(context, setOf(grandUplineNumber))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SheetSync", "addUplineContact: grand-upline lookup failed, direct upline unaffected", e)
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "addUplineContact failed", e)
            }
        }
    }

    /**
     * Updates the current user's `plan` column to reflect whether they actually
     * granted contacts permission during PermissionSetupActivity.
     *
     * Goes through the update_verification_status() RPC rather than a direct
     * PATCH on contacts - the open "Allow anon update own contact" policy that
     * used to allow that direct write was removed, since nothing enforced it
     * being the caller's own row. This RPC is a plain SECURITY DEFINER function
     * (see Supabase), so it runs with its own permissions regardless of the
     * caller's table access.
     */
    /**
     * Reads Settings.Secure.ANDROID_ID fresh from the given context. Used
     * by calls that need to prove device identity to the server (see the
     * android_id ownership checks added to update_verification_status and
     * record_sync_checkin) but that run well after onboarding, where no
     * androidId value is already in hand the way submit() has one. This is
     * a plain direct read - it does not reproduce the retry/timing-quirk
     * workaround OnboardingActivity uses on first-ever launch, since by the
     * time these calls run the OS has been up and settled for a while.
     */
    private fun readAndroidId(context: Context): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: ""
    }

    fun updateVerificationStatus(context: Context, verified: Boolean, callback: ((Boolean) -> Unit)? = null) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback?.invoke(false)
                    return@runOnIoThread
                }
                val androidId = readAndroidId(context)
                if (androidId.isBlank()) {
                    Log.w("SheetSync", "updateVerificationStatus: android ID unavailable, skipping")
                    callback?.invoke(false)
                    return@runOnIoThread
                }

                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                json.put("p_verified", verified)
                json.put("p_android_id", androidId)

                val request = buildRequest("rpc/update_verification_status", "POST", json.toString())

                httpClient.newCall(request).execute().use { response ->
                    if (response.code in 200..299) {
                        callback?.invoke(true)
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
     * Removes every contact this app added to the phone (identified the
     * same way reconcileFromExistingContacts() finds them: display name
     * ending in "VGK<N>"), then clears the local synced-numbers set so a
     * future resume starts clean instead of thinking those contacts are
     * still there. Only ever touches contacts this app created - never the
     * user's own address book entries.
     *
     * Returns how many contacts were removed, purely so the caller can show
     * a confirmation - the pause itself (UserPrefs.setSyncPaused) is what
     * actually stops syncing, and is expected to already be set by the
     * caller before this runs.
     */
    fun deleteAllSyncedContacts(context: Context): Int {
        val pattern = Regex("VGK(\\d+)$")
        val rawContactIdsToDelete = ArrayList<Long>()

        val cursor = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME
            ),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME} LIKE ?",
            arrayOf(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, "%VGK%"),
            null
        )
        cursor?.use {
            val rawIdIndex = it.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)?.trim() ?: continue
                if (pattern.containsMatchIn(name)) {
                    rawContactIdsToDelete.add(it.getLong(rawIdIndex))
                }
            }
        }

        if (rawContactIdsToDelete.isEmpty()) return 0

        val ops = ArrayList<ContentProviderOperation>()
        for (rawId in rawContactIdsToDelete) {
            ops.add(
                ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
                    .withSelection("${ContactsContract.RawContacts._ID} = ?", arrayOf(rawId.toString()))
                    .build()
            )
        }

        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            Log.w("SheetSync", "deleteAllSyncedContacts: applyBatch failed", e)
            return 0
        }

        // Clear the local record too - without this, the app would still
        // believe these numbers are synced and skip re-adding them once
        // the user resumes.
        UserPrefs.setSyncedNumbers(context, emptySet())

        return rawContactIdsToDelete.size
    }

    /**
     * Tells the backend this user paused (or resumed) syncing, for record-
     * keeping only - matches the "mark inactive, never delete the row"
     * rule. This is fire-and-forget by default: the pause itself is
     * enforced locally by UserPrefs.isSyncPaused() regardless of whether
     * this call succeeds, so a failed/slow network request never blocks
     * or delays the pause from working. Pass a callback to also surface
     * the real result (used by the delete/resume UI to show a genuine
     * success/failure toast instead of hiding failures in Logcat, the way
     * the earlier silent version did).
     */
    fun reportSyncPauseStatus(context: Context, paused: Boolean, callback: ((Boolean, String) -> Unit)? = null) {
        runOnIoThread {
            val whatsapp = UserPrefs.getWhatsapp(context)
            if (whatsapp.isNullOrEmpty()) {
                callback?.invoke(false, "No whatsapp saved locally (UserPrefs.getWhatsapp is null/empty)")
                return@runOnIoThread
            }
            val androidId = readAndroidId(context)
            if (androidId.isBlank()) {
                callback?.invoke(false, "Android ID unavailable")
                return@runOnIoThread
            }
            try {
                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                json.put("p_android_id", androidId)
                json.put("p_paused", paused)

                // Now goes through the report_sync_pause_status() RPC
                // (SECURITY DEFINER) instead of a direct PATCH on contacts -
                // the open "Allow limited update for anon" policy that made
                // the PATCH work was actually unrestricted (qual: true, no
                // real per-row check), so it was removed. The RPC itself
                // still verifies whatsapp+android_id match before writing,
                // same ownership check as before, just enforced server-side
                // now instead of relying on an RLS policy that didn't
                // actually enforce it.
                val request = buildRequest("rpc/report_sync_pause_status", "POST", json.toString())
                httpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code !in 200..299) {
                        val body = try { response.body?.string() } catch (e: Exception) { null }
                        Log.w("SheetSync", "reportSyncPauseStatus failed with code $code body=$body")
                        callback?.invoke(false, "Server returned $code: ${body ?: "(no body)"}")
                    } else {
                        callback?.invoke(true, "Updated status for $whatsapp")
                    }
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "reportSyncPauseStatus failed", e)
                callback?.invoke(false, "Exception: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Reports this user's live 0-3 setup stage and stamps the first-reached
     * timestamp columns as needed. See PermissionHealth.Status.stage.
     */
    fun reportSetupStage(context: Context, stage: String, callback: ((Boolean) -> Unit)? = null) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback?.invoke(false)
                    return@runOnIoThread
                }
                val androidId = readAndroidId(context)
                if (androidId.isBlank()) {
                    callback?.invoke(false)
                    return@runOnIoThread
                }
                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                json.put("p_android_id", androidId)
                json.put("p_stage", stage)

                // Now goes through the report_setup_stage() RPC (SECURITY
                // DEFINER), same reasoning as reportSyncPauseStatus above -
                // the direct PATCH relied on an "Allow limited update for
                // anon" policy that turned out to have no real restriction
                // (qual: true). The RPC also does the first-reached-stage
                // stamping atomically in the same statement now, so the
                // three separate stampFirstReachedIfNull follow-up calls
                // below are no longer needed.
                val request = buildRequest("rpc/report_setup_stage", "POST", json.toString())
                val responseCode = httpClient.newCall(request).execute().use { it.code }

                if (responseCode !in 200..299) {
                    Log.w("SheetSync", "reportSetupStage: live stage update failed with code $responseCode")
                    callback?.invoke(false)
                    return@runOnIoThread
                }

                callback?.invoke(true)
            } catch (e: Exception) {
                Log.w("SheetSync", "reportSetupStage failed", e)
                callback?.invoke(false)
            }
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
     * "<username> VGK<number>", e.g. "John VGK1").
     */
    private fun getDevicePhoneNumbers(context: Context): Set<String> {
        val numbers = HashSet<String>()
        val pattern = Regex("VGK\\d+$")

        // Pushing the "%VGK%" filter into the query's selection args
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
            arrayOf("%VGK%"),
            null
        )
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)?.trim() ?: continue
                if (!pattern.containsMatchIn(name)) continue

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
        val androidId = readAndroidId(context)
        if (androidId.isBlank()) return null
        try {
            val json = JSONObject()
            json.put("p_whatsapp", whatsapp)
            json.put("p_android_id", androidId)
            // Now goes through the get_my_groups() RPC (SECURITY DEFINER) -
            // same reasoning as fetchPlan above. group_id/extra_groups
            // aren't in the contacts_public view since they're this row's
            // own membership info, not something other users need to see.
            val request = buildRequest("rpc/get_my_groups", "POST", json.toString())
            httpClient.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    return null
                }
                val body = bodyString(response)
                // RPC table-returning functions come back as a row array,
                // same shape as a PostgREST table GET.
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

    /**
     * Same group membership as fetchMyGroupsSplit, flattened into one
     * list. Delegates to fetchMyGroupsSplit rather than making its own
     * separate network call, since both were reading identical data -
     * one request instead of two, same result either caller needs.
     */
    private fun fetchMyGroups(context: Context): List<Long>? {
        val (homeGroup, extra) = fetchMyGroupsSplit(context) ?: return null
        val groups = ArrayList<Long>()
        if (homeGroup != null) groups.add(homeGroup)
        groups.addAll(extra)
        return if (groups.isEmpty()) null else groups
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

    /**
     * Result of the single-call sync_checkin RPC: replaces the old
     * fetchMyGroups + fetchAllContacts + stampLastSyncedAt trio (up to 3
     * round-trips) with exactly one. status lets the caller show a
     * dedicated "banned" screen instead of quietly syncing zero contacts;
     * contacts is already empty for a banned user server-side, so no
     * client-side special-casing is needed for that part.
     */
    data class SyncCheckinResult(
        val groupId: Long?,
        val extraGroups: List<Long>,
        val status: String?,
        val contacts: List<Triple<String, String, String>>
    )

    /**
     * Single network call replacing fetchMyGroups + fetchAllContacts +
     * stampLastSyncedAt for the manual "Sync Now" path. Returns null only
     * on a real fetch failure (offline handling is the caller's job, same
     * as before) - a banned or groupless user is still a successful
     * result, just with an empty contacts list and status reflecting why.
     */
    private fun syncCheckin(context: Context): SyncCheckinResult? {
        val whatsapp = UserPrefs.getWhatsapp(context) ?: return null
        val androidId = readAndroidId(context)
        if (androidId.isBlank()) return null

        return runBlocking {
            withRetry { attempt ->
                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                json.put("p_android_id", androidId)
                val request = buildRequest("rpc/sync_checkin", "POST", json.toString())
                httpClient.newCall(request).execute().use { response ->
                    val responseCode = response.code
                    if (responseCode in 200..299) {
                        val body = bodyString(response)
                        val arr = JSONArray(body)
                        if (arr.length() == 0) return@use null
                        val obj = arr.getJSONObject(0)
                        val groupId = if (obj.isNull("group_id")) null else obj.optLong("group_id")
                        val extra = ArrayList<Long>()
                        obj.optJSONArray("extra_groups")?.let {
                            for (i in 0 until it.length()) extra.add(it.getLong(i))
                        }
                        val contactsArr = obj.optJSONArray("contacts") ?: JSONArray()
                        val contacts = ArrayList<Triple<String, String, String>>()
                        for (i in 0 until contactsArr.length()) {
                            val c = contactsArr.getJSONObject(i)
                            contacts.add(Triple(c.optString("whatsapp"), c.optString("referral"), c.optString("name")))
                        }
                        val statusText = obj.optString("status", null)
                        // Server just saw this user: restart the 5-day inactivity timer.
                        if (!isBannedStatus(statusText)) InactivityWarningWorker.reschedule(context)
                        SyncCheckinResult(groupId, extra, statusText, contacts)
                    } else {
                        if (!isRetryable(responseCode)) {
                            // A banned account can be rejected with a hard
                            // error carrying the BANNED marker instead of a
                            // clean status="banned" row. Treat it as a ban,
                            // not a generic failure.
                            val raw = rawErrorMessage(response)
                            if (raw != null && raw.contains("banned", ignoreCase = true)) {
                                return@use SyncCheckinResult(null, emptyList(), "banned", emptyList())
                            }
                            throw NonRetryableFailure()
                        }
                        Log.w("SheetSync", "syncCheckin attempt ${attempt + 1} failed with code $responseCode, retrying...")
                        null
                    }
                }
            }
        }
    }

    /**
     * Lightweight ban check used every time the dashboard opens. One
     * sync_checkin round trip; result is true (banned), false (not
     * banned) or null (couldn't tell - offline/server error, in which
     * case the caller leaves the last-known state alone). Updates the
     * persisted flag either way it gets a definite answer.
     */
    fun checkBanStatus(context: Context, callback: (Boolean?) -> Unit) {
        runOnIoThread {
            if (!isOnline(context)) {
                callback(null)
                return@runOnIoThread
            }
            val result = try { syncCheckin(context) } catch (e: Exception) { null }
            if (result == null) {
                callback(null)
                return@runOnIoThread
            }
            val banned = isBannedStatus(result.status)
            UserPrefs.setBanned(context, banned)
            callback(banned)
        }
    }


    private fun fetchAllContacts(context: Context? = null): List<Triple<String, String, String>>? {
        val groupFilter = if (context != null) {
            val groups = fetchMyGroups(context)
            if (groups.isNullOrEmpty()) {
                return emptyList()
            }
            "&group_id=in.(${groups.joinToString(",")})"
        } else {
            ""
        }

        // fetchAllContacts is called both from coroutine contexts
        // (importAllContactsFromSheetSuspend) and from plain
        // runOnIoThread contexts, so runBlocking bridges into the
        // suspend-based withRetry/delayBeforeRetry from either caller.
        return runBlocking {
            withRetry { attempt ->
                val request = buildRequest("contacts_public?select=whatsapp,referral,name$groupFilter", "GET")
                httpClient.newCall(request).execute().use { response ->
                    val responseCode = response.code
                    if (responseCode in 200..299) {
                        val body = bodyString(response)
                        val arr = JSONArray(body)
                        val result = ArrayList<Triple<String, String, String>>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            result.add(Triple(obj.optString("whatsapp"), obj.optString("referral"), obj.optString("name")))
                        }
                        result
                    } else {
                        if (!isRetryable(responseCode)) {
                            // Non-retryable failure: withRetry only knows
                            // "null means try again," so a hard failure
                            // here has to escape the retry loop some other
                            // way. Throwing this marker exception and
                            // catching it inside withRetry does that
                            // without changing withRetry's generic
                            // "null = retry" contract for every other caller.
                            throw NonRetryableFailure()
                        }
                        Log.w("SheetSync", "fetchAllContacts attempt ${attempt + 1} failed with code $responseCode, retrying...")
                        null
                    }
                }
            }
        }
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
     *
     * Reconciles UserPrefs' synced-numbers set to match what's ACTUALLY
     * on the phone right now, in both directions:
     *   - numbers found here that aren't marked synced yet get added
     *   - numbers marked synced that are no longer found here (their
     *     VGK contact was deleted) get REMOVED from the synced set
     * Without the second half, a manually-deleted contact would stay
     * marked "already synced" forever, and the next Sync tap would never
     * bring it back - the app would keep reporting "No new numbers" even
     * though that contact is genuinely missing from the phone again.
     *
     * Also returns every "VGK<N>" label number currently in use on
     * the phone, so callers can find and reuse the lowest free number
     * instead of always incrementing past the highest one ever assigned -
     * e.g. after deleting VGK1 and VGK2, the next new contact
     * should become VGK1 again, not 3.
     */
    /** One row from a scan of this device's VGK-tagged contacts: enough to reconcile, dedupe, or delete without a second query. */
    private data class VgkContactRow(val rawContactId: Long, val name: String, val normalizedPhone: String)

    /**
     * Single query over the Phone table for every VGK-tagged contact on
     * the device, returning name + phone + raw contact id together.
     *
     * Both reconcileFromExistingContacts and removeStaleVgkContacts used
     * to each run their own separate ContentResolver query (and
     * removeStaleVgkContacts ran two of its own, one against Data for
     * names and a second against Phone for numbers, joined by hand in
     * Kotlin) even though a single sync calls both back to back. Phone
     * already exposes RAW_CONTACT_ID directly (removeStaleVgkContacts'
     * second query already relied on this), so one pass here is enough
     * to give both callers everything they need - down from three
     * device-contacts scans per sync to one.
     */
    private fun scanVgkContacts(context: Context): List<VgkContactRow> {
        val rows = ArrayList<VgkContactRow>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%VGK%"),
            null
        )
        cursor?.use {
            val rawIdIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)?.trim() ?: continue
                val phone = it.getString(numIndex) ?: continue
                val normalized = normalizePhone(phone)
                if (normalized.isEmpty()) continue
                rows.add(VgkContactRow(it.getLong(rawIdIndex), name, normalized))
            }
        }
        return rows
    }

    private fun reconcileFromExistingContacts(context: Context): Set<Int> {
        val pattern = Regex("VGK(\\d+)$")
        val existingPhones = HashSet<String>()
        val numbersInUse = HashSet<Int>()

        // Contacts saved by this app are now named "<username> VGK<N>",
        // so matching on a "VGK<digits>" suffix is what identifies a
        // device contact as one this app created.
        for (row in scanVgkContacts(context)) {
            val match = pattern.find(row.name) ?: continue
            match.groupValues[1].toIntOrNull()?.let { numbersInUse.add(it) }
            existingPhones.add(row.normalizedPhone)
        }

        // Rebuild the synced set to match reality: any number no longer
        // backed by a real VGK contact on the phone (deleted) drops
        // out, so a later Sync tap treats it as new again instead of
        // silently believing it's still there. existingPhones IS that
        // reconciled set - it already represents "every VGK number
        // currently on the phone," which is exactly what should count as
        // synced going forward.
        val previouslySynced = UserPrefs.getSyncedNumbers(context)
        if (existingPhones != previouslySynced) {
            UserPrefs.setSyncedNumbers(context, existingPhones)
        }

        return numbersInUse
    }

    /**
     * Deletes VGK-tagged contacts from the phone whose number is NOT in
     * [currentServerPhones] - i.e. people who were in this group before but
     * have since been removed/banned server-side (group_id cleared, or the
     * whole row deleted).
     *
     * SAFETY: callers must only invoke this when [currentServerPhones] is
     * known to be a real, successful fetch result - never on a null/failed
     * fetch. A failed fetch must never be treated as "everyone left the
     * group." This function itself does not distinguish real-empty from
     * glitch-empty; that check belongs to the caller (see
     * importAllContactsFromSheetSuspend).
     *
     * Returns the number of contacts removed.
     */
    private fun logContactsRemoved(context: Context, removed: Int) {
        val text = if (removed == 1) {
            "1 contact removed - that user is no longer active"
        } else {
            "$removed contacts removed - those users are no longer active"
        }
        ActivityLog.add(context, ActivityLog.Type.CONTACTS_REMOVED, text)
    }

    private fun removeStaleVgkContacts(context: Context, currentServerPhones: Set<String>): Int {
        val pattern = Regex("VGK\\d+$")
        val rawIdsToDelete = ArrayList<Long>()
        val phonesRemoved = HashSet<String>()

        for (row in scanVgkContacts(context)) {
            if (!pattern.containsMatchIn(row.name)) continue
            if (!currentServerPhones.contains(row.normalizedPhone)) {
                rawIdsToDelete.add(row.rawContactId)
                phonesRemoved.add(row.normalizedPhone)
            }
        }

        if (rawIdsToDelete.isEmpty()) return 0

        val ops = ArrayList<ContentProviderOperation>()
        for (rawId in rawIdsToDelete) {
            ops.add(
                ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
                    .withSelection("${ContactsContract.RawContacts._ID} = ?", arrayOf(rawId.toString()))
                    .build()
            )
        }

        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            Log.w("SheetSync", "removeStaleVgkContacts: applyBatch failed", e)
            return 0
        }

        // Drop the removed numbers from the locally-synced set so they're
        // treated as "new" again if they're ever re-added to the group.
        if (phonesRemoved.isNotEmpty()) {
            val remaining = UserPrefs.getSyncedNumbers(context)
                .filterNot { phonesRemoved.contains(normalizePhone(it)) }
                .toSet()
            UserPrefs.setSyncedNumbers(context, remaining)
        }

        return rawIdsToDelete.size
    }

    /**
     * Returns the smallest positive integer NOT already in [numbersInUse].
     * This is what lets deleted VGK numbers become reusable: if 1
     * and 2 were deleted (so numbersInUse might be {3, 4}), this returns
     * 1 - the lowest gap - rather than continuing from the highest number
     * ever assigned.
     */
    private fun lowestFreeNumber(numbersInUse: Set<Int>): Int {
        var candidate = 1
        while (numbersInUse.contains(candidate)) {
            candidate++
        }
        return candidate
    }

    /**
     * Stamps last_synced_at with the current time - called after any sync
     * attempt that actually reached the server and ran (fetchAllContacts
     * succeeded), success or "nothing new" both count as a real check-in.
     * A failed/offline attempt does NOT stamp this, since nothing actually
     * reached the server that time.
     *
     * Folded into the same PATCH as a real update where possible rather
     * than firing as its own separate network call - see callers.
     *
     * Fire-and-forget: only used by the Supabase-side 30-day inactivity
     * job, never read back by this app, so a failed stamp here is not
     * worth retrying or surfacing to the user.
     */
    /**
     * Stamps last_synced_at = now() for this device's whatsapp row.
     * This is the ONLY thing this function does - no status, no
     * status_reason, nothing else - by design, so it can be verified
     * working in total isolation before anything else is layered back on.
     *
     * callback reports exactly what happened: true on a confirmed 2xx,
     * false with the real reason otherwise - so a caller (or a temporary
     * test button) can show the person what actually occurred instead of
     * it disappearing into Logcat only.
     */
    fun stampLastSyncedAt(context: Context, callback: ((Boolean, String) -> Unit)? = null) {
        runOnIoThread {
            val whatsapp = UserPrefs.getWhatsapp(context)
            if (whatsapp.isNullOrEmpty()) {
                callback?.invoke(false, "No whatsapp saved locally (UserPrefs.getWhatsapp is null/empty)")
                return@runOnIoThread
            }
            try {
                // Uses the record_sync_checkin RPC (see accompanying SQL)
                // rather than a plain PATCH, because this needs to
                // atomically do two things in one statement: always stamp
                // last_synced_at, AND flip status back to 'active' only if
                // the row was auto-flagged 'sync_timeout' by the daily cron
                // job - never if the person deliberately paused
                // ('paused_by_user'). A plain PATCH can't express "update
                // this column conditionally on that column's own current
                // value" safely without a read-then-write race.
                val androidId = readAndroidId(context)
                if (androidId.isBlank()) {
                    callback?.invoke(false, "Android ID unavailable")
                    return@runOnIoThread
                }
                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                json.put("p_android_id", androidId)
                val request = buildRequest("rpc/record_sync_checkin", "POST", json.toString())
                httpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code !in 200..299) {
                        val body = try { response.body?.string() } catch (e: Exception) { null }
                        Log.w("SheetSync", "stampLastSyncedAt failed with code $code body=$body")
                        callback?.invoke(false, "Server returned $code: ${body ?: "(no body)"}")
                    } else {
                        InactivityWarningWorker.reschedule(context)
                        callback?.invoke(true, "Updated last_synced_at for $whatsapp")
                    }
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "stampLastSyncedAt failed", e)
                callback?.invoke(false, "Exception: ${e.javaClass.simpleName}: ${e.message}")
            }
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

            if (UserPrefs.isBanned(context)) {
                return@withContext Triple(0, 0, "BANNED")
            }

            val numbersInUse = reconcileFromExistingContacts(context).toMutableSet()
            val newlySynced = HashSet<String>()

            try {
                val contacts = fetchAllContacts(context)
                if (contacts == null) {
                    return@withContext Triple(0, 1, "Failed to fetch contacts from server")
                }
                // Reached the server successfully - this counts as a real
                // check-in regardless of whether any contacts were new,
                // so the 7-day inactivity job never mistakes a quiet-but-
                // healthy sync for a silent/uninstalled one. Logged (not
                // surfaced to the user) on failure - a failed check-in
                // stamp shouldn't interrupt an otherwise-successful sync
                // with a visible error, but it must not vanish silently
                // either, given this exact call site went unnoticed and
                // broken for hours before being caught.
                stampLastSyncedAt(context) { success, message ->
                    if (!success) {
                        Log.w("SheetSync", "Sync check-in stamp failed during import: $message")
                    }
                }

                // Remove VGK contacts that have dropped out of the user's
                // group(s) (banned, or removed) since the last sync. Guard
                // against the dangerous case: an empty `contacts` result
                // that is a glitch rather than a real "zero group members"
                // state. fetchAllContacts() returns emptyList() both when
                // the user genuinely has no groups AND is meant to return
                // null on any real fetch failure - but to be extra safe,
                // re-check the user's own group membership before treating
                // an empty list as ground truth for deletion. If contacts
                // is non-empty, there's nothing to second-guess - it's
                // clearly a real, current server list.
                val safeToReconcileDeletes = if (contacts.isNotEmpty()) {
                    true
                } else {
                    // contacts is empty - only trust this enough to delete
                    // everyone if we can independently confirm the user
                    // really has zero groups right now. If that lookup
                    // fails or times out, skip deletion entirely this sync
                    // rather than risk wiping everyone on a glitch.
                    val myGroups = fetchMyGroups(context)
                    myGroups != null && myGroups.isEmpty()
                }
                if (safeToReconcileDeletes) {
                    val currentServerPhones = contacts.map { normalizePhone(it.first) }.toSet()
                    val removed = removeStaleVgkContacts(context, currentServerPhones)
                    if (removed > 0) {
                        Log.i("SheetSync", "Removed $removed stale VGK contact(s) no longer in user's group(s)")
                        logContactsRemoved(context, removed)
                    }
                }

                val alreadySynced = UserPrefs.getSyncedNumbers(context).map { normalizePhone(it) }.toSet()
                val toAdd = ArrayList<Pair<String, String>>()
                for ((phone, _, name) in contacts) {
                    if (phone.isEmpty() || name.isEmpty() || alreadySynced.contains(normalizePhone(phone))) {
                        continue
                    }
                    val nextNumber = lowestFreeNumber(numbersInUse)
                    numbersInUse.add(nextNumber)
                    toAdd.add(Pair("$name VGK$nextNumber", phone))
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

            val numbersInUse = reconcileFromExistingContacts(context).toMutableSet()
            val newlySynced = HashSet<String>()

            try {
                // One call replaces the old fetchMyGroups + fetchAllContacts +
                // stampLastSyncedAt trio - see syncCheckin's own comment for why.
                val result = syncCheckin(context)
                if (result == null) {
                    callback?.invoke(0, 1, "Failed to fetch contacts from server")
                    return@runOnIoThread
                }

                if (isBannedStatus(result.status)) {
                    // Server already returned an empty contacts list for a
                    // banned user, but bail out explicitly here too so the
                    // caller can show a dedicated "banned" screen instead of
                    // just reporting "no new numbers" like a quiet, healthy
                    // sync would.
                    UserPrefs.setBanned(context, true)
                    callback?.invoke(0, 0, "BANNED")
                    return@runOnIoThread
                }
                UserPrefs.setBanned(context, false)

                val contacts = result.contacts

                // No separate safety double-check needed anymore: the
                // server now returns group membership and the matching
                // contact list from the SAME query, atomically, so they
                // can no longer disagree the way two separate round-trips
                // could. An empty list here is trustworthy on its own.
                val currentServerPhones = contacts.map { normalizePhone(it.first) }.toSet()
                val removed = removeStaleVgkContacts(context, currentServerPhones)
                if (removed > 0) {
                    Log.i("SheetSync", "Removed $removed stale VGK contact(s) no longer in user's group(s)")
                    logContactsRemoved(context, removed)
                }

                val alreadySynced = UserPrefs.getSyncedNumbers(context).map { normalizePhone(it) }.toSet()
                val toAdd = ArrayList<Pair<String, String>>()
                for ((phone, _, name) in contacts) {
                    if (phone.isEmpty() || name.isEmpty() || alreadySynced.contains(normalizePhone(phone))) {
                        continue
                    }
                    val nextNumber = lowestFreeNumber(numbersInUse)
                    numbersInUse.add(nextNumber)
                    toAdd.add(Pair("$name VGK$nextNumber", phone))
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
            val ops = ArrayList(buildContactOps(name, phone, insertIndex = 0))
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Pair(true, null)
        } catch (e: Exception) {
            Log.w("SheetSync", "addSingleContactDetailed: failed to add $name", e)
            Pair(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
