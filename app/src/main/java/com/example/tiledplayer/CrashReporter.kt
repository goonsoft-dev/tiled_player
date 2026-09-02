package com.example.tiledplayer

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "TiledPlayerCrash"
private const val CRASH_FILE = "last_crash.txt"

/**
 * A last-resort diagnostic net: writes any uncaught exception's full stack
 * trace to a file in internal storage before letting the normal Android crash
 * flow proceed (still calls the previous handler — this never swallows a
 * fatal exception or tries to keep the process limping along). The physical
 * test device is often unreachable over WiFi adb right when a crash happens,
 * so a live logcat capture can't be relied on; a file that survives process
 * death lets the next session (or an adb pull whenever the device is finally
 * reachable) recover the exact cause instead of only "it crashed."
 */
object CrashReporter {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashFile(appContext, thread, throwable) }
                .onFailure { Log.e(TAG, "Failed to persist crash log", it) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = "Crash at $timestamp on thread \"${thread.name}\"\n$sw"
        File(context.filesDir, CRASH_FILE).writeText(report)
    }

    /** Returns and clears the last recorded crash, if any (shown once per crash). */
    fun consumeLastCrash(context: Context): String? {
        val file = File(context.filesDir, CRASH_FILE)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()?.also { file.delete() }
    }
}
