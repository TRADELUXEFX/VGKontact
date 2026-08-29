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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Kontact Groups screen.
 *
 * Styled to match the Profile screen exactly: a single card_background
 * card, uppercase muted field labels, bold value text, and thin
 * stats_card_border divider lines between sections/rows - no nested
 * pill-shaped boxes or per-row rounded corners.
 *
 * Contains a box-level search field (searches every group, joined or
 * not), two tap-to-expand sections when not searching ("Your Groups"
 * and "All Kontact Groups"), and while searching, one flat results list
 * pulled from the combined group set (joined matches shown in green
 * with "Joined \u00B7" prefixed to their kontact count).
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
    private lateinit var yourGroupsHeader: LinearLayout
    private lateinit var yourGroupsChevron: ImageView
    private lateinit var yourGroupsSubText: TextView
    private lateinit var yourGroupsRows: LinearLayout
    private lateinit var allGroupsHeader: LinearLayout
    private lateinit var allGroupsChevron: ImageView
    private lateinit var allGroupsSubText: TextView
    private lateinit var allGroupsRows: LinearLayout

    private lateinit var resultsView: LinearLayout
    private lateinit var resultsList: LinearLayout
    private lateinit var noResultsText: TextView

    private val CONTACT_US_WHATSAPP_NUMBER = "09110321143"

    private var allGroups: List<GroupSummary> = emptyList()
    private var joinedGroupIds: Set<Long> = emptySet()
    private var currentSearchQuery = ""

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
        yourGroupsHeader = findViewById(R.id.yourGroupsHeader)
        yourGroupsChevron = findViewById(R.id.yourGroupsChevron)
        yourGroupsSubText = findViewById(R.id.yourGroupsSubText)
        yourGroupsRows = findViewById(R.id.yourGroupsRows)
        allGroupsHeader = findViewById(R.id.allGroupsHeader)
        allGroupsChevron = findViewById(R.id.allGroupsChevron)
        allGroupsSubText = findViewById(R.id.allGroupsSubText)
        allGroupsRows = findViewById(R.id.allGroupsRows)

        resultsView = findViewById(R.id.resultsView)
        resultsList = findViewById(R.id.resultsList)
        noResultsText = findViewById(R.id.noResultsText)

        wireSectionToggle(yourGroupsHeader, yourGroupsChevron, yourGroupsRows)
        wireSectionToggle(allGroupsHeader, allGroupsChevron, allGroupsRows)

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

    /** Tapping [header] toggles [rows]' visibility and rotates [chevron]. */
    private fun wireSectionToggle(header: LinearLayout, chevron: ImageView, rows: LinearLayout) {
        header.tag = false // collapsed by default
        header.setOnClickListener {
            val nowOpen = header.tag != true
            header.tag = nowOpen
            rows.visibility = if (nowOpen) View.VISIBLE else View.GONE
            chevron.rotation = if (nowOpen) 180f else 0f
        }
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
        val totalKontacts = joined.sumOf { it.homeCount }

        groupsCountText.text = "${allGroups.size} groups \u00B7 ${joined.size} joined"

        yourGroupsSubText.text = if (joined.isEmpty()) "None joined yet"
            else "${joined.size} joined \u00B7 ${kontactWord(totalKontacts)}"
        allGroupsSubText.text = "${notJoined.size} ${if (notJoined.size == 1) "group" else "groups"}"

        if (currentSearchQuery.isEmpty()) {
            sectionsView.visibility = View.VISIBLE
            resultsView.visibility = View.GONE

            renderRows(yourGroupsRows, joined)
            renderRows(allGroupsRows, notJoined)
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

    /** Clears [container] and inflates one plain row per group in [groups]. */
    private fun renderRows(container: LinearLayout, groups: List<GroupSummary>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for ((index, group) in groups.withIndex()) {
            val row = inflater.inflate(R.layout.item_group_row, container, false)
            val title = row.findViewById<TextView>(R.id.groupRowTitle)
            val counts = row.findViewById<TextView>(R.id.groupRowCounts)
            val divider = row.findViewById<View>(R.id.groupRowDivider)

            val isJoined = group.groupId in joinedGroupIds
            title.text = "Group ${group.groupId}"

            if (isJoined) {
                title.setTextColor(ContextCompat.getColor(this, R.color.vg_green_dark))
                counts.setTextColor(ContextCompat.getColor(this, R.color.vg_green_dark))
                counts.text = "Joined \u00B7 ${kontactWord(group.homeCount)}"
            } else {
                title.setTextColor(ContextCompat.getColor(this, R.color.vg_dark))
                counts.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                counts.text = kontactWord(group.homeCount)
            }

            // Skip the divider under the last row so the list doesn't end
            // with a trailing line right before the next section label.
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
