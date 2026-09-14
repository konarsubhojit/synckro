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
 * After the quiet period elapses a pair additionally waits out its dispatch window, so repeated
 * signals are delayed rather than dropped and each pair is rate limited independently.
 *
 * This coordinator deliberately stores no pending work. Callers must persist work before signaling
 * so cancelling or recreating the coordinator cannot discard durable rows.
 *
 * @param debounceMs quiet period required before dispatch; zero dispatches immediately
 * @param minDispatchIntervalMs minimum spacing between dispatches of the same pair; zero disables
 *   rate limiting
 * @param dispatchHistory persisted dispatch times; a durable implementation keeps the window intact
 *   across process death
 * @param clock wall-clock source, injectable so window boundaries are deterministic in tests
 * @throws IllegalArgumentException when [debounceMs] or [minDispatchIntervalMs] is negative
 */
class PairSignalCoordinator(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val minDispatchIntervalMs: Long = DEFAULT_MIN_DISPATCH_INTERVAL_MS,
    private val dispatchHistory: PairDispatchHistory = InMemoryPairDispatchHistory(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(debounceMs >= 0) { "debounceMs must be >= 0 but was $debounceMs" }
        require(minDispatchIntervalMs >= 0) {
            "minDispatchIntervalMs must be >= 0 but was $minDispatchIntervalMs"
        }
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
                awaitDispatchWindow(pairId)
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
                    dispatchHistory.recordDispatch(pairId, clock())
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

    /**
     * Suspends until [pairId]'s dispatch window has expired, re-reading the history after each wait
     * so a dispatch recorded meanwhile still spaces this one out.
     *
     * A stored time in the future means the clock moved backwards; it is repaired to the current
     * time and a full window is awaited so a bad timestamp cannot stall dispatch indefinitely.
     */
    private suspend fun awaitDispatchWindow(pairId: Long) {
        if (minDispatchIntervalMs == 0L) return
        while (true) {
            val nowMs = clock()
            val lastDispatchAtMs = dispatchHistory.lastDispatchAtMs(pairId) ?: return
            if (lastDispatchAtMs > nowMs) {
                dispatchHistory.recordDispatch(pairId, nowMs)
                delay(minDispatchIntervalMs)
                continue
            }
            val waitMs = minDispatchIntervalMs - (nowMs - lastDispatchAtMs)
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }

    companion object {
        internal const val DEFAULT_DEBOUNCE_MS = 5_000L
        internal const val DEFAULT_MIN_DISPATCH_INTERVAL_MS = 60_000L
    }
}
