package com.synckro.data.watcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.synckro.domain.sync.WatcherLifecycleTrigger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject

/**
 * Restores the watcher host after a reboot or an app update.
 *
 * Neither broadcast guarantees that a `dataSync` foreground service may be started — Android 15
 * forbids it from `BOOT_COMPLETED` — so the decision is delegated to [WatcherServiceController].
 * When the start is not permitted, watching resumes the next time the app is in the foreground and
 * durable WorkManager sync keeps running in the meantime.
 */
@AndroidEntryPoint
class WatcherBootReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: WatcherServiceController

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val trigger =
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED -> WatcherLifecycleTrigger.BOOT_COMPLETED
                Intent.ACTION_MY_PACKAGE_REPLACED -> WatcherLifecycleTrigger.PACKAGE_REPLACED
                else -> return
            }
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                val action = withTimeout(EVALUATION_TIMEOUT_MS) { controller.evaluate(trigger) }
                Timber.i("Watcher restart after %s resolved to %s", trigger, action)
            } catch (e: Exception) {
                // A restart failure must never crash the boot broadcast; periodic sync still runs.
                Timber.w(e, "Failed to restore watcher host after %s", trigger)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        /**
         * Shared across broadcasts so each delivery does not leak a scope. Boot and package-replaced
         * broadcasts are rare and the work is short-lived, so the scope is never cancelled.
         */
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * `goAsync()` only keeps the broadcast alive for about ten seconds, so give up before the
         * system force-finishes it and `pendingResult.finish()` would throw.
         */
        const val EVALUATION_TIMEOUT_MS = 8_000L
    }
}
