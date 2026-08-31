package com.vgkontact.app

import android.app.Activity
import android.content.Intent
import androidx.core.content.ContextCompat
import android.widget.ImageView
import android.widget.TextView

object BottomNavHelper {

    enum class Tab { HOME, UPGRADE, HISTORY, PROFILE }

    fun setup(activity: Activity, current: Tab) {
        val homeIcon = activity.findViewById<ImageView>(R.id.navHomeIcon)
        val upgradeIcon = activity.findViewById<ImageView>(R.id.navUpgradeIcon)
        val historyIcon = activity.findViewById<ImageView>(R.id.navHistoryIcon)
        val profileIcon = activity.findViewById<ImageView>(R.id.navProfileIcon)

        val homeLabel = activity.findViewById<TextView>(R.id.navHomeLabel)
        val upgradeLabel = activity.findViewById<TextView>(R.id.navUpgradeLabel)
        val historyLabel = activity.findViewById<TextView>(R.id.navHistoryLabel)
        val profileLabel = activity.findViewById<TextView>(R.id.navProfileLabel)

        val activeColor = ContextCompat.getColor(activity, R.color.vg_green)
        val inactiveColor = ContextCompat.getColor(activity, R.color.text_muted)

        val pairs = listOf(
            Tab.HOME to (homeIcon to homeLabel),
            Tab.UPGRADE to (upgradeIcon to upgradeLabel),
            Tab.HISTORY to (historyIcon to historyLabel),
            Tab.PROFILE to (profileIcon to profileLabel)
        )
        for ((tab, views) in pairs) {
            val (icon, label) = views
            val color = if (tab == current) activeColor else inactiveColor
            icon.setColorFilter(color)
            label.setTextColor(color)
        }

        activity.findViewById<android.widget.LinearLayout>(R.id.navHomeTab)?.setOnClickListener {
            navigateTo(activity, current, Tab.HOME, MainMenuActivity::class.java)
        }
        activity.findViewById<android.widget.LinearLayout>(R.id.navUpgradeTab)?.setOnClickListener {
            navigateTo(activity, current, Tab.UPGRADE, IncreaseLimitActivity::class.java)
        }
        activity.findViewById<android.widget.LinearLayout>(R.id.navHistoryTab)?.setOnClickListener {
            navigateTo(activity, current, Tab.HISTORY, HistoryActivity::class.java)
        }
        activity.findViewById<android.widget.LinearLayout>(R.id.navProfileTab)?.setOnClickListener {
            navigateTo(activity, current, Tab.PROFILE, ProfileActivity::class.java)
        }
    }

    private fun navigateTo(activity: Activity, current: Tab, target: Tab, destination: Class<*>) {
        if (current == target) return

        val intent = Intent(activity, destination)
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        activity.startActivity(intent)

        if (target == Tab.HOME) {
            activity.finish()
        }
    }
}
