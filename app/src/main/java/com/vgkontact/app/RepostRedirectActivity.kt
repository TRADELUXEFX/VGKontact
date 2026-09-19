package com.vgkontact.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Invisible pass-through screen the daily repost notification launches
 * into. Exists only so the "today's date" in the prefilled WhatsApp
 * message is computed at the moment the user actually taps the
 * notification, not baked in back when the notification was first
 * built (which could be days earlier if the user hasn't reopened the
 * app - a PendingIntent's Intent extras are frozen at creation time,
 * so there's no way to get a live date without a real component
 * running at tap time).
 *
 * Being a real Activity (not a BroadcastReceiver) means this is never
 * subject to Android's background-activity-launch restriction that
 * blocked the earlier RepostActionReceiver approach - the system
 * itself is doing the launching either way, whether that's this
 * activity's manifest entry or the WhatsApp hand-off it performs a
 * moment later.
 *
 * No layout, no visible frame - it opens, redirects, and closes in one
 * motion so the user never perceives it as a separate screen.
 */
class RepostRedirectActivity : Activity() {

    companion object {
        // Same admin number as FloatingContactHelper.CONTACT_US_WHATSAPP_NUMBER
        private const val ADMIN_WHATSAPP_NUMBER = "09110321143"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val today = SimpleDateFormat("MMM d", Locale.US).format(Date())
        val message = Uri.encode("I've reposted today's post ($today)")
        val uri = Uri.parse("https://wa.me/$ADMIN_WHATSAPP_NUMBER?text=$message")
        val waIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            startActivity(waIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }

        finish()
    }
}
