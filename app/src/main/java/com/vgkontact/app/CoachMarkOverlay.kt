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

/**
 * Points at real dashboard buttons one at a time - a dimmed overlay with a
 * cutout "hole" around the target view, plus a tooltip card.
 *
 * The tooltip docks at a FIXED spot for every step - by default just below
 * the status bar at the top of the screen - and never moves after that to
 * track the target's exact position. Only the highlight cutout moves
 * between steps.
 *
 * Top-docked is the default for every step because most targets on this
 * dashboard sit far enough down the screen (buttons, nav tabs) that a
 * top-docked tooltip is never anywhere near them. The one exception is a
 * target that sits high up right under the header (e.g. the contact-limit
 * card) - a top-docked tooltip there would land on or overlap it, so that
 * step alone opts into dockAtBottom = true on its Step. Everything else
 * uses the default.
 *
 * Runs only once per install: gated by UserPrefs.isWalkthroughDone(), the
 * same one-time pattern PermissionSetupActivity uses for its own gate.
 * Call CoachMarkOverlay.showIfNeeded(activity, steps) once the dashboard's
 * views are laid out (e.g. from a view.post { } in onCreate) so target
 * positions are already known.
 */
object CoachMarkOverlay {

    data class Step(
        val target: View,
        val title: String,
        val message: String,
        // Set true only for a step whose target sits high enough on
        // screen that the default top dock would land on or near it.
        val dockAtBottom: Boolean = false
    )

    fun showIfNeeded(activity: Activity, steps: List<Step>) {
        if (UserPrefs.isWalkthroughDone(activity)) return
        if (steps.isEmpty()) return

        // Attach to the content root itself (not its first child) so the
        // overlay and tooltip are guaranteed to be added last / drawn last
        // regardless of how many siblings the activity's layout has (e.g.
        // MainMenuActivity's root FrameLayout has both the scrollable
        // dashboard AND the floating bottom_nav_bar as direct children -
        // grabbing only childAt(0) missed that structure entirely).
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        var index = 0

        val overlay = HighlightView(activity)
        overlay.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val tooltip = buildTooltip(activity)
        val tooltipTitle = tooltip.findViewById<TextView>(1001)
        val tooltipMessage = tooltip.findViewById<TextView>(1002)
        val tooltipCounter = tooltip.findViewById<TextView>(1003)
        val tooltipNextButton = tooltip.findViewById<Button>(1004)
        val tooltipSkipText = tooltip.findViewById<TextView>(1005)

        fun finish() {
            UserPrefs.setWalkthroughDone(activity)
            root.removeView(overlay)
            root.removeView(tooltip)
        }

        fun renderStep() {
            val step = steps[index]

            // Dock position comes straight from the step's own flag now -
            // top by default, bottom only for the one step that opts in.
            // No longer inferred from the target's on-screen position,
            // which was fragile before the target had been scrolled into
            // place at all.
            val dockTop = !step.dockAtBottom
            dockTooltip(activity, tooltip, top = dockTop)

            // Scroll the target into view, leaving clearance on whichever
            // side the tooltip now occupies, so the target doesn't end up
            // sitting behind the (now fixed) tooltip.
            scrollTargetClearOfDock(step.target, root, clearTop = dockTop)

            // The scroll above is not synchronous - it schedules a layout
            // pass. Measuring on the very next frame (via post) is enough
            // for a NestedScrollView's fling-free scrollTo to have applied,
            // matching the same "wait for layout" pattern already used to
            // call showDashboardTourIfNeeded() in the first place.
            step.target.post {
                val rect = rectOf(step.target, root)
                overlay.setTargetRect(rect)
                tooltipTitle.text = step.title
                tooltipMessage.text = step.message
                tooltipCounter.text = "${index + 1} of ${steps.size}"
                tooltipNextButton.text = if (index == steps.size - 1) "Got it" else "Next"
            }
        }

        tooltipNextButton.setOnClickListener {
            if (index < steps.size - 1) {
                index += 1
                renderStep()
            } else {
                finish()
            }
        }
        tooltipSkipText.setOnClickListener { finish() }
        overlay.setOnClickListener { /* swallow taps outside the tooltip */ }

        root.addView(overlay)
        root.addView(tooltip)
        renderStep()
    }

