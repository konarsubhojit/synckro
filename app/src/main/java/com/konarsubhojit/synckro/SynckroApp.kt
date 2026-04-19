package com.konarsubhojit.synckro

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import timber.log.Timber

/**
 * Application entry point. Initialises Hilt, Timber and WorkManager with a
 * Hilt-aware [androidx.work.WorkerFactory] so that workers can use DI.
 */
@HiltAndroidApp
class SynckroApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    /**
     * Performs application startup initialization.
     *
     * Sets up application-wide behavior on process start (for example, enables Timber debug
     * logging when the build is a debug build).
     */
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()
}
