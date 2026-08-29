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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val joinedGroupIds: List<Long> = emptyList(),
    // "Contact limit" = the sum of each joined group's real cap (its
    // homeCount from get_all_groups_summary, e.g. "Group 1: 5 kontacts")
    // across every group this user belongs to (home group + redeemed
    // extra_groups). This is NOT a hardcoded number anywhere in the app -
    // it's always whatever is actually seeded per-group on Supabase right
    // now, summed live. If group caps change server-side, this number
    // changes automatically with them.
    //
    // e.g. joined Group 1 (5) + Group 4 (5) + Group 7 (5) -> contactLimit = 15
    //
    // 0 means "no groups joined yet". -1 means "couldn't be determined"
    // (offline, or the groups-summary call failed) - same "unknown"
    // convention as joinedGroupCount, and the UI should treat it the same
    // way (show last-known value rather than 0).
    val contactLimit: Long = -1L
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

                    // At signup time, permission setup hasn't happened yet (it's the
                    // very next screen), so this will always be false here. That's
                    // correct: nobody should be marked VERIFIED before they've
                    // actually granted contacts access. PermissionSetupActivity
                    // flips this to VERIFIED afterward via updateVerificationStatus().
                    val hasContactsPermission = context?.let {
                        ContextCompat.checkSelfPermission(it, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(it, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
                    } ?: false

                    val json = JSONObject()
                    json.put("whatsapp", whatsapp)
                    json.put("referral", referral)
< truncated lines 181-887 >

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
            Triple(submitted, failed, errorDetail)
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
