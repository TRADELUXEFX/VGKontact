package com.vgkontact.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "vgkontact_sync"
    private const val CHANNEL_NAME = "Sync Notifications"
    private const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance)
            channel.description = "Notifications for contact sync operations"

            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showSyncCompleteNotification(context: Context, submitted: Int, failed: Int, errorDetail: String? = null) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val message = if (submitted == 0 && failed == 0) {
            "No new numbers"
        } else if (submitted > 0 && failed == 0) {
            val label = if (submitted == 1) "number" else "numbers"
            "$submitted new $label added"
        } else {
            "$submitted new added, $failed failed - tap to retry"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sync Complete")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun showNoInternetNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("No Internet Connection")
            .setContentText("Couldn't sync Kontacts - check your connection and try again")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun showSyncStartedNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Syncing Kontacts")
            .setContentText("Importing contacts from sheet...")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setProgress(0, 0, true)
            .setOngoing(true)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun dismissSyncNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private const val NEW_NUMBERS_NOTIFICATION_ID = 1002

    fun showNewNumbersAvailableNotification(context: Context, count: Int) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val label = if (count == 1) "number" else "numbers"
        val message = "$count new $label ready - tap to import"

        val intent = Intent(context, MainMenuActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New Kontacts Available")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(NEW_NUMBERS_NOTIFICATION_ID, builder.build())
    }

    private const val PERMISSION_WARNING_NOTIFICATION_ID = 1003

    /**
     * Alerts the user in their notification tray when a permission has gone
     * missing - catches the case where an OEM battery manager (or the user
     * themself, in system Settings) silently turns something off and the
     * user hasn't opened the app to see the dashboard's warning banner.
     * Without this, a background sync failure (e.g. contacts permission
     * revoked) could go unnoticed indefinitely.
     */
    fun showPermissionWarningNotification(context: Context, message: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainMenuActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VG Kontact needs attention")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(PERMISSION_WARNING_NOTIFICATION_ID, builder.build())
    }

    private const val LIMIT_WARNING_NOTIFICATION_ID = 1004
    private const val LIMIT_REACHED_NOTIFICATION_ID = 1005

    /**
     * Fired once when the user's FREE (home) group reaches 70% of its cap -
     * a heads-up before it is actually full, so they can unlock more
     * before new kontacts stop being able to add.
     */
    fun showLimitWarningNotification(context: Context, current: Int, limit: Long) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainMenuActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remaining = (limit - current).coerceAtLeast(0L)
        val label = if (remaining == 1L) "spot" else "spots"
        val message = "Only $remaining $label left ($current/$limit) - unlock more before you run out"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Almost at your contact limit")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(LIMIT_WARNING_NOTIFICATION_ID, builder.build())
    }

    /**
     * Fired once when the user's FREE (home) group becomes full - new
     * kontacts can no longer be added, so this is the "you need to act
     * now" notification. Purchased and referred groups never affect it.
     */
    fun showLimitReachedNotification(context: Context, current: Int, limit: Long) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainMenuActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = "You've reached your contact limit ($current/$limit) - unlock more to keep adding kontacts"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Contact limit reached")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(LIMIT_REACHED_NOTIFICATION_ID, builder.build())
    }

    private const val KEY_REDEEMED_NOTIFICATION_ID = 1007

    /**
     * Fired right after a redeem key successfully unlocks more capacity.
     * The screen already shows a Toast for this, but a Toast disappears
     * in a couple seconds and leaves no trace if the user has switched
     * away from the app - unlike every other limit-related event (warning,
     * reached, referral joined), which gets a real notification. This
     * closes that gap so a successful redeem is never silently missed.
     */
    fun showKeyRedeemedNotification(context: Context, submitted: Int) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainMenuActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = if (submitted > 0) {
            val label = if (submitted == 1) "contact was" else "contacts were"
            "$submitted $label unlocked with your key"
        } else {
            "Contact limit increased with your key"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Key redeemed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(KEY_REDEEMED_NOTIFICATION_ID, builder.build())
    }

    private const val DAILY_REPOST_NOTIFICATION_ID = 1008

    /**
     * Persistent daily reminder nudging the user to go reshare the
     * admin's WhatsApp Status. Ongoing (not swipeable away) since this
     * is a recurring daily prompt, not a one-off event - matches the
     * "act now" framing already used by showLimitReachedNotification,
     * but stays up until the next day's refresh rather than
     * auto-cancelling on tap, since the user needs it as a standing
     * reminder to come back to.
     *
     * Points at RepostRedirectActivity rather than building the
     * WhatsApp intent here, so the prefilled message's date is always
     * computed live at tap time rather than frozen to whenever this
     * notification was last (re)built - see that class's doc comment.
     */
    fun showDailyRepostNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val redirectIntent = Intent(context, RepostRedirectActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val repostPendingIntent = PendingIntent.getActivity(
            context, 0, redirectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Grow your views today")
            .setContentText("Tap Repost, open admin's status and reshare it")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Tap Repost, open admin's status and reshare it"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(repostPendingIntent)
            .addAction(R.drawable.ic_notification, "Repost", repostPendingIntent)

        notificationManager.notify(DAILY_REPOST_NOTIFICATION_ID, builder.build())
    }

    private const val INACTIVITY_WARNING_NOTIFICATION_ID = 1009

    fun showInactivityWarningNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainMenuActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = "You haven't opened the app in 5 days. Open it now so you don't lose your status views."
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Don't lose your status views")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(INACTIVITY_WARNING_NOTIFICATION_ID, builder.build())
    }

    fun dismissInactivityWarningNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(INACTIVITY_WARNING_NOTIFICATION_ID)
    }

    private const val REFERRAL_JOINED_NOTIFICATION_ID = 1006

    /**
     * Fired when MainMenuActivity.checkForNewReferrals() detects the
     * user's referral count has gone up since the last check. Own
     * dedicated ID (not the shared NOTIFICATION_ID group) so this can
     * never silently replace an unrelated, possibly-still-unread sync
     * notification, matching the newer per-type-ID pattern the limit
     * notifications above already use rather than the older shared one.
     */
    fun showReferralJoinedNotification(context: Context, gained: Int) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainMenuActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val label = if (gained == 1) "person" else "people"
        val message = "$gained new $label joined using your referral code"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New referral!")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(REFERRAL_JOINED_NOTIFICATION_ID, builder.build())
    }
}
