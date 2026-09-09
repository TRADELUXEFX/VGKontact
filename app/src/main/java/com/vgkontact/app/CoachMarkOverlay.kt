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
 * cutout "hole" around the target view, plus a tooltip card below/above it.
 *
 * Runs only once per install: gated by UserPrefs.isWalkthroughDone(), the
 * same one-time pattern PermissionSetupActivity uses for its own gate.
 * Call CoachMarkOverlay.showIfNeeded(activity, steps) once the dashboard's
 * views are laid out (e.g. from a view.post { } in onCreate) so target
 * positions are already known.
 */
object CoachMarkOverlay {

    data class Step(val target: View, val title: String, val message: String)

    fun showIfNeeded(activity: Activity, steps: List<Step>) {
        if (UserPrefs.isWalkthroughDone(activity)) return
        if (steps.isEmpty()) return

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
            .getChildAt(0) as? ViewGroup ?: return

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

            // Scroll the target into view first - previously renderStep()
            // measured the target's position immediately, which only
            // worked for views already on screen (steps 1-2). For a view
            // further down the dashboard (e.g. kontactGroupsButton on
            // step 3), the view could be partially or fully off-screen
            // when measured, producing a highlight rect that didn't
            // actually frame the button - it either missed it entirely or
            // clipped into whatever view happened to be on screen at that
            // location instead. requestRectangleOnScreen asks every
            // scrollable ancestor (the dashboard's NestedScrollView) to
            // bring the target fully into view before we measure or draw
            // anything.
            step.target.requestRectangleOnScreen(
                Rect(0, 0, step.target.width, step.target.height)
            )

            // The scroll above is not synchronous - it schedules a layout
            // pass. Measuring on the very next frame (via post) is enough
            // for a NestedScrollView's fling-free scrollTo to have applied,
            // matching the same "wait for layout" pattern already used to
            // call showDashboardTourIfNeeded() in the first place.
            step.target.post {
                val rect = rectOf(step.target)
                overlay.setTargetRect(rect)
                tooltipTitle.text = step.title
                tooltipMessage.text = step.message
                tooltipCounter.text = "${index + 1} of ${steps.size}"
                tooltipNextButton.text = if (index == steps.size - 1) "Got it" else "Next"
                positionTooltip(activity, tooltip, rect)
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

    private fun rectOf(view: View): Rect {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun positionTooltip(activity: Activity, tooltip: View, targetRect: Rect) {
        val params = tooltip.layoutParams as FrameLayout.LayoutParams
        val screenHeight = activity.resources.displayMetrics.heightPixels
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val margin = (16 * activity.resources.displayMetrics.density).toInt()

        params.leftMargin = margin
        params.rightMargin = margin
        params.gravity = Gravity.TOP
        tooltip.layoutParams = params

        // Measure the tooltip's real height (it changes per step - shorter
        // vs longer tip text) instead of guessing a fixed number, so the
        // "does it fit below/above" check is accurate for every step -
        // including the bottom-nav targets (steps 4 and 5), which previously
        // used a hardcoded guess that let the tooltip cover the very tab
        // it was pointing at.
        tooltip.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth - 2 * margin, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val tooltipHeight = tooltip.measuredHeight

        val spaceBelow = screenHeight - targetRect.bottom
        val spaceAbove = targetRect.top

        params.topMargin = when {
            // Enough room below the target - the normal case.
            spaceBelow >= tooltipHeight + margin -> targetRect.bottom + margin
            // Not enough below (e.g. bottom-nav tabs) but enough above - flip up.
            spaceAbove >= tooltipHeight + margin -> targetRect.top - tooltipHeight - margin
            // Neither side has room - pin near the bottom of the screen
            // rather than letting it run off-screen or overlap the target.
            else -> (screenHeight - tooltipHeight - margin).coerceAtLeast(margin)
        }
        tooltip.layoutParams = params
    }

    private fun buildTooltip(activity: Activity): LinearLayout {
        val density = activity.resources.displayMetrics.density
        val container = LinearLayout(activity)
        container.orientation = LinearLayout.VERTICAL
        container.background = activity.getDrawable(R.drawable.coach_mark_tooltip_background)
        container.setPadding((20 * density).toInt(), (18 * density).toInt(), (20 * density).toInt(), (18 * density).toInt())
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
        next.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.vg_green))
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
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun setTargetRect(rect: Rect) {
            val padding = 12f
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
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            targetRect?.let { rect ->
                canvas.drawRoundRect(rect, 16f, 16f, holePaint)
                canvas.drawRoundRect(rect, 16f, 16f, strokePaint)
            }
        }
    }
}