    /**
     * Scrolls the nearest scrollable ancestor (the dashboard's
     * NestedScrollView) so the target view has clearance on the side
     * where the tooltip is currently docked. No-ops if the target has no
     * scrollable ancestor (e.g. the bottom nav tabs, which are always on
     * screen at a fixed position regardless of scroll state).
     */
    private fun scrollTargetClearOfDock(target: View, root: View, clearTop: Boolean) {
        val density = target.resources.displayMetrics.density
        val dockClearance = (170 * density).toInt() // tooltip card + margins

        var scrollView: androidx.core.widget.NestedScrollView? = null
        var parent = target.parent
        while (parent is View) {
            if (parent is androidx.core.widget.NestedScrollView) {
                scrollView = parent
                break
            }
            parent = parent.parent
        }
        if (scrollView == null) return

        var offset = 0
        var v: View = target
        while (v !== scrollView) {
            offset += v.top
            v = v.parent as View
        }
        val targetTop = offset
        val targetBottom = targetTop + target.height

        if (clearTop) {
            // Tooltip is docked at the top - make sure the target isn't
            // hidden underneath it by ensuring enough scroll-space above.
            val visibleTop = scrollView.scrollY + dockClearance
            if (targetTop < visibleTop) {
                scrollView.scrollTo(0, (targetTop - dockClearance).coerceAtLeast(0))
            }
        } else {
            // Tooltip is docked at the bottom - make sure the target sits
            // above that zone.
            val visibleBottom = scrollView.scrollY + scrollView.height - dockClearance
            if (targetBottom > visibleBottom) {
                scrollView.scrollTo(0, (targetBottom - scrollView.height + dockClearance).coerceAtLeast(0))
            } else if (targetTop < scrollView.scrollY) {
                scrollView.scrollTo(0, targetTop)
            }
        }
    }

    /**
     * Returns the target's bounds in the *overlay's own coordinate space*
     * (i.e. relative to `root`, the content view the overlay is a child
     * of) rather than raw window coordinates.
     *
     * getLocationInWindow() returns coordinates relative to the top-left
     * of the whole window, which includes the status bar. But the overlay
     * view is added as a child of android.R.id.content, whose local (0,0)
     * already starts *below* the status bar. Drawing at the raw window
     * coordinate on the overlay's canvas therefore places the hole too
     * high by exactly the status bar's height. Subtracting root's own
     * window position converts the target's coordinate into root/overlay-
     * local space so the two line up.
     */
    private fun rectOf(view: View, root: View): Rect {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rootLocation = IntArray(2)
        root.getLocationInWindow(rootLocation)
        val left = location[0] - rootLocation[0]
        val top = location[1] - rootLocation[1]
        return Rect(left, top, left + view.width, top + view.height)
    }

    /**
     * Docks the tooltip at a fixed spot - either just below the status
     * bar or just above the floating bottom nav bar - and nowhere else.
     * Only ever snaps between these two positions between steps; never
     * follows a target's exact position.
     */
    private fun dockTooltip(activity: Activity, tooltip: View, top: Boolean) {
        val params = tooltip.layoutParams as FrameLayout.LayoutParams
        val density = activity.resources.displayMetrics.density
        val margin = (16 * density).toInt()
        // Clear space for the floating bottom nav bar (56dp-ish tall pill +
        // its own 14dp bottom margin) so a bottom-docked tooltip sits just
        // above it instead of covering it.
        val navBarClearance = (86 * density).toInt()
        // Clear space below the status bar for a top-docked tooltip.
        val statusBarClearance = (24 * density).toInt()

        params.leftMargin = margin
        params.rightMargin = margin
        if (top) {
            params.gravity = Gravity.TOP
            params.topMargin = statusBarClearance
            params.bottomMargin = 0
        } else {
            params.gravity = Gravity.BOTTOM
            params.topMargin = 0
            params.bottomMargin = navBarClearance
        }
        tooltip.layoutParams = params
    }

