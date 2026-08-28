package com.vgkontact.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * One-time screen shown right after registration, before the dashboard.
 *
 * Order is fixed and sequential - one system prompt at a time, never all three
 * fired together:
 *   1. Contacts   (READ_CONTACTS + WRITE_CONTACTS)
 *   2. Notifications (POST_NOTIFICATIONS, Android 13+ only)
 *   3. Battery optimization exemption (opens system Settings, not a popup)
 *
 * Once all three have been asked (granted or denied - we never block on denial),
 * it shows a plain loading screen while contact stats are pre-warmed in the
 * background, then hands off to MainMenuActivity with data already ready.
 *
 * Runs only once per install: UserPrefs.isPermissionSetupDone() gates re-entry,
 * so returning users skip straight past this screen.
 */
class PermissionSetupActivity : AppCompatActivity() {

    private enum class Step { CONTACTS, NOTIFICATIONS, BATTERY, DONE }

    private lateinit var permissionStepContainer: LinearLayout
    private lateinit var loadingContainer: LinearLayout
    private lateinit var stepCounterText: TextView
    private lateinit var stepTitleText: TextView
    private lateinit var stepDescriptionText: TextView
    private lateinit var stepIcon: ImageView
    private lateinit var stepActionButton: Button

    private var currentStep: Step = Step.CONTACTS
    private var batterySettingsLaunched: Boolean = false

    private val CONTACTS_REQUEST_CODE = 200
    private val NOTIFICATIONS_REQUEST_CODE = 201

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Safety net: if this has already run before, skip straight to the dashboard.
        if (UserPrefs.isPermissionSetupDone(this)) {
            goToDashboard()
            return
        }

        setContentView(R.layout.activity_permission_setup)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        permissionStepContainer = findViewById(R.id.permissionStepContainer)
        loadingContainer = findViewById(R.id.loadingContainer)
        stepCounterText = findViewById(R.id.stepCounterText)
        stepTitleText = findViewById(R.id.stepTitleText)
        stepDescriptionText = findViewById(R.id.stepDescriptionText)
        stepIcon = findViewById(R.id.stepIcon)
        stepActionButton = findViewById(R.id.stepActionButton)

        showStep(Step.CONTACTS)
    }

    private fun showStep(step: Step) {
        currentStep = step
        when (step) {
            Step.CONTACTS -> {
                stepCounterText.text = "STEP 1 OF 3"
                stepTitleText.text = "Contacts Access"
                stepDescriptionText.text =
                    "VG Kontact needs contacts access to add and sync numbers on your phone."
                stepIcon.setImageResource(R.drawable.ic_contacts)
                stepActionButton.text = "Allow Contacts Access"
                stepActionButton.setOnClickListener { requestContactsPermission() }
            }
            Step.NOTIFICATIONS -> {
                stepCounterText.text = "STEP 2 OF 3"
                stepTitleText.text = "Stay Notified"
                stepDescriptionText.text =
                    "Get notified when new Kontacts are ready to sync."
                stepIcon.setImageResource(R.drawable.ic_notification_bell)
                stepActionButton.text = "Allow Notifications"
                stepActionButton.setOnClickListener { requestNotificationPermission() }
            }
            Step.BATTERY -> {
                stepCounterText.text = "STEP 3 OF 3"
                stepTitleText.text = "Reliable Background Sync"
                stepDescriptionText.text =
                    "Allow VG Kontact to run in the background so syncing stays reliable."
                stepIcon.setImageResource(R.drawable.ic_battery)
                stepActionButton.text = "Allow Background Activity"
                stepActionButton.setOnClickListener { requestBatteryExemption() }
            }
            Step.DONE -> {
                UserPrefs.setPermissionSetupDone(this)
                showLoadingScreen()
            }
        }
    }

    private fun advanceTo(next: Step) {
        runOnUiThread { showStep(next) }
    }

    // ---------------- Step 1: Contacts ----------------

    private fun checkContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestContactsPermission() {
        if (checkContactsPermission()) {
            syncContactsThenAdvance()
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS),
            CONTACTS_REQUEST_CODE
        )
    }

    // Fires the contacts sync in the background right after permission is
    // granted, then moves the wizard on immediately - we don't make the user
    // wait on the network here, since fetchImportStats() on the loading
    // screen (Step.DONE) will reflect the up-to-date sync state anyway.
    private fun syncContactsThenAdvance() {
        SheetSync.importAllContactsFromSheet(this)
        advanceTo(Step.NOTIFICATIONS)
    }

    // ---------------- Step 2: Notifications ----------------

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Not needed on this OS version - skip straight through.
            advanceTo(Step.BATTERY)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) {
            advanceTo(Step.BATTERY)
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATIONS_REQUEST_CODE
        )
    }

    // ---------------- Step 3: Battery optimization ----------------

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            advanceTo(Step.DONE)
            return
        }
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
            batterySettingsLaunched = true
        } catch (e: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                batterySettingsLaunched = true
            } catch (e2: Exception) {
                Toast.makeText(this, "Please allow background activity manually in Settings", Toast.LENGTH_LONG).show()
                // Couldn't open Settings at all - nothing to wait for, so don't
                // block the user here; let them proceed.
                advanceTo(Step.DONE)
            }
        }
        // No callback for a Settings screen - we check the real state again in onResume,
        // but only once we know we actually left for Settings (see batterySettingsLaunched).
    }

    override fun onResume() {
        super.onResume()
        // Only relevant for the battery step, and only once the user has actually
        // been sent to the system Settings screen and come back - otherwise the
        // very first onResume() after showStep(BATTERY) would skip the step
        // before the user ever saw or tapped the button.
        if (currentStep == Step.BATTERY && batterySettingsLaunched) {
            batterySettingsLaunched = false
            advanceTo(Step.DONE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // We advance regardless of grant/deny - denial just means that feature
        // won't work yet, it never blocks the user from reaching the dashboard.
        when (requestCode) {
            CONTACTS_REQUEST_CODE -> {
                if (checkContactsPermission()) {
                    SheetSync.importAllContactsFromSheet(this)
                }
                advanceTo(Step.NOTIFICATIONS)
            }
            NOTIFICATIONS_REQUEST_CODE -> advanceTo(Step.BATTERY)
        }
    }

    // ---------------- Loading screen + handoff ----------------

    private fun showLoadingScreen() {
        permissionStepContainer.visibility = View.GONE
        loadingContainer.visibility = View.VISIBLE

        // Pre-warm contact stats in the background (which numbers are already on
        // the device vs. available to import) so the dashboard opens with real
        // data already in place - never a blank/generic first paint.
        SheetSync.fetchImportStats(this) {
            runOnUiThread { goToDashboard() }
        }
    }

    private fun goToDashboard() {
        startActivity(Intent(this, MainMenuActivity::class.java))
        finish()
    }
}
