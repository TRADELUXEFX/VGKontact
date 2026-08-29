package com.vgkontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Kontact Groups screen.
 *
 * Styled to match the Profile screen: a single card_background card,
 * uppercase muted field labels, bold value text, and thin
 * stats_card_border divider lines between sections/rows - no nested
 * pill-shaped boxes or per-row rounded corners.
 *
 * Contains a box-level search field (searches every group, joined or
 * not) and, when not searching, a two-way tab switch ("Your Groups" /
 * "All Kontact Groups") above a single paginated list - only one of
 * the two lists is ever visible at a time, matching the referral
 * leaderboard's pager (HistoryActivity: same ENTRIES_PER_PAGE pattern,
 * same item_group_page_button.xml / page_button_selected_background /
 * page_button_default_background drawables). Joined rows show a small
 * sync icon next to the group name instead of a "Joined" label. While
 * searching, one flat results list is shown instead, pulled from the
 * combined group set (joined matches still shown with the sync icon).
 *
 * Data comes from SheetSync.fetchAllGroupsSummary() (the
 * get_all_groups_summary RPC) plus SheetSync.fetchImportStats() for the
 * user's own joined-group ids. Tapping any row jumps to the unlock
 * screen (UpgradePlanActivity) pre-targeted at that group via
 * EXTRA_TARGET_GROUP_ID.
 */
class GroupsActivity : AppCompatActivity() {

    private lateinit var groupsCountText: TextView
    private lateinit var groupSearchInput: EditText
    private lateinit var joinGroupsButton: Button
    private lateinit var noCodeContactUsButton: Button
    private lateinit var allGroupsErrorText: TextView

    private lateinit var sectionsView: LinearLayout
    private lateinit var yourGroupsTab: TextView
    private lateinit var allGroupsTab: TextView
    private lateinit var activeGroupsSubText: TextView
    private lateinit var activeGroupsRows: LinearLayout
    private lateinit var activeGroupsEmptyText: TextView
    private lateinit var groupsPagerScroll: HorizontalScrollView
    private lateinit var groupsPagerContainer: LinearLayout

    private lateinit var resultsView: LinearLayout
    private lateinit var resultsList: LinearLayout
    private lateinit var noResultsText: TextView

    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"
    private val ENTRIES_PER_PAGE = 5

    private var allGroups: List<GroupSummary> = emptyList()
    private var joinedGroupIds: Set<Long> = emptySet()
    private var currentSearchQuery = ""