    private fun buildTooltip(activity: Activity): LinearLayout {
        val density = activity.resources.displayMetrics.density
        val container = LinearLayout(activity)
        container.orientation = LinearLayout.VERTICAL
        container.background = activity.getDrawable(R.drawable.coach_mark_tooltip_background)
        // Slightly tighter padding than before (16/14 vs 20/18) - this
        // card needs to stay compact since, in the top-docked case, it now
        // sits close above content like step 2's contact-limit card, and a
        // shorter card leaves more breathing room before it could ever
        // reach far enough down to overlap that content.
        container.setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), (14 * density).toInt())
        container.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        container.elevation = 12 * density

        val counter = TextView(activity)
        counter.id = 1003
        counter.textSize = 12f
        counter.setTextColor(activity.getColor(R.color.text_muted))
        container.addView(counter)

        val title = TextView(activity)
        title.id = 1001
        title.textSize = 16f
        title.setTextColor(activity.getColor(R.color.vg_dark))
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        val titleParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        titleParams.topMargin = (6 * density).toInt()
        title.layoutParams = titleParams
        container.addView(title)

        val message = TextView(activity)
        message.id = 1002
        message.textSize = 14f
        message.setTextColor(activity.getColor(R.color.text_secondary))
        val msgParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        msgParams.topMargin = (6 * density).toInt()
        message.layoutParams = msgParams
        container.addView(message)

        val actionsRow = LinearLayout(activity)
        actionsRow.orientation = LinearLayout.HORIZONTAL
        actionsRow.gravity = Gravity.CENTER_VERTICAL
        val actionsParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        actionsParams.topMargin = (14 * density).toInt()
        actionsRow.layoutParams = actionsParams

        val skip = TextView(activity)
        skip.id = 1005
        skip.text = "Skip"
        skip.textSize = 14f
        skip.setTextColor(activity.getColor(R.color.text_muted))
        skip.setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        val skipParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        skip.layoutParams = skipParams
        actionsRow.addView(skip)

        val next = Button(activity)
        next.id = 1004
        next.text = "Next"
        next.textSize = 14f
        next.setTextColor(android.graphics.Color.WHITE)
        next.isAllCaps = false
        next.background = activity.getDrawable(R.drawable.coach_mark_button_background)
        next.stateListAnimator = null
        next.setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
        actionsRow.addView(next)

        container.addView(actionsRow)
        return container
    }

    /**
     * Draws a dark scrim over the whole screen with a rounded-rect hole cut
     * out around the current target, so that view (and only that view)
     * stays visible and tappable-looking underneath.
     */
    private class HighlightView(activity: Activity) : View(activity) {
        private var targetRect: RectF? = null
        private val scrimPaint = Paint().apply {
            color = Color.parseColor("#CC000000")
        }
        private val holePaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            isAntiAlias = true
        }
        private val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#1FAA59")
            isAntiAlias = true
        }

        init {
            // NOTE: deliberately NOT using LAYER_TYPE_SOFTWARE here. Forcing
            // a software layer on the whole view takes it out of the normal
            // hardware-accelerated RenderNode pipeline, which is what
            // Android actually uses to compare `elevation` between sibling
            // views. The floating bottom nav bar (bottom_nav_bar.xml) draws
            // at elevation 10dp using a hardware layer; a software-layer
            // overlay can't be reliably Z-compared against it, so the nav
            // bar kept winning and painting over the punched-out hole.
            // Instead we punch the hole into an offscreen layer via
            // saveLayer() below and composite that onto this (still
            // hardware-accelerated) view's canvas, so elevation ordering
            // against the nav bar works normally.
            elevation = 12 * resources.displayMetrics.density
        }

        fun setTargetRect(rect: Rect) {
            val padding = 6f
            targetRect = RectF(
                rect.left - padding,
                rect.top - padding,
                rect.right + padding,
                rect.bottom + padding
            )
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Composite the scrim + hole on an offscreen layer instead of
            // drawing PorterDuff.CLEAR straight onto the view's own canvas.
            // saveLayer() gives us a transparent buffer to punch a real
            // hole into; that buffer is then drawn as a single bitmap onto
            // the (hardware-accelerated) view canvas, so this view's
            // elevation is compared normally against sibling views like
            // the bottom nav bar instead of being defeated by a software
            // layer.
            val layerId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            targetRect?.let { rect ->
                canvas.drawRoundRect(rect, 16f, 16f, holePaint)
                canvas.drawRoundRect(rect, 16f, 16f, strokePaint)
            }
            canvas.restoreToCount(layerId)
        }
    }
}
