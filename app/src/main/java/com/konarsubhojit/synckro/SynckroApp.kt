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
            // Mirror the fatal to Timber so all planted trees (including the
            // file tree) record it, then block briefly for the disk write.
            Timber.e(throwable, "Uncaught exception on thread %s", thread.name)
            fileTree.flushBlocking()
            // Delegate to whatever was there before (usually the system
            // handler that shows the "app has stopped" dialog).
            previous?.uncaughtException(thread, throwable)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()
}
