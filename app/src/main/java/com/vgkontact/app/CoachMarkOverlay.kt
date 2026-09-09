package com.vgkontact.app

import android.app.Activity
import android.view.View
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence

/**
 * Points at real dashboard buttons one at a time, using TapTargetView's
 * TapTargetSequence instead of a hand-built scrim + tooltip.
 *
 * The earlier hand-rolled version (draw a scrim View, punch a
 * PorterDuff.CLEAR hole around a live View's measured Rect, position a
 * separately-built tooltip) kept surfacing the same class of bug: a
 * container view's bounds didn't match the visual thing meant to be
 * highlighted, view.post{} raced ahead of scroll-into-view completing, and
 * the floating bottom nav bar's own elevation drew over the scrim's cutout.
 * TapTargetSequence owns all of that internally (it measures the target
 * itself right before showing, handles scrolling, and draws as a proper
 * overlay above everything else), so those bugs don't reappear step by step.
 *
 * Visual tradeoff: TapTargetView renders a colored circular spotlight with
 * title/description text on the tinted background, not the previous
 * rounded-rect cutout + white card tooltip. Kept vg_green as the spotlight
 * tint so it doesn't look totally unrelated to the rest of the app.
 */
object CoachMarkOverlay {

    data class Step(val target: View, val title: String, val message: String)

    fun showIfNeeded(activity: Activity, steps: List<Step>) {
        if (UserPrefs.isWalkthroughDone(activity)) return
        if (steps.isEmpty()) return

        val targets = steps.map { step ->
            TapTarget.forView(step.target, step.title, step.message)
                .outerCircleColor(R.color.vg_green)
                .outerCircleAlpha(0.96f)
                .targetCircleColor(android.R.color.white)
                .titleTextSize(20)
                .titleTextColor(android.R.color.white)
                .descriptionTextSize(15)
                .descriptionTextColor(android.R.color.white)
                .dimColor(android.R.color.black)
                .drawShadow(true)
                .cancelable(true)
                .tintTarget(true)
                .transparentTarget(false)
                .targetRadius(50)
        }

        TapTargetSequence(activity)
            .targets(targets)
            .listener(object : TapTargetSequence.Listener {
                override fun onSequenceFinish() {
                    UserPrefs.setWalkthroughDone(activity)
                }

                override fun onSequenceStep(lastTarget: TapTarget?, targetClicked: Boolean) {
                    // No per-step bookkeeping needed - UserPrefs is only
                    // updated once the whole sequence completes or is
                    // dismissed, matching the previous "runs once" gate.
                }

                override fun onSequenceCanceled(lastTarget: TapTarget?) {
                    // User tapped outside a target to dismiss early - treat
                    // this the same as finishing, so the walkthrough doesn't
                    // reappear on next launch.
                    UserPrefs.setWalkthroughDone(activity)
                }
            })
            .start()
    }
}
