package com.synckro.domain.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Conflates signals into independent trailing-edge debounce windows for each sync pair.
 *
 * This coordinator deliberately stores no pending work. Callers must persist work before signaling
 * so cancelling or recreating the coordinator cannot discard durable rows.
 */
class PairSignalCoordinator(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    private val mutex = Mutex()
    private val pendingSignals = mutableMapOf<Long, Job>()

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
        mutex.withLock {
            pendingSignals.remove(pairId)?.cancel()

            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    delay(debounceMs)
                    val currentJob = coroutineContext.job
                    val shouldDispatch =
                        mutex.withLock {
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
            pendingSignals[pairId] = job
            job.start()
        }
    }

    suspend fun cancelPendingSignals() {
        val jobs =
            mutex.withLock {
                pendingSignals.values.toList().also { pendingSignals.clear() }
            }
        jobs.forEach(Job::cancel)
    }

    companion object {
        internal const val DEFAULT_DEBOUNCE_MS = 5_000L
    }
}
