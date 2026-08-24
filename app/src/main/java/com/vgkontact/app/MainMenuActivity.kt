package com.vgkontact.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vgkontact.app.databinding.ActivityMainMenuBinding
import kotlinx.coroutines.launch

class MainMenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainMenuBinding

    private val supportWhatsappNumber = "2349110321143"

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            runImportAllContacts()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Permission needed")
                .setMessage("VGKontact needs access to your contacts to import them. You can allow this from your phone's app settings.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.profileIcon.setOnClickListener {
            showProfile()
        }

        binding.chatIcon.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/$supportWhatsappNumber")
            }
            startActivity(intent)
        }

        binding.syncKontactButton.setOnClickListener {
            requestContactsPermissionAndImport()
        }

        binding.kontactHistoryButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    private fun loadStats() {
        binding.statsCard.visibility = View.VISIBLE
        binding.statsProgressBar.visibility = View.VISIBLE
        binding.statsContent.visibility = View.GONE

        lifecycleScope.launch {
            val result = SheetSync.fetchHistory()
            binding.statsProgressBar.visibility = View.GONE

            result.onSuccess { summary ->
                binding.statsTotalText.text = summary.total.toString()

                val today = summary.days.firstOrNull()
                binding.statsTodayText.text = if (today != null && isToday(today.date)) {
                    "${today.count} added today"
                } else {
                    "No Kontacts added today yet"
                }

                binding.statsContent.visibility = View.VISIBLE
            }.onFailure {
                binding.statsCard.visibility = View.GONE
            }
        }
    }

    private fun isToday(isoDate: String): Boolean {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return isoDate == sdf.format(java.util.Date())
    }

    private fun showProfile() {
        val whatsapp = UserPrefs.getWhatsapp(this).takeUnless { it.isNullOrBlank() } ?: "—"
        val referral = UserPrefs.getReferral(this).takeUnless { it.isNullOrBlank() } ?: "—"

        AlertDialog.Builder(this)
            .setTitle("My Profile")
            .setMessage("Your WhatsApp Number:\n$whatsapp\n\nReferral WhatsApp Number:\n$referral")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun requestContactsPermissionAndImport() {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) {
            runImportAllContacts()
        } else {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun runImportAllContacts() {
        val progressDialog = AlertDialog.Builder(this)
            .setView(buildImportProgressView())
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val contacts = readDeviceContacts()
            val result = SheetSync.importAllContacts(contacts, this@MainMenuActivity)
            progressDialog.dismiss()

            val message = when {
                contacts.isEmpty() ->
                    "No contacts with phone numbers were found on this device."
                result.failed == 0 ->
                    "Imported ${result.submitted} contact${if (result.submitted == 1) "" else "s"} successfully."
                else ->
                    "Imported ${result.submitted} contact${if (result.submitted == 1) "" else "s"}. ${result.failed} could not be saved — check your connection and try again."
            }

            AlertDialog.Builder(this@MainMenuActivity)
                .setTitle("Import all Contacts")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun buildImportProgressView(): View {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt())
        }
        container.addView(ProgressBar(this))
        container.addView(TextView(this).apply {
            text = "Importing your contacts…"
            setPadding(0, (16 * density).toInt(), 0, 0)
        })
        return container
    }

    private fun readDeviceContacts(): List<SheetSync.DeviceContact> {
        val contacts = mutableListOf<SheetSync.DeviceContact>()
        val seenNumbers = mutableSetOf<String>()

        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val name = if (nameIndex >= 0) it.getString(nameIndex) else null
                val rawNumber = if (numberIndex >= 0) it.getString(numberIndex) else null
                val number = rawNumber?.filter { c -> c.isDigit() || c == '+' }

                if (!number.isNullOrBlank() && seenNumbers.add(number)) {
                    contacts.add(
                        SheetSync.DeviceContact(
                            name = name?.takeUnless { n -> n.isBlank() } ?: "Unknown",
                            phoneNumber = number
                        )
                    )
                }
            }
        }

        return contacts
    }

    override fun onResume() {
        super.onResume()
        binding.phoneNumberText.text = UserPrefs.getWhatsapp(this) ?: ""
        loadStats()
    }
}

