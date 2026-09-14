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
 *   2. When DownloadManager finishes, completion is detected by whichever
 *      of two independent signals notices first - the progress poller
 *      seeing a terminal status, or the ACTION_DOWNLOAD_COMPLETE broadcast
 *      (which is not reliably delivered on every device/OEM) - then we
 *      grab the file and launch ACTION_VIEW on a FileProvider content://
 *      URI with REQUEST_INSTALL_PACKAGES-backed install permission.
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
    private var activeDownloadId: Long? = null
    private var activeVersionCode: Int? = null
    private var activeDownloadManager: DownloadManager? = null

    /** Guards against finishDownload() firing twice for the same download -
     * now that both pollProgress() and the ACTION_DOWNLOAD_COMPLETE
     * receiver can independently detect completion (see pollProgress doc),
     * whichever notices first should win and the other should no-op. */
    private var finishedDownloadIds: MutableSet<Long> = mutableSetOf()

    /** State UpdateDownloader is actually in right now, for a caller (e.g.
     * onResume) to re-sync its UI to instead of guessing. */
    sealed class State {
        object Idle : State()
        data class InProgress(val versionCode: Int) : State()
        data class ReadyToInstall(val versionCode: Int) : State()
    }

    /**
     * Reports what UpdateDownloader is actually doing right now, so a
     * caller re-created after a transient teardown (activity backgrounded
     * to grant the install-unknown-apps permission, or a rotation) can
     * re-sync its banner instead of relying on stale in-memory UI state.
     * Checks, in order: a download this object is still actively polling
     * in-process; an in-flight download recorded in UserPrefs from before
     * teardown (the object singleton itself survives activity recreation,
     * but not full process death, so this prefs flag is the backstop for
     * that case too); then a completed file already on disk.
     */
    fun currentState(context: Context, latestVersionCode: Int): State {
        val appContext = context.applicationContext

        if (pollingJob?.isActive == true && activeVersionCode == latestVersionCode) {
            return State.InProgress(latestVersionCode)
        }

        val existingFile = File(File(appContext.getExternalFilesDir(null), APK_SUBDIR), APK_FILENAME)
        val cachedVersionCode = UserPrefs.getDownloadedUpdateVersionCode(appContext)
        if (existingFile.exists() && cachedVersionCode == latestVersionCode) {
            return State.ReadyToInstall(latestVersionCode)
        }

        val inFlightVersionCode = UserPrefs.getInFlightUpdateVersionCode(appContext)
        if (inFlightVersionCode == latestVersionCode) {
            return State.InProgress(latestVersionCode)
        }

        return State.Idle
    }

    /**
     * Re-attaches progress/completion callbacks to a download already
     * enqueued in DownloadManager after this object's receiver/pollingJob
     * got cleared (e.g. by a stale cancel() call, or full process death
     * that survived because DownloadManager itself is a system service).
     * Falls back to a fresh start() if no matching DownloadManager entry
     * can be found (e.g. it finished and DownloadManager already dropped
     * the row) or if this object's in-process state is missing entirely -
     * either way the caller ends up correctly attached, never stuck.
     */
    fun reattach(
        context: Context,
        downloadUrl: String,
        latestVersionCode: Int,
        onProgress: (Int) -> Unit,
        onInstallPromptShown: (willNeedPermissionFirst: Boolean) -> Unit
    ) {
        val appContext = context.applicationContext

        when (val state = currentState(appContext, latestVersionCode)) {
            is State.ReadyToInstall -> {
                val existingFile = File(File(appContext.getExternalFilesDir(null), APK_SUBDIR), APK_FILENAME)
                onProgress(100)
                onInstallPromptShown(willNeedPermissionFirst(appContext))
                launchInstall(appContext, existingFile)
            }
            is State.InProgress -> {
                if (pollingJob?.isActive == true && activeVersionCode == latestVersionCode && activeDownloadId != null) {
                    // Still tracked in-process (this object survived, only the
                    // caller was recreated) - just re-register the receiver and
                    // resume polling so progress/completion reach the new caller.
                    val downloadManager = activeDownloadManager
                        ?: (appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
                    val destFile = File(File(appContext.getExternalFilesDir(null), APK_SUBDIR), APK_FILENAME)
                    registerCompletionReceiver(appContext, downloadManager, activeDownloadId!!, latestVersionCode, onProgress, onInstallPromptShown)
                    pollProgress(appContext, downloadManager, activeDownloadId!!, latestVersionCode, destFile, onProgress, onInstallPromptShown)
                } else {
                    // In-flight per UserPrefs but this object's own tracking is
                    // gone (full process death) - no downloadId survived to
                    // re-attach to, so the only correct move is to start fresh;
                    // start() itself handles deleting any stale partial file.
                    start(appContext, downloadUrl, latestVersionCode, onProgress, onInstallPromptShown)
                }
            }
            is State.Idle -> {
                start(appContext, downloadUrl, latestVersionCode, onProgress, onInstallPromptShown)
            }
        }
    }

    /** Deletes any previously downloaded update APK. Call when a newer
     * version supersedes it, so a stale file is never reused for install. */
    fun clearDownloadedApk(context: Context) {
        val file = File(File(context.applicationContext.getExternalFilesDir(null), APK_SUBDIR), APK_FILENAME)
        if (file.exists()) file.delete()
    }

    /**
     * Stops THIS caller's progress polling and unregisters its completion
     * receiver, without deleting any partially-downloaded file and without
     * abandoning the download - DownloadManager keeps running it regardless
     * (this only stops OUR listening for it), and UserPrefs' in-flight flag
     * is left intact so a later reattach()/onResume can tell the download
     * is still going and re-sync to it. Previously this was called from
     * onDestroy() unconditionally, which also fires on transient teardown
     * (backgrounding to grant the install-unknown-apps permission), wiping
     * the only thing tracking that download - do not call this from
     * onDestroy() for that reason; call it only for a genuine user-cancel.
     */
    fun cancel(context: Context) {
        pollingJob?.cancel()
        pollingJob = null
        activeDownloadId?.let { id -> synchronized(finishedDownloadIds) { finishedDownloadIds.remove(id) } }
        activeDownloadId = null
        activeVersionCode = null
        activeDownloadManager = null
        receiver?.let {
            try { context.applicationContext.unregisterReceiver(it) } catch (e: Exception) { /* not registered, ignore */ }
        }
        receiver = null
        UserPrefs.clearInFlightUpdateVersionCode(context)
    }

    /**
     * Stops only THIS caller's listening (receiver + polling) without
     * touching the in-flight tracking in UserPrefs or activeDownloadId -
     * the download keeps running and stays discoverable for a future
     * reattach(). This is the safe call for transient teardown (onDestroy
     * from backgrounding), as opposed to cancel() which is for a genuine
     * user-initiated abandon.
     */
    fun detachListenersOnly(context: Context) {
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

            .setDestinationUri(Uri.fromFile(destFile))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadId = downloadManager.enqueue(request)
        activeDownloadId = downloadId
        activeVersionCode = latestVersionCode
        activeDownloadManager = downloadManager
        UserPrefs.setInFlightUpdateVersionCode(appContext, latestVersionCode)

        registerCompletionReceiver(appContext, downloadManager, downloadId, latestVersionCode, onProgress, onInstallPromptShown)
        pollProgress(appContext, downloadManager, downloadId, latestVersionCode, destFile, onProgress, onInstallPromptShown)
    }

    /**
     * Registers the broadcast receiver that fires when DownloadManager
     * finishes this download, verifies it succeeded, persists the
     * completed version code, and hands off to the installer. Extracted
     * out of start() so reattach() can register the same completion
     * handling against a download it's re-attaching to rather than one it
     * just enqueued itself.
     */
    private fun registerCompletionReceiver(
        appContext: Context,
        downloadManager: DownloadManager,
        downloadId: Long,
        latestVersionCode: Int,
        onProgress: (Int) -> Unit,
        onInstallPromptShown: (willNeedPermissionFirst: Boolean) -> Unit
    ) {
        // Unregister any previous receiver before registering a new one.
        receiver?.let {
            try { appContext.unregisterReceiver(it) } catch (e: Exception) { /* not registered, ignore */ }
        }

        val destFile = File(File(appContext.getExternalFilesDir(null), APK_SUBDIR), APK_FILENAME)

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

                // pollProgress() may have already detected STATUS_SUCCESSFUL
                // and called finishDownload() itself if this broadcast was
                // slow, batched, or dropped by the OS (common on OEM battery
                // optimizers) - don't double-fire if so.
                synchronized(finishedDownloadIds) {
                    if (!finishedDownloadIds.add(downloadId)) return
                }
                finishDownload(appContext, success && destFile.exists(), destFile, latestVersionCode, onProgress, onInstallPromptShown)
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    /**
     * Shared success/failure handling once a download is known to be
     * finished - clears in-flight tracking either way, then either reports
     * failure or persists completion and launches the installer.
     */
    private fun finishDownload(
        appContext: Context,
        success: Boolean,
        destFile: File,
        latestVersionCode: Int,
        onProgress: (Int) -> Unit,
        onInstallPromptShown: (willNeedPermissionFirst: Boolean) -> Unit
    ) {
        activeDownloadId = null
        activeVersionCode = null
        activeDownloadManager = null
        UserPrefs.clearInFlightUpdateVersionCode(appContext)

        if (!success) {
            Log.w(TAG, "Update download failed or file missing")
            onProgress(-1)
            return
        }

        UserPrefs.setDownloadedUpdateVersionCode(appContext, latestVersionCode)
        onProgress(100)
        onInstallPromptShown(willNeedPermissionFirst(appContext))
        launchInstall(appContext, destFile)
    }

    /**
     * Polls DownloadManager every 500ms to report percent progress, using a
     * cancellable IO-dispatcher coroutine rather than a raw Thread. Storing
     * the Job lets cancel() stop this cleanly (e.g. on activity teardown)
     * instead of leaving a background loop running with no listener left
     * to hear it - a raw Thread has no equivalent cancellation hook.
     *
     * Previously this loop just set downloading = false and quietly
     * returned once it saw STATUS_SUCCESSFUL/STATUS_FAILED, leaving
     * completion entirely up to the separate ACTION_DOWNLOAD_COMPLETE
     * broadcast receiver. That broadcast is not reliable on every device -
     * OEM battery optimizers (MIUI, Samsung, etc.) and Doze can delay it,
     * batch it, or drop it outright - so the button could sit frozen at
     * whatever percent it last reported and never reach 100%/INSTALL even
     * though the file had already finished downloading. Now the poller
     * detects the terminal status itself and drives completion directly;
     * the broadcast receiver becomes a fast-path that's a no-op if the
     * poller already handled it (see finishedDownloadIds).
     */
    private fun pollProgress(
        appContext: Context,
        downloadManager: DownloadManager,
        downloadId: Long,
        latestVersionCode: Int,
        destFile: File,
        onProgress: (Int) -> Unit,
        onInstallPromptShown: (willNeedPermissionFirst: Boolean) -> Unit
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
                        cursor.close()

                        val success = status == DownloadManager.STATUS_SUCCESSFUL
                        // Whichever of {poller, broadcast receiver} notices
                        // completion first wins; the other becomes a no-op.
                        val shouldFinish = synchronized(finishedDownloadIds) {
                            finishedDownloadIds.add(downloadId)
                        }
                        if (shouldFinish) {
                            receiver?.let {
                                try { appContext.unregisterReceiver(it) } catch (e: Exception) { /* ignore */ }
                            }
                            receiver = null
                            finishDownload(appContext, success && destFile.exists(), destFile, latestVersionCode, onProgress, onInstallPromptShown)
                        }
                        break
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
                            // once a terminal status is seen above.
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

