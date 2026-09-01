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

        val homeTab = activity.findViewById<android.widget.LinearLayout>(R.id.navHomeTab)
        val upgradeTab = activity.findViewById<android.widget.LinearLayout>(R.id.navUpgradeTab)
        val historyTab = activity.findViewById<android.widget.LinearLayout>(R.id.navHistoryTab)
        val profileTab = activity.findViewById<android.widget.LinearLayout>(R.id.navProfileTab)

        val activeTabBackground = ContextCompat.getDrawable(activity, R.drawable.nav_active_tab_background)

        val pairs = listOf(
            Tab.HOME to Triple(homeIcon, homeLabel, homeTab),
            Tab.UPGRADE to Triple(upgradeIcon, upgradeLabel, upgradeTab),
            Tab.HISTORY to Triple(historyIcon, historyLabel, historyTab),
            Tab.PROFILE to Triple(profileIcon, profileLabel, profileTab)
        )
        for ((tab, views) in pairs) {
            val (icon, label, tabContainer) = views
            val isActive = tab == current
            val color = if (isActive) activeColor else inactiveColor
            icon.setColorFilter(color)
            label.setTextColor(color)

            // Active tab gets the soft green capsule (from the floating
            // pill nav concept); inactive tabs keep the plain borderless
            // ripple so taps still show touch feedback.
            if (isActive) {
                tabContainer?.background = activeTabBackground
            } else {
                val outValue = android.util.TypedValue()
                activity.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, outValue, true
                )
                tabContainer?.setBackgroundResource(outValue.resourceId)
            }
        }

        homeTab?.setOnClickListener {
            navigateTo(activity, current, Tab.HOME, MainMenuActivity::class.java)
        }
        upgradeTab?.setOnClickListener {
            navigateTo(activity, current, Tab.UPGRADE, IncreaseLimitActivity::class.java)
        }
        historyTab?.setOnClickListener {
            navigateTo(activity, current, Tab.HISTORY, HistoryActivity::class.java)
        }
        profileTab?.setOnClickListener {
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
