package com.synckro.data.watcher

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.synckro.MainActivity
import com.synckro.R
import com.synckro.domain.sync.LocalChangeWatcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that hosts [LocalChangeWatcher] registrations for Instant Sync pairs.
 *
 * The service exists so watching survives the app leaving the foreground: Android only keeps
 * `ContentObserver`/`FileObserver` registrations alive while the process does. It runs with the
 * `dataSync` foreground-service type and a persistent, low-importance notification (see
 * `docs/notifications.md`).
 *
 * Watching itself never transfers bytes. Change notifications are coarse prompts that the pair's
 * sync work will act on, so losing the host only degrades Instant Sync to the pair's periodic
 * schedule.
 *
 * Lifecycle decisions — including whether the platform even permits a start — belong to
 * [WatcherServiceController]; this class only reconciles registrations while it is alive and stops
 * itself as soon as no pair is watchable.
 */
@AndroidEntryPoint
class InstantSyncWatcherService : Service() {
    @Inject lateinit var watchablePairs: WatchablePairs

    @Inject lateinit var watcher: LocalChangeWatcher

    @Inject lateinit var controller: WatcherServiceController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var coordinator: WatcherRegistrationCoordinator? = null
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // A null intent means the system re-created a sticky service after process death.
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        controller.onHostStarted()
        startObserving()
        return START_STICKY
    }

    override fun onDestroy() {
        observeJob = null
        serviceScope.cancel()
        coordinator?.unregisterAll()
        coordinator = null
        controller.onHostStopped()
        super.onDestroy()
    }

    /**
     * Calls `startForeground` with the API-appropriate service type.
     *
     * @return false when the platform refused the promotion, in which case the caller must stop the
     *   service rather than risk an ANR-style `RemoteServiceException`.
     */
    private fun promoteToForeground(): Boolean =
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+).
            Timber.w(e, "Watcher host could not enter the foreground")
            false
        } catch (e: SecurityException) {
            // Missing FOREGROUND_SERVICE_DATA_SYNC permission or an exhausted platform quota.
            Timber.w(e, "Watcher host was denied the dataSync foreground type")
            false
        }

    private fun startObserving() {
        if (observeJob?.isActive == true) return
        val registrations =
            coordinator ?: WatcherRegistrationCoordinator(watcher).also { coordinator = it }
        observeJob =
            serviceScope.launch {
                watchablePairs.observe().collect { desiredPairIds ->
                    if (desiredPairIds.isEmpty()) {
                        Timber.i("No watchable pairs remain; stopping watcher host")
                        stopSelf()
                    } else {
                        val watched = registrations.reconcile(desiredPairIds)
                        Timber.d("Watcher host observing %d pair(s)", watched.size)
                    }
                }
            }
    }

    private fun buildNotification(): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, WATCHER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.watcher_notification_title))
            .setContentText(getString(R.string.watcher_notification_content))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        /** Starts (or reconciles) the watcher host. */
        const val ACTION_START = "com.synckro.action.START_WATCHERS"

        /** Stops the watcher host. */
        const val ACTION_STOP = "com.synckro.action.STOP_WATCHERS"

        /**
         * Notification channel for the persistent watcher notification. Created by
         * `SynckroApp.createNotificationChannels()`.
         */
        const val WATCHER_CHANNEL_ID = "synckro_watcher"

        /** Distinct from SyncWorker's per-pair progress notification ids. */
        private const val NOTIFICATION_ID = 200_000
    }
}
