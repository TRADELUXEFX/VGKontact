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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class DayCount(val date: String, val count: Int)

data class ReferralEntry(val whatsapp: String, val referralCount: Int)

data class ImportStats(
    val totalInDatabase: Int,
    val syncedToPhone: Int,
    val availableToImport: Int,
    // How many groups (home group + redeemed extra_groups) the current user
    // belongs to. Surfaced for the dashboard's stats tile - this reflects
    // real data, not a placeholder. -1 means "couldn't be determined" (e.g.
    // offline), which the UI treats the same as "unknown".
    val joinedGroupCount: Int = -1,
    // The actual group IDs from joinedGroupCount above, sorted ascending,
    // so screens can show "Group 3" instead of just "1 Group". Empty when
    // joinedGroupCount is 0 or -1 (unknown).
    val joinedGroupIds: List<Long> = emptyList()
)

// One row of the "browse all groups" list. homeCount and extraCount are
// kept separate (not summed) per product decision: homeCount = contacts
// whose group_id is this group; extraCount = contacts who unlocked this
// group via a redeemed key (present in their extra_groups array).
data class GroupSummary(
    val groupId: Long,
    val homeCount: Long,
    val extraCount: Long
)

object SheetSync {

    private val SUPABASE_URL = BuildConfig.SUPABASE_URL
    private val SUPABASE_ANON_KEY = BuildConfig.SUPABASE_ANON_KEY

