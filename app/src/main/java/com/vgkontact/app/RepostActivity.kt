package com.vgkontact.app

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Repost & Earn screen, opened from the floating Repost button
 * (FloatingRepostHelper).
 *
 * Two tabs, same segmented-switcher pattern as HistoryActivity:
 *  - My streak: daily streak, last-7-days dots, today's task + button,
 *    milestone tiles (3 / 7 / 14 / 30 days).
 *  - Leaderboard: ranked list of reposters.
 *
 * REPOST FLOW: tapping the button opens WhatsApp to the admin with a
 * prefilled "I've reposted today's post (date)" message - the same
 * hand-off RepostRedirectActivity does for the notification - and
 * counts today's repost immediately (tap = counted, per product
 * decision). Nothing here verifies the user really reposted.
 *
 * DATA: Supabase is the source of truth (see RepostSync and
 * repost_supabase.sql). RepostPrefs is an on-device copy so the screen
 * shows something instantly and still works offline. A tap made offline
 * is flagged "pending" and re-sent on the next sync; the database keeps
 * one row per user per day, so retrying can never double count.
 *
 * The leaderboard shows other people's numbers MASKED by the server
 * (0803 *** 9087). If the server can't be reached it falls back to just
 * the user's own row, with a note saying so.
 */
class RepostActivity : BaseActivity() {

    private lateinit var backButton: ImageView
    private lateinit var tabMyStreakButton: Button
    private lateinit var tabBoardButton: Button
    private lateinit var myStreakPanel: View
    private lateinit var leaderboardPanel: View

    private lateinit var streakNumberText: TextView
    private lateinit var streakSubText: TextView
    private lateinit var totalRepostsText: TextView
    private lateinit var weekRow: LinearLayout
    private lateinit var repostNowButton: Button
    private lateinit var nextMilestonePill: TextView
    private lateinit var milestoneProgress: ProgressBar
    private lateinit var milestoneRow: LinearLayout

    private lateinit var boardProgress: ProgressBar
    private lateinit var boardListContainer: LinearLayout
    private lateinit var boardPagerScroll: View
    private lateinit var boardPagerContainer: LinearLayout
    private lateinit var boardNoteText: TextView

    /** One row on the leaderboard. */
    private data class BoardRow(
        /** Real leaderboard rank (1 = top), as ranked by the server. */
        val rank: Int,
        val label: String,
        val reposts: Int,
        val streak: Int,
        val isMe: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_repost)
        // Floating chat/repost buttons appear on every screen. On this
        // screen the repost button would just reopen itself, so only the
        // contact button is wanted - FloatingContactHelper skips the
        // repost FAB when told this is the repost screen.
        FloatingContactHelper.attach(this, bottomMarginDp = 0, showRepostFab = false)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        backButton = findViewById(R.id.repostBackButton)
        tabMyStreakButton = findViewById(R.id.tabMyStreakButton)
        tabBoardButton = findViewById(R.id.tabBoardButton)
        myStreakPanel = findViewById(R.id.myStreakPanel)
        leaderboardPanel = findViewById(R.id.leaderboardPanel)

        streakNumberText = findViewById(R.id.streakNumberText)
        streakSubText = findViewById(R.id.streakSubText)
        totalRepostsText = findViewById(R.id.totalRepostsText)
        weekRow = findViewById(R.id.weekRow)
        repostNowButton = findViewById(R.id.repostNowButton)
        nextMilestonePill = findViewById(R.id.nextMilestonePill)
        milestoneProgress = findViewById(R.id.milestoneProgress)
        milestoneRow = findViewById(R.id.milestoneRow)

        boardProgress = findViewById(R.id.boardProgress)
        boardListContainer = findViewById(R.id.boardListContainer)
        boardPagerScroll = findViewById(R.id.boardPagerScroll)
        boardPagerContainer = findViewById(R.id.boardPagerContainer)
        boardNoteText = findViewById(R.id.boardNoteText)

        backButton.setOnClickListener { finish() }
        tabMyStreakButton.setOnClickListener { showTab(showBoard = false) }
        tabBoardButton.setOnClickListener { showTab(showBoard = true) }
        repostNowButton.setOnClickListener { onRepostTapped() }

