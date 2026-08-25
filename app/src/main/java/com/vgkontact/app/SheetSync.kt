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

    private const val SCRIPT_URL = "https://script.google.com/macros/s/AKfycbymQeMq3U6cbmNZOZMCT8bmpLg_YRLxRBpRZleql8_gonAMVfzweCL8SG-xTyZ03F9m/exec"

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
                // Code.gs's getHistory() (triggered by ?action=history) returns
                // { total: <number>, days: [ { date, count }, ... ] } - NOT the
                // { status, contacts } shape this used to assume.
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

                    // HistoryActivity expects list[0] to be the running total,
                    // followed by one entry per day.
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

    fun importAllContactsFromSheet(context: Context, callback: ((Int, Int) -> Unit)? = null) {
        thread {
            var submitted = 0
            var failed = 0
            var contactCount = 0
            try {
                // Code.gs's getPreview() (the default GET) returns a plain JSON array:
                // [ { whatsapp, referral, timestamp }, ... ] - not { status, contacts }.
                // No limit param -> server returns every row.
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

                    val contactsArray = JSONArray(response.toString())
                    for (i in 0 until contactsArray.length()) {
                        val contactObj = contactsArray.getJSONObject(i)
                        val phone = contactObj.optString("whatsapp")

                        contactCount++
                        val contactName = "VG KONTACT $contactCount"

                        if (addSingleContact(context, contactName, phone)) {
                            submitted++
                        } else {
                            failed++
                        }
                    }
                } else {
                    conn.disconnect()
                    failed++
                }
            } catch (e: Exception) {
                Log.e("SheetSync", "Error importing contacts", e)
                failed++
            }
            callback?.invoke(submitted, failed)
        }
    }

    private fun addSingleContact(context: Context, name: String, phone: String): Boolean {
        if (phone.isEmpty()) return false
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
            true
        } catch (e: Exception) {
            Log.e("SheetSync", "Failed inserting contact: $name", e)
            false
        }
    }
}