    /** true = "Your Groups" tab active, false = "All Kontact Groups". */
    private var showingYourGroups = true
    private var currentPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_groups)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        groupsCountText = findViewById(R.id.groupsCountText)
        groupSearchInput = findViewById(R.id.groupSearchInput)
        joinGroupsButton = findViewById(R.id.joinGroupsButton)
        noCodeContactUsButton = findViewById(R.id.noCodeContactUsButton)
        allGroupsErrorText = findViewById(R.id.allGroupsErrorText)

        sectionsView = findViewById(R.id.sectionsView)
        yourGroupsTab = findViewById(R.id.yourGroupsTab)
        allGroupsTab = findViewById(R.id.allGroupsTab)
        activeGroupsSubText = findViewById(R.id.activeGroupsSubText)
        activeGroupsRows = findViewById(R.id.activeGroupsRows)
        activeGroupsEmptyText = findViewById(R.id.activeGroupsEmptyText)
        groupsPagerScroll = findViewById(R.id.groupsPagerScroll)
        groupsPagerContainer = findViewById(R.id.groupsPagerContainer)

        resultsView = findViewById(R.id.resultsView)
        resultsList = findViewById(R.id.resultsList)
        noResultsText = findViewById(R.id.noResultsText)

        yourGroupsTab.setOnClickListener { switchTab(showYourGroups = true) }
        allGroupsTab.setOnClickListener { switchTab(showYourGroups = false) }
        updateTabStyles()

        groupSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                applySearch()
            }
        })

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
        loadGroupsSummary()
        loadAllGroups()
        groupSearchInput.setText("")
        currentSearchQuery = ""
    }

    /** Switches the active tab, resets to page 0, and re-renders. */
    private fun switchTab(showYourGroups: Boolean) {
        if (showingYourGroups == showYourGroups) return
        showingYourGroups = showYourGroups
        currentPage = 0
        updateTabStyles()
        applySearch()
    }

    private fun updateTabStyles() {
        yourGroupsTab.setBackgroundResource(
            if (showingYourGroups) R.drawable.tab_selected_background else 0
        )
        yourGroupsTab.setTextColor(
            ContextCompat.getColor(this, if (showingYourGroups) R.color.white else R.color.text_muted)
        )
        allGroupsTab.setBackgroundResource(
            if (!showingYourGroups) R.drawable.tab_selected_background else 0
        )
        allGroupsTab.setTextColor(
            ContextCompat.getColor(this, if (!showingYourGroups) R.color.white else R.color.text_muted)
        )
    }

    private fun loadGroupsSummary() {
        SheetSync.fetchImportStats(this) { stats ->
            runOnUiThread {
                if (stats != null) {
                    joinedGroupIds = stats.joinedGroupIds.toSet()
                    applySearch()
                }
            }
        }
    }

    private fun loadAllGroups() {
        SheetSync.fetchAllGroupsSummary { groups ->
            runOnUiThread {
                if (groups == null) {
                    allGroupsErrorText.visibility = View.VISIBLE
                } else {
                    allGroupsErrorText.visibility = View.GONE
                    allGroups = groups
                    applySearch()
                }
            }
        }
    }

    private fun applySearch() {
        val joined = allGroups.filter { it.groupId in joinedGroupIds }
        val notJoined = allGroups.filter { it.groupId !in joinedGroupIds }

        groupsCountText.text = "${allGroups.size}"

        if (currentSearchQuery.isEmpty()) {
            sectionsView.visibility = View.VISIBLE
            resultsView.visibility = View.GONE

            val activeList = if (showingYourGroups) joined else notJoined

            activeGroupsSubText.text = if (showingYourGroups) {
                if (joined.isEmpty()) "None joined yet"
                else "${joined.size} ${if (joined.size == 1) "kontact group" else "kontact groups"} joined"
            } else {
                "${notJoined.size} ${if (notJoined.size == 1) "kontact group" else "kontact groups"} available"
            }

            if (activeList.isEmpty()) {
                activeGroupsRows.visibility = View.GONE
                groupsPagerScroll.visibility = View.GONE
                activeGroupsEmptyText.visibility = View.VISIBLE
                activeGroupsEmptyText.text = if (showingYourGroups) "None joined yet" else "No groups yet"
            } else {
                activeGroupsRows.visibility = View.VISIBLE
                activeGroupsEmptyText.visibility = View.GONE
                if (currentPage * ENTRIES_PER_PAGE >= activeList.size) currentPage = 0
                renderPage(activeGroupsRows, activeList, currentPage, showJoinedIcon = showingYourGroups)
                renderPager(activeList.size)
            }
        } else {
            sectionsView.visibility = View.GONE
            resultsView.visibility = View.VISIBLE

            val matches = allGroups.filter {
                it.groupId.toString().contains(currentSearchQuery, ignoreCase = true)
            }

            if (matches.isEmpty()) {
                resultsList.visibility = View.GONE
                noResultsText.visibility = View.VISIBLE
                noResultsText.text = "No groups match \u201c$currentSearchQuery\u201d"
            } else {
                resultsList.visibility = View.VISIBLE
                noResultsText.visibility = View.GONE
                renderRows(resultsList, matches)
            }
        }
    }

    private fun kontactWord(count: Long): String = if (count == 1L) "1 kontact" else "$count kontacts"

    /** Clears [container] and inflates only the rows for [page] of [groups], [ENTRIES_PER_PAGE] at a time. */
    private fun renderPage(container: LinearLayout, groups: List<GroupSummary>, page: Int, showJoinedIcon: Boolean) {
        val start = page * ENTRIES_PER_PAGE
        val end = minOf(start + ENTRIES_PER_PAGE, groups.size)
        if (start >= groups.size) {
            container.removeAllViews()
            return
        }
        renderRows(container, groups.subList(start, end), showJoinedIcon)
    }

    /**
     * Builds the numbered page row (1, 2, 3...) below the active list,
     * identical pattern to HistoryActivity's pager. Hidden entirely
     * when everything fits on one page.
     */
    private fun renderPager(totalCount: Int) {
        val pageCount = (totalCount + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE

        if (pageCount <= 1) {
            groupsPagerScroll.visibility = View.GONE
            return
        }

        groupsPagerScroll.visibility = View.VISIBLE
        groupsPagerContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (pageIndex in 0 until pageCount) {
            val pageButton = inflater.inflate(R.layout.item_group_page_button, groupsPagerContainer, false) as TextView
            pageButton.text = (pageIndex + 1).toString()
            pageButton.setOnClickListener {
                if (currentPage != pageIndex) {
                    currentPage = pageIndex
                    applySearch()
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

    /** Clears [container] and inflates one plain row per group in [groups]. */
    private fun renderRows(container: LinearLayout, groups: List<GroupSummary>, showJoinedIcon: Boolean = true) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for ((index, group) in groups.withIndex()) {
            val row = inflater.inflate(R.layout.item_group_row, container, false)
            val syncIcon = row.findViewById<ImageView>(R.id.groupRowSyncIcon)
            val title = row.findViewById<TextView>(R.id.groupRowTitle)
            val counts = row.findViewById<TextView>(R.id.groupRowCounts)
            val divider = row.findViewById<View>(R.id.groupRowDivider)

            val isJoined = group.groupId in joinedGroupIds
            title.text = "Group ${group.groupId}"

            if (isJoined && showJoinedIcon) {
                syncIcon.visibility = View.VISIBLE
                title.setTextColor(ContextCompat.getColor(this, R.color.vg_green_dark))
                counts.setTextColor(ContextCompat.getColor(this, R.color.vg_green_dark))
            } else {
                syncIcon.visibility = View.GONE
                title.setTextColor(ContextCompat.getColor(this, R.color.vg_dark))
                counts.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            }
            counts.text = kontactWord(group.homeCount)

            // Skip the divider under the last row so the list doesn't end
            // with a trailing line right before the pager/button below.
            divider.visibility = if (index == groups.lastIndex) View.GONE else View.VISIBLE

            row.setOnClickListener {
                openUnlockScreenFor(group.groupId)
            }

            container.addView(row)
        }
    }

    private fun openUnlockScreenFor(groupId: Long) {
        val intent = Intent(this, UpgradePlanActivity::class.java)
        intent.putExtra(UpgradePlanActivity.EXTRA_TARGET_GROUP_ID, groupId)
        startActivity(intent)
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
