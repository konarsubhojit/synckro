package com.synckro.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChangeWatcherTest {
    @Test
    fun `registered watcher reports coarse pair change with an unstable location hint`() {
        val watcher = InMemoryLocalChangeWatcher()
        val events = mutableListOf<LocalChangeEvent>()

        val result = watcher.register(pairId = 42, listener = events::add)
        watcher.emitChange(pairId = 42, locationHint = "content://example/document/changed")

        assertTrue(result is LocalChangeWatchRegistrationResult.Registered)
        assertEquals(
            listOf(LocalChangeEvent.Changed(42, "content://example/document/changed")),
            events,
        )
    }

    @Test
    fun `unregister and shutdown are idempotent`() {
        val watcher = InMemoryLocalChangeWatcher()
        val events = mutableListOf<LocalChangeEvent>()
        val registration =
            (
                watcher.register(pairId = 42, listener = events::add)
                    as LocalChangeWatchRegistrationResult.Registered
            )
                .registration

        registration.unregister()
        watcher.emitChange(pairId = 42)
        registration.unregister()
        watcher.shutdown()
        watcher.shutdown()
        watcher.emitChange(pairId = 42)

        assertTrue(events.isEmpty())
        assertEquals(
            LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown),
            watcher.register(pairId = 42) {},
        )
    }

    @Test
    fun `unavailable capability reports its scan fallback`() {
        val capability =
            LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN)
        val watcher =
            InMemoryLocalChangeWatcher(
                capability,
            )

        val result = watcher.register(pairId = 42) {}

        assertEquals(
            LocalChangeWatchRegistrationResult.Unavailable(capability),
            result,
        )
    }

    @Test
    fun `permission and volume failures are reported for their pair`() {
        val watcher = InMemoryLocalChangeWatcher()
        val events = mutableListOf<LocalChangeEvent>()
        watcher.register(pairId = 42, listener = events::add)

        watcher.emitFailure(pairId = 42, failure = LocalChangeWatchFailure.PermissionDenied)
        watcher.emitFailure(pairId = 42, failure = LocalChangeWatchFailure.VolumeUnavailable)

        assertEquals(
            listOf(
                LocalChangeEvent.Failure(42, LocalChangeWatchFailure.PermissionDenied),
                LocalChangeEvent.Failure(42, LocalChangeWatchFailure.VolumeUnavailable),
            ),
            events,
        )
    }

    @Test
    fun `each repeated listener registration has an independent lifecycle`() {
        val watcher = InMemoryLocalChangeWatcher()
        val events = mutableListOf<LocalChangeEvent>()
        val first =
            (
                watcher.register(pairId = 42, listener = events::add)
                    as LocalChangeWatchRegistrationResult.Registered
            )
                .registration
        watcher.register(pairId = 42, listener = events::add)

        first.unregister()
        watcher.emitChange(pairId = 42)

        assertEquals(listOf(LocalChangeEvent.Changed(42)), events)
    }

    private class InMemoryLocalChangeWatcher(
        override val capability: LocalChangeWatcherCapability = LocalChangeWatcherCapability.Available,
    ) : LocalChangeWatcher {
        private val listeners = mutableMapOf<Long, MutableList<RegisteredListener>>()
        private var isShutdown = false

        override fun register(
            pairId: Long,
            listener: (LocalChangeEvent) -> Unit,
        ): LocalChangeWatchRegistrationResult {
            val unavailable = capability as? LocalChangeWatcherCapability.Unavailable
            if (unavailable != null) return LocalChangeWatchRegistrationResult.Unavailable(unavailable)
            if (isShutdown) return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown)

            val registration = Any()
            listeners.getOrPut(pairId) { mutableListOf() }.add(RegisteredListener(registration, listener))
            var isUnregistered = false
            return LocalChangeWatchRegistrationResult.Registered(
                LocalChangeWatchRegistration {
                    if (!isUnregistered) {
                        listeners[pairId]?.removeAll { it.token === registration }
                        isUnregistered = true
                    }
                },
            )
        }

        override fun shutdown() {
            if (!isShutdown) {
                listeners.clear()
                isShutdown = true
            }
        }

        fun emitChange(
            pairId: Long,
            locationHint: String? = null,
        ) = emit(LocalChangeEvent.Changed(pairId, locationHint))

        fun emitFailure(
            pairId: Long,
            failure: LocalChangeWatchFailure,
        ) = emit(LocalChangeEvent.Failure(pairId, failure))

        private fun emit(event: LocalChangeEvent) {
            listeners[event.pairId]?.toList()?.forEach { it.listener(event) }
        }

        private data class RegisteredListener(
            val token: Any,
            val listener: (LocalChangeEvent) -> Unit,
        )
    }
}
