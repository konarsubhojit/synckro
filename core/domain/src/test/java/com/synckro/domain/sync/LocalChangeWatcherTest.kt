package com.synckro.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun `shutdown takes precedence over unavailable capability`() {
        val watcher =
            InMemoryLocalChangeWatcher(
                LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN),
            )

        watcher.shutdown()

        assertEquals(
            LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown),
            watcher.register(pairId = 42) {},
        )
    }

    @Test
    fun `failures are reported for their pair`() {
        val watcher = InMemoryLocalChangeWatcher()
        val events = mutableListOf<LocalChangeEvent>()
        watcher.register(pairId = 42, listener = events::add)

        watcher.emitFailure(pairId = 42, failure = LocalChangeWatchFailure.PermissionDenied)
        watcher.register(pairId = 42, listener = events::add)
        watcher.emitFailure(pairId = 42, failure = LocalChangeWatchFailure.VolumeUnavailable)
        watcher.register(pairId = 42, listener = events::add)
        watcher.emitFailure(pairId = 42, failure = LocalChangeWatchFailure.Unknown())
        watcher.register(pairId = 42, listener = events::add)
        watcher.emitFailure(pairId = 42, failure = LocalChangeWatchFailure.Unknown("I/O error"))

        assertEquals(
            listOf(
                LocalChangeEvent.Failure(42, LocalChangeWatchFailure.PermissionDenied),
                LocalChangeEvent.Failure(42, LocalChangeWatchFailure.VolumeUnavailable),
                LocalChangeEvent.Failure(42, LocalChangeWatchFailure.Unknown()),
                LocalChangeEvent.Failure(42, LocalChangeWatchFailure.Unknown("I/O error")),
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

    @Test
    fun `concurrent lifecycle calls are safe`() {
        val watcher = InMemoryLocalChangeWatcher()
        val executor = Executors.newFixedThreadPool(3)
        try {
            val registrations =
                executor.submit {
                    repeat(100) {
                        val result = watcher.register(pairId = 42) {}
                        (result as? LocalChangeWatchRegistrationResult.Registered)
                            ?.registration
                            ?.unregister()
                    }
                }
            val shutdowns = executor.submit { repeat(100) { watcher.shutdown() } }
            val additionalRegistrations =
                executor.submit {
                    repeat(100) { watcher.register(pairId = 43) {} }
                }

            registrations.get(5, TimeUnit.SECONDS)
            shutdowns.get(5, TimeUnit.SECONDS)
            additionalRegistrations.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdown()
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(
            LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown),
            watcher.register(pairId = 42) {},
        )
    }

    private class InMemoryLocalChangeWatcher(
        override val capability: LocalChangeWatcherCapability = LocalChangeWatcherCapability.Available,
    ) : LocalChangeWatcher {
        private val lock = Any()
        private val listeners = mutableMapOf<Long, MutableList<RegisteredListener>>()
        private var isShutdown = false

        override fun register(
            pairId: Long,
            listener: (LocalChangeEvent) -> Unit,
        ): LocalChangeWatchRegistrationResult =
            synchronized(lock) {
                if (isShutdown) {
                    return@synchronized LocalChangeWatchRegistrationResult.Failed(
                        LocalChangeWatchFailure.Shutdown,
                    )
                }
                val unavailable = capability as? LocalChangeWatcherCapability.Unavailable
                if (unavailable != null) {
                    return@synchronized LocalChangeWatchRegistrationResult.Unavailable(unavailable)
                }

                val registration = Any()
                listeners.getOrPut(pairId) { mutableListOf() }.add(RegisteredListener(registration, listener))
                var isUnregistered = false
                LocalChangeWatchRegistrationResult.Registered(
                    LocalChangeWatchRegistration {
                        synchronized(lock) {
                            if (!isUnregistered) {
                                listeners[pairId]?.removeAll { it.token === registration }
                                isUnregistered = true
                            }
                        }
                    },
                )
            }

        override fun shutdown() {
            synchronized(lock) {
                if (!isShutdown) {
                    listeners.clear()
                    isShutdown = true
                }
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
            val registrations =
                synchronized(lock) {
                    listeners[event.pairId]?.toList().orEmpty().also {
                        if (event is LocalChangeEvent.Failure) listeners.remove(event.pairId)
                    }
                }
            registrations.forEach { it.listener(event) }
        }

        private data class RegisteredListener(
            val token: Any,
            val listener: (LocalChangeEvent) -> Unit,
        )
    }
}
