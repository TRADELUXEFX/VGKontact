package com.vgkontact.app

import android.content.ContentProviderOperation
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class DayCount(val date: String, val count: Int)

object SheetSync {

    private const val SUPABASE_URL = "https://ixniesqmbdemeaskkhvy.supabase.co"
    private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Iml4bmllc3FtYmRlbWVhc2traHZ5Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODc2NjM0MjksImV4cCI6MjEwMzIzOTQyOX0.I9mWqP4JPjlYNHWMs1vR1bcVB9-XuXzO73ai2IZUEwQ"

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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

    fun submit(whatsapp: String, referral: String = "", context: Context? = null, callback: ((Boolean, String?) -> Unit)? = null) {
        thread {
            try {
                val conn = openConnection("contacts", "POST")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true

                val jsonParam = JSONObject().apply {
                    put("whatsapp", whatsapp)
                    put("referral", referral)
                }

                OutputStreamWriter(conn.outputStream).use { os ->
                    os.write(jsonParam.toString())
                    os.flush()
                }

                val responseCode = conn.responseCode
                conn.disconnect()

                if (responseCode in 200..299) {
                    callback?.invoke(true, "Successfully registered")
                } else {
                    callback?.invoke(false, "Server error code: $responseCode")
                }
            } catch (e: Exception) {
                Log.e("SheetSync", "Error submitting user", e)
                callback?.invoke(false, e.message ?: "Failed to submit")
            }
        }
    }

    fun fetchHistory(context: Context? = null, callback: ((List<DayCount>?, String?) -> Unit)? = null) {
        thread {
            try {
                val conn = openConnection("rpc/contact_history", "POST")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { os ->
                    os.write("{}")
                    os.flush()
                }

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

                    val daysArray = JSONArray(response.toString())
                    var total = 0
                    val historyList = ArrayList<DayCount>()
                    val dailyEntries = ArrayList<DayCount>()
                    for (i in 0 until daysArray.length()) {
                        val dayObj = daysArray.getJSONObject(i)
                        val count = dayObj.optInt("count", 0)
                        total += count
                        dailyEntries.add(DayCount(dayObj.optString("day"), count))
                    }
                    historyList.add(DayCount("Total Kontacts", total))
                    historyList.addAll(dailyEntries)
                    callback?.invoke(historyList, null)
                } else {
                    conn.disconnect()
                    callback?.invoke(null, "Server response error code: $responseCode")
                }
            } catch (e: Exception) {
                Log.e("SheetSync", "Error in fetchHistory", e)
                callback?.invoke(null, e.message ?: "Error connecting")
            }
        }
    }

    private fun fetchAllContacts(): List<Pair<String, String>>? {
        return try {
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
                result
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.e("SheetSync", "Error fetching contacts", e)
            null
        }
    }

    fun checkForNewNumbersSync(context: Context): Int {
        val contacts = fetchAllContacts() ?: return 0
        val alreadySynced = UserPrefs.getSyncedNumbers(context)
        var newCount = 0
        for ((phone, _) in contacts) {
            if (phone.isNotEmpty() && !alreadySynced.contains(phone)) {
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

                val alreadySynced = UserPrefs.getSyncedNumbers(context)
                for ((phone, _) in contacts) {
                    if (phone.isEmpty() || alreadySynced.contains(phone)) {
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
        if (phone.isEmpty()) return Pair(false, "Empty phone number")
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
            Log.e("SheetSync", "Failed inserting contact: $name", e)
            Pair(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
