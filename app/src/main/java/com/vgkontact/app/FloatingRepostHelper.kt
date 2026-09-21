package com.vgkontact.app

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat

/**
 * App-wide floating "Repost" button. Sits directly ABOVE the floating
 * Contact Us chat button (FloatingContactHelper) and opens
 * RepostActivity.
 *
 * Not called directly by screens: FloatingContactHelper.attach() calls
 * [attach] for every screen that already attaches the chat button, so
 * the two always appear together and no existing Activity needed to be
 * edited.
 *
 * Same construction as the chat FAB (60dp green circle, 18dp margin,
 * added straight to android.R.id.content so it floats above scrolling
 * content and the bottom nav), with two differences:
 *  - it is lifted by one button-height + gap so it stacks above the chat button
 *  - it shows a small red dot while today's repost is still pending
 */
object FloatingRepostHelper {

    private const val FAB_TAG = "floating_repost_fab"
    private const val BADGE_TAG = "floating_repost_badge"

    private const val SIZE_DP = 60
    private const val MARGIN_DP = 18
    // 60dp chat button + 12dp gap between the two
    private const val STACK_OFFSET_DP = SIZE_DP + 12

    /**
     * @param chatBottomMarginDp the same extra bottom margin the chat
     * button was given, so both buttons lift together over screens
     * that have a bottom nav.
     * @return the FAB, or the existing one if already attached.
     */
    fun attach(activity: Activity, chatBottomMarginDp: Int): View? {
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null

        contentRoot.findViewWithTag<View>(FAB_TAG)?.let { return it }

        val density = activity.resources.displayMetrics.density
        val sizePx = (SIZE_DP * density).toInt()
        val marginPx = (MARGIN_DP * density).toInt()
        val bottomPx = ((chatBottomMarginDp + STACK_OFFSET_DP) * density).toInt()
        val iconPaddingPx = (16 * density).toInt()

        val fab = ImageView(activity).apply {
            tag = FAB_TAG
            id = View.generateViewId()
            setImageResource(R.drawable.ic_repost)
            setColorFilter(ContextCompat.getColor(activity, R.color.white))
            background = ContextCompat.getDrawable(activity, R.drawable.floating_repost_fab_background)
            setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
            elevation = 12 * density
            contentDescription = activity.getString(R.string.repost_fab_description)
            isClickable = true
            isFocusable = true
        }

        val params = FrameLayout.LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = marginPx
            bottomMargin = marginPx + bottomPx
        }

        fab.setOnClickListener {
            activity.startActivity(Intent(activity, RepostActivity::class.java))
        }

        contentRoot.addView(fab, params)

        // Red "pending" dot, top-right of the button. Added as its own
        // sibling view (not a child of the ImageView, which can't hold
        // children) and positioned to overlap the button's corner.
        val dotSizePx = (14 * density).toInt()
        val dot = View(activity).apply {
            tag = BADGE_TAG
            background = ContextCompat.getDrawable(activity, R.drawable.repost_badge_background)
            // Must sit above the FAB (12dp) so it isn't hidden behind it,
            // but BELOW the coach-mark tour's scrim (14dp - see
            // CoachMarkOverlay), otherwise the dot would poke through the
            // dimmed overlay during the walkthrough.
            elevation = 13 * density
            isClickable = false
        }
        val dotParams = FrameLayout.LayoutParams(dotSizePx, dotSizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            // Nudge onto the FAB's top-right edge
            rightMargin = marginPx - (2 * density).toInt()
            bottomMargin = marginPx + bottomPx + sizePx - dotSizePx + (2 * density).toInt()
        }
        contentRoot.addView(dot, dotParams)

        refreshBadge(activity)
        return fab
    }

    /**
     * Updates the FAB for today's repost status. Safe to call any time;
     * does nothing if the button isn't attached.
     *
     * Pending (not yet reposted today): button is red, corner dot shown.
     * Done (already reposted today): button is green, corner dot hidden.
     *
     * Call this after attach() and again right after the user completes
     * a repost, so the button flips from red to green immediately
     * without waiting for the next screen load.
     */
    fun refreshBadge(activity: Activity) {
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val fab = contentRoot.findViewWithTag<View>(FAB_TAG)
        val dot = contentRoot.findViewWithTag<View>(BADGE_TAG)

        val repostedToday = RepostPrefs.hasRepostedToday(activity)

        fab?.background = ContextCompat.getDrawable(
            activity,
            if (repostedToday) R.drawable.floating_repost_fab_background
            else R.drawable.floating_repost_fab_background_pending
        )

        dot?.visibility = if (repostedToday) View.GONE else View.VISIBLE
    }
}
