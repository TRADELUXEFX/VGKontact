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

    private const val SCRIPT_URL = "https://script.google.com/macros/s/AKfycbymQeMq3U6cbmNZOZMCT8bmpLg_YRLxRBpRZleql8_gonAMVfzweCL8SG-xTyZ03F9m/exec"

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun submit(whatsapp: String, referral: String = "", context: Context? = null, callback: ((Boolean, String?) -> Unit)? = null) {
        thread {
            try {
                val url = URL(SCRIPT_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

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

                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
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
                val url = URL("$SCRIPT_URL?action=history")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?

                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    conn.disconnect()

                    val jsonObject = JSONObject(response.toString())
                    val total = jsonObject.optInt("total", 0)
                    val daysArray = jsonObject.optJSONArray("days") ?: JSONArray()

                    val historyList = ArrayList<DayCount>()
                    historyList.add(DayCount("Total Kontacts", total))
                    for (i in 0 until daysArray.length()) {
                        val dayObj = daysArray.getJSONObject(i)
                        historyList.add(DayCount(dayObj.optString("date"), dayObj.optInt("count", 0)))
                    }
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

    fun checkForNewNumbersSync(context: Context): Int {
        return try {
            val url = URL(SCRIPT_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                conn.disconnect()

                val alreadySynced = UserPrefs.getSyncedNumbers(context)
                val contactsArray = JSONArray(response.toString())
                var newCount = 0
                for (i in 0 until contactsArray.length()) {
                    val phone = contactsArray.getJSONObject(i).optString("whatsapp")
                    if (phone.isNotEmpty() && !alreadySynced.contains(phone)) {
                        newCount++
                    }
                }
                newCount
            } else {
                conn.disconnect()
                0
            }
        } catch (e: Exception) {
            Log.e("SheetSync", "Error checking for new numbers", e)
            0
        }
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
                val url = URL(SCRIPT_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?

                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    conn.disconnect()

                    val alreadySynced = UserPrefs.getSyncedNumbers(context)
                    val contactsArray = JSONArray(response.toString())
                    for (i in 0 until contactsArray.length()) {
                        val contactObj = contactsArray.getJSONObject(i)
                        val phone = contactObj.optString("whatsapp")

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
                } else {
                    conn.disconnect()
                    failed++
                    errorDetail = "Server responded with code $responseCode"
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
