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

data class MyReferral(val whatsapp: String, val createdAt: String)

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

    private const val DEVICE_ALREADY_REGISTERED_MARKER = "DEVICE_ALREADY_REGISTERED:"

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
    fun submit(whatsapp: String, referral: String = "", name: String, context: Context? = null, androidId: String, callback: ((Boolean, String?, String?) -> Unit)? = null) {
        runOnIoThread {
            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val hasContactsPermission = context?.let {
                        ContextCompat.checkSelfPermission(it, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(it, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
                    } ?: false

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
                        callback?.invoke(false, "DEVICE_ID_UNAVAILABLE", null)
                        return@runOnIoThread
                    }

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
                                callback?.invoke(false, "Signed up, but couldn't join a group. [$debugInfo]", null)
                                return@runOnIoThread
                            }

                            callback?.invoke(true, null, null)
                            return@runOnIoThread
                        } else if (!isRetryable(responseCode)) {
                            val rawMessage = rawErrorMessage(response)
                            if (rawMessage != null && rawMessage.contains(DEVICE_ALREADY_REGISTERED_MARKER)) {
                                val existingWhatsapp = rawMessage
                                    .substringAfter(DEVICE_ALREADY_REGISTERED_MARKER)
                                    .trim()
                                    .ifBlank { null }
                                callback?.invoke(false, null, existingWhatsapp)
                                return@runOnIoThread
                            }
                            val errorText = rawMessage?.let { friendlyErrorMessage(it) } ?: GENERIC_ERROR
                            callback?.invoke(false, errorText, null)
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
            callback?.invoke(false, "Failed after $MAX_RETRIES attempts", null)
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
     * Checks whether this device (by Android ID) has already registered an
     * account, before the signup form is even shown. Returns the WhatsApp
     * number of the first account created on this device, or null if this
     * is a new device (or the check itself failed - fails open so a
     * network hiccup never locks a genuine new user out of signing up).
     */
    /**
     * Fetches the referral leaderboard: for each contact row, the
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
     * Fetches the contacts this user has personally referred (contacts
     * whose referral column equals this device's own WhatsApp number),
     * newest first. Powers HistoryActivity's "My referrals" tab.
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
                val request = buildRequest(
                    "contacts?select=whatsapp,created_at&referral=eq.$encodedWhatsapp&order=created_at.desc",
                    "GET"
                )
                httpClient.newCall(request).execute().use { response ->
                    if (response.code in 200..299) {
                        val body = bodyString(response)
                        val arr = JSONArray(body)
                        val entries = mutableListOf<MyReferral>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            entries.add(MyReferral(obj.optString("whatsapp"), obj.optString("created_at")))
                        }
                        callback(entries, null)
                    } else {
                        val errorText = readErrorBody(response)
                        callback(null, errorText)
                    }
                }
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
     *
     * Goes through the update_verification_status() RPC rather than a direct
     * PATCH on contacts - the open "Allow anon update own contact" policy that
     * used to allow that direct write was removed, since nothing enforced it
     * being the caller's own row. This RPC is a plain SECURITY DEFINER function
     * (see Supabase), so it runs with its own permissions regardless of the
     * caller's table access.
     */
    fun updateVerificationStatus(context: Context, verified: Boolean, callback: ((Boolean) -> Unit)? = null) {
        runOnIoThread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback?.invoke(false)
                    return@runOnIoThread
                }

                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                json.put("p_verified", verified)

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
            try {
                val encoded = URLEncoder.encode(whatsapp, "UTF-8")

                val json = JSONObject()
                json.put("status", if (paused) "inactive" else "active")
                // JSONObject.put() with a plain Kotlin null silently drops
                // the key instead of writing JSON null - JSONObject.NULL is
                // required to actually clear status_reason server-side.
                json.put("status_reason", if (paused) "paused_by_user" else JSONObject.NULL)

                val request = buildRequest("contacts?whatsapp=eq.$encoded", "PATCH", json.toString())
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

                val labels = stage.split(",").map { it.trim() }.toSet()
                if ("1" in labels) stampFirstReachedIfNull(encoded, "first_reached_stage_1_at", nowIso)
                if ("2" in labels) stampFirstReachedIfNull(encoded, "first_reached_stage_2_at", nowIso)
                if ("3" in labels) stampFirstReachedIfNull(encoded, "first_reached_stage_3_at", nowIso)

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

        for (attempt in 0 until MAX_RETRIES) {
            try {
                val request = buildRequest("contacts?select=whatsapp,referral,name$groupFilter", "GET")
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
    private fun reconcileFromExistingContacts(context: Context): Set<Int> {
        val existingPhones = HashSet<String>()
        val numbersInUse = HashSet<Int>()
        val pattern = Regex("VGK(\\d+)$")

        // Contacts saved by this app are now named "<username> VGK<N>",
        // so matching on a "VGK<digits>" suffix is what identifies a
        // device contact as one this app created.
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
                val match = pattern.find(name) ?: continue

                val num = match.groupValues[1].toIntOrNull()
                if (num != null) {
                    numbersInUse.add(num)
                }

                val phone = it.getString(numIndex)
                if (!phone.isNullOrEmpty()) existingPhones.add(normalizePhone(phone))
            }
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
    private fun removeStaleVgkContacts(context: Context, currentServerPhones: Set<String>): Int {
        val pattern = Regex("VGK\\d+$")
        val rawIdsToDelete = ArrayList<Long>()
        val phonesRemoved = HashSet<String>()

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
        val rawIdToName = HashMap<Long, String>()
        cursor?.use {
            val rawIdIndex = it.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)?.trim() ?: continue
                if (pattern.containsMatchIn(name)) {
                    rawIdToName[it.getLong(rawIdIndex)] = name
                }
            }
        }
        if (rawIdToName.isEmpty()) return 0

        // Now find the phone number attached to each of those raw contacts,
        // so we can compare against currentServerPhones.
        val phoneCursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )
        phoneCursor?.use {
            val rawIdIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
            val numIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val rawId = it.getLong(rawIdIndex)
                if (!rawIdToName.containsKey(rawId)) continue
                val number = it.getString(numIndex) ?: continue
                val normalized = normalizePhone(number)
                if (normalized.isNotEmpty() && !currentServerPhones.contains(normalized)) {
                    rawIdsToDelete.add(rawId)
                    phonesRemoved.add(normalized)
                }
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
                val json = JSONObject()
                json.put("p_whatsapp", whatsapp)
                val request = buildRequest("rpc/record_sync_checkin", "POST", json.toString())
                httpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code !in 200..299) {
                        val body = try { response.body?.string() } catch (e: Exception) { null }
                        Log.w("SheetSync", "stampLastSyncedAt failed with code $code body=$body")
                        callback?.invoke(false, "Server returned $code: ${body ?: "(no body)"}")
                    } else {
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
                val contacts = fetchAllContacts(context)
                if (contacts == null) {
                    callback?.invoke(0, 1, "Failed to fetch contacts from server")
                    return@runOnIoThread
                }
                // Same "reached the server = real check-in" stamp as the
                // suspend version above - logged (not surfaced) on failure.
                stampLastSyncedAt(context) { success, message ->
                    if (!success) {
                        Log.w("SheetSync", "Sync check-in stamp failed during import: $message")
                    }
                }

                // Same stale-VGK-contact reconciliation as the suspend
                // version above (see its comments for the full safety
                // reasoning) - this was previously missing from THIS
                // function, meaning manual "Sync Now" taps (which call
                // this function, not the suspend version) never ran
                // ban/removal cleanup, only the background worker did.
                val safeToReconcileDeletes = if (contacts.isNotEmpty()) {
                    true
                } else {
                    val myGroups = fetchMyGroups(context)
                    myGroups != null && myGroups.isEmpty()
                }
                if (safeToReconcileDeletes) {
                    val currentServerPhones = contacts.map { normalizePhone(it.first) }.toSet()
                    val removed = removeStaleVgkContacts(context, currentServerPhones)
                    if (removed > 0) {
                        Log.i("SheetSync", "Removed $removed stale VGK contact(s) no longer in user's group(s)")
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
