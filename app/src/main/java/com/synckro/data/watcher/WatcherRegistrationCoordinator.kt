package com.synckro.data.watcher

import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.LocalChangeWatchRegistration
import com.synckro.domain.sync.LocalChangeWatchRegistrationResult
import com.synckro.domain.sync.LocalChangeWatcher
import timber.log.Timber

/**
 * Keeps the active [LocalChangeWatcher] registrations in sync with the set of pairs that should be
 * watched.
 *
 * The coordinator owns no scheduling policy: it only adds registrations for newly eligible pairs
 * and unregisters pairs that are no longer eligible, so pair edits, deletions and settings changes
 * reconcile by re-invoking [reconcile]. Registrations that end with a
 * [LocalChangeEvent.Failure] (revoked SAF permission, removed volume, …) are dropped so the next
 * reconcile pass can register them again once the pair becomes watchable.
 *
 * Instances are safe for concurrent use.
 */
class WatcherRegistrationCoordinator(
    private val watcher: LocalChangeWatcher,
    private val onChange: (LocalChangeEvent.Changed) -> Unit = {},
) {
    private val lock = Any()

    /** Serializes [reconcile] so overlapping passes cannot register the same pair twice. */
    private val reconcileLock = Any()
    private val registrations = mutableMapOf<Long, LocalChangeWatchRegistration>()

    /**
     * Bumped by [unregisterAll] so a registration that completes concurrently with a teardown is
     * released instead of leaking an observer.
     */
    private var generation = 0L

    /** Pair ids with a live registration, for diagnostics and tests. */
    val watchedPairIds: Set<Long>
        get() = synchronized(lock) { registrations.keys.toSet() }

    /**
     * Registers [desiredPairIds] that are not watched yet and unregisters everything else.
     *
     * @return the pair ids that are watched after reconciliation
     */
    fun reconcile(desiredPairIds: Set<Long>): Set<Long> =
        synchronized(reconcileLock) { reconcileSerially(desiredPairIds) }

    private fun reconcileSerially(desiredPairIds: Set<Long>): Set<Long> {
        val startGeneration = synchronized(lock) { generation }
        val toUnregister =
            synchronized(lock) {
                val stale = registrations.keys.filterNot { it in desiredPairIds }
                stale.mapNotNull { pairId -> registrations.remove(pairId) }
            }
        toUnregister.forEach { it.unregister() }

        desiredPairIds.forEach { pairId ->
            val alreadyWatched = synchronized(lock) { registrations.containsKey(pairId) }
            if (alreadyWatched) return@forEach
            when (val result = watcher.register(pairId) { event -> onEvent(pairId, event) }) {
                is LocalChangeWatchRegistrationResult.Registered ->
                    registerOrUndo(pairId, result.registration, startGeneration)
                is LocalChangeWatchRegistrationResult.Unavailable ->
                    Timber.i(
                        "Watcher unavailable for pair %d; falling back to %s",
                        pairId,
                        result.capability.fallback,
                    )
                is LocalChangeWatchRegistrationResult.Failed ->
                    Timber.w("Watcher registration failed for pair %d: %s", pairId, result.failure)
            }
        }
        return watchedPairIds
    }

    /** Unregisters every pair. The coordinator can be reused afterwards. */
    fun unregisterAll() {
        val pending =
            synchronized(lock) {
                generation++
                registrations.values.toList().also { registrations.clear() }
            }
        pending.forEach { it.unregister() }
    }

    private fun registerOrUndo(
        pairId: Long,
        registration: LocalChangeWatchRegistration,
        startGeneration: Long,
    ) {
        val replaced =
            synchronized(lock) {
                if (generation != startGeneration) {
                    registration.unregister()
                    return
                }
                registrations.put(pairId, registration)
            }
        replaced?.unregister()
    }

    private fun onEvent(
        pairId: Long,
        event: LocalChangeEvent,
    ) {
        when (event) {
            is LocalChangeEvent.Changed -> onChange(event)
            is LocalChangeEvent.Failure -> {
                // A failure implicitly ends its registration, so drop the handle we hold.
                synchronized(lock) { registrations.remove(pairId) }
                Timber.w("Watcher registration for pair %d ended: %s", pairId, event.failure)
            }
        }
    }
}
