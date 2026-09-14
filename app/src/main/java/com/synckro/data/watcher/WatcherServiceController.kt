package com.synckro.data.watcher

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.synckro.domain.sync.WatcherLifecycleAction
import com.synckro.domain.sync.WatcherLifecyclePolicy
import com.synckro.domain.sync.WatcherLifecycleTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Starts and stops the watcher host. Abstracted so the controller is unit-testable. */
interface WatcherServiceStarter {
    /** Requests a foreground start; returns false when the platform refused it. */
    fun start(): Boolean

    fun stop()
}

/** [WatcherServiceStarter] backed by [InstantSyncWatcherService]. */
@Singleton
class ContextWatcherServiceStarter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : WatcherServiceStarter {
        override fun start(): Boolean =
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, InstantSyncWatcherService::class.java)
                        .setAction(InstantSyncWatcherService.ACTION_START),
                )
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException:
                // the app is in the background without a start exemption.
                Timber.w(e, "Watcher foreground service start was not allowed")
                false
            } catch (e: SecurityException) {
                // API 34+ rejects a dataSync start when the type permission is missing or the
                // platform quota for this service type is exhausted.
                Timber.w(e, "Watcher foreground service start was rejected")
                false
            }

        override fun stop() {
            context.stopService(Intent(context, InstantSyncWatcherService::class.java))
        }
    }

/**
 * Single entry point for the watcher host lifecycle.
 *
 * Every trigger (app foreground, boot, app update, configuration change) funnels through
 * [evaluate], which applies [WatcherLifecyclePolicy] and then starts or stops
 * [InstantSyncWatcherService]. A refused start is never retried from the background: the host is
 * restored the next time the app is in the foreground, while durable periodic work keeps syncing.
 */
@Singleton
class WatcherServiceController
    @Inject
    constructor(
        private val watchablePairs: WatchablePairs,
        private val starter: WatcherServiceStarter,
        private val policy: WatcherLifecyclePolicy,
    ) {
        @Volatile
        private var isHostRunning: Boolean = false

        /** Serializes evaluations so concurrent triggers cannot start or stop the host twice. */
        private val evaluationMutex = Mutex()

        /** Applies the lifecycle policy for [trigger] and returns the action that was taken. */
        suspend fun evaluate(trigger: WatcherLifecycleTrigger): WatcherLifecycleAction =
            evaluationMutex.withLock { evaluateLocked(trigger) }

        private suspend fun evaluateLocked(trigger: WatcherLifecycleTrigger): WatcherLifecycleAction {
            val watchablePairIds = watchablePairs.current()
            val action =
                policy.decide(
                    trigger = trigger,
                    hasWatchablePairs = watchablePairIds.isNotEmpty(),
                    isHostRunning = isHostRunning,
                )
            return when (action) {
                WatcherLifecycleAction.START ->
                    if (starter.start()) {
                        action
                    } else {
                        isHostRunning = false
                        WatcherLifecycleAction.DEFER_UNTIL_APP_FOREGROUND
                    }
                WatcherLifecycleAction.STOP -> {
                    if (isHostRunning) starter.stop()
                    action
                }
                WatcherLifecycleAction.DEFER_UNTIL_APP_FOREGROUND -> {
                    Timber.i(
                        "Deferring watcher host start for trigger %s; periodic sync remains active",
                        trigger,
                    )
                    action
                }
            }
        }

        /** Called by [InstantSyncWatcherService] once it is running in the foreground. */
        fun onHostStarted() {
            isHostRunning = true
        }

        /** Called by [InstantSyncWatcherService] when it stops for any reason. */
        fun onHostStopped() {
            isHostRunning = false
        }
    }
