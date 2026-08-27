package com.vgkontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Kontact Groups screen. Shows the groups the user has already joined
 * (same summary data the dashboard used to show inline), an honest note
 * that more groups may exist (no backend endpoint lists them yet - see
 * activity_groups.xml for details), a button to join more groups via the
 * existing key-redeem flow, and a WhatsApp contact-us option for users who
 * don't have a code yet.
 */
class GroupsActivity : AppCompatActivity() {

    private lateinit var groupsCountText: TextView
    private lateinit var joinedGroupsTitleText: TextView
    private lateinit var joinedGroupsMetaText: TextView
    private lateinit var joinGroupsButton: Button
    private lateinit var noCodeContactUsButton: Button

    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_groups)

        groupsCountText = findViewById(R.id.groupsCountText)
        joinedGroupsTitleText = findViewById(R.id.joinedGroupsTitleText)
        joinedGroupsMetaText = findViewById(R.id.joinedGroupsMetaText)
        joinGroupsButton = findViewById(R.id.joinGroupsButton)
        noCodeContactUsButton = findViewById(R.id.noCodeContactUsButton)

        joinGroupsButton.setOnClickListener {
            startActivity(Intent(this, UpgradePlanActivity::class.java))
        }

        noCodeContactUsButton.setOnClickListener {
            openWhatsAppForUnlockCode()
        }

        loadGroupsSummary()
    }

    override fun onResume() {
        super.onResume()
        // Refresh every time this screen becomes visible, so redeeming a
        // key on UpgradePlanActivity and coming back shows the new count
        // right away.
        loadGroupsSummary()
    }

    private fun loadGroupsSummary() {
        SheetSync.fetchImportStats(this) { stats ->
            runOnUiThread {
                if (stats != null) {
                    updateGroupsSummary(stats)
                }
            }
        }
    }

    private fun updateGroupsSummary(stats: ImportStats) {
        if (stats.joinedGroupCount < 0) {
            return
        }
        val count = stats.joinedGroupCount
        groupsCountText.text = if (count == 1) "1 joined" else "$count joined"
        if (count == 0) {
            joinedGroupsTitleText.text = "No groups yet"
            joinedGroupsMetaText.text = "Tap \u201cJoin Kontact Groups\u201d to get started"
        } else {
            joinedGroupsTitleText.text = if (count == 1) "1 Group" else "$count Groups"
            joinedGroupsMetaText.text = if (stats.totalInDatabase == 1) "1 kontact" else "${stats.totalInDatabase} kontacts"
        }
    }

    private fun openWhatsAppForUnlockCode() {
        val message = Uri.encode("Hi VG Kontact, I don't have an unlock code yet and would like to join more groups.")
        val uri = Uri.parse("https://wa.me/$CONTACT_US_WHATSAPP_NUMBER?text=$message")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
