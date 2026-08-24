package com.vgkontact.app

import android.content.ContentProviderOperation
import android.content.Context
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

    private const val SCRIPT_URL = "https://script.google.com/macros/s/AKfycbwP5xI8LTC7L3gBIbP-wvi4cqixawCc59SgIf6fGrpVT3iX5LcHi-KW9nZHsaIvwdq_/exec"

    interface SyncCallback {
        fun onSuccess(message: String)
        fun onError(error: String)
    }

    interface FetchCallback {
        fun onSuccess(contactsCount: Int)
        fun onError(error: String)
    }

    interface HistoryCallback {
        fun onSuccess(history: List<DayCount>)
        fun onError(error: String)
    }

    // Submission method called by OnboardingActivity
    fun submit(context: Context, whatsapp: String, referral: String, callback: (Boolean, String?) -> Unit) {
        registerUser(whatsapp, referral, object : SyncCallback {
            override fun onSuccess(message: String) {
                callback(true, message)
            }
            override fun onError(error: String) {
                callback(false, error)
            }
        })
    }

    // Method called by OnboardingActivity & SheetSync
    fun registerUser(whatsapp: String, referral: String, callback: SyncCallback) {
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
                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                    callback.onSuccess("Registration synced successfully")
                } else {
                    callback.onError("Server returned response code: $responseCode")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("SheetSync", "Error registering user", e)
                callback.onError(e.message ?: "Network request failed")
            }
        }
    }

    // Method called by MainMenuActivity
    fun importAllContactsFromSheet(context: Context, callback: (Boolean, String?) -> Unit) {
        fetchAndSyncContactsToPhone(context, object : FetchCallback {
            override fun onSuccess(contactsCount: Int) {
                callback(true, "Synced $contactsCount contacts successfully")
            }
            override fun onError(error: String) {
                callback(false, error)
            }
        })
    }

    // Fetch method called by MainMenuActivity & HistoryActivity
    fun fetchHistory(context: Context, callback: (List<DayCount>?, String?) -> Unit) {
        thread {
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

                    val jsonObject = JSONObject(response.toString())
                    if (jsonObject.optString("status") == "success") {
                        val contactsArray = jsonObject.optJSONArray("contacts") ?: JSONArray()
                        val dayCountMap = mutableMapOf<String, Int>()

                        for (i in 0 until contactsArray.length()) {
                            val key = "Today"
                            dayCountMap[key] = (dayCountMap[key] ?: 0) + 1
                        }

                        val historyList = dayCountMap.map { DayCount(it.key, it.value) }
                        callback(historyList, null)
                    } else {
                        callback(null, jsonObject.optString("message", "Failed to parse history"))
                    }
                } else {
                    callback(null, "Server error code: $responseCode")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("SheetSync", "Error fetching history", e)
                callback(null, e.message ?: "Failed to fetch history")
            }
        }
    }

    // Base method to import contacts directly to device contact book
    fun fetchAndSyncContactsToPhone(context: Context, callback: FetchCallback) {
        thread {
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

                    val jsonObject = JSONObject(response.toString())
                    if (jsonObject.optString("status") == "success") {
                        val contactsArray = jsonObject.optJSONArray("contacts") ?: JSONArray()
                        val addedCount = addContactsToDevice(context, contactsArray)
                        callback.onSuccess(addedCount)
                    } else {
                        callback.onError(jsonObject.optString("message", "Failed to parse contacts"))
                    }
                } else {
                    callback.onError("Server error code: $responseCode")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("SheetSync", "Error fetching contacts", e)
                callback.onError(e.message ?: "Failed to download contacts")
            }
        }
    }

    private fun addContactsToDevice(context: Context, contactsArray: JSONArray): Int {
        var count = 0
        for (i in 0 until contactsArray.length()) {
            val contactObj = contactsArray.getJSONObject(i)
            val name = contactObj.optString("name")
            val phone = contactObj.optString("phone")

            if (phone.isNotEmpty()) {
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

                try {
                    context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                    count++
                } catch (e: Exception) {
                    Log.e("SheetSync", "Failed to add contact: $name", e)
                }
            }
        }
        return count
    }
}
