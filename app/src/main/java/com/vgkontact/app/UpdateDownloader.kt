package com.vgkontact.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Downloads an update APK in the background using Android's own
 * DownloadManager (no browser involved), then hands the finished file to
 * the system package installer.
 *
 * Flow:
 *   1. start() enqueues the download and reports progress via [onProgress],
 *      polling DownloadManager on a cancellable IO coroutine (not a raw
 *      Thread) so it can be torn down cleanly via cancel() if the caller
 *      goes away mid-download.
 *   2. When DownloadManager finishes, a broadcast fires, we grab the file,
 *      and launch ACTION_VIEW on a FileProvider content:// URI with
 *      REQUEST_INSTALL_PACKAGES-backed install permission.
 *   3. On API 26+, canRequestPackageInstalls() is checked right before
 *      firing that intent, so the caller knows whether Android is about
 *      to interrupt with its own one-time "Allow installs from this app?"
 *      screen instead of the real install confirmation - that lets the UI
 *      say "ALLOW ACCESS" rather than "INSTALL" when a permission screen,
 *      not the installer, is what's really about to appear. On API 24-25
 *      there's no per-app check available (installs from unknown sources
 *      is a single device-wide toggle there, not a per-app permission),
 *      so willNeedPermissionFirst() returns false and we can't predict it
 *      - Android still surfaces its own dialog if it's off, we just can't
 *      give the button more precise copy ahead of time on those two older
 *      versions. Either way this is a hard OS gate no code can skip; the
 *      user allows it once and re-taps.
 */
object UpdateDownloader {

    private const val TAG = "UpdateDownloader"
    private const val APK_SUBDIR = "updates"
    private const val APK_FILENAME = "vgkontact-update.apk"

    private var receiver: BroadcastReceiver? = null
    private var pollingJob: Job? = null

    /** Deletes any previously downloaded update APK. Call when a newer
     * version supersedes it, so a stale file is never reused for install. */
    fun clearDownloadedApk(context: Context) {
        val file = File(File(context.applicationContext.getExternalFilesDir(null), APK_SUBDIR), APK_FILENAME)
        if (file.exists()) file.delete()
    }

    /**
     * Cancels an in-flight download's progress polling and unregisters its
     * completion receiver, without deleting any partially-downloaded file
     * (DownloadManager keeps running the actual download regardless - this
     * only stops OUR listening for it). Call from the hosting activity's
     * onDestroy() so a rotation or back-navigation mid-download doesn't
     * leak a coroutine or a registered receiver.
     */
    fun cancel(context: Context) {
        pollingJob?.cancel()
        pollingJob = null
        receiver?.let {
            try { context.applicationContext.unregisterReceiver(it) } catch (e: Exception) { /* not registered, ignore */ }
        }
        receiver = null
    }

