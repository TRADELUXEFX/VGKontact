package com.vgkontact.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ContactRow(
    val whatsapp: String,
    val referral: String,
    val timestamp: String
)

data class DayCount(
    val date: String,
    val count: Int
)

data class HistorySummary(
    val total: Int,
    val days: List<DayCount>
)

object SheetSync {

    // TODO: replace with your deployed Apps Script Web App URL
    private const val ENDPOINT_URL = "https://script.google.com/macros/s/AKfycbxXy1QFBK5VANJXZhfPwVfzw888PDMmUq74SCa4jPr4nSM6uWz1dgvGDXAWAhYSHMVA/exec"

    suspend fun submit(whatsappNumber: String, referralNumber: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date())

                val params = listOf(
                    "whatsapp" to whatsappNumber,
                    "referral" to referralNumber,
                    "timestamp" to timestamp
                ).joinToString("&") { (key, value) ->
                    "$key=${java.net.URLEncoder.encode(value, "UTF-8")}"
                }

                val connection = URL(ENDPOINT_URL).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(params.toByteArray()) }

                val code = connection.responseCode
                connection.disconnect()

                if (code in 200..299) Result.success(Unit)
                else Result.failure(Exception("Server responded with $code"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    data class DeviceContact(
        val name: String,
        val phoneNumber: String
    )

    data class ImportResult(
        val submitted: Int,
        val failed: Int
    )

    suspend fun importAllContactsFromSheet(
        context: android.content.Context,
        limit: Int = 1000
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            // Fetch contacts from Google Sheet
            val sheetContacts = fetchPreview(limit).getOrNull() ?: emptyList()
            
            var submitted = 0
            var failed = 0

            // Add each contact from sheet to phone
            for ((index, contact) in sheetContacts.withIndex()) {
                val result = syncContactToPhone(
                    context = context,
                    name = contact.referral.takeUnless { it.isBlank() } ?: "KONTACT ${index + 1}",
                    phoneNumber = contact.whatsapp
                )
                if (result.isSuccess) submitted++ else failed++
            }

            ImportResult(submitted = submitted, failed = failed)
        } catch (e: Exception) {
            ImportResult(submitted = 0, failed = 1)
        }
    }

    suspend fun fetchPreview(limit: Int = 10): Result<List<ContactRow>> =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL("$ENDPOINT_URL?limit=$limit").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                val code = connection.responseCode
                val body = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else null
                connection.disconnect()

                if (body == null) return@withContext Result.failure(Exception("Server responded with $code"))

                val jsonArray = JSONArray(body)
                val rows = (0 until jsonArray.length()).map { i ->
                    val obj = jsonArray.getJSONObject(i)
                    ContactRow(
                        whatsapp = obj.optString("whatsapp"),
                        referral = obj.optString("referral"),
                        timestamp = obj.optString("timestamp")
                    )
                }
                Result.success(rows)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun fetchHistory(): Result<HistorySummary> =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL("$ENDPOINT_URL?action=history").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                val code = connection.responseCode
                val body = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else null
                connection.disconnect()

                if (body == null) return@withContext Result.failure(Exception("Server responded with $code"))

                val obj = org.json.JSONObject(body)
                val total = obj.optInt("total", 0)
                val daysArray = obj.optJSONArray("days") ?: JSONArray()
                val days = (0 until daysArray.length()).map { i ->
                    val dayObj = daysArray.getJSONObject(i)
                    DayCount(
                        date = dayObj.optString("date"),
                        count = dayObj.optInt("count", 0)
                    )
                }
                Result.success(HistorySummary(total = total, days = days))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun syncContactToPhone(
        context: android.content.Context,
        name: String,
        phoneNumber: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cr = context.contentResolver

            // Insert contact
            val contactOps = android.content.ContentProviderOperation.newInsert(
                android.provider.ContactsContract.Contacts.CONTENT_URI
            ).withValue(android.provider.ContactsContract.Contacts.DISPLAY_NAME, name)
                .build()

            val contactId = cr.applyBatch(android.provider.ContactsContract.AUTHORITY, arrayListOf(contactOps))
            
            if (contactId.isNotEmpty()) {
                val contactUri = contactId[0].uri
                val phoneOps = android.content.ContentProviderOperation.newInsert(
                    android.provider.ContactsContract.Data.CONTENT_URI
                ).withValue(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 
                    android.content.ContentUris.parseId(contactUri!!))
                    .withValue(android.provider.ContactsContract.Data.MIMETYPE, 
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                    .build()
                
                cr.applyBatch(android.provider.ContactsContract.AUTHORITY, arrayListOf(phoneOps))
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to create contact"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
