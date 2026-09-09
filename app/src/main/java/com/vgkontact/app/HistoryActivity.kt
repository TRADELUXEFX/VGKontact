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
class HistoryActivity : AppCompatActivity() {

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
    private lateinit var headerTotalPill: LinearLayout

    private lateinit var myReferralsTotalText: TextView
    private lateinit var myReferralsSearchInput: EditText
    private lateinit var myReferralsListContainer: LinearLayout
    private lateinit var myReferralsEmptyText: TextView
    private lateinit var myReferralsNoResultsText: TextView
    private lateinit var myReferralsPagerScroll: HorizontalScrollView
    private lateinit var myReferralsPagerContainer: LinearLayout

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
        headerTotalPill = findViewById(R.id.headerTotalPill)
        myReferralsSearchInput = findViewById(R.id.myReferralsSearchInput)
        myReferralsListContainer = findViewById(R.id.myReferralsListContainer)
        myReferralsEmptyText = findViewById(R.id.myReferralsEmptyText)
        myReferralsNoResultsText = findViewById(R.id.myReferralsNoResultsText)
        myReferralsPagerScroll = findViewById(R.id.myReferralsPagerScroll)
        myReferralsPagerContainer = findViewById(R.id.myReferralsPagerContainer)

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
        headerTotalPill.visibility = View.VISIBLE
        tabMyReferralsButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.white)
        tabMyReferralsButton.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
        tabLeaderboardButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.transparent)
        tabLeaderboardButton.setTextColor(ContextCompat.getColor(this, R.color.text_muted))

        if (!myReferralsLoaded) {
            loadMyReferrals()
        }
    }

    private fun showLeaderboardTab() {
        myReferralsPanel.visibility = View.GONE
        leaderboardPanel.visibility = View.VISIBLE
        headerTotalPill.visibility = View.GONE
        tabLeaderboardButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.white)
        tabLeaderboardButton.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
        tabMyReferralsButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.transparent)
        tabMyReferralsButton.setTextColor(ContextCompat.getColor(this, R.color.text_muted))

        if (!leaderboardLoaded) {
            loadReferralLeaderboard()
        }
    }

    private fun loadMyReferrals() {
        myReferralsListContainer.removeAllViews()
        myReferralsEmptyText.visibility = View.GONE
        myReferralsNoResultsText.visibility = View.GONE
        myReferralsPagerScroll.visibility = View.GONE

        SheetSync.fetchMyReferrals(this) { list, error ->
            runOnUiThread {
                if (list != null) {
                    myReferralsLoaded = true
                    myReferrals = list
                    myReferralsTotalText.text = list.size.toString()
                    myReferralsSearchQuery = ""
                    myReferralsSearchInput.setText("")
                    if (list.isEmpty()) {
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
        renderMyReferralsPage()
        renderMyReferralsPager()
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

            val numberView = TextView(this).apply {
                text = entry.whatsapp
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.vg_dark))
            }

            val timeView = TextView(this).apply {
                text = formatRelativeTime(entry.createdAt)
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_muted))
            }

            textColumn.addView(numberView)
            textColumn.addView(timeView)

            // WhatsApp nudge icon - opens a chat to this referral's
            // number with a pre-filled follow-up message.
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

            textRow.addView(avatar)
            textRow.addView(textColumn)
            textRow.addView(nudgeIcon)
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
                    renderMyReferralsPage()
                    updateMyReferralsPagerSelection()
                }
            }
            myReferralsPagerContainer.addView(pageButton)
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
