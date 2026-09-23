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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Referral leaderboard. Shows every referrer's WhatsApp number next to how
 * many people they've referred (contacts.referral grouped/counted server-
 * side data, ranked highest referral count first). See
 * SheetSync.fetchReferralLeaderboard for how the count is derived.
 *
 * Paginated the same way the old Groups screen was: a fixed number of
 * rows per page, with numbered circular page buttons below the list
 * (reusing item_group_page_button.xml / page_button_selected_background
 * / page_button_default_background so the pager looks identical to that
 * screen). Rows within a page are separated by a thin divider line,
 * matching the Profile card's field-row style - no divider after the
 * last row on a page.
 *
 * Two tabs sit above the panel, same segmented-control pattern as
 * IncreaseLimitActivity's "Referral rewards" / "Redeem a key" tabs:
 * "My referrals" shows this user's own referral count + list, and
 * "Leaderboard" shows the ranked list above (search + pager unchanged).
 * Each tab only fetches its data the first time it's opened.
 */
class HistoryActivity : BaseActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var noResultsText: TextView
    private lateinit var historySearchInput: EditText
    private lateinit var dayListContainer: LinearLayout
    private lateinit var historyPagerScroll: HorizontalScrollView
    private lateinit var historyPagerContainer: LinearLayout

    private lateinit var tabMyReferralsButton: Button
    private lateinit var tabLeaderboardButton: Button
    private lateinit var myReferralsPanel: LinearLayout
    private lateinit var leaderboardPanel: LinearLayout
    private lateinit var recentReferralsHeaderRow: LinearLayout

    private lateinit var myReferralsTotalText: TextView
    private lateinit var myReferralsSearchInput: EditText
    private lateinit var myReferralsListContainer: LinearLayout
    private lateinit var myReferralsEmptyText: TextView
    private lateinit var myReferralsNoResultsText: TextView
    private lateinit var myReferralsPagerScroll: HorizontalScrollView
    private lateinit var myReferralsPagerContainer: LinearLayout
    private lateinit var myReferralsBreadcrumb: LinearLayout
    private lateinit var myReferralsProgressBar: ProgressBar
    // Bumped on every My referrals fetch so a slow, superseded response
    // (e.g. user drilled in/out or switched tabs quickly) can't overwrite
    // the list for the request that's actually current.
    private var myReferralsRequestId = 0
    private lateinit var breadcrumbBackButton: ImageView
    private lateinit var breadcrumbPathText: TextView

    private val ENTRIES_PER_PAGE = 5

    private var allEntries: List<ReferralEntry> = emptyList()
    private var filteredEntries: List<ReferralEntry> = emptyList()
    private var currentPage = 0
    private var currentSearchQuery = ""
    private var leaderboardLoaded = false

    private var myReferrals: List<MyReferral> = emptyList()
    private var filteredMyReferrals: List<MyReferral> = emptyList()
    private var myReferralsCurrentPage = 0
    private var myReferralsSearchQuery = ""
    private var myReferralsLoaded = false
    private var myReferralsCounts: Map<String, Int> = emptyMap()

    /** One entry per drill-in level: whose referrals are showing, and the label shown in the breadcrumb for them. */
    private data class ReferralStackEntry(val whatsapp: String, val label: String)
    /** Root of the stack is always the logged-in user (label unused - breadcrumb hides at depth 0). */
    private var referralStack: MutableList<ReferralStackEntry> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        FloatingContactHelper.attach(this)
        BottomNavHelper.setup(this, BottomNavHelper.Tab.HISTORY)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        progressBar = findViewById(R.id.progressBar)
        emptyText = findViewById(R.id.emptyText)
        noResultsText = findViewById(R.id.noResultsText)
        historySearchInput = findViewById(R.id.historySearchInput)
        dayListContainer = findViewById(R.id.dayListContainer)
        historyPagerScroll = findViewById(R.id.historyPagerScroll)
        historyPagerContainer = findViewById(R.id.historyPagerContainer)

        tabMyReferralsButton = findViewById(R.id.tabMyReferralsButton)
        tabLeaderboardButton = findViewById(R.id.tabLeaderboardButton)
        myReferralsPanel = findViewById(R.id.myReferralsPanel)
        leaderboardPanel = findViewById(R.id.leaderboardPanel)

        myReferralsTotalText = findViewById(R.id.myReferralsTotalText)
        recentReferralsHeaderRow = findViewById(R.id.recentReferralsHeaderRow)
        myReferralsSearchInput = findViewById(R.id.myReferralsSearchInput)
        myReferralsListContainer = findViewById(R.id.myReferralsListContainer)
        myReferralsEmptyText = findViewById(R.id.myReferralsEmptyText)
        myReferralsNoResultsText = findViewById(R.id.myReferralsNoResultsText)
        myReferralsPagerScroll = findViewById(R.id.myReferralsPagerScroll)
        myReferralsPagerContainer = findViewById(R.id.myReferralsPagerContainer)
        myReferralsBreadcrumb = findViewById(R.id.myReferralsBreadcrumb)
        myReferralsProgressBar = findViewById(R.id.myReferralsProgressBar)
        breadcrumbBackButton = findViewById(R.id.breadcrumbBackButton)
        breadcrumbPathText = findViewById(R.id.breadcrumbPathText)

        // Whole breadcrumb row is clickable now, not just the small
        // arrow icon - a tiny 20dp icon was too easy to miss/mistap as
        // the only way back. Keeping the icon's own listener too is
        // harmless (same action either way).
        myReferralsBreadcrumb.setOnClickListener { popReferralLevel() }
        breadcrumbBackButton.setOnClickListener { popReferralLevel() }

        historySearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                applySearch()
            }
        })

        myReferralsSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                myReferralsSearchQuery = s?.toString()?.trim() ?: ""
                applyMyReferralsSearch()
            }
        })

        tabMyReferralsButton.setOnClickListener { showMyReferralsTab() }
        tabLeaderboardButton.setOnClickListener { showLeaderboardTab() }

        // Mirrors IncreaseLimitActivity: calling showMyReferralsTab() first
        // both sets the initial active/inactive tab styling and triggers
        // the first load for that tab.
        showMyReferralsTab()
    }

    private fun showMyReferralsTab() {
        myReferralsPanel.visibility = View.VISIBLE
        leaderboardPanel.visibility = View.GONE
        recentReferralsHeaderRow.visibility = View.VISIBLE
        // Both search fields now live in the header (moved up from
        // inside their panels, per request) and are shown/hidden in
        // lockstep with which tab is active, same as the panels
        // themselves.
        myReferralsSearchInput.visibility = View.VISIBLE
        historySearchInput.visibility = View.GONE
        tabMyReferralsButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.white)
        tabMyReferralsButton.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
        tabLeaderboardButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.transparent)
        tabLeaderboardButton.setTextColor(ContextCompat.getColor(this, R.color.white))

        // Reselecting the "My referrals" tab always resets any drill-in
        // depth back to the root list, same as navigating away and back
        // to the app would - avoids leaving the user stranded mid-tree
        // with a hidden breadcrumb if they'd switched tabs while drilled in.
        val wasDrilledIn = referralStack.isNotEmpty()
        referralStack.clear()
        myReferralsCounts = emptyMap()
        updateBreadcrumb()

        if (!myReferralsLoaded) {
            loadMyReferrals()
        } else if (wasDrilledIn) {
            // Was mid-tree when the tab lost focus - myReferrals still
            // holds that drilled-in list, so re-fetch the root list
            // rather than just re-rendering it.
            loadMyReferrals()
        }
    }

    private fun showLeaderboardTab() {
        myReferralsPanel.visibility = View.GONE
        leaderboardPanel.visibility = View.VISIBLE
        recentReferralsHeaderRow.visibility = View.GONE
        myReferralsSearchInput.visibility = View.GONE
        historySearchInput.visibility = View.VISIBLE
        tabLeaderboardButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.white)
        tabLeaderboardButton.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
        tabMyReferralsButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.transparent)
        tabMyReferralsButton.setTextColor(ContextCompat.getColor(this, R.color.white))

        if (!leaderboardLoaded) {
            loadReferralLeaderboard()
        }
    }

    private fun loadMyReferrals() {
        myReferralsListContainer.removeAllViews()
        myReferralsEmptyText.visibility = View.GONE
        myReferralsNoResultsText.visibility = View.GONE
        myReferralsPagerScroll.visibility = View.GONE
        // Root list - breadcrumb is never shown here. Set eagerly so it
        // can't remain visible on screen from a previous drill-in level
        // while this reload is in flight.
        myReferralsBreadcrumb.visibility = View.GONE
        // Show a spinner while the request is in flight, same as the
        // leaderboard does - otherwise the panel just sits blank until
        // the network call returns.
        myReferralsProgressBar.visibility = View.VISIBLE
        val requestId = ++myReferralsRequestId

        // Root level only: this user's own direct referrals. This uses
        // fetchReferralsFor() (a single request) rather than
        // fetchMyReferrals(), because fetchMyReferrals also runs a second
        // sequential request for 2nd-level referrals that this screen
        // immediately discards - drilling into someone's downline is
        // handled by loadReferralsForStack() instead.
        val myWhatsapp = UserPrefs.getWhatsapp(this)
        if (myWhatsapp.isNullOrEmpty()) {
            myReferralsProgressBar.visibility = View.GONE
            myReferralsLoaded = true
            myReferrals = emptyList()
            myReferralsTotalText.text = "0"
            myReferralsEmptyText.visibility = View.VISIBLE
            myReferralsEmptyText.text = "No referrals yet."
            return
        }

        SheetSync.fetchReferralsFor(myWhatsapp) { list, error ->
            runOnUiThread {
                // A newer load (or drill-in) has started since this one -
                // let that one own the UI.
                if (requestId != myReferralsRequestId) return@runOnUiThread
                myReferralsProgressBar.visibility = View.GONE
                if (list != null) {
                    myReferralsLoaded = true
                    myReferrals = list.filter { it.level == 1 }
                    myReferralsTotalText.text = myReferrals.size.toString()
                    myReferralsSearchQuery = ""
                    myReferralsSearchInput.setText("")
                    if (myReferrals.isEmpty()) {
                        myReferralsEmptyText.visibility = View.VISIBLE
                        myReferralsEmptyText.text = "No referrals yet."
                        return@runOnUiThread
                    }
                    applyMyReferralsSearch()
                } else {
                    myReferralsEmptyText.visibility = View.VISIBLE
                    val message = if (error == "NO_INTERNET") {
                        "No internet connection. Check your connection and try again."
                    } else {
                        "Couldn't load your referrals. Please try again."
                    }
                    myReferralsEmptyText.text = message
                    Toast.makeText(this@HistoryActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Loads the direct referrals of whoever is at the top of
     * [referralStack] (used for every drill-in level, depth 1+). Shares
     * applyMyReferralsSearch/renderMyReferralsPage/renderMyReferralsPager
     * with the root list so search and pagination behave identically at
     * any depth.
     */
    private fun loadReferralsForStack() {
        val target = referralStack.last()
        myReferralsListContainer.removeAllViews()
        myReferralsEmptyText.visibility = View.GONE
        myReferralsNoResultsText.visibility = View.GONE
        myReferralsPagerScroll.visibility = View.GONE
        updateBreadcrumb()
        myReferralsProgressBar.visibility = View.VISIBLE
        val requestId = ++myReferralsRequestId

        SheetSync.fetchReferralsFor(target.whatsapp) { list, error ->
            runOnUiThread {
                if (requestId != myReferralsRequestId) return@runOnUiThread
                myReferralsProgressBar.visibility = View.GONE
                if (list != null) {
                    myReferrals = list
                    myReferralsTotalText.text = myReferrals.size.toString()
                    myReferralsSearchQuery = ""
                    myReferralsSearchInput.setText("")
                    if (myReferrals.isEmpty()) {
                        myReferralsEmptyText.visibility = View.VISIBLE
                        myReferralsEmptyText.text = "${target.label} has no referrals yet."
                        return@runOnUiThread
                    }
                    applyMyReferralsSearch()
                } else {
                    myReferralsEmptyText.visibility = View.VISIBLE
                    val message = if (error == "NO_INTERNET") {
                        "No internet connection. Check your connection and try again."
                    } else {
                        "Couldn't load referrals. Please try again."
                    }
                    myReferralsEmptyText.text = message
                    Toast.makeText(this@HistoryActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Tapping a row drills into that referral's own downline, pushing a new breadcrumb level. */
    private fun drillIntoReferral(entry: MyReferral) {
        referralStack.add(ReferralStackEntry(entry.whatsapp, entry.whatsapp))
        loadReferralsForStack()
    }

    /** Breadcrumb back arrow: pops one level, back to the root list if the stack empties out. */
    private fun popReferralLevel() {
        if (referralStack.isEmpty()) return
        referralStack.removeAt(referralStack.lastIndex)
        // Hide the breadcrumb row immediately rather than waiting for the
        // async reload below to finish - otherwise, on a slow connection,
        // the old breadcrumb can stay visible/overlapping on screen for a
        // moment after tapping back, on top of the root list's own
        // "RECENT REFERRALS" header row.
        updateBreadcrumb()
        if (referralStack.isEmpty()) {
            loadMyReferrals()
        } else {
            loadReferralsForStack()
        }
    }

    /** Shows/hides and fills the drill-in breadcrumb pill based on current drill depth. */
    private fun updateBreadcrumb() {
        // "RECENT REFERRALS" + the total-referrals pill only describe the
        // current user's own list - hide them at any drilled-in depth,
        // where the list shown belongs to someone else's downline
        // instead. Runs on every drill-in/drill-out transition since
        // this function is already called from both directions
        // (drillIntoReferral -> loadReferralsForStack, and
        // popReferralLevel).
        recentReferralsHeaderRow.visibility = if (referralStack.isEmpty()) View.VISIBLE else View.GONE

        if (referralStack.isEmpty()) {
            myReferralsBreadcrumb.visibility = View.GONE
            return
        }
        myReferralsBreadcrumb.visibility = View.VISIBLE
        // "<name>'s referrals" instead of a "You > A > B" trail - simpler
        // to read at a glance, and doesn't require understanding what a
        // breadcrumb trail even is. Only the deepest level's label is
        // shown; popReferralLevel() still walks back one level at a time
        // even though this text doesn't spell out the full path.
        breadcrumbPathText.text = "${referralStack.last().label}'s referrals"
    }

    /**
     * Filters [myReferrals] by [myReferralsSearchQuery] (matched against
     * the WhatsApp number, same approach as [applySearch]), resets to
     * page 0, and re-renders the list and pager.
     */
    private fun applyMyReferralsSearch() {
        myReferralsCurrentPage = 0

        filteredMyReferrals = if (myReferralsSearchQuery.isEmpty()) {
            myReferrals
        } else {
            myReferrals.filter { it.whatsapp.contains(myReferralsSearchQuery, ignoreCase = true) }
        }

        if (filteredMyReferrals.isEmpty() && myReferralsSearchQuery.isNotEmpty()) {
            myReferralsListContainer.removeAllViews()
            myReferralsPagerScroll.visibility = View.GONE
            myReferralsNoResultsText.visibility = View.VISIBLE
            myReferralsNoResultsText.text = "No referrals match \u201c$myReferralsSearchQuery\u201d"
            return
        }

        myReferralsNoResultsText.visibility = View.GONE
        if (referralStack.isEmpty()) {
            // Wait for invite counts before rendering anything, rather
            // than painting rows with "no invites yet" first and then
            // silently swapping in the real counts (and the chevron/nudge
            // icon that depend on them) a moment later once the counts
            // finish loading. That two-pass render made rows visibly
            // change out from under the user right after the screen
            // opened.
            loadCountsForCurrentPage()
        } else {
            renderMyReferralsPage()
            renderMyReferralsPager()
        }
    }

    /**
     * Fetches invite counts for just the numbers on [myReferralsCurrentPage]
     * (one batched request via fetchReferralCountsFor) and re-renders the
     * page once they arrive, so counts appear without slowing down the
     * initial row render.
     */
    private fun loadCountsForCurrentPage() {
        val start = myReferralsCurrentPage * ENTRIES_PER_PAGE
        val end = minOf(start + ENTRIES_PER_PAGE, filteredMyReferrals.size)
        if (start >= filteredMyReferrals.size) {
            renderMyReferralsPage()
            renderMyReferralsPager()
            return
        }
        val pageNumbers = filteredMyReferrals.subList(start, end).map { it.whatsapp }

        SheetSync.fetchReferralCountsFor(pageNumbers) { counts, _ ->
            runOnUiThread {
                // Fall back to an empty count map on failure rather than
                // leaving the screen stuck with nothing rendered - rows
                // will just show "no invites yet" until the next reload,
                // same as any other count that failed to load, instead of
                // the list never appearing at all.
                myReferralsCounts = counts ?: emptyMap()
                renderMyReferralsPage()
                renderMyReferralsPager()
            }
        }
    }

    /**
     * Converts a Nigerian phone number (local "0..." or already-international
     * "234...") into the bare international digits wa.me expects, e.g.
     * "08108709629" -> "2348108709629". Same digit-stripping approach as
     * SheetSync.normalizePhone, but re-prefixed with "234" instead of bare
     * local digits, since wa.me requires the full international number.
     */
    private fun toWhatsAppNumber(raw: String): String {
        var digits = raw.filter { it.isDigit() }
        if (digits.startsWith("234")) {
            return digits
        }
        digits = digits.removePrefix("0")
        return "234$digits"
    }

    /** Opens WhatsApp to [number] with a pre-filled nudge message, same intent pattern as ProfileActivity.openWhatsAppContactUs. */
    private fun openWhatsAppNudge(number: String) {
        val message = Uri.encode("Hi \uD83D\uDE42 you registered under me on VGKONTACT")
        val uri = Uri.parse("https://wa.me/${toWhatsAppNumber(number)}?text=$message")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    /** Renders just the rows for [myReferralsCurrentPage], each with a bottom divider except the last. */
    private fun renderMyReferralsPage() {
        myReferralsListContainer.removeAllViews()

        val start = myReferralsCurrentPage * ENTRIES_PER_PAGE
        val end = minOf(start + ENTRIES_PER_PAGE, filteredMyReferrals.size)
        if (start >= filteredMyReferrals.size) return

        val pageEntries = filteredMyReferrals.subList(start, end)

        for ((index, entry) in pageEntries.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val textRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 18, 0, 18)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Avatar circle - purely decorative (no photo data available),
            // matches Option 1's row style.
            val avatarSizePx = (34 * resources.displayMetrics.density).toInt()
            val avatar = ImageView(this).apply {
                setImageResource(R.drawable.ic_profile)
                setColorFilter(ContextCompat.getColor(this@HistoryActivity, R.color.vg_green))
                background = ContextCompat.getDrawable(this@HistoryActivity, R.drawable.avatar_circle_tint_background)
                val paddingPx = (7 * resources.displayMetrics.density).toInt()
                setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                layoutParams = LinearLayout.LayoutParams(avatarSizePx, avatarSizePx)
            }

            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val marginPx = (12 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = marginPx
                }
            }

            val numberRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val numberView = TextView(this).apply {
                text = entry.whatsapp
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.vg_dark))
            }
            numberRow.addView(numberView)

            val timeView = TextView(this).apply {
                text = formatRelativeTime(entry.createdAt)
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_muted))
            }

            textColumn.addView(numberRow)
            textColumn.addView(timeView)

            textRow.addView(avatar)
            textRow.addView(textColumn)

            // Invite count pill (e.g. "18 invited") - filled in once
            // loadCountsForCurrentPage's batched fetch returns; shows
            // "no invites yet" when the count is 0/unknown, matching the
            // drill-in mockup. Only shown at root depth (referralStack
            // empty) - a nudge icon replaces it one level deeper, since
            // you're already looking at that referral's own downline
            // there and don't need their count restated.
            if (referralStack.isEmpty()) {
                val count = myReferralsCounts[entry.whatsapp] ?: 0

                // Trailing pill - wraps the nudge icon, invite-count text,
                // and (when tappable) the chevron all inside one rounded
                // container, so they read as a single control rather than
                // three separate items floating on the row.
                val trailingPillPaddingH = (12 * resources.displayMetrics.density).toInt()
                val trailingPillPaddingV = (6 * resources.displayMetrics.density).toInt()
                val trailingPill = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = ContextCompat.getDrawable(this@HistoryActivity, R.drawable.referral_number_pill_background)
                    setPadding(trailingPillPaddingH, trailingPillPaddingV, trailingPillPaddingH, trailingPillPaddingV)
                }

                // WhatsApp nudge icon - placed first, before the invite
                // count. Was previously only shown one level deep into a
                // drill-in. Added here too so root-list rows can always
                // message a referral, regardless of whether they've
                // invited anyone yet. Its own click listener + isClickable,
                // separate from the row's drill-in listener below, so
                // tapping the icon doesn't also trigger navigation into
                // the row.
                val iconSizePx = (18 * resources.displayMetrics.density).toInt()
                val nudgeIcon = ImageView(this).apply {
                    setImageResource(R.drawable.ic_chat)
                    setColorFilter(ContextCompat.getColor(this@HistoryActivity, R.color.vg_green))
                    contentDescription = "Message ${entry.whatsapp} on WhatsApp"
                    layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    isClickable = true
                    isFocusable = true
                    val outValue = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    setOnClickListener { openWhatsAppNudge(entry.whatsapp) }
                }
                trailingPill.addView(nudgeIcon)

                val countMarginPx = (8 * resources.displayMetrics.density).toInt()
                val countView = TextView(this).apply {
                    text = if (count > 0) "$count invited" else "no invites yet"
                    textSize = 12f
                    setTextColor(
                        ContextCompat.getColor(
                            this@HistoryActivity,
                            if (count > 0) R.color.vg_green else R.color.text_muted
                        )
                    )
                    if (count > 0) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = countMarginPx
                    }
                }
                trailingPill.addView(countView)

                // Only rows with at least one invite are worth drilling
                // into - matches the mockup, where only Ravi (18 invited)
                // is tappable and Amara (no invites yet) is not.
                if (count > 0) {
                    row.setOnClickListener { drillIntoReferral(entry) }
                    row.isClickable = true
                    row.isFocusable = true
                    // Ripple feedback on tap, same as any other clickable
                    // row in the app.
                    val outValue = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    row.setBackgroundResource(outValue.resourceId)

                    // Trailing chevron - the only thing on this row that
                    // actually signals "tap to see who they referred".
                    // Before this, a tappable row looked identical to a
                    // non-tappable one except for a green vs grey count
                    // label, which isn't a strong enough visual cue that
                    // the row leads somewhere. Kept inside the same pill
                    // as the icon and count.
                    val chevronSizePx = (16 * resources.displayMetrics.density).toInt()
                    val chevronMarginPx = (6 * resources.displayMetrics.density).toInt()
                    val chevron = ImageView(this).apply {
                        setImageResource(R.drawable.ic_chevron_right)
                        setColorFilter(ContextCompat.getColor(this@HistoryActivity, R.color.vg_green))
                        contentDescription = null
                        layoutParams = LinearLayout.LayoutParams(chevronSizePx, chevronSizePx).apply {
                            marginStart = chevronMarginPx
                        }
                    }
                    trailingPill.addView(chevron)
                }

                textRow.addView(trailingPill)
            } else {
                // WhatsApp nudge icon - opens a chat to this referral's
                // number with a pre-filled follow-up message. Also shown
                // on root-list rows now (see block above); duplicated
                // here rather than shared since the two branches build
                // very different rows (count pill + chevron vs none of
                // that one level deep).
                val iconSizePx = (24 * resources.displayMetrics.density).toInt()
                val iconMarginPx = (12 * resources.displayMetrics.density).toInt()
                val nudgeIcon = ImageView(this).apply {
                    setImageResource(R.drawable.ic_chat)
                    setColorFilter(ContextCompat.getColor(this@HistoryActivity, R.color.vg_green))
                    contentDescription = "Message ${entry.whatsapp} on WhatsApp"
                    layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                        marginStart = iconMarginPx
                    }
                    setOnClickListener { openWhatsAppNudge(entry.whatsapp) }
                }
                textRow.addView(nudgeIcon)
            }

            row.addView(textRow)

            // Skip the divider after the last row on this page.
            if (index != pageEntries.lastIndex) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(ContextCompat.getColor(this@HistoryActivity, R.color.stats_card_border))
                }
                row.addView(divider)
            }

            myReferralsListContainer.addView(row)
        }
    }

    /** Builds the numbered page row below the "My referrals" list, mirroring [renderPager]. */
    private fun renderMyReferralsPager() {
        val pageCount = (filteredMyReferrals.size + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE

        if (pageCount <= 1) {
            myReferralsPagerScroll.visibility = View.GONE
            return
        }

        myReferralsPagerScroll.visibility = View.VISIBLE
        myReferralsPagerContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (pageIndex in 0 until pageCount) {
            val pageButton = inflater.inflate(R.layout.item_group_page_button, myReferralsPagerContainer, false) as TextView
            pageButton.text = (pageIndex + 1).toString()
            pageButton.setOnClickListener {
                if (myReferralsCurrentPage != pageIndex) {
                    myReferralsCurrentPage = pageIndex
                    updateMyReferralsPagerSelection()
                    if (referralStack.isEmpty()) {
                        // Same reasoning as applyMyReferralsSearch(): wait
                        // for the new page's counts before rendering it,
                        // rather than painting stale/empty counts from
                        // the previous page for a moment first.
                        loadCountsForCurrentPage()
                    } else {
                        renderMyReferralsPage()
                    }
                }
            }
            myReferralsPagerContainer.addView(pageButton)
            // Inflated after setContentView() already ran, so
            // BaseActivity's one-time font pass never reaches it.
            FontHelper.applyPoppinsAsync(this, pageButton)
        }

        updateMyReferralsPagerSelection()
    }

    /** Re-styles every "My referrals" page button so only myReferralsCurrentPage shows as selected. */
    private fun updateMyReferralsPagerSelection() {
        for (i in 0 until myReferralsPagerContainer.childCount) {
            val pageButton = myReferralsPagerContainer.getChildAt(i) as TextView
            val isSelected = i == myReferralsCurrentPage
            pageButton.setBackgroundResource(
                if (isSelected) R.drawable.page_button_selected_background else R.drawable.page_button_default_background
            )
            pageButton.setTextColor(
                ContextCompat.getColor(this, if (isSelected) R.color.white else R.color.vg_dark)
            )
        }
    }

    /**
     * Formats a Postgres/PostgREST timestamp (e.g. "2026-09-05T14:32:10")
     * as a short relative label like "2 days ago", "Today", or "Yesterday".
     * Falls back to the raw string if it can't be parsed.
     */
    private fun formatRelativeTime(createdAt: String): String {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss"
        )
        var parsedDate: Date? = null
        for (pattern in patterns) {
            try {
                val cleaned = createdAt.substringBefore("+").substringBefore("Z")
                parsedDate = SimpleDateFormat(pattern, Locale.US).parse(cleaned)
                if (parsedDate != null) break
            } catch (e: Exception) {
                // try next pattern
            }
        }
        val date = parsedDate ?: return createdAt

        val diffMs = Date().time - date.time
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        return when {
            days <= 0 -> {
                val timeFormat = SimpleDateFormat("h:mm a", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("Africa/Lagos")
                }
                "Today at ${timeFormat.format(date)}"
            }
            days == 1L -> "Yesterday"
            else -> "$days days ago"
        }
    }

    private fun loadReferralLeaderboard() {
        progressBar.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        dayListContainer.removeAllViews()
        historyPagerScroll.visibility = View.GONE

        SheetSync.fetchReferralLeaderboard(this) { list, error ->
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (list != null) {
                    leaderboardLoaded = true
                    if (list.isEmpty()) {
                        emptyText.visibility = View.VISIBLE
                        emptyText.text = "No referrals yet."
                        return@runOnUiThread
                    }
                    allEntries = list
                    currentPage = 0
                    currentSearchQuery = ""
                    historySearchInput.setText("")
                    applySearch()
                } else {
                    emptyText.visibility = View.VISIBLE
                    val message = if (error == "NO_INTERNET") {
                        "No internet connection. Check your connection and try again."
                    } else {
                        "Couldn't load referral history. Please try again."
                    }
                    emptyText.text = message
                    Toast.makeText(this@HistoryActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Filters [allEntries] by [currentSearchQuery] (matched against the
     * WhatsApp number, same approach as GroupsActivity.applySearch),
     * resets to page 0, and re-renders the list and pager.
     */
    private fun applySearch() {
        currentPage = 0

        filteredEntries = if (currentSearchQuery.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { it.whatsapp.contains(currentSearchQuery, ignoreCase = true) }
        }

        if (filteredEntries.isEmpty() && currentSearchQuery.isNotEmpty()) {
            dayListContainer.removeAllViews()
            historyPagerScroll.visibility = View.GONE
            noResultsText.visibility = View.VISIBLE
            noResultsText.text = "No referrals match \u201c$currentSearchQuery\u201d"
            return
        }

        noResultsText.visibility = View.GONE
        renderCurrentPage()
        renderPager()
    }

    /** Renders just the rows for [currentPage], each with a bottom divider except the last. */
    private fun renderCurrentPage() {
        dayListContainer.removeAllViews()

        val start = currentPage * ENTRIES_PER_PAGE
        val end = minOf(start + ENTRIES_PER_PAGE, filteredEntries.size)
        if (start >= filteredEntries.size) return

        val pageEntries = filteredEntries.subList(start, end)

        for ((index, entry) in pageEntries.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val textRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 26, 0, 26)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val numberView = TextView(this).apply {
                text = entry.whatsapp
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.vg_dark))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val countView = TextView(this).apply {
                text = entry.referralCount.toString()
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.vg_green))
            }

            textRow.addView(numberView)
            textRow.addView(countView)
            row.addView(textRow)

            // Skip the divider after the last row on this page.
            if (index != pageEntries.lastIndex) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(ContextCompat.getColor(this@HistoryActivity, R.color.stats_card_border))
                }
                row.addView(divider)
            }

            dayListContainer.addView(row)
        }
    }

    /**
     * Builds the numbered page row (1, 2, 3...) below the leaderboard,
     * identical pattern to the old GroupsActivity pager. Hidden entirely
     * when everything fits on one page.
     */
    private fun renderPager() {
        val pageCount = (filteredEntries.size + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE

        if (pageCount <= 1) {
            historyPagerScroll.visibility = View.GONE
            return
        }

        historyPagerScroll.visibility = View.VISIBLE
        historyPagerContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (pageIndex in 0 until pageCount) {
            val pageButton = inflater.inflate(R.layout.item_group_page_button, historyPagerContainer, false) as TextView
            pageButton.text = (pageIndex + 1).toString()
            pageButton.setOnClickListener {
                if (currentPage != pageIndex) {
                    currentPage = pageIndex
                    renderCurrentPage()
                    updatePagerSelection()
                }
            }
            historyPagerContainer.addView(pageButton)
            // Inflated after setContentView() already ran, so
            // BaseActivity's one-time font pass never reaches it.
            FontHelper.applyPoppinsAsync(this, pageButton)
        }

        updatePagerSelection()
    }

    /** Re-styles every page button so only currentPage shows as selected. */
    private fun updatePagerSelection() {
        for (i in 0 until historyPagerContainer.childCount) {
            val pageButton = historyPagerContainer.getChildAt(i) as TextView
            val isSelected = i == currentPage
            pageButton.setBackgroundResource(
                if (isSelected) R.drawable.page_button_selected_background else R.drawable.page_button_default_background
            )
            pageButton.setTextColor(
                ContextCompat.getColor(this, if (isSelected) R.color.white else R.color.vg_dark)
            )
        }
    }
}
