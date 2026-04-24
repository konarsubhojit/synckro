package com.konarsubhojit.synckro.util.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * [Timber.Tree] that appends log records to a file under the app's external
 * files directory (`Android/data/<applicationId>/files/logs/`). This path is
 * app-scoped on Android 11+, requires no runtime permission, and is readable
 * by the user via a file manager or `adb pull` — ideal for debug builds where
 * developers and testers need to share logs without a USB cable.
 *
 * Writes happen on a single-threaded executor so callers never block on disk
 * I/O. The current log file is rotated when it exceeds [maxFileBytes];
 * [maxBackups] previous files are retained and older ones are deleted.
 *
 * Only intended for debug builds — release builds should not plant this tree,
 * to avoid leaking information onto external storage.
 */
class FileLoggingTree(
    context: Context,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxBackups: Int = DEFAULT_MAX_BACKUPS,
) : Timber.Tree() {

    private val logDir: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "logs",
    ).apply { mkdirs() }

    private val logFile: File = File(logDir, "synckro-debug.log")
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "synckro-file-logger").apply { isDaemon = true }
    }
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * Absolute path to the current log file. Exposed so a debug "About"
     * screen or error dialog can tell the user where to find the logs.
     */
    val currentLogPath: String get() = logFile.absolutePath

    /**
     * Writes a log entry asynchronously. Non-blocking for callers.
     */
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val timestamp = dateFormat.get()!!.format(Date())
        val level = priorityLabel(priority)
        val head = "$timestamp $level/${tag ?: "Synckro"}: $message"
        val body = if (t == null) head else "$head\n${stackTraceOf(t)}"
        executor.execute { appendSafely(body) }
    }

    private fun appendSafely(line: String) {
        try {
            rotateIfNeeded()
            logFile.appendText(line + "\n", Charsets.UTF_8)
        } catch (e: Throwable) {
            // Fall back to Logcat so a disk-full / permission error doesn't
            // silently swallow the very error we're trying to diagnose.
            Log.w("FileLoggingTree", "Failed to write log entry", e)
        }
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < maxFileBytes) return
        // Shift existing .N files up by one and drop the oldest.
        for (i in maxBackups downTo 1) {
            val src = backupFile(i - 1)
            val dst = backupFile(i)
            if (src.exists()) {
                if (dst.exists()) dst.delete()
                src.renameTo(dst)
            }
        }
        if (logFile.exists()) logFile.renameTo(backupFile(1))
    }

    private fun backupFile(index: Int): File =
        if (index <= 0) logFile else File(logDir, "synckro-debug.log.$index")

    /**
     * Best-effort flush: block briefly for any queued writes to complete.
     * Useful from an uncaught-exception handler so the fatal log entry
     * actually makes it onto disk before the process dies.
     */
    fun flushBlocking(timeoutMs: Long = 1000L) {
        val latch = java.util.concurrent.CountDownLatch(1)
        executor.execute { latch.countDown() }
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun priorityLabel(priority: Int): String = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> "?"
    }

    private fun stackTraceOf(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return sw.toString()
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES: Long = 1L * 1024 * 1024 // 1 MB
        const val DEFAULT_MAX_BACKUPS: Int = 3
    }
}
