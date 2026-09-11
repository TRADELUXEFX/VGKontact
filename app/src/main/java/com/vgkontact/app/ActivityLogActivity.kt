package com.vgkontact.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Simple history/activity feed opened from the dashboard bell. Shows
 * every event ActivityLog has recorded (contact syncs, limit
 * warnings/increases, permission issues, sync-frequency changes, and
 * referral joins once that's wired to real server data), newest first.
 * Opening this screen clears the unread dot on the bell - see
 * MainMenuActivity.renderNotificationDot(), called again in onResume
 * there so the dot disappears the moment the user comes back to the
 * dashboard from here.
 *
 * Rows are built programmatically into a plain LinearLayout inside a
 * ScrollView (item_activity_log_row.xml per row), matching this
 * codebase's general preference for hand-built views over a
 * RecyclerView adapter for lists this small (capped at 100 entries).
 */
class ActivityLogActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var backButton: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activity_log)
        FloatingContactHelper.attach(this, bottomMarginDp = 0)

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        listContainer = findViewById(R.id.activityLogListContainer)
        emptyText = findViewById(R.id.activityLogEmptyText)
        backButton = findViewById(R.id.activityLogBackButton)

        backButton.setOnClickListener { finish() }

        // Clear unread the moment the feed is opened, not on exit - so if
        // the user backs out immediately the dot is still gone, matching
        // how a normal notification tray behaves.
        ActivityLog.markAllRead(this)

        renderEntries()
    }

    private fun renderEntries() {
        listContainer.removeAllViews()
        val entries = ActivityLog.getAll(this)

        if (entries.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            return
        }
        emptyText.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        entries.forEachIndexed { index, entry ->
            val row = inflater.inflate(R.layout.item_activity_log_row, listContainer, false)
            row.findViewById<TextView>(R.id.rowMessage).text = entry.message
            row.findViewById<TextView>(R.id.rowTime).text = entry.displayTime()
            row.findViewById<ImageView>(R.id.rowIcon).setImageResource(iconFor(entry.type))
            listContainer.addView(row)

            // Hairline divider between rows, skipped after the last entry -
            // matches the RECENT REFERRALS divider pattern in
            // activity_history.xml.
            if (index < entries.lastIndex) {
                val divider = View(this)
                divider.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                divider.setBackgroundColor(ContextCompat.getColor(this, R.color.stats_card_border))
                listContainer.addView(divider)
            }
        }
    }

    /**
     * Reuses existing drawables already in the project rather than adding
     * new per-type icons - close enough visually for a first pass, easy
     * to swap for dedicated icons later if it's worth the design time.
     */
    private fun iconFor(type: ActivityLog.Type): Int {
        return when (type) {
            ActivityLog.Type.CONTACT_SYNCED -> R.drawable.ic_notification_bell
            ActivityLog.Type.LIMIT_WARNING -> R.drawable.ic_notification_bell
            ActivityLog.Type.LIMIT_REACHED -> R.drawable.ic_notification_bell
            ActivityLog.Type.LIMIT_INCREASED -> R.drawable.ic_arrow_forward
            ActivityLog.Type.REFERRAL_JOINED -> R.drawable.ic_notification_bell
            ActivityLog.Type.PERMISSION_ISSUE -> R.drawable.ic_notification_bell
            ActivityLog.Type.SYNC_FREQUENCY_CHANGED -> R.drawable.ic_arrow_forward
        }
    }
}
