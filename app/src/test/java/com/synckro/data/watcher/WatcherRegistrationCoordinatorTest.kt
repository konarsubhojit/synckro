package com.synckro.data.watcher

import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.LocalChangeWatchFailure
import com.synckro.domain.sync.LocalChangeWatchRegistration
import com.synckro.domain.sync.LocalChangeWatchRegistrationResult
import com.synckro.domain.sync.LocalChangeWatcher
import com.synckro.domain.sync.LocalChangeWatcherCapability
import com.synckro.domain.sync.LocalChangeWatcherFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatcherRegistrationCoordinatorTest {
    private val watcher = RecordingWatcher()

    @Test
    fun `registers newly eligible pairs only once`() {
        val coordinator = WatcherRegistrationCoordinator(watcher)

        coordinator.reconcile(setOf(1L, 2L))
        coordinator.reconcile(setOf(1L, 2L))

        assertEquals(listOf(1L, 2L), watcher.registeredPairIds)
        assertEquals(setOf(1L, 2L), coordinator.watchedPairIds)
    }

    @Test
    fun `unregisters pairs that are no longer eligible`() {
        val coordinator = WatcherRegistrationCoordinator(watcher)
        coordinator.reconcile(setOf(1L, 2L))

        coordinator.reconcile(setOf(2L))

        assertEquals(listOf(1L), watcher.unregisteredPairIds)
        assertEquals(setOf(2L), coordinator.watchedPairIds)
    }

    @Test
    fun `unavailable and failed registrations are not tracked and can be retried`() {
        watcher.resultOverrides[1L] =
            LocalChangeWatchRegistrationResult.Unavailable(
                LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN),
            )
        watcher.resultOverrides[2L] =
            LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown)
        val coordinator = WatcherRegistrationCoordinator(watcher)

        assertEquals(emptySet<Long>(), coordinator.reconcile(setOf(1L, 2L)))

        watcher.resultOverrides.clear()
        assertEquals(setOf(1L, 2L), coordinator.reconcile(setOf(1L, 2L)))
    }

    @Test
    fun `failure event drops the registration so the next pass re-registers`() {
        val coordinator = WatcherRegistrationCoordinator(watcher)
        coordinator.reconcile(setOf(1L))

        watcher.emit(1L, LocalChangeEvent.Failure(1L, LocalChangeWatchFailure.PermissionDenied))

        assertTrue(coordinator.watchedPairIds.isEmpty())
        coordinator.reconcile(setOf(1L))
        assertEquals(listOf(1L, 1L), watcher.registeredPairIds)
    }

    @Test
    fun `change events are forwarded`() {
        val changes = mutableListOf<LocalChangeEvent.Changed>()
        val coordinator = WatcherRegistrationCoordinator(watcher, onChange = changes::add)
        coordinator.reconcile(setOf(7L))

        watcher.emit(7L, LocalChangeEvent.Changed(7L, "content://tree/7"))

        assertEquals(listOf(LocalChangeEvent.Changed(7L, "content://tree/7")), changes)
    }

    @Test
    fun `unregisterAll releases every registration`() {
        val coordinator = WatcherRegistrationCoordinator(watcher)
        coordinator.reconcile(setOf(1L, 2L))

        coordinator.unregisterAll()

        assertEquals(listOf(1L, 2L), watcher.unregisteredPairIds)
        assertTrue(coordinator.watchedPairIds.isEmpty())
    }

    private class RecordingWatcher : LocalChangeWatcher {
        val registeredPairIds = mutableListOf<Long>()
        val unregisteredPairIds = mutableListOf<Long>()
        val resultOverrides = mutableMapOf<Long, LocalChangeWatchRegistrationResult>()
        private val listeners = mutableMapOf<Long, (LocalChangeEvent) -> Unit>()

        override val capability: LocalChangeWatcherCapability = LocalChangeWatcherCapability.Available

        override fun register(
            pairId: Long,
            listener: (LocalChangeEvent) -> Unit,
        ): LocalChangeWatchRegistrationResult {
            resultOverrides[pairId]?.let { return it }
            registeredPairIds += pairId
            listeners[pairId] = listener
            return LocalChangeWatchRegistrationResult.Registered(
                LocalChangeWatchRegistration {
                    unregisteredPairIds += pairId
                    listeners.remove(pairId)
                },
            )
        }

        override fun shutdown() = Unit

        fun emit(
            pairId: Long,
            event: LocalChangeEvent,
        ) {
            listeners[pairId]?.invoke(event)
        }
    }
}
