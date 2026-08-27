package com.vgkontact.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Kontact Groups screen. Shows the groups the user has already joined
 * (same summary data the dashboard used to show inline) plus a single
 * button to join more groups, which still goes to the existing
 * key-redeem flow (UpgradePlanActivity). Pulled out of the dashboard so
 * the dashboard only needs one "Kontact Groups" entry point instead of a
 * summary card plus two separate buttons.
 */
class GroupsActivity : AppCompatActivity() {

    private lateinit var groupsCountText: TextView
    private lateinit var joinedGroupsTitleText: TextView
    private lateinit var joinedGroupsMetaText: TextView
    private lateinit var joinGroupsButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_groups)

        groupsCountText = findViewById(R.id.groupsCountText)
        joinedGroupsTitleText = findViewById(R.id.joinedGroupsTitleText)
        joinedGroupsMetaText = findViewById(R.id.joinedGroupsMetaText)
        joinGroupsButton = findViewById(R.id.joinGroupsButton)

        joinGroupsButton.setOnClickListener {
            startActivity(Intent(this, UpgradePlanActivity::class.java))
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
}
