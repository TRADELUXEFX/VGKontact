package com.vgkontact.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Handles the "Repost" action button on the daily repost notification.
 * Opens a WhatsApp chat with the admin number - same wa.me pattern
 * already used by FloatingContactHelper's "Contact Us" bubble, so all
 * admin-WhatsApp entry points in the app share one number and one
 * failure path. The user's own job from there (going to the admin's
 * Status tab and tapping reshare) happens inside WhatsApp itself and
 * isn't something this app can trigger directly - there's no intent
 * WhatsApp exposes for "reshare this status", so this only gets the
 * user to the chat; the notification copy is what tells them what to
 * do next.
 */
class RepostActionReceiver : BroadcastReceiver() {

    companion object {
        // Same admin number as FloatingContactHelper.CONTACT_US_WHATSAPP_NUMBER
        private const val ADMIN_WHATSAPP_NUMBER = "09110321143"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val uri = Uri.parse("https://wa.me/$ADMIN_WHATSAPP_NUMBER")
        val waIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(waIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
