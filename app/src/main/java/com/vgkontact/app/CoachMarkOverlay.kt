package com.vgkontact.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * First-run dashboard tour: a simple green ring drawn around one real
 * button at a time, plus a fixed tooltip card explaining it.
 *
 * Deliberately simple - no canvas hole-punching, no scroll-position
 * syncing, no multi-frame post{} chains. The ring is an ordinary sibling
 * View positioned with plain pixel math from getLocationInWindow(), and
 * the tooltip always docks at a fixed top or bottom spot. If a target is
 * off-screen behind a scrollable area, this does not auto-scroll to it -
 * callers should pick targets that are already visible, or call
 * scrollTo() themselves before showIfNeeded().
 *
 * Runs once per install, gated by UserPrefs.isWalkthroughDone(). Call
 * showIfNeeded(activity, steps) after the screen has been laid out
 * (e.g. from a view.post{} in onCreate).
 */
object CoachMarkOverlay {

    data class Step(
        val target: View,
        val title: String,
        val message: String,
        val dockAtBottom: Boolean = false
    )

    fun showIfNeeded(activity: Activity, steps: List<Step>) {
        if (UserPrefs.isWalkthroughDone(activity)) return
        if (steps.isEmpty()) return

        // android.R.id.content is the Activity's true top-level container.
        // Adding views here guarantees they draw above everything else on
        // screen, including a floating bottom nav bar in its own layout.
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val ring = buildRing(activity)
        val tooltip = TooltipView(activity)

        var index = 0

        fun finish() {
            UserPrefs.setWalkthroughDone(activity)
            root.removeView(ring)
            root.removeView(tooltip.root)
        }

        fun showStep() {
            val step = steps[index]
            positionRing(ring, step.target, root)
            tooltip.dockAt(top = !step.dockAtBottom)
            tooltip.bind(
                title = step.title,
                message = step.message,
                counter = "${index + 1} of ${steps.size}",
                nextLabel = if (index == steps.size - 1) "Got it" else "Next"
            )
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

        root.addView(ring, ring.layoutParams)
        root.addView(tooltip.root, tooltip.root.layoutParams)

        // A single post{} isn't enough: on first run the dashboard's stats
        // card is still reflowing (sync stats/limit numbers populate async
        // after onCreate), so the very first target can still measure 0x0
        // or a stale size one frame later, which is what drew the ring
        // around the whole card instead of the small button. Wait for an
        // actual completed layout pass on the target - via
        // OnGlobalLayoutListener - rather than guessing a frame count.
        val firstTarget = steps[0].target
        if (firstTarget.width > 0 && firstTarget.height > 0) {
            showStep()
        } else {
            val vto = firstTarget.viewTreeObserver
            val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (firstTarget.width > 0 && firstTarget.height > 0) {
                        if (vto.isAlive) {
                            vto.removeOnGlobalLayoutListener(this)
                        }
                        showStep()
                    }
                    // else: keep waiting for a later layout pass that
                    // actually gives the target real dimensions.
                }
            }
            vto.addOnGlobalLayoutListener(listener)
        }
    }

    /**
     * Moves the ring to sit around target's current on-screen position.
     * Plain pixel math - getLocationInWindow() for both views, subtract to
     * get target's position relative to root, no scroll offsets involved
     * because both are read fresh at call time.
     */
    private fun positionRing(ring: View, target: View, root: View) {
        val t = IntArray(2)
        target.getLocationInWindow(t)
        val r = IntArray(2)
        root.getLocationInWindow(r)

        val pad = (6 * ring.resources.displayMetrics.density).toInt()
        val params = ring.layoutParams as FrameLayout.LayoutParams
        params.width = target.width + pad * 2
        params.height = target.height + pad * 2
        params.leftMargin = t[0] - r[0] - pad
        params.topMargin = t[1] - r[1] - pad
        params.gravity = Gravity.TOP or Gravity.START
        ring.layoutParams = params
    }

    private fun buildRing(activity: Activity): View {
        val density = activity.resources.displayMetrics.density
        val ring = View(activity)
        ring.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * density
            setStroke((3 * density).toInt(), Color.parseColor("#1FAA59"))
        }
        ring.layoutParams = FrameLayout.LayoutParams(0, 0)
        ring.elevation = 12 * density
        return ring
    }

    /**
     * The tooltip card. Docks at a fixed spot - top or bottom - and never
     * follows the target.
     */
    private class TooltipView(private val activity: Activity) {
        var onNext: (() -> Unit)? = null
        var onSkip: (() -> Unit)? = null

        val root: LinearLayout
        private val titleView: TextView
        private val messageView: TextView
        private val counterView: TextView
        private val nextButton: Button
        private val skipView: TextView

        init {
            val built = build()
            root = built.first
            counterView = built.second[0] as TextView
            titleView = built.second[1] as TextView
            messageView = built.second[2] as TextView
            skipView = built.second[3] as TextView
            nextButton = built.second[4] as Button

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

        private fun build(): Pair<LinearLayout, List<View>> {
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
                textSize = 12f
                setTextColor(activity.getColor(R.color.text_muted))
            }
            container.addView(counter)

            val title = TextView(activity).apply {
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
                text = "Skip"
                textSize = 14f
                setTextColor(activity.getColor(R.color.text_muted))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            actionsRow.addView(skip)

            val next = Button(activity).apply {
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
            return Pair(container, listOf(counter, title, message, skip, next))
        }
    }
}
