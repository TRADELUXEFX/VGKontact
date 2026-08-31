package com.vgkontact.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

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
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var noResultsText: TextView
    private lateinit var historySearchInput: EditText
    private lateinit var dayListContainer: LinearLayout
    private lateinit var historyPagerScroll: HorizontalScrollView
    private lateinit var historyPagerContainer: LinearLayout

    private val ENTRIES_PER_PAGE = 10

    private var allEntries: List<ReferralEntry> = emptyList()
    private var filteredEntries: List<ReferralEntry> = emptyList()
    private var currentPage = 0
    private var currentSearchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        BottomNavHelper.setup(this, BottomNavHelper.Tab.HISTORY)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        progressBar = findViewById(R.id.progressBar)
        emptyText = findViewById(R.id.emptyText)
        noResultsText = findViewById(R.id.noResultsText)
        historySearchInput = findViewById(R.id.historySearchInput)
        dayListContainer = findViewById(R.id.dayListContainer)
        historyPagerScroll = findViewById(R.id.historyPagerScroll)
        historyPagerContainer = findViewById(R.id.historyPagerContainer)

        historySearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                applySearch()
            }
        })

        loadReferralLeaderboard()
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
