package com.vgkontact.app

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * TEMPORARY DEBUG TOOL - catches any crash the app has and saves the full
 * error message to internal storage (always writable, no permissions
 * needed) so OnboardingActivity can show it in an on-screen popup the next
 * time the app opens. See OnboardingActivity.onCreate() for the part that
 * displays it.
 *
 * Remove this whole file, the popup code in OnboardingActivity, and the
 * android:name line in AndroidManifest.xml once the crash is fixed - none
 * of this is meant to ship.
 */
class CrashLoggingApplication : Application() {

    companion object {
        const val CRASH_LOG_FILENAME = "crash_log.txt"
    }

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val fullLog = "CRASH at ${java.util.Date()}\nThread: ${thread.name}\n\n${sw}"

            Log.e("VGKONTACT_CRASH", fullLog)

            try {
                File(filesDir, CRASH_LOG_FILENAME).writeText(fullLog)
            } catch (e: Exception) {
                Log.e("VGKONTACT_CRASH", "Failed to write internal crash log", e)
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
