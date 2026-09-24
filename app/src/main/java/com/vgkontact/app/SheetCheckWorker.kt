package com.vgkontact.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class SheetCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Check permission health on every background run - this is the
            // one place that can catch a silently-revoked permission (e.g.
            // an OEM battery manager turning off background activity) even
            // if the user hasn't opened the app to see the dashboard's
            // warning banner. Only notify when something is actually wrong,
            // so this stays silent on every normal healthy run.
            val status = PermissionHealth.check(applicationContext)
            if (status.severity != PermissionHealth.Severity.NONE) {
                NotificationHelper.showPermissionWarningNotification(applicationContext, status.message())
            }

            if (!status.contactsGranted) {
                // Can't sync at all without this - no point attempting the
                // import, the warning notification above already told the
                // user why.
                return Result.success()
            }

            if (UserPrefs.isBanned(applicationContext)) {
                // Banned: nothing to sync, and no "new numbers" notification.
                return Result.success()
            }

            if (UserPrefs.isSyncPaused(applicationContext)) {
                // User tapped "Delete My Contacts" - checked locally, so
                // this blocks syncing instantly and even offline, without
                // depending on any server round-trip to take effect.
                return Result.success()
            }

            if (!isOnline(applicationContext)) {
                // No user-facing notice here on purpose: every failure
                // reason is designed to self-heal without the user doing
                // anything, so telling them adds no useful action they
                // could take - it would just be noise, especially for
                // anyone who's regularly offline for stretches at a time.
                // scheduleRetryOnReconnect() is the actual fix: it retries
                // the instant the phone has a connection again.
                scheduleRetryOnReconnect(applicationContext)
                return Result.success()
            }

            val (submitted, failed, errorDetail) = SheetSync.importAllContactsFromSheetSuspend(applicationContext)

            if (errorDetail == "NO_INTERNET") {
                // Went offline between the isOnline() check above and the
                // actual sync attempt (race condition, rare but possible).
                scheduleRetryOnReconnect(applicationContext)
            } else if (errorDetail == "BANNED") {
                // Banned users are handled elsewhere - nothing to retry.
            } else if (submitted == 0 && failed > 0) {
                // Reached (or tried to reach) the server but nothing could
                // be synced - could be a server hiccup, timeout, etc. Same
                // reasoning as no-internet: this is just as likely to be
                // transient, so retry automatically instead of asking the
                // user to notice and act. The retry only requires a live
                // network connection, which will already be true again
                // almost immediately in most transient-failure cases.
                scheduleRetryOnReconnect(applicationContext)
            }

            if (submitted > 0) {
                NotificationHelper.showNewNumbersAvailableNotification(applicationContext, submitted)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    /**
     * Local connectivity peek - does NOT gate whether this worker runs
     * (see schedule() below: no NetworkType constraint, on purpose, so
     * the worker still runs and can detect it's offline). This only lets
     * doWork() skip straight to queuing the reconnect retry instead of
     * wasting a network round-trip it already knows will fail.
     */
    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Queues a ONE-TIME run that Android holds until the phone has a
     * network connection, then fires - no waiting for the next scheduled
     * interval and no action needed from the user. This is the actual
     * fix: no failure notification is shown for any reason (see
     * doWork() above), because every failure case here is treated as
     * self-healing, so this retry is what actually guarantees the sync
     * eventually happens. Reuses this same worker class, so the retry
     * gets identical behavior (permission/ban/pause checks, the
     * new-numbers notice) - nothing extra to keep in sync between paths.
     *
     * A short linear backoff (starting at 30s) is set so a server-side
     * failure - where the network is already connected, so the
     * NetworkType.CONNECTED constraint below is satisfied immediately -
     * doesn't retry instantly and hammer a server that's already
     * struggling. For the no-internet case this has no real effect: the
     * retry was already waiting on the network constraint regardless, so
     * it fires as soon as a connection appears either way.
     *
     * Own unique work name (separate from the periodic WORK_NAME below)
     * so this doesn't collide with or cancel the regular schedule.
     * ExistingWorkPolicy.REPLACE means repeated failures before the
     * retry succeeds just refresh this one pending job, not stack up
     * several redundant ones that would all fire at once.
     */
    private fun scheduleRetryOnReconnect(context: Context) {
        val request = OneTimeWorkRequestBuilder<SheetCheckWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            RETRY_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    companion object {
        private const val WORK_NAME = "vgkontact_sheet_check"
        private const val RETRY_WORK_NAME = "vgkontact_sheet_check_retry"

        // hours defaults to 24 but can be overridden by the user in Notification Settings.
        //
        // keepExisting = true is for the app-launch call: if a schedule is
        // already registered it is left alone, so its countdown is NOT
        // restarted every time the app opens (restarting it on each launch
        // meant a user who opens the app daily could push the background
        // sync back forever and never get one). If nothing is registered yet
        // it is created as normal.
        //
        // keepExisting = false (the default) is for when the user picks a new
        // interval: the old schedule is replaced so the new interval applies.
        fun schedule(
            context: Context,
            hours: Int = UserPrefs.getNotificationFrequencyHours(context),
            keepExisting: Boolean = false
        ) {
            val safeHours = hours.coerceAtLeast(1)
            val request = PeriodicWorkRequestBuilder<SheetCheckWorker>(safeHours.toLong(), TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                if (keepExisting) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
        }
    }
}
