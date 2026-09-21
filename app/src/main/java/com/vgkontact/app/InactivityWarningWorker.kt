package com.vgkontact.app

import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Reminds the user to open the app before the server marks them inactive.
 *
 * A one-off timer is (re)started every time the server confirms a
 * check-in (see SheetSync.syncCheckin / stampLastSyncedAt). As long as
 * the user keeps syncing or opening the app, the timer keeps getting
 * pushed back and never fires. It only fires when 5 days pass with no
 * check-in at all.
 */
class InactivityWarningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext
        // Nothing to warn about for banned, paused or signed-out users.
        if (!UserPrefs.isRegistered(c) || UserPrefs.isBanned(c) || UserPrefs.isSyncPaused(c)) {
            return Result.success()
        }
        NotificationHelper.showInactivityWarningNotification(c)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "vgkontact_inactivity_warning"
        private const val WARN_AFTER_DAYS = 5L

        fun reschedule(context: Context) {
            val app = context.applicationContext
            // User is active again - take down a warning that may still be showing.
            NotificationHelper.dismissInactivityWarningNotification(app)
            val request = OneTimeWorkRequestBuilder<InactivityWarningWorker>()
                .setInitialDelay(WARN_AFTER_DAYS, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
