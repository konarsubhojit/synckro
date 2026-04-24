package com.konarsubhojit.synckro

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.konarsubhojit.synckro.util.logging.FileLoggingTree
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import timber.log.Timber

/**
 * Application entry point. Initialises Hilt, Timber and WorkManager with a
 * Hilt-aware [androidx.work.WorkerFactory] so that workers can use DI.
 *
 * In debug builds we also plant [FileLoggingTree] (writing to
 * `Android/data/<applicationId>/files/logs/synckro-debug.log`) and register an
 * uncaught-exception handler that flushes the fatal stack trace to the same
 * file before the process dies. This makes bug reports reproducible without
 * needing a USB cable to grab Logcat.
 */
@HiltAndroidApp
class SynckroApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            val fileTree = FileLoggingTree(this)
            Timber.plant(fileTree)
            installUncaughtExceptionHandler(fileTree)
            Timber.i("Synckro %s (%s) started. Debug log: %s",
                BuildConfig.VERSION_NAME, BuildConfig.APPLICATION_ID, fileTree.currentLogPath)
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
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()
}
