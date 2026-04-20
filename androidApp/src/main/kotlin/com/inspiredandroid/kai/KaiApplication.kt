package com.inspiredandroid.kai

import android.app.Application
import android.util.Log
import com.inspiredandroid.kai.sandbox.sandboxModule
import com.inspiredandroid.kai.setup.FirstRunSetupManager
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "KaiCrash"

class KaiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        startKoin {
            androidContext(this@KaiApplication)
            androidLogger()
            modules(appModule, sandboxModule)
        }
        get<FirstRunSetupManager>(FirstRunSetupManager::class.java).checkAndRun()
    }

    /**
     * Installs a global [Thread.UncaughtExceptionHandler] that:
     * 1. Logs the full stack trace via [Log.e] (visible in logcat / CI)
     * 2. Writes a timestamped crash report to [filesDir]/crashes/ for in-app retrieval
     * 3. Delegates to the original handler so the system can show the crash dialog
     */
    private fun installCrashHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val crashDir = File(filesDir, "crashes").also { it.mkdirs() }

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val report = buildString {
                    appendLine("=== Kai Crash Report ===")
                    appendLine("Time   : $timestamp")
                    appendLine("Thread : ${thread.name} (id=${thread.id})")
                    appendLine("Process: ${android.os.Process.myPid()}")
                    appendLine()
                    appendLine(throwable.stackTraceToString())
                    // Walk the entire cause chain
                    var cause = throwable.cause
                    while (cause != null) {
                        appendLine("--- Caused by ---")
                        appendLine(cause.stackTraceToString())
                        cause = cause.cause
                    }
                }

                Log.e(TAG, report)

                // Keep the last 10 crash reports
                val file = File(crashDir, "crash_$timestamp.txt")
                file.writeText(report)
                crashDir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(10)
                    ?.forEach { it.delete() }

            } catch (_: Exception) {
                // Never let the crash handler itself crash
            } finally {
                original?.uncaughtException(thread, throwable)
            }
        }
    }
}
