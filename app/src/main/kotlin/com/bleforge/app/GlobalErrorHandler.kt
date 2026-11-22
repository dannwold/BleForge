/*
 * ==============================================================================
 * FILE: GlobalErrorHandler.kt
 * CURRENT VERSION: v1.0.9
 * CHANGELOG:
 *   - v1.0.1: Initial GlobalErrorBus (CoroutineExceptionHandler) + GlobalCrashHandler.
 *   - v1.0.5: Added CrashLogStore to persist stack traces to filesDir/last_crash.txt before kill.
 *   - v1.0.9: Appended detailed KDoc & inline comments explaining zero-logcat fault recovery.
 *
 * HOW THIS ERROR HANDLER WORKS:
 *   - Layer 1 (GlobalErrorBus): Catches uncaught coroutine exceptions via CoroutineExceptionHandler
 *     and emits AppError to the UI ErrorBanner without crashing the app.
 *   - Layer 2 (CrashLogStore): Writes any fatal thread exception stack trace to internal storage
 *     so even background crashes (where Android blocks popup activities) surface on next launch.
 *   - Layer 3 (GlobalCrashHandler): Launches CrashRecoveryActivity in a separate :crash_recovery
 *     process with Copy Trace and Restart App actions before terminating the crashed process.
 * ==============================================================================
 */

package com.bleforge.app

import android.content.Context
import android.content.Intent
import com.bleforge.core.model.AppError
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object GlobalErrorBus {
    private val _currentError = MutableStateFlow<AppError?>(null)
    val currentError: StateFlow<AppError?> = _currentError.asStateFlow()

    fun report(title: String, message: String, throwable: Throwable? = null) {
        _currentError.value = AppError(title, message, throwable)
    }
    fun clear() { _currentError.value = null }

    val coroutineHandler = CoroutineExceptionHandler { _, throwable ->
        report("Background Operation Error", throwable.localizedMessage ?: "Unexpected error occurred", throwable)
    }
}

object CrashLogStore {
    private const val FILE_NAME = "last_crash.txt"
    fun save(context: Context, trace: String) {
        runCatching { File(context.filesDir, FILE_NAME).writeText(trace) }
    }
    fun consume(context: Context): String? = runCatching {
        val f = File(context.filesDir, FILE_NAME)
        if (f.exists()) f.readText().also { f.delete() } else null
    }.getOrNull()
}

class GlobalCrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val trace = sw.toString()
        CrashLogStore.save(context, trace)
        try {
            val intent = Intent(context, CrashRecoveryActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(CrashRecoveryActivity.EXTRA_STACK_TRACE, trace)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            defaultHandler?.uncaughtException(thread, throwable)
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
