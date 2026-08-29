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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Kontact Groups screen - "one box" layout.
 *
 * Shows a single card containing:
 *  - a box-level search field (searches every group, joined or not)
 *  - two independently-collapsible sections when not searching:
 *      "Your Groups" (groups the user has joined) and
 *      "All Kontact Groups" (every group that exists)
 *  - while searching, both sections are replaced by one flat results
 *    list pulled from the combined group set; joined matches keep their
 *    checkmark + "Joined" label so they're still distinguishable.
 *
 * Data comes from SheetSync.fetchAllGroupsSummary() (the
 * get_all_groups_summary RPC) plus SheetSync.fetchImportStats() for the
 * user's own joined-group ids. Tapping any row (joined or not) jumps to
 * the unlock screen (UpgradePlanActivity) pre-targeted at that group via
 * EXTRA_TARGET_GROUP_ID - joined rows just re-open the same screen,
 * which already handles an already-unlocked group gracefully.
 */
class GroupsActivity : AppCompatActivity() {

    private lateinit var groupsCountText: TextView
    private lateinit var groupSearchInput: EditText
    private lateinit var joinGroupsButton: Button
    private lateinit var noCodeContactUsButton: Button
    private lateinit var allGroupsErrorText: TextView

    private lateinit var sectionsView: LinearLayout
    private lateinit var yourGroupsHeaderContainer: FrameLayout
    private lateinit var yourGroupsBody: LinearLayout
    private lateinit var yourGroupsRows: LinearLayout
    private lateinit var allGroupsHeaderContainer: FrameLayout
    private lateinit var allGroupsBody: LinearLayout
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
        yourGroupsHeaderContainer = findViewById(R.id.yourGroupsHeaderContainer)
        yourGroupsBody = findViewById(R.id.yourGroupsBody)
        yourGroupsRows = findViewById(R.id.yourGroupsRows)
        allGroupsHeaderContainer = findViewById(R.id.allGroupsHeaderContainer)
        allGroupsBody = findViewById(R.id.allGroupsBody)
        allGroupsRows = findViewById(R.id.allGroupsRows)

        resultsView = findViewById(R.id.resultsView)
        resultsList = findViewById(R.id.resultsList)
        noResultsText = findViewById(R.id.noResultsText)

        setupSectionHeader(
            container = yourGroupsHeaderContainer,
            body = yourGroupsBody,
            title = "Your Groups"
        )
        setupSectionHeader(
            container = allGroupsHeaderContainer,
            body = allGroupsBody,
            title = "All Kontact Groups",
            muted = true
        )

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
        // Refresh every time this screen becomes visible, so redeeming a
        // key on UpgradePlanActivity and coming back shows the new
        // joined state right away.
        loadGroupsSummary()
        loadAllGroups()
        groupSearchInput.setText("")
        currentSearchQuery = ""
    }

    /**
     * Inflates a section-header row into [container] and wires it to
     * toggle [body]'s visibility + rotate its chevron on tap. Each
     * section (Your Groups / All Kontact Groups) collapses/expands
     * independently, matching the HTML reference.
     */
    private fun setupSectionHeader(
        container: FrameLayout,
        body: LinearLayout,
        title: String,
        muted: Boolean = false
    ) {
        val header = LayoutInflater.from(this)
            .inflate(R.layout.item_group_section_header, container, false)
        container.addView(header)

        val dot = header.findViewById<ImageView>(R.id.sectionDot)
        val titleText = header.findViewById<TextView>(R.id.sectionTitle)
        val chevron = header.findViewById<ImageView>(R.id.sectionChevron)

        titleText.text = title
        if (muted) {
            dot.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
        }

        header.tag = false // collapsed by default
        header.setOnClickListener {
            val nowOpen = header.tag != true
            header.tag = nowOpen
            body.visibility = if (nowOpen) View.VISIBLE else View.GONE
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

    /**
     * Central render function. When the search box is empty, shows the
     * two collapsible sections (Your Groups / All Kontact Groups). When
     * there's a query, hides both sections and shows one flat list of
     * every group (joined or not) whose id contains the query.
     */
    private fun applySearch() {
        val joined = allGroups.filter { it.groupId in joinedGroupIds }
        val notJoined = allGroups.filter { it.groupId !in joinedGroupIds }

        val totalKontacts = joined.sumOf { it.homeCount }
        groupsCountText.text = "${allGroups.size} groups \u00B7 ${joined.size} joined"

        updateSectionSub(
            header = yourGroupsHeaderContainer,
            text = if (joined.isEmpty()) "None joined yet"
                   else "${joined.size} joined \u00B7 ${kontactWord(totalKontacts)}"
        )
        updateSectionSub(
            header = allGroupsHeaderContainer,
            text = "${notJoined.size} ${if (notJoined.size == 1) "group" else "groups"}"
        )

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

    private fun updateSectionSub(header: FrameLayout, text: String) {
        header.findViewById<TextView>(R.id.sectionSub)?.text = text
    }

    private fun kontactWord(count: Long): String = if (count == 1L) "1 kontact" else "$count kontacts"

    /** Clears [container] and inflates one row per group in [groups]. */
    private fun renderRows(container: LinearLayout, groups: List<GroupSummary>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (group in groups) {
            val row = inflater.inflate(R.layout.item_group_row, container, false)
            val root = row.findViewById<LinearLayout>(R.id.groupRowRoot)
            val check = row.findViewById<ImageView>(R.id.groupRowCheck)
            val title = row.findViewById<TextView>(R.id.groupRowTitle)
            val counts = row.findViewById<TextView>(R.id.groupRowCounts)

            val isJoined = group.groupId in joinedGroupIds
            title.text = "Group ${group.groupId}"

            if (isJoined) {
                root.setBackgroundResource(R.drawable.group_row_joined_background)
                check.visibility = View.VISIBLE
                title.setTextColor(ContextCompat.getColor(this, R.color.vg_green_dark))
                counts.setTextColor(ContextCompat.getColor(this, R.color.vg_green_dark))
                counts.text = "Joined \u00B7 ${kontactWord(group.homeCount)}"
            } else {
                root.setBackgroundResource(R.drawable.group_row_background)
                check.visibility = View.GONE
                title.setTextColor(ContextCompat.getColor(this, R.color.vg_dark))
                counts.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                counts.text = kontactWord(group.homeCount)
            }

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