    /**
     * Kicks off the download. Safe to call again if a previous download's
     * receiver is still registered - it will be replaced.
     *
     * @param context Activity context (needed to launch the install intent).
     * @param downloadUrl Direct URL to the .apk file (e.g. a GitHub Release asset).
     * @param latestVersionCode Version this download corresponds to - lets a second
     *        call reuse an already-downloaded file for the same version instead of
     *        re-downloading, while a genuinely newer version correctly triggers a
     *        fresh download.
     * @param onProgress Called periodically with 0-100. Called with -1 on failure.
     * @param onInstallPromptShown Called right before the install intent is launched.
     *        The Boolean is true if Android is expected to show its one-time "allow
     *        installs" screen first instead of the real install confirmation (only
     *        detectable on API 26+ - see class doc).
     */
    fun start(
        context: Context,
        downloadUrl: String,
        latestVersionCode: Int,
        onProgress: (Int) -> Unit,
        onInstallPromptShown: (willNeedPermissionFirst: Boolean) -> Unit
    ) {
        val appContext = context.applicationContext

        // If a prior tap already finished downloading THIS SAME version
        // (e.g. the user backed out of the "allow installs" screen and
        // tapped UPDATE/INSTALL again), skip straight to the install
        // prompt instead of re-downloading. If a newer version has since
        // shipped, the cached file is stale - delete it and re-fetch.
        val existingFile = File(File(appContext.getExternalFilesDir(null), APK_SUBDIR), APK_FILENAME)
        val cachedVersionCode = UserPrefs.getDownloadedUpdateVersionCode(appContext)
        if (existingFile.exists() && cachedVersionCode == latestVersionCode) {
            onProgress(100)
            onInstallPromptShown(willNeedPermissionFirst(appContext))
            launchInstall(appContext, existingFile)
            return
        } else if (existingFile.exists()) {
            existingFile.delete()
        }

        val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val destDir = File(appContext.getExternalFilesDir(null), APK_SUBDIR)
        if (!destDir.exists()) destDir.mkdirs()
        val destFile = File(destDir, APK_FILENAME)

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("VGKontact update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION)
            .setDestinationUri(Uri.fromFile(destFile))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadId = downloadManager.enqueue(request)

        // Unregister any previous receiver before registering a new one.
        receiver?.let {
            try { appContext.unregisterReceiver(it) } catch (e: Exception) { /* not registered, ignore */ }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (completedId != downloadId) return

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                var success = false
                if (cursor.moveToFirst()) {
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(statusIdx)
                    success = status == DownloadManager.STATUS_SUCCESSFUL
                }
                cursor.close()

                try { appContext.unregisterReceiver(this) } catch (e: Exception) { /* ignore */ }
                receiver = null

                if (!success || !destFile.exists()) {
                    Log.w(TAG, "Update download failed or file missing")
                    onProgress(-1)
                    return
                }

                UserPrefs.setDownloadedUpdateVersionCode(appContext, latestVersionCode)
                onProgress(100)
                onInstallPromptShown(willNeedPermissionFirst(appContext))
                launchInstall(appContext, destFile)
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }

        pollProgress(downloadManager, downloadId, onProgress)
    }

    /**
     * Polls DownloadManager every 500ms to report percent progress, using a
     * cancellable IO-dispatcher coroutine rather than a raw Thread. Storing
     * the Job lets cancel() stop this cleanly (e.g. on activity teardown)
     * instead of leaving a background loop running with no listener left
     * to hear it - a raw Thread has no equivalent cancellation hook.
     */
    private fun pollProgress(
        downloadManager: DownloadManager,
        downloadId: Long,
        onProgress: (Int) -> Unit
    ) {
        pollingJob?.cancel()
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            var downloading = true
            while (downloading && isActive) {
                val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                if (cursor.moveToFirst()) {
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(statusIdx)

                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                        downloading = false
                    } else {
                        val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val downloaded = cursor.getLong(bytesIdx)
                        val total = cursor.getLong(totalIdx)
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt()
                            onProgress(percent)
                        } else if (downloaded > 0) {
                            // GitHub Release asset URLs redirect to
                            // objects.githubusercontent.com, and that
                            // response frequently omits Content-Length -
                            // DownloadManager then reports total size as
                            // -1/unknown for the whole download, so the
                            // percent branch above never fires and the
                            // button sits frozen on "UPDATE" until it
                            // jumps straight to 100%. There's no real
                            // percent to report without a total, so this
                            // caps a synthetic climb at 99% based on MB
                            // downloaded (roughly 1% per 200KB, capped)
                            // just so the button visibly moves instead of
                            // looking frozen - true 100% still only fires
                            // from the real completion callback below.
                            val syntheticPercent = ((downloaded / 200_000L).toInt()).coerceIn(1, 99)
                            onProgress(syntheticPercent)
                        }
                    }
                } else {
                    downloading = false
                }
                cursor.close()
                if (downloading) delay(500)
            }
        }
    }

    /**
     * True if Android is expected to interrupt with its own one-time
     * "Allow installs from this app?" screen before the real install
     * confirmation shows. Only detectable on API 26+, where per-app
     * unknown-app-install permission exists as canRequestPackageInstalls().
     * On API 24-25 that gate is a single device-wide toggle with no
     * per-app check available, so this returns false there - Android's
     * own dialog still surfaces if it's off, we just can't give the
     * button more precise copy ahead of time on those older versions.
     */
    private fun willNeedPermissionFirst(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            !context.packageManager.canRequestPackageInstalls()
        } else {
            false
        }
    }

    /**
     * Launches Android's system install prompt for the given APK file. If
     * willNeedPermissionFirst() returned true, this same call is what
     * Android intercepts to show its "allow installs" screen instead -
     * there is no separate code path for that; it's the OS inserting
     * itself before the intent reaches the real installer.
     */
    private fun launchInstall(context: Context, apkFile: File) {
        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch install prompt", e)
        }
    }
}

