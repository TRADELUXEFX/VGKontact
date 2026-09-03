package com.vgkontact.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * App-wide floating "Contact Us" button, styled like WhatsApp's chat
 * bubble that hovers over every screen. Call [attach] once from an
 * activity's onCreate() (after setContentView) to add it.
 *
 * The button is added directly to the activity's content FrameLayout
 * (android.R.id.content) rather than to each screen's own XML, so no
 * layout file needs to be touched and it's guaranteed to float above
 * everything else already on screen, including scrolling content and
 * the bottom nav pill.
 *
 * Reuses the same WhatsApp deep-link support number already used by
 * MainMenuActivity/ProfileActivity/IncreaseLimitActivity's "Contact Us"
 * buttons, so all contact-us entry points in the app go to the same
 * place.
 */
object FloatingContactHelper {

    private const val CONTACT_US_WHATSAPP_NUMBER = "09110321143"
    private const val FAB_TAG = "floating_contact_fab"

    /**
     * @param bottomMarginDp extra bottom margin (in dp) to lift the button
     * above screens that have their own floating bottom nav bar, so it
     * doesn't overlap it. Pass 0 for screens without a bottom nav bar.
     */
    fun attach(activity: Activity, bottomMarginDp: Int = 110) {
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // Avoid adding a second bubble if attach() is somehow called twice
        // (e.g. re-created activity) for the same screen.
        if (contentRoot.findViewWithTag<View>(FAB_TAG) != null) return

        val density = activity.resources.displayMetrics.density
        val sizePx = (60 * density).toInt()
        val marginPx = (18 * density).toInt()
        val bottomMarginPx = (bottomMarginDp * density).toInt()
        val iconPaddingPx = (16 * density).toInt()

        val fab = ImageView(activity).apply {
            tag = FAB_TAG
            id = View.generateViewId()
            setImageResource(R.drawable.ic_chat)
            setColorFilter(ContextCompat.getColor(activity, R.color.white))
            background = ContextCompat.getDrawable(activity, R.drawable.floating_contact_fab_background)
            setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
            elevation = 12 * density
            contentDescription = activity.getString(R.string.menu_contact_us)
            isClickable = true
            isFocusable = true
        }

        val params = FrameLayout.LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = marginPx
            bottomMargin = marginPx + bottomMarginPx
        }

        fab.setOnClickListener { openWhatsAppContactUs(activity) }

        contentRoot.addView(fab, params)
    }

    private fun openWhatsAppContactUs(activity: Activity) {
        val message = Uri.encode("Hi VG Kontact, I need help with...")
        val uri = Uri.parse("https://wa.me/$CONTACT_US_WHATSAPP_NUMBER?text=$message")
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(activity, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
