package com.synckro

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.synckro.data.worker.SyncWorker
import com.synckro.util.logging.FileLoggingTree
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Application entry point. Initialises Hilt, Timber and WorkManager with a
 * Hilt-aware [androidx.work.WorkerFactory] so that workers can use DI.
 *
 * In debug builds we also plant [FileLoggingTree] (writing to
 * `data/data/<applicationId>/files/logs/synckro-debug.log`) and register an
 * uncaught-exception handler that flushes the fatal stack trace to the same
 * file before the process dies. The debug "Export debug log" button on the
 * Accounts screen copies the log to the public Downloads folder so it can be
 * read without a USB cable.
 */
@HiltAndroidApp
class SynckroApp :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            val fileTree = FileLoggingTree(this)
            Timber.plant(fileTree)
            installUncaughtExceptionHandler(fileTree)
            Timber.i(
                "Synckro %s (%s) started. Debug log: %s",
                BuildConfig.VERSION_NAME,
                BuildConfig.APPLICATION_ID,
                fileTree.currentLogPath,
            )
        }
    }

    /**
     * Creates all notification channels required by the app. Safe to call on every launch —
     * creating an already-existing channel is a no-op on API 26+.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel =
                NotificationChannel(
                    SyncWorker.SYNC_CHANNEL_ID,
                    getString(R.string.sync_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.sync_channel_description)
                    setShowBadge(false)
                }
            nm.createNotificationChannel(channel)
        }
    }

    private fun installUncaughtExceptionHandler(fileTree: FileLoggingTree) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Mirror the fatal to Timber so all planted trees (including
                // the file tree) record it, then block briefly for the disk
                // write. Both calls are guarded so a logging-side failure
                // never prevents us from delegating to the previous handler
                // (usually the system one that shows "app has stopped").
                Timber.e(throwable, "Uncaught exception on thread %s", thread.name)
                fileTree.flushBlocking()
            } catch (t: Throwable) {
                // Intentionally swallowed: at this point the process is
                // already going down, and the only thing worse than losing
                // the log entry is losing the system crash dialog too.
                android.util.Log.e("SynckroApp", "Failed to mirror uncaught exception to log", t)
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
                .build()
}
