package com.synckro.domain.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Conflates signals into independent trailing-edge debounce windows for each sync pair.
 *
 * This coordinator deliberately stores no pending work. Callers must persist work before signaling
 * so cancelling or recreating the coordinator cannot discard durable rows.
 *
 * @param debounceMs quiet period required before dispatch; zero dispatches immediately
 * @throws IllegalArgumentException when [debounceMs] is negative
 */
class PairSignalCoordinator(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    private val pendingSignals = ConcurrentHashMap<Long, Job>()

    init {
        require(debounceMs >= 0) { "debounceMs must be >= 0" }
    }

    /**
     * Resets [pairId]'s debounce window, replacing any pending signal and its callback.
     */
    suspend fun signal(
        pairId: Long,
        onDebounced: suspend (Long) -> Unit,
    ) {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(debounceMs)
                if (pendingSignals.remove(pairId, coroutineContext.job)) {
                    onDebounced(pairId)
                }
            }
        val replaced = pendingSignals.put(pairId, job)
        job.invokeOnCompletion {
            pendingSignals.remove(pairId, job)
        }
        replaced?.cancel()
        job.start()
    }

    /**
     * Drops every callback that is still waiting for its debounce window.
     *
     * Persisted work is unaffected and can be signaled again after cancellation or restart.
     */
    suspend fun cancelPendingSignals() {
        pendingSignals.entries.toList().forEach { (pairId, job) ->
            if (pendingSignals.remove(pairId, job)) {
                job.cancel()
            }
        }
    }

    companion object {
        internal const val DEFAULT_DEBOUNCE_MS = 5_000L
    }
}