    private const val MAX_RETRIES = 3
    private const val BASE_DELAY_MS = 1000L

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun openConnection(path: String, method: String): HttpURLConnection {
        val url = URL("$SUPABASE_URL/rest/v1/$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
        conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        return conn
    }

    private fun isRetryable(responseCode: Int?): Boolean {
        return responseCode == null || responseCode >= 500 || responseCode == 429
    }

    private fun sleepBeforeRetry(attempt: Int) {
        try {
            Thread.sleep(BASE_DELAY_MS * (attempt + 1))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private const val GENERIC_ERROR = "Something went wrong. Please try again."

    private fun readErrorBody(conn: HttpURLConnection): String {
        val raw = try {
            val stream = conn.errorStream ?: return GENERIC_ERROR
            val reader = BufferedReader(InputStreamReader(stream))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line)
            }
            reader.close()
            val body = response.toString()
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

    fun submit(whatsapp: String, referral: String = "", context: Context? = null, callback: ((Boolean, String?) -> Unit)? = null) {
        thread {
            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val conn = openConnection("contacts", "POST")
                    // return=representation so we get the inserted row back (need its id
                    // to assign a group right after).
                    conn.setRequestProperty("Prefer", "return=representation")
                    conn.doOutput = true

                    val json = JSONObject()
                    json.put("whatsapp", whatsapp)
                    json.put("referral", referral)

                    val writer = OutputStreamWriter(conn.outputStream)
                    writer.write(json.toString())
                    writer.flush()
                    writer.close()

                    val responseCode = conn.responseCode

                    if (responseCode in 200..299) {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream))
                        val response = StringBuilder()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            response.append(line)
                        }
                        reader.close()
                        conn.disconnect()

                        // Pull out the new row's id so we can assign it to a group.
                        val contactId = try {
                            val arr = JSONArray(response.toString())
                            if (arr.length() > 0) arr.getJSONObject(0).optLong("id", -1L) else -1L
                        } catch (e: Exception) {
                            Log.e("SheetSync", "submit: failed to parse insert response, group assignment will be skipped. Raw response: $response", e)
                            -1L
                        }

                        var groupAssigned = false
                        if (contactId > 0) {
                            groupAssigned = assignGroupToContact(contactId)
                        } else {
                            Log.e("SheetSync", "submit: contactId invalid ($contactId) after insert, group assignment skipped. Raw response: $response")
                        }

                        if (!groupAssigned) {
                            val debugInfo = "id=$contactId resp=${response.toString().take(150)}"
                            callback?.invoke(false, "Signed up, but couldn't join a group. [$debugInfo]")
                            return@thread
                        }

                        callback?.invoke(true, null)
                        return@thread
                    } else if (!isRetryable(responseCode)) {
                        val errorText = readErrorBody(conn)
                        conn.disconnect()
                        callback?.invoke(false, errorText)
                        return@thread
                    }
                    conn.disconnect()
                    Log.w("SheetSync", "submit attempt ${attempt + 1} failed with code $responseCode, retrying...")
                } catch (e: Exception) {
                    Log.w("SheetSync", "submit attempt ${attempt + 1} threw exception, retrying...", e)
                }

                if (attempt < MAX_RETRIES - 1) {
                    sleepBeforeRetry(attempt)
                }
            }
            callback?.invoke(false, "Failed after $MAX_RETRIES attempts")
        }
    }

    /**
     * Calls the assign_group_to_contact(p_contact_id) Postgres function,
     * which does "pick a group" and "save it on the contact" as ONE atomic
     * database transaction — either both happen or neither does, so a
     * contact can never be left with a group reserved-but-not-saved.
     * Retries on transient failures, matching the pattern used elsewhere
     * in this file. Runs synchronously on the calling thread - callers
     * already run this inside thread { } from submit().
     */
    private fun assignGroupToContact(contactId: Long): Boolean {
        // Brief pause before the first attempt: the contact row we just
        // inserted may not be visible yet to this RPC call on some
        // connections, even though the insert's own response already came
        // back successful. A short wait avoids treating that normal,
        // very-short replication delay as a real failure.
        try {
            Thread.sleep(400L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        for (attempt in 0 until MAX_RETRIES) {
            try {
                val conn = openConnection("rpc/assign_group_to_contact", "POST")
                conn.doOutput = true
                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(JSONObject().put("p_contact_id", contactId).toString())
                writer.flush()
                writer.close()

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    conn.disconnect()
                    return true
                }
                Log.e("SheetSync", "assign_group_to_contact attempt ${attempt + 1} failed with code $responseCode for contact $contactId")
                conn.disconnect()
                // Treat 400 as retryable here specifically: our function
                // returns 400 when it can't see the contact row yet, which
                // is a timing issue on the first attempt, not a real
                // client error - it should resolve itself within a retry
                // or two as the row becomes visible.
                if (!isRetryable(responseCode) && responseCode != 400) return false
            } catch (e: Exception) {
                Log.e("SheetSync", "assign_group_to_contact attempt ${attempt + 1} threw exception for contact $contactId", e)
            }
            if (attempt < MAX_RETRIES - 1) {
                sleepBeforeRetry(attempt)
            }
        }
        Log.e("SheetSync", "assignGroupToContact: giving up after $MAX_RETRIES attempts, contact $contactId has no group")
        return false
    }

    fun fetchHistory(context: Context? = null, callback: ((List<DayCount>?, String?) -> Unit)? = null) {
        thread {
            try {
                val conn = openConnection("contacts?select=created_at", "GET")
                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    conn.disconnect()

                    val arr = JSONArray(response.toString())
                    callback?.invoke(listOf(DayCount("all", arr.length())), null)
                } else {
                    val errorText = readErrorBody(conn)
                    conn.disconnect()
                    callback?.invoke(null, errorText)
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
        thread {
            try {
                val conn = openConnection("contacts?select=referral&referral=not.is.null", "GET")
                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    conn.disconnect()

                    val arr = JSONArray(response.toString())
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
                    val errorText = readErrorBody(conn)
                    conn.disconnect()
                    callback?.invoke(null, errorText)
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

    fun fetchPlan(context: Context, callback: (String?) -> Unit) {
        thread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback(null)
                    return@thread
                }
                val encoded = java.net.URLEncoder.encode(whatsapp, "UTF-8")
                val conn = openConnection("contacts?whatsapp=eq.$encoded&select=plan", "GET")
                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    conn.disconnect()

                    val arr = JSONArray(response.toString())
                    if (arr.length() > 0) {
                        val plan = arr.getJSONObject(0).optString("plan", "FREE")
                        callback(if (plan.isEmpty()) "FREE" else plan)
                    } else {
                        callback(null)
                    }
                } else {
                    conn.disconnect()
                    callback(null)
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchPlan failed", e)
                callback(null)
            }
        }
    }

    /**
     * Returns the 3-way breakdown shown on the main dashboard:
     * - totalInDatabase: every whatsapp row in Supabase (the full pool for everyone)
     * - syncedToPhone: how many of those this specific user already has saved locally
     * - availableToImport: totalInDatabase - syncedToPhone
     *
     * NOTE: this does NOT apply any plan limit yet. Once a FREE-plan cap is decided,
     * availableToImport should become: min(planLimit, totalInDatabase) - syncedToPhone
     * (see TODO in MainMenuActivity where this is consumed).
     */
    fun fetchImportStats(context: Context, callback: (ImportStats?) -> Unit) {
        thread {
            val contacts = fetchAllContacts(context)
            if (contacts == null) {
                callback(null)
                return@thread
            }
            val totalInDatabase = contacts.count { it.first.isNotEmpty() }

            // Cross-check against numbers actually saved on the device (not just our
            // own sync history), so contacts added outside the app - or lost via an
            // app reinstall/data clear - still count as already-synced.
            val knownSynced = UserPrefs.getSyncedNumbers(context).map { normalizePhone(it) }.toSet()
            val onDevice = if (checkContactsPermission(context))
                getDevicePhoneNumbers(context).map { normalizePhone(it) }.toSet()
            else emptySet()
            val syncedToPhone = contacts.count {
                it.first.isNotEmpty() &&
                    normalizePhone(it.first).let { n -> knownSynced.contains(n) || onDevice.contains(n) }
            }

            val availableToImport = (totalInDatabase - syncedToPhone).coerceAtLeast(0)

            // fetchAllContacts() above already resolved this user's own group
            // internally to build its query filter; fetchMyGroups() here makes
            // a second small network call rather than threading that internal
            // value through fetchAllContacts's signature (which has other
            // callers). Keeps this change local to this function.
            val myGroups = fetchMyGroups(context)
            val joinedGroupCount = myGroups?.size ?: -1
            val joinedGroupIds = myGroups?.sorted() ?: emptyList()
            callback(ImportStats(totalInDatabase, syncedToPhone, availableToImport, joinedGroupCount, joinedGroupIds))
        }
    }

    /**
     * Fetches every group that exists (not just the current user's own),
     * with home-group and extra-key contact counts kept separate, via the
     * get_all_groups_summary() Postgres function. Read-only aggregate data;
     * safe under the anon key (see grant in the function's own definition).
     * Returns null on any network/parse failure - callers should treat
     * that the same as "couldn't be determined" (same convention as the
     * rest of this file, e.g. fetchMyGroups()).
     */
    fun fetchAllGroupsSummary(callback: (List<GroupSummary>?) -> Unit) {
        thread {
            try {
                val conn = openConnection("rpc/get_all_groups_summary", "POST")
                conn.doOutput = true
                val writer = OutputStreamWriter(conn.outputStream)
                writer.write("{}")
                writer.flush()
                writer.close()

                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    callback(null)
                    return@thread
                }

                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                conn.disconnect()

                val arr = JSONArray(response.toString())
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
                callback(result.sortedBy { it.groupId })
            } catch (e: Exception) {
                Log.e("SheetSync", "fetchAllGroupsSummary failed", e)
                callback(null)
            }
        }
    }

    /**
     * Normalizes a Nigerian phone number for comparison purposes only (not for
     * storage/display). Strips spaces/dashes and collapses +234 / 234 / 0 prefixes
     * down to the bare 10-digit subscriber number, e.g.:
     *   "+2348012345678" -> "8012345678"
     *   "08012345678"     -> "8012345678"
     *   "2348012345678"   -> "8012345678"
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
     * "VG KONTACT <number>"). We deliberately do NOT scan the user's whole address
     * book here - a stranger's pre-existing contact could coincidentally share a
     * number with a row in our database, which would wrongly count as "already
     * synced" for a brand new user who never synced anything. Scoping to our own
     * naming pattern avoids that false match.
     */
    private fun getDevicePhoneNumbers(context: Context): Set<String> {
        val numbers = HashSet<String>()
        val pattern = Regex("^VG KONTACT (\\d+)$")

        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null, null, null
        )
        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                if (!pattern.matches(name.trim())) continue

                val contactId = it.getString(idIndex)
                val phoneCursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null
                )
                phoneCursor?.use { pc ->
                    val numIndex = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (pc.moveToNext()) {
                        val num = pc.getString(numIndex)
                        if (!num.isNullOrEmpty()) numbers.add(num)
                    }
                }
            }
        }
        return numbers
    }

    /**
     * Looks up the current user's own group_id + extra_groups (unlocked via keys)
     * from Supabase, using their saved WhatsApp number. Returns null if the user
     * can't be found or has no groups yet (shouldn't normally happen post-signup).
     */
    private fun fetchMyGroups(context: Context): List<Long>? {
        val whatsapp = UserPrefs.getWhatsapp(context) ?: return null
        try {
            val encoded = java.net.URLEncoder.encode(whatsapp, "UTF-8")
            val conn = openConnection("contacts?whatsapp=eq.$encoded&select=group_id,extra_groups", "GET")
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line)
            }
            reader.close()
            conn.disconnect()

            val arr = JSONArray(response.toString())
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
        } catch (e: Exception) {
            Log.e("SheetSync", "fetchMyGroups failed", e)
            return null
        }
    }

    /**
     * Redeems a key code for the current user. On success, the key's
     * groups_unlock get merged into the user's extra_groups server-side
     * (see redeem_key() Postgres function) and this returns the list of
     * newly unlocked group ids. Returns null on any failure (invalid,
     * expired, already used, or network error) - caller shows a generic
     * "invalid or expired key" message in that case.
     */
    fun redeemKey(context: Context, code: String, callback: (List<Long>?) -> Unit) {
        thread {
            try {
                val whatsapp = UserPrefs.getWhatsapp(context)
                if (whatsapp.isNullOrEmpty()) {
                    callback(null)
                    return@thread
                }

                // Need this contact's row id for the redeem_key() RPC call.
                val encoded = java.net.URLEncoder.encode(whatsapp, "UTF-8")
                val idConn = openConnection("contacts?whatsapp=eq.$encoded&select=id", "GET")
                if (idConn.responseCode !in 200..299) {
                    idConn.disconnect()
                    callback(null)
                    return@thread
                }
                val idReader = BufferedReader(InputStreamReader(idConn.inputStream))
                val idResponse = StringBuilder()
                var idLine: String?
                while (idReader.readLine().also { idLine = it } != null) {
                    idResponse.append(idLine)
                }
                idReader.close()
                idConn.disconnect()

                val idArr = JSONArray(idResponse.toString())
                if (idArr.length() == 0) {
                    callback(null)
                    return@thread
                }
                val contactId = idArr.getJSONObject(0).optLong("id", -1L)
                if (contactId <= 0) {
                    callback(null)
                    return@thread
                }

                // Call redeem_key(p_code, p_contact_id) RPC
                val rpcConn = openConnection("rpc/redeem_key", "POST")
                rpcConn.doOutput = true
                val body = JSONObject()
                body.put("p_code", code)
                body.put("p_contact_id", contactId)
                val writer = OutputStreamWriter(rpcConn.outputStream)
                writer.write(body.toString())
                writer.flush()
                writer.close()

                if (rpcConn.responseCode !in 200..299) {
                    rpcConn.disconnect()
                    callback(null)
                    return@thread
                }

                val reader = BufferedReader(InputStreamReader(rpcConn.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                rpcConn.disconnect()

                // redeem_key() returns an int8[] as JSON array, or null if invalid/expired/used
                val trimmed = response.toString().trim()
                if (trimmed == "null" || trimmed.isEmpty()) {
                    callback(null)
                    return@thread
                }
                val arr = JSONArray(trimmed)
                val unlocked = ArrayList<Long>()
                for (i in 0 until arr.length()) {
                    unlocked.add(arr.getLong(i))
                }
                callback(unlocked)
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
                // No group assigned yet - nothing to sync rather than falling back
                // to "everyone", which would defeat the whole point of grouping.
                return emptyList()
            }
            "&group_id=in.(${groups.joinToString(",")})"
        } else {
            ""
        }

        for (attempt in 0 until MAX_RETRIES) {
            try {
                val conn = openConnection("contacts?select=whatsapp,referral$groupFilter", "GET")
                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    conn.disconnect()

                    val arr = JSONArray(response.toString())
                    val result = ArrayList<Pair<String, String>>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        result.add(Pair(obj.optString("whatsapp"), obj.optString("referral")))
                    }
                    return result
                } else {
                    conn.disconnect()
                    if (!isRetryable(responseCode)) {
                        return null
                    }
                    Log.w("SheetSync", "fetchAllContacts attempt ${attempt + 1} failed with code $responseCode, retrying...")
                }
            } catch (e: Exception) {
                Log.w("SheetSync", "fetchAllContacts attempt ${attempt + 1} threw exception, retrying...", e)
            }

            if (attempt < MAX_RETRIES - 1) {
                sleepBeforeRetry(attempt)
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

    private fun reconcileFromExistingContacts(context: Context) {
        var maxFound = UserPrefs.getContactCounter(context)
        val existingPhones = HashSet<String>()
        val pattern = Regex("^VG KONTACT (\\d+)$")

        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null, null, null
        )
        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                if (!pattern.matches(name.trim())) continue

                val num = pattern.find(name.trim())?.groupValues?.get(1)?.toIntOrNull()
                if (num != null && num > maxFound) {
                    maxFound = num
                }

                val contactId = it.getString(idIndex)
                val phoneCursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null
                )
                phoneCursor?.use { pc ->
                    val numIndex = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (pc.moveToNext()) {
                        val phone = pc.getString(numIndex)
                        if (!phone.isNullOrEmpty()) existingPhones.add(phone)
                    }
                }
            }
        }

        if (maxFound > UserPrefs.getContactCounter(context)) {
            UserPrefs.setContactCounter(context, maxFound)
        }
        if (existingPhones.isNotEmpty()) {
            UserPrefs.addSyncedNumbers(context, existingPhones)
        }
    }

    fun importAllContactsFromSheet(context: Context, callback: ((Int, Int, String?) -> Unit)? = null) {
        thread {
            var submitted = 0
            var failed = 0
            var errorDetail: String? = null

            if (!isOnline(context)) {
                callback?.invoke(0, 0, "NO_INTERNET")
                return@thread
            }

            reconcileFromExistingContacts(context)
            var contactCount = UserPrefs.getContactCounter(context)
            val newlySynced = HashSet<String>()

            try {
                val contacts = fetchAllContacts(context)
                if (contacts == null) {
                    callback?.invoke(0, 1, "Failed to fetch contacts from server")
                    return@thread
                }

                val alreadySynced = UserPrefs.getSyncedNumbers(context).map { normalizePhone(it) }.toSet()
                for ((phone, _) in contacts) {
                    if (phone.isEmpty() || alreadySynced.contains(normalizePhone(phone))) {
                        continue
                    }

                    val candidateName = "VG KONTACT ${contactCount + 1}"
                    val (ok, err) = addSingleContactDetailed(context, candidateName, phone)
                    if (ok) {
                        contactCount++
                        submitted++
                        newlySynced.add(phone)
                    } else {
                        failed++
                        if (errorDetail == null) errorDetail = err
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

    private fun addSingleContactDetailed(context: Context, name: String, phone: String): Pair<Boolean, String?> {
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
