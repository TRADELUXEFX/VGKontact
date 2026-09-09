package com.vgkontact.app

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.core.widget.NestedScrollView

/**
 * First-run dashboard tour: a dimmed scrim with a hole punched around one
 * real button at a time, plus a tooltip card explaining it.
 *
 * The tooltip sits at a FIXED position on screen - just below the status
 * bar - for every step, and never moves to chase the target. Only the
 * highlight hole moves between steps. One step (the contact-limit card,
 * which sits right under the header) opts into docking at the bottom
 * instead, via Step.dockAtBottom, since a top-docked tooltip would land on
 * top of it.
 *
 * Runs once per install, gated by UserPrefs.isWalkthroughDone(). Call
 * showIfNeeded(activity, steps) after the dashboard has been laid out
 * (e.g. from a view.post{} in onCreate).
 */
object CoachMarkOverlay {

    data class Step(
        val target: View,
        val title: String,
        val message: String,
        val dockAtBottom: Boolean = false
    )

    private const val TOOLTIP_TITLE_ID = 1001
    private const val TOOLTIP_MESSAGE_ID = 1002
    private const val TOOLTIP_COUNTER_ID = 1003
    private const val TOOLTIP_NEXT_ID = 1004
    private const val TOOLTIP_SKIP_ID = 1005

    fun showIfNeeded(activity: Activity, steps: List<Step>) {
        if (UserPrefs.isWalkthroughDone(activity)) return
        if (steps.isEmpty()) return

        // android.R.id.content is the Activity's true top-level container.
        // Adding views here (not to some inner layout) guarantees they are
        // the very last children drawn, above everything else on screen -
        // including a floating bottom nav bar that lives in its own
        // sibling layout, not inside the scrollable dashboard content.
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val overlay = HighlightView(activity)
        val tooltip = TooltipView(activity)

        var index = 0

        fun finish() {
            UserPrefs.setWalkthroughDone(activity)
            root.removeView(overlay)
            root.removeView(tooltip.root)
        }

        fun showStep() {
            val step = steps[index]
            tooltip.dockAt(top = !step.dockAtBottom)
            scrollIntoClearView(step.target, dockAtBottom = step.dockAtBottom)

            // Scrolling above is not synchronous. One frame later the
            // NestedScrollView has settled, so the target's final on-
            // screen position can be measured correctly.
            step.target.post {
                overlay.highlight(boundsInWindow(step.target, root))
                tooltip.bind(
                    title = step.title,
                    message = step.message,
                    counter = "${index + 1} of ${steps.size}",
                    nextLabel = if (index == steps.size - 1) "Got it" else "Next"
                )
            }
        }

        tooltip.onNext = {
            if (index < steps.size - 1) {
                index += 1
                showStep()
            } else {
                finish()
            }
        }
        tooltip.onSkip = { finish() }
        overlay.setOnClickListener { /* swallow taps outside the tooltip */ }

        root.addView(overlay, MATCH_MATCH)
        root.addView(tooltip.root, tooltip.root.layoutParams)

        // HighlightView has just been added and hasn't been through a
        // layout pass yet - it has no real width/height the instant this
        // line runs. Calling highlight() before that first layout landed
        // produced a degenerate, near-zero-size hole (visible as a stray
        // dot instead of a ring around the real button). Waiting for
        // overlay's own layout to complete guarantees onDraw has a
        // correctly sized canvas before the first step ever tries to
        // draw a hole into it.
        overlay.doOnNextLayout { showStep() }
    }

    /**
     * Scrolls target's nearest NestedScrollView ancestor, if any, so the
     * target isn't hidden behind the fixed tooltip dock. No-op for
     * targets with no scrollable ancestor (e.g. bottom nav tabs, which
     * are always on screen regardless of scroll position).
     */
    private fun scrollIntoClearView(target: View, dockAtBottom: Boolean) {
        val scrollView = findScrollViewAncestor(target) ?: return
        val density = target.resources.displayMetrics.density
        val clearance = (170 * density).toInt() // tooltip card height + margins

        val targetTop = sumOffsetsUpTo(target, scrollView)
        val targetBottom = targetTop + target.height

        if (dockAtBottom) {
            val visibleBottom = scrollView.scrollY + scrollView.height - clearance
            when {
                targetBottom > visibleBottom ->
                    scrollView.scrollTo(0, (targetBottom - scrollView.height + clearance).coerceAtLeast(0))
                targetTop < scrollView.scrollY ->
                    scrollView.scrollTo(0, targetTop)
            }
        } else {
            val visibleTop = scrollView.scrollY + clearance
            if (targetTop < visibleTop) {
                scrollView.scrollTo(0, (targetTop - clearance).coerceAtLeast(0))
            }
        }
    }

    private fun findScrollViewAncestor(view: View): NestedScrollView? {
        var p = view.parent
        while (p != null) {
            if (p is NestedScrollView) return p
            p = p.parent
        }
        return null
    }

