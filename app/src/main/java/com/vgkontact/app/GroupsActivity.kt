package com.vgkontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Kontact Groups screen. Shows the groups the user has already joined
 * (same summary data the dashboard used to show inline), a real list of
 * every group that exists with per-group counts (via SheetSync
 * .fetchAllGroupsSummary(), the get_all_groups_summary RPC), a button to
 * join more groups via the existing key-redeem flow, and a WhatsApp
 * contact-us option for users who don't have a code yet.
 *
 * Tapping any row in "All Kontact Groups" jumps straight to the unlock
 * screen (UpgradePlanActivity), pre-targeted at that group via
 * EXTRA_TARGET_GROUP_ID, instead of the generic "Join Kontact Groups"
 * button below.
 */
class GroupsActivity : AppCompatActivity() {

    private lateinit var groupsCountText: TextView
    private lateinit var joinedGroupsTitleText: TextView
    private lateinit var joinedGroupsMetaText: TextView
    private lateinit var joinGroupsButton: Button
    private lateinit var noCodeContactUsButton: Button
    private lateinit var allGroupsContainer: LinearLayout
    private lateinit var allGroupsErrorText: TextView

    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_groups)

        groupsCountText = findViewById(R.id.groupsCountText)
        joinedGroupsTitleText = findViewById(R.id.joinedGroupsTitleText)
        joinedGroupsMetaText = findViewById(R.id.joinedGroupsMetaText)
        joinGroupsButton = findViewById(R.id.joinGroupsButton)
        noCodeContactUsButton = findViewById(R.id.noCodeContactUsButton)
        allGroupsContainer = findViewById(R.id.allGroupsContainer)
        allGroupsErrorText = findViewById(R.id.allGroupsErrorText)

        joinGroupsButton.setOnClickListener {
            startActivity(Intent(this, UpgradePlanActivity::class.java))
        }

        noCodeContactUsButton.setOnClickListener {
            openWhatsAppForUnlockCode()
        }

        loadGroupsSummary()
        loadAllGroups()
    }

    override fun onResume() {
        super.onResume()
        // Refresh every time this screen becomes visible, so redeeming a
        // key on UpgradePlanActivity and coming back shows the new count
        // right away.
        loadGroupsSummary()
        loadAllGroups()
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

    private fun loadAllGroups() {
        SheetSync.fetchAllGroupsSummary { groups ->
            runOnUiThread {
                if (groups == null) {
                    // Failed (offline, etc) - show the error text instead of
                    // silently leaving an empty list with no explanation.
                    allGroupsContainer.removeAllViews()
                    allGroupsErrorText.visibility = android.view.View.VISIBLE
                } else {
                    allGroupsErrorText.visibility = android.view.View.GONE
                    populateAllGroups(groups)
                }
            }
        }
    }

    private fun populateAllGroups(groups: List<GroupSummary>) {
        allGroupsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (group in groups) {
            val row = inflater.inflate(R.layout.item_group_summary, allGroupsContainer, false)
            val title = row.findViewById<TextView>(R.id.groupRowTitle)
            val counts = row.findViewById<TextView>(R.id.groupRowCounts)

            title.text = "Group ${group.groupId}"
            // "Extra" (key-unlocked) counts are an internal admin concept
            // and shouldn't be shown to the user - just the kontacts they'd
            // get access to (the home count).
            counts.text = if (group.homeCount == 1L) "1 kontact" else "${group.homeCount} kontacts"

            row.setOnClickListener {
                openUnlockScreenFor(group.groupId)
            }

            allGroupsContainer.addView(row)
        }
    }

    private fun openUnlockScreenFor(groupId: Long) {
        val intent = Intent(this, UpgradePlanActivity::class.java)
        intent.putExtra(UpgradePlanActivity.EXTRA_TARGET_GROUP_ID, groupId)
        startActivity(intent)
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
            // Show the actual group ID(s) the user joined (e.g. "Group 3"),
            // not just a count, so the user knows which group they're in.
            // stats.joinedGroupIds is sorted ascending by fetchImportStats.
            val ids = stats.joinedGroupIds
            joinedGroupsTitleText.text = when {
                ids.isEmpty() -> if (count == 1) "1 Group" else "$count Groups"
                ids.size == 1 -> "Group ${ids[0]}"
                else -> "Groups " + ids.joinToString(", ")
            }
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