        showTab(showBoard = false)
        renderStreakTab()
    }

    override fun onResume() {
        super.onResume()
        // Streak can roll over to a new day while the app sits in the
        // background, so re-render whenever we come back to the screen.
        // Show the local copy instantly, then refresh from the server.
        renderStreakTab()
        syncStatsFromServer()
        if (leaderboardPanel.visibility == View.VISIBLE) loadLeaderboardFromServer()
    }

    /**
     * Pulls the user's real numbers from Supabase, saves them into the
     * local copy (RepostPrefs), and redraws. If the server can't be
     * reached nothing changes - the screen keeps showing the local copy.
     */
    private fun syncStatsFromServer() {
        // If an earlier tap never reached the server, send it now. Safe to
        // repeat: the database keeps one row per user per day.
        if (RepostPrefs.hasPendingUploadToday(this)) {
            RepostSync.recordRepost(this) { stats ->
                if (stats != null) {
                    RepostPrefs.clearPendingUpload(this)
                    fetchAndApplyStats()
                }
            }
            return
        }
        fetchAndApplyStats()
    }

    private fun fetchAndApplyStats() {
        RepostSync.fetchMyStats(this) { stats ->
            if (stats == null) return@fetchMyStats
            RepostPrefs.saveServerStats(
                context = this,
                streak = stats.streak,
                total = stats.totalReposts,
                best = stats.bestStreak,
                todayIfDone = stats.doneToday,
                recentDates = stats.recentDates
            )
            runOnUiThread { if (!isFinishing) renderStreakTab() }
        }
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    private fun showTab(showBoard: Boolean) {
        myStreakPanel.visibility = if (showBoard) View.GONE else View.VISIBLE
        leaderboardPanel.visibility = if (showBoard) View.VISIBLE else View.GONE
        styleTab(tabMyStreakButton, active = !showBoard)
        styleTab(tabBoardButton, active = showBoard)
        if (showBoard) {
            renderLeaderboard()          // local fallback shows instantly
            loadLeaderboardFromServer()  // then real data replaces it
        }
    }

    /**
     * Same styling HistoryActivity.showLeaderboardTab() uses: active tab
     * is tinted white with green text, inactive is transparent with
     * white text.
     */
    private fun styleTab(button: Button, active: Boolean) {
        if (active) {
            button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.white)
            button.setTextColor(ContextCompat.getColor(this, R.color.vg_green))
        } else {
            button.backgroundTintList =
                ContextCompat.getColorStateList(this, android.R.color.transparent)
            button.setTextColor(ContextCompat.getColor(this, R.color.white))
        }
    }

    // ------------------------------------------------------------------
    // Repost action
    // ------------------------------------------------------------------

    private fun onRepostTapped() {
        if (RepostPrefs.hasRepostedToday(this)) {
            // Already counted today: do NOT count again, but still open
            // WhatsApp so the user can retry if the first attempt never
            // got there (slow network, WhatsApp didn't open, etc).
            // If the earlier count never reached the server, this is also
            // a good moment to resend it (safe: one row per user per day).
            if (RepostPrefs.hasPendingUploadToday(this)) syncStatsFromServer()
            openWhatsAppToAdmin()
            return
        }

        // Count locally FIRST so the button flips instantly and the
        // repost is never lost if WhatsApp is slow or the network is down.
        val milestone = RepostPrefs.recordRepostToday(this)
        renderStreakTab()

        // Then tell the server. The database allows one row per user per
        // day, so a retry or double tap can never double count. If this
        // call fails (offline), the next syncStatsFromServer() in
        // onResume reconciles - see the note in RepostPrefs.saveServerStats.
        RepostPrefs.markPendingUpload(this)
        RepostSync.recordRepost(this) { stats ->
            // Failed (offline etc): leave the pending flag set so the
            // next syncStatsFromServer() retries it.
            if (stats == null) return@recordRepost
            RepostPrefs.clearPendingUpload(this)
            RepostPrefs.saveServerStats(
                context = this,
                streak = stats.streak,
                total = stats.totalReposts,
                best = stats.bestStreak,
                todayIfDone = true,
                recentDates = emptySet()
            )
            // record_repost doesn't return the week dots; fetch them.
            syncStatsFromServer()
        }

        openWhatsAppToAdmin()

        if (milestone != null) {
            Toast.makeText(
                this,
                getString(R.string.repost_milestone_unlocked, milestone),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openWhatsAppToAdmin() {
        val today = SimpleDateFormat("MMM d", Locale.US).format(Date())
        val message = Uri.encode("I've reposted today's post ($today)")
        val uri = Uri.parse("https://wa.me/$ADMIN_WHATSAPP_NUMBER?text=$message")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // My streak tab
    // ------------------------------------------------------------------

    private fun renderStreakTab() {
        val streak = RepostPrefs.getCurrentStreak(this)
        val doneToday = RepostPrefs.hasRepostedToday(this)

        streakNumberText.text = streak.toString()
        totalRepostsText.text = RepostPrefs.getTotalReposts(this).toString()
        streakSubText.text = getString(
            if (doneToday) R.string.repost_sub_done else R.string.repost_sub_pending
        )

        renderRepostButton(doneToday)
        renderWeekDots(doneToday)
        renderMilestones(streak)

        // Keep the floating button's "pending" badge in sync if it is
        // showing on this screen's parent stack.
        FloatingRepostHelper.refreshBadge(this)
    }

    private fun renderRepostButton(doneToday: Boolean) {
        if (doneToday) {
            repostNowButton.text = getString(R.string.btn_repost_done)
            repostNowButton.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.vg_green_tint)
            repostNowButton.setTextColor(ContextCompat.getColor(this, R.color.vg_green_dark))
        } else {
            repostNowButton.text = getString(R.string.btn_repost_now)
            repostNowButton.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.vg_green)
            repostNowButton.setTextColor(ContextCompat.getColor(this, R.color.white))
        }
    }

    /** Last 7 calendar days ending today, oldest on the left. */
    private fun renderWeekDots(doneToday: Boolean) {
        weekRow.removeAllViews()
        val dates = RepostPrefs.getRepostDates(this)
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val letterFmt = SimpleDateFormat("EEEEE", Locale.getDefault()) // single letter
        val density = resources.displayMetrics.density

        for (offset in 6 downTo 0) {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -offset) }
            val key = dayFmt.format(cal.time)
            val isToday = offset == 0
            val done = key in dates

            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val dot = ImageView(this).apply {
                val size = (32 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                val pad = (7 * density).toInt()
                setPadding(pad, pad, pad, pad)
                when {
                    done -> {
                        setBackgroundResource(R.drawable.repost_dot_on)
                        setImageResource(R.drawable.ic_check)
                        setColorFilter(ContextCompat.getColor(this@RepostActivity, R.color.white))
                    }
                    isToday -> setBackgroundResource(R.drawable.repost_dot_today)
                    else -> setBackgroundResource(R.drawable.repost_dot_off)
                }
            }

            val letter = TextView(this).apply {
                text = letterFmt.format(cal.time)
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@RepostActivity, R.color.text_muted))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            }

            cell.addView(dot)
            cell.addView(letter)
            weekRow.addView(cell)
        }
    }

    private fun renderMilestones(streak: Int) {
        milestoneRow.removeAllViews()
        val reached = RepostPrefs.getReachedMilestones(this)
        val next = RepostPrefs.nextMilestone(streak)
        val density = resources.displayMetrics.density

        // Progress bar + pill toward the next milestone
        if (next == null) {
            milestoneProgress.progress = 100
            nextMilestonePill.text = getString(R.string.repost_all_milestones)
        } else {
            milestoneProgress.progress = ((streak.toFloat() / next) * 100).toInt().coerceIn(0, 100)
            val left = next - streak
            nextMilestonePill.text = resources.getQuantityString(
                R.plurals.repost_days_to_go, left, left
            )
        }

        RepostPrefs.MILESTONES.forEachIndexed { index, days ->
            val isReached = days in reached || streak >= days
            val isNext = !isReached && days == next

            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
                setBackgroundResource(
                    when {
                        isReached -> R.drawable.repost_milestone_reached
                        isNext -> R.drawable.repost_milestone_next
                        else -> R.drawable.repost_milestone_default
                    }
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = (8 * density).toInt()
                }
            }

            val accent = ContextCompat.getColor(
                this,
                when {
                    isReached -> R.color.vg_green_dark
                    isNext -> R.color.vg_green
                    else -> R.color.text_muted
                }
            )

            tile.addView(TextView(this).apply {
                text = days.toString()
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent)
                gravity = Gravity.CENTER
            })
            tile.addView(TextView(this).apply {
                text = getString(R.string.repost_days_label)
                textSize = 10f
                isAllCaps = true
                letterSpacing = 0.04f
                setTextColor(accent)
                gravity = Gravity.CENTER
            })
            if (isReached) {
                val unlockedRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, (6 * density).toInt(), 0, 0)
                }
                val checkSize = (10 * density).toInt()
                unlockedRow.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(accent)
                    layoutParams = LinearLayout.LayoutParams(checkSize, checkSize)
                })
                unlockedRow.addView(TextView(this).apply {
                    text = getString(R.string.repost_unlocked)
                    textSize = 9f
                    setTextColor(accent)
                    setPadding((2 * density).toInt(), 0, 0, 0)
                })
                tile.addView(unlockedRow)
            }

            milestoneRow.addView(tile)
        }
    }

    // ------------------------------------------------------------------
    // Leaderboard tab
    // ------------------------------------------------------------------

    /** Rows currently shown; replaced when the server answers. */
    private var boardRows: List<BoardRow> = emptyList()

    /** Which leaderboard page is showing (0 = first). */
    private var boardPage = 0

    /** True once a real server response has been received this session. */
    private var boardFromServer = false

    /**
     * Local fallback: just the current user, from the on-device copy.
     * Shown instantly and whenever the server can't be reached.
     */
    private fun localOnlyBoard(): List<BoardRow> = listOf(
        BoardRow(
            rank = 1,
            label = getString(R.string.repost_you),
            reposts = RepostPrefs.getTotalReposts(this),
            streak = RepostPrefs.getCurrentStreak(this),
            isMe = true
        )
    )

    /**
     * Fetches the real leaderboard from Supabase. The server masks other
     * people's numbers (0803 *** 9087) and flags the caller's own row, so
     * no full WhatsApp numbers ever reach this screen.
     */
    private fun loadLeaderboardFromServer() {
        boardProgress.visibility = View.VISIBLE
        RepostSync.fetchLeaderboard(this) { entries ->
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                boardProgress.visibility = View.GONE
                if (entries == null) {
                    // Couldn't reach the server: keep the local fallback.
                    boardFromServer = false
                } else {
                    boardFromServer = true
                    boardRows = entries.map {
                        BoardRow(
                            rank = it.rank,
                            label = if (it.isMe) getString(R.string.repost_you) else it.displayNumber,
                            reposts = it.totalReposts,
                            streak = it.streak,
                            isMe = it.isMe
                        )
                    }
                }
                renderLeaderboard()
            }
        }
    }

    private fun renderLeaderboard() {
        boardListContainer.removeAllViews()
        // Server rows arrive already ranked; the local fallback is one row.
        val allRows = if (boardFromServer) boardRows else localOnlyBoard()

        // Show BOARD_PAGE_SIZE (10) rows at a time. If the list shrank
        // since the last render (e.g. after a refresh), clamp the page.
        val pageCount = maxOf(1, (allRows.size + BOARD_PAGE_SIZE - 1) / BOARD_PAGE_SIZE)
        if (boardPage >= pageCount) boardPage = pageCount - 1
        val start = boardPage * BOARD_PAGE_SIZE
        val rows = allRows.subList(start, minOf(start + BOARD_PAGE_SIZE, allRows.size))
        val density = resources.displayMetrics.density
        val rankBackgrounds = listOf(
            R.drawable.repost_rank_1,
            R.drawable.repost_rank_2,
            R.drawable.repost_rank_3
        )

        rows.forEachIndexed { index, row ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // Same horizontal inset on EVERY row (and on the column
                // headers in activity_repost.xml), so the rank badge, name
                // and count line up under their headers. Only the "You"
                // row adds a tinted background, which spans the full width
                // of the divider above it.
                setPadding(
                    (12 * density).toInt(), (13 * density).toInt(),
                    (12 * density).toInt(), (13 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (row.isMe) {
                    setBackgroundResource(R.drawable.repost_row_me_background)
                }
            }

            // Numbered circle badge: gold / silver / bronze for the top 3,
            // soft green for everyone else. Plain vector shapes + a digit,
            // so it renders identically on every Android version.
            line.addView(TextView(this).apply {
                text = row.rank.toString()
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                val top3 = row.rank in 1..3
                setBackgroundResource(
                    if (top3) rankBackgrounds[row.rank - 1] else R.drawable.repost_rank_other
                )
                setTextColor(
                    ContextCompat.getColor(
                        this@RepostActivity,
                        if (top3) R.color.white else R.color.vg_green_dark
                    )
                )
                val badge = (28 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(badge, badge)
            })

            // Name + optional streak chip (flame vector + number) in one
            // weighted column so the reposts count stays right-aligned.
            val nameColumn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (12 * density).toInt()
                }
            }
            nameColumn.addView(TextView(this).apply {
                text = row.label
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@RepostActivity, R.color.vg_dark))
            })
            if (row.streak >= 3) {
                val chip = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.repost_streak_chip)
                    setPadding((8 * density).toInt(), (2 * density).toInt(),
                        (8 * density).toInt(), (2 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (8 * density).toInt() }
                }
                val flameSize = (12 * density).toInt()
                chip.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_flame)
                    setColorFilter(ContextCompat.getColor(this@RepostActivity, R.color.vg_green_dark))
                    layoutParams = LinearLayout.LayoutParams(flameSize, flameSize)
                })
                chip.addView(TextView(this).apply {
                    text = row.streak.toString()
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@RepostActivity, R.color.vg_green_dark))
                    setPadding((3 * density).toInt(), 0, 0, 0)
                })
                nameColumn.addView(chip)
            }
            line.addView(nameColumn)

            line.addView(TextView(this).apply {
                text = row.reposts.toString()
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@RepostActivity, R.color.vg_green))
            })

            boardListContainer.addView(line)
            FontHelper.applyPoppinsAsync(this, line)

            if (index != rows.lastIndex) {
                boardListContainer.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(ContextCompat.getColor(this@RepostActivity, R.color.stats_card_border))
                })
            }
        }

        // Be upfront when we could not reach the server and are only
        // showing the user's own row.
        if (!boardFromServer) {
            boardNoteText.text = getString(R.string.repost_board_offline_note)
            boardNoteText.visibility = View.VISIBLE
        } else if (rows.isEmpty()) {
            boardNoteText.text = getString(R.string.repost_board_empty_note)
            boardNoteText.visibility = View.VISIBLE
        } else {
            boardNoteText.visibility = View.GONE
        }

        renderBoardPager(pageCount)
    }

    /**
     * Numbered page buttons under the leaderboard, same look as the
     * Referrals screen (item_group_page_button + the page_button_*
     * backgrounds). Hidden when everything fits on one page.
     */
    private fun renderBoardPager(pageCount: Int) {
        if (pageCount <= 1) {
            boardPagerScroll.visibility = View.GONE
            return
        }
        boardPagerScroll.visibility = View.VISIBLE
        boardPagerContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (pageIndex in 0 until pageCount) {
            val button = inflater.inflate(
                R.layout.item_group_page_button, boardPagerContainer, false
            ) as TextView
            button.text = (pageIndex + 1).toString()
            val selected = pageIndex == boardPage
            button.setBackgroundResource(
                if (selected) R.drawable.page_button_selected_background
                else R.drawable.page_button_default_background
            )
            button.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.white else R.color.vg_dark)
            )
            button.setOnClickListener {
                if (boardPage != pageIndex) {
                    boardPage = pageIndex
                    renderLeaderboard()
                }
            }
            boardPagerContainer.addView(button)
            // Inflated after setContentView(), so BaseActivity's one-time
            // font pass never reaches it.
            FontHelper.applyPoppinsAsync(this, button)
        }
    }

    companion object {
        /** Leaderboard rows per page. */
        private const val BOARD_PAGE_SIZE = 10

        // Same admin number used by RepostRedirectActivity and
        // FloatingContactHelper.
        private const val ADMIN_WHATSAPP_NUMBER = "09110321143"
    }
}
