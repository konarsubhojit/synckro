package com.synckro.domain.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

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
    init {
        require(debounceMs >= 0) { "debounceMs must be >= 0 but was $debounceMs" }
    }

    private val lock = Any()
    private val pendingSignals = mutableMapOf<Long, Job>()

    /**
     * Resets [pairId]'s debounce window, replacing any pending signal and its callback.
     */
    fun signal(
        pairId: Long,
        onDebounced: suspend (Long) -> Unit,
    ) {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(debounceMs)
                val currentJob = coroutineContext.job
                val shouldDispatch =
                    synchronized(lock) {
                        if (pendingSignals[pairId] === currentJob) {
                            pendingSignals.remove(pairId)
                            true
                        } else {
                            false
                        }
                    }
                if (shouldDispatch) {
                    onDebounced(pairId)
                }
            }
        val replaced =
            synchronized(lock) {
                pendingSignals.put(pairId, job)
            }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (pendingSignals[pairId] === job) {
                    pendingSignals.remove(pairId)
                }
            }
        }
        replaced?.cancel()
        job.start()
    }

    /**
     * Drops every callback that is still waiting for its debounce window.
     *
     * Persisted work is unaffected and can be signaled again after cancellation or restart.
     */
    fun cancelPendingSignals() {
        val jobs =
            synchronized(lock) {
                pendingSignals.values.toList().also {
                    pendingSignals.clear()
                }
            }
        jobs.forEach(Job::cancel)
    }

    companion object {
        internal const val DEFAULT_DEBOUNCE_MS = 5_000L
    }
}
