package com.vgkontact.app

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * First-run dashboard tour: a full-screen dimming scrim with a rounded-rect
 * cutout ("spotlight") around one real button at a time, plus a fixed
 * tooltip card explaining it. The cutout is punched with PorterDuff.CLEAR
 * on a hardware layer, so everything outside the target stays dark and
 * only the target itself reads at full brightness.
 *
 * Deliberately simple otherwise - no scroll-position syncing, no
 * multi-frame post{} chains. The scrim is an ordinary sibling View
 * positioned to fill the root, and the tooltip always docks at a fixed
 * top or bottom spot. If a target is off-screen behind a scrollable area,
 * this does not auto-scroll to it - callers should pick targets that are
 * already visible, or call scrollTo() themselves before showIfNeeded().
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

        val scrim = SpotlightScrimView(activity)
        val tooltip = TooltipView(activity)
        // Hidden until the first real showStep() call binds content and
        // docks it - otherwise it flashes at its default position/size
        // for the frame(s) before layout/measurement is ready.
        tooltip.root.visibility = View.GONE

        var index = 0

        fun finish() {
            UserPrefs.setWalkthroughDone(activity)
            root.removeView(scrim)
            root.removeView(tooltip.root)
        }

        fun showStep() {
            val step = steps[index]
            positionSpotlight(scrim, step.target, root)
            tooltip.dockAt(top = !step.dockAtBottom)
            tooltip.bind(
                title = step.title,
                message = step.message,
                counter = "${index + 1}/${steps.size}",
                nextLabel = if (index == steps.size - 1) "Got it" else "Next"
            )
            tooltip.root.visibility = View.VISIBLE
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

        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(tooltip.root, tooltip.root.layoutParams)

        // The floating "Contact Us" bubble (FloatingContactHelper) sets its
        // own elevation (12dp) so it can float above scrolling content.
        // Elevation wins over add-order when siblings are compared, so
        // without this the FAB would still poke through the dimmed scrim
        // and stay tappable during the tour. Give the scrim/tooltip more
        // elevation than the FAB so the tour always sits on top of it.
        val density = activity.resources.displayMetrics.density
        scrim.elevation = 14 * density
        tooltip.root.elevation = 16 * density

        // A single post{} isn't enough: on first run the dashboard's stats
        // card is still reflowing (sync stats/limit numbers populate async
        // after onCreate), so the very first target can still measure 0x0
        // or a stale size one frame later, which is what drew the spotlight
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
     * Moves the spotlight cutout to sit around target's current on-screen
     * position. Plain pixel math - getLocationInWindow() for both views,
     * subtract to get target's position relative to root, no scroll
     * offsets involved because both are read fresh at call time.
     */
    private fun positionSpotlight(scrim: SpotlightScrimView, target: View, root: View) {
        val t = IntArray(2)
        target.getLocationInWindow(t)
        val r = IntArray(2)
        root.getLocationInWindow(r)

        val pad = 6 * scrim.resources.displayMetrics.density
        val left = (t[0] - r[0]).toFloat() - pad
        val top = (t[1] - r[1]).toFloat() - pad
        scrim.setHole(
            RectF(left, top, left + target.width + pad * 2, top + target.height + pad * 2)
        )
    }

    /**
     * Full-screen dimming scrim that punches a rounded-rect hole around
     * the current target, with a thin green stroke tracing the cutout
     * edge for definition against whatever's behind it.
     */
    private class SpotlightScrimView(activity: Activity) : View(activity) {
        private val density = activity.resources.displayMetrics.density
        private val cornerRadius = 16 * density
        private val hole = RectF()

        private val dimPaint = Paint().apply {
            color = Color.parseColor("#CC000000") // ~80% black
            isAntiAlias = true
        }
        private val clearPaint = Paint().apply {
            isAntiAlias = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            color = Color.parseColor("#1FAA59")
            isAntiAlias = true
        }

        init {
            // CLEAR blending only works correctly on a hardware layer -
            // without this the "hole" would just draw black-on-black
            // instead of actually cutting through to the views beneath.
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        fun setHole(rect: RectF) {
            hole.set(rect)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (hole.isEmpty) return
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRoundRect(hole, cornerRadius, cornerRadius, clearPaint)
            canvas.drawRoundRect(hole, cornerRadius, cornerRadius, strokePaint)
        }

        // Block every touch except inside the cutout, so the dimmed area
        // can't be tapped through to whatever's underneath - only the
        // spotlighted target itself stays interactive during the tour.
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            return !hole.contains(event.x, event.y)
        }
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

            // Counter now lives in the actions row (left side) instead of
            // above the title, so it sits on the same line as Skip/Next
            // per the "5/5   Skip   Next" layout.
            val counter = TextView(activity).apply {
                textSize = 13f
                setTextColor(activity.getColor(R.color.text_muted))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val actionsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) }
            }
            actionsRow.addView(counter)

            // Skip and Next are grouped together on the right, tightly
            // spaced, rather than Skip floating on the left with a big gap
            // before Next.
            val buttonGroup = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val skip = TextView(activity).apply {
                text = "Skip"
                textSize = 14f
                setTextColor(activity.getColor(R.color.text_secondary))
                background = activity.getDrawable(R.drawable.coach_mark_skip_background)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(10) }
            }
            buttonGroup.addView(skip)

            val next = Button(activity).apply {
                text = "Next"
                textSize = 14f
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = activity.getDrawable(R.drawable.coach_mark_button_background)
                stateListAnimator = null
                setPadding(dp(20), dp(10), dp(20), dp(10))
            }
            buttonGroup.addView(next)

            actionsRow.addView(buttonGroup)
            container.addView(actionsRow)
            return Pair(container, listOf(counter, title, message, skip, next))
        }
    }
}
