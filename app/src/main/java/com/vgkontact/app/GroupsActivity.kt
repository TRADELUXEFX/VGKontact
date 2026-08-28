package com.vgkontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

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
    private lateinit var groupsPagerScroll: HorizontalScrollView
    private lateinit var groupsPagerContainer: LinearLayout

    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    // Fixed at 10 per page per product decision - with large group counts
    // (50+), a single long list made the Join/Contact Us buttons hard to
    // reach, so the list is now paginated with numbered page buttons
    // instead of one continuous scroll.
    private val GROUPS_PER_PAGE = 10

    private var allGroups: List<GroupSummary> = emptyList()
    private var currentPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_groups)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        groupsCountText = findViewById(R.id.groupsCountText)
        joinedGroupsTitleText = findViewById(R.id.joinedGroupsTitleText)
        joinedGroupsMetaText = findViewById(R.id.joinedGroupsMetaText)
        joinGroupsButton = findViewById(R.id.joinGroupsButton)
        noCodeContactUsButton = findViewById(R.id.noCodeContactUsButton)
        allGroupsContainer = findViewById(R.id.allGroupsContainer)
        allGroupsErrorText = findViewById(R.id.allGroupsErrorText)
        groupsPagerScroll = findViewById(R.id.groupsPagerScroll)
        groupsPagerContainer = findViewById(R.id.groupsPagerContainer)

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
        allGroups = groups
        // Reset to page 1 on every fresh load (e.g. coming back from
        // UpgradePlanActivity after redeeming a key) rather than trying to
        // preserve whatever page the user was on, since the group list/
        // counts may have changed.
        currentPage = 0
        renderCurrentPage()
        renderPager()
    }

    private fun renderCurrentPage() {
        allGroupsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val start = currentPage * GROUPS_PER_PAGE
        val end = minOf(start + GROUPS_PER_PAGE, allGroups.size)
        if (start >= allGroups.size) return

        for (group in allGroups.subList(start, end)) {
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

    /**
     * Builds the numbered page row (1, 2, 3...) below the group list.
     * Hidden entirely when everything fits on one page (10 or fewer
     * groups), since a single "1" button with nothing else to switch to
     * would just be clutter.
     */
    private fun renderPager() {
        val pageCount = (allGroups.size + GROUPS_PER_PAGE - 1) / GROUPS_PER_PAGE

        if (pageCount <= 1) {
            groupsPagerScroll.visibility = android.view.View.GONE
            return
        }

        groupsPagerScroll.visibility = android.view.View.VISIBLE
        groupsPagerContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (pageIndex in 0 until pageCount) {
            val pageButton = inflater.inflate(R.layout.item_group_page_button, groupsPagerContainer, false) as TextView
            pageButton.text = (pageIndex + 1).toString()
            pageButton.setOnClickListener {
                if (currentPage != pageIndex) {
                    currentPage = pageIndex
                    renderCurrentPage()
                    updatePagerSelection()
                }
            }
            groupsPagerContainer.addView(pageButton)
        }

        updatePagerSelection()
    }

    /** Re-styles every page button so only currentPage shows as selected. */
    private fun updatePagerSelection() {
        for (i in 0 until groupsPagerContainer.childCount) {
            val pageButton = groupsPagerContainer.getChildAt(i) as TextView
            val isSelected = i == currentPage
            pageButton.setBackgroundResource(
                if (isSelected) R.drawable.page_button_selected_background else R.drawable.page_button_default_background
            )
            pageButton.setTextColor(
                ContextCompat.getColor(this, if (isSelected) R.color.white else R.color.vg_dark)
            )
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
