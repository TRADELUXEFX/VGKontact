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

data class ImportStats(val totalInDatabase: Int, val syncedToPhone: Int, val availableToImport: Int)

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
                    conn.setRequestProperty("Prefer", "return=minimal")
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
                        conn.disconnect()
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
            val contacts = fetchAllContacts()
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
            callback(ImportStats(totalInDatabase, syncedToPhone, availableToImport))
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

    /** Every phone number currently saved on the device, for stats cross-checking. */
    private fun getDevicePhoneNumbers(context: Context): Set<String> {
        val numbers = HashSet<String>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )
        cursor?.use {
            val numIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val num = it.getString(numIndex)
                if (!num.isNullOrEmpty()) numbers.add(num)
            }
        }
        return numbers
    }

    private fun fetchAllContacts(): List<Pair<String, String>>? {
        for (attempt in 0 until MAX_RETRIES) {
            try {
                val conn = openConnection("contacts?select=whatsapp,referral", "GET")
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
        val contacts = fetchAllContacts() ?: return 0
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
                val contacts = fetchAllContacts()
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
