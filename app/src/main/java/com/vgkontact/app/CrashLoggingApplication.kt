package com.vgkontact.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * TEMPORARY DEBUG TOOL - catches any crash the app has and writes the full
 * error message to a plain text file at:
 *   /storage/emulated/0/Android/data/com.vgkontact.app/files/crash_log.txt
 *
 * Open that file with any file manager app or "Files" app after a crash to
 * see exactly what went wrong, no computer or cable needed.
 *
 * Remove this whole file (and the android:name line in AndroidManifest.xml
 * that points to it) once the crash is fixed - it's not meant to ship.
 */
class CrashLoggingApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))

                val logFile = File(getExternalFilesDir(null), "crash_log.txt")
                logFile.writeText(
                    "CRASH at ${java.util.Date()}\n" +
                    "Thread: ${thread.name}\n\n" +
                    sw.toString()
                )
            } catch (e: Exception) {
                // If writing the log itself fails, there's nothing more we can do here.
            }

            // Still let the app crash normally afterward, so behavior doesn't change -
            // this only adds logging, it doesn't swallow the crash.
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
