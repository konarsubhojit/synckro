package com.synckro.domain.sync

/**
 * Records when each sync pair last dispatched instant work.
 *
 * [PairSignalCoordinator] reads and writes this history to enforce its per-pair dispatch window.
 * Implementations that persist the timestamp keep the window intact across process death, so a
 * restart cannot amplify dispatch by restoring an empty window.
 */
interface PairDispatchHistory {
    /** Returns the wall-clock time of [pairId]'s last dispatch, or `null` when it never dispatched. */
    suspend fun lastDispatchAtMs(pairId: Long): Long?

    /** Stores [atMs] as [pairId]'s newest dispatch time. */
    suspend fun recordDispatch(
        pairId: Long,
        atMs: Long,
    )
}

/**
 * Process-local [PairDispatchHistory] for tests and offline development.
 *
 * The recorded window is lost when the process dies, so production wiring must use a persisted
 * implementation instead.
 */
class InMemoryPairDispatchHistory : PairDispatchHistory {
    private val lock = Any()
    private val dispatchTimesMs = mutableMapOf<Long, Long>()

    override suspend fun lastDispatchAtMs(pairId: Long): Long? = synchronized(lock) { dispatchTimesMs[pairId] }

    override suspend fun recordDispatch(
        pairId: Long,
        atMs: Long,
    ) {
        synchronized(lock) { dispatchTimesMs[pairId] = atMs }
    }
}