    private fun sumOffsetsUpTo(view: View, ancestor: View): Int {
        var offset = 0
        var v: View = view
        while (v !== ancestor) {
            offset += v.top
            val nextParent = v.parent
            if (nextParent !is View) break
            v = nextParent
        }
        return offset
    }

    /**
     * Target's bounds converted into root's local coordinate space.
     * getLocationInWindow() is window-relative (includes the status bar);
     * root's own top-left, subtracted here, is not at (0,0) in that same
     * space - it starts below the status bar. Without this conversion the
     * hole is drawn a status-bar's-height too high.
     */
    private fun boundsInWindow(target: View, root: View): Rect {
        val t = IntArray(2)
        target.getLocationInWindow(t)
        val r = IntArray(2)
        root.getLocationInWindow(r)
        val left = t[0] - r[0]
        val top = t[1] - r[1]
        return Rect(left, top, left + target.width, top + target.height)
    }

    private val MATCH_MATCH = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    /**
     * Dark scrim covering the screen with a rounded-rect hole cut around
     * the current target. Hardware-accelerated (no forced software
     * layer) so its elevation is compared normally against the floating
     * bottom nav bar's own elevation - the hole is punched via an
     * offscreen saveLayer() instead, which keeps that comparison intact.
     */
    private class HighlightView(activity: Activity) : View(activity) {
        private var target: RectF? = null
        private val scrim = Paint().apply { color = Color.parseColor("#CC000000") }
        private val hole = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            isAntiAlias = true
        }
        private val ring = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#1FAA59")
            isAntiAlias = true
        }

        init {
            elevation = 12 * resources.displayMetrics.density
        }

        fun highlight(rect: Rect) {
            val pad = 6f
            target = RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
            target?.let {
                canvas.drawRoundRect(it, 16f, 16f, hole)
                canvas.drawRoundRect(it, 16f, 16f, ring)
            }
            canvas.restoreToCount(layer)
        }
    }

    /**
     * The tooltip card. Docks at a fixed spot - top or bottom - and only
     * ever snaps between those two; never follows the target.
     */
    private class TooltipView(private val activity: Activity) {
        var onNext: (() -> Unit)? = null
        var onSkip: (() -> Unit)? = null

        val root: LinearLayout = build()
        private val titleView = root.findViewById<TextView>(TOOLTIP_TITLE_ID)
        private val messageView = root.findViewById<TextView>(TOOLTIP_MESSAGE_ID)
        private val counterView = root.findViewById<TextView>(TOOLTIP_COUNTER_ID)
        private val nextButton = root.findViewById<Button>(TOOLTIP_NEXT_ID)
        private val skipView = root.findViewById<TextView>(TOOLTIP_SKIP_ID)

        init {
            nextButton.setOnClickListener { onNext?.invoke() }
            skipView.setOnClickListener { onSkip?.invoke() }
        }

        fun bind(title: String, message: String, counter: String, nextLabel: String) {
            titleView.text = title
            messageView.text = message
            counterView.text = counter
            nextButton.text = nextLabel
        }

        fun dockAt(top: Boolean) {
            val density = activity.resources.displayMetrics.density
            val sideMargin = (16 * density).toInt()
            val statusBarClearance = (24 * density).toInt()
            val navBarClearance = (86 * density).toInt() // nav pill height + its own margin

            val params = root.layoutParams as FrameLayout.LayoutParams
            params.leftMargin = sideMargin
            params.rightMargin = sideMargin
            if (top) {
                params.gravity = Gravity.TOP
                params.topMargin = statusBarClearance
                params.bottomMargin = 0
            } else {
                params.gravity = Gravity.BOTTOM
                params.topMargin = 0
                params.bottomMargin = navBarClearance
            }
            root.layoutParams = params
        }

        private fun build(): LinearLayout {
            val density = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = activity.getDrawable(R.drawable.coach_mark_tooltip_background)
                setPadding(dp(20), dp(14), dp(20), dp(14))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                elevation = dp(12).toFloat()
            }

            val counter = TextView(activity).apply {
                id = TOOLTIP_COUNTER_ID
                textSize = 12f
                setTextColor(activity.getColor(R.color.text_muted))
            }
            container.addView(counter)

            val title = TextView(activity).apply {
                id = TOOLTIP_TITLE_ID
                textSize = 16f
                setTextColor(activity.getColor(R.color.vg_dark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            container.addView(title)

            val message = TextView(activity).apply {
                id = TOOLTIP_MESSAGE_ID
                textSize = 14f
                setTextColor(activity.getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            container.addView(message)

            val actionsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) }
            }

            val skip = TextView(activity).apply {
                id = TOOLTIP_SKIP_ID
                text = "Skip"
                textSize = 14f
                setTextColor(activity.getColor(R.color.text_muted))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            actionsRow.addView(skip)

            val next = Button(activity).apply {
                id = TOOLTIP_NEXT_ID
                text = "Next"
                textSize = 14f
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = activity.getDrawable(R.drawable.coach_mark_button_background)
                stateListAnimator = null
                setPadding(dp(20), dp(10), dp(20), dp(10))
            }
            actionsRow.addView(next)

            container.addView(actionsRow)
            return container
        }
    }
}
