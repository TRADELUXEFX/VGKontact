package com.vgkontact.app

import android.content.Context
import androidx.work.CoroutineWorker
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

            val (submitted, failed, errorDetail) = SheetSync.importAllContactsFromSheetSuspend(applicationContext)
            
            if (submitted > 0) {
                NotificationHelper.showNewNumbersAvailableNotification(applicationContext, submitted)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "vgkontact_sheet_check"

        // hours defaults to 24 but can be overridden by the user in Notification Settings
        fun schedule(context: Context, hours: Int = UserPrefs.getNotificationFrequencyHours(context)) {
            val safeHours = hours.coerceAtLeast(1)
            val request = PeriodicWorkRequestBuilder<SheetCheckWorker>(safeHours.toLong(), TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
        }
    }
}
