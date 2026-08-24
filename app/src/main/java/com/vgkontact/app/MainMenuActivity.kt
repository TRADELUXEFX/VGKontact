package com.vgkontact.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
            runImportContactsFromSheet()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Permission needed")
                .setMessage("VGKontact needs access to your contacts to import them from Google Sheet. You can allow this from your phone's app settings.")
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

        // Fixed: Changed button action to import from sheet to phone
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
            this, Manifest.permission.WRITE_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) {
            runImportContactsFromSheet()
        } else {
            contactsPermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
        }
    }

    private fun runImportContactsFromSheet() {
        val progressDialog = AlertDialog.Builder(this)
            .setView(buildImportProgressView())
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            // Fixed: Changed to import from Sheet to Phone instead of Phone to Sheet
            val result = SheetSync.importAllContactsFromSheet(this@MainMenuActivity)
            progressDialog.dismiss()

            val message = when {
                result.submitted == 0 && result.failed == 0 ->
                    "No contacts found in your Google Sheet."
                result.failed == 0 ->
                    "Imported ${result.submitted} contact${if (result.submitted == 1) "" else "s"} from Google Sheet to your phone successfully."
                else ->
                    "Imported ${result.submitted} contact${if (result.submitted == 1) "" else "s"}. ${result.failed} could not be saved — check your connection and try again."
            }

            AlertDialog.Builder(this@MainMenuActivity)
                .setTitle("Import Contacts from Google Sheet")
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
            text = "Importing contacts from Google Sheet…"
            setPadding(0, (16 * density).toInt(), 0, 0)
        })
        return container
    }

    override fun onResume() {
        super.onResume()
        binding.phoneNumberText.text = UserPrefs.getWhatsapp(this) ?: ""
        loadStats()
    }
}
