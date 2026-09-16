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

class CompositeLocalChangeWatcherTest {
    private var now = 0L
    private val normalizer = LocalChangeHintNormalizer("/storage/emulated/0")
    private val deduper = LocalChangeEventDeduper(normalizer, windowMillis = 2_000, clock = { now })

    @Test
    fun `hints for the same file from different sources normalize equally`() {
        val safHint =
            "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera/" +
                "document/primary%3ADCIM%2FCamera%2Fphoto.jpg"

        assertEquals("DCIM/Camera/photo.jpg", normalizer.normalize("/storage/emulated/0/DCIM/Camera/photo.jpg"))
        assertEquals("DCIM/Camera/photo.jpg", normalizer.normalize(safHint))
        assertEquals("DCIM/Camera/photo.jpg", normalizer.normalize("DCIM/Camera/photo.jpg"))
        assertEquals("", normalizer.normalize(null))
        assertEquals(
            "content://media/external/images/media/1",
            normalizer.normalize("content://media/external/images/media/1"),
        )
    }

    @Test
    fun `first registered delegate wins`() {
        val fileObserver = FakeWatcher()
        val mediaStore = FakeWatcher()
        val saf = FakeWatcher()

        val result = CompositeLocalChangeWatcher(listOf(fileObserver, mediaStore, saf), deduper).register(5) {}

        assertTrue(result is LocalChangeWatchRegistrationResult.Registered)
        assertEquals(1, fileObserver.registerCalls)
        assertEquals(0, mediaStore.registerCalls)
        assertEquals(0, saf.registerCalls)
    }

    @Test
    fun `unavailable delegate falls through to next delegate`() {
        val unavailable =
            LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN)
        val fileObserver = FakeWatcher(unavailable)
        val saf = FakeWatcher()

        val result = CompositeLocalChangeWatcher(listOf(fileObserver, saf), deduper).register(5) {}

        assertTrue(result is LocalChangeWatchRegistrationResult.Registered)
        assertEquals(1, fileObserver.registerCalls)
        assertEquals(1, saf.registerCalls)
    }

    @Test
    fun `failed delegate aborts without trying fallback`() {
        val fileObserver = FakeWatcher()
        fileObserver.result =
            LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.PermissionDenied)
        val saf = FakeWatcher()

        assertEquals(
            LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.PermissionDenied),
            CompositeLocalChangeWatcher(listOf(fileObserver, saf), deduper).register(5) {},
        )
        assertEquals(0, saf.registerCalls)
    }

    @Test
    fun `unsupported delegates fall back cleanly`() {
        val unavailable =
            LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN)
        val composite =
            CompositeLocalChangeWatcher(
                listOf(FakeWatcher(unavailable), FakeWatcher(unavailable)),
                deduper,
            )

        assertEquals(unavailable, composite.capability)
        assertEquals(
            LocalChangeWatchRegistrationResult.Unavailable(unavailable),
            composite.register(pairId = 5) {},
        )
    }

    @Test
    fun `unregister and shutdown release every delegate`() {
        val saf = FakeWatcher()
        val fileObserver = FakeWatcher()
        val composite = CompositeLocalChangeWatcher(listOf(saf, fileObserver), deduper)
        val events = mutableListOf<LocalChangeEvent>()
        val registration =
            (composite.register(pairId = 5, listener = events::add) as LocalChangeWatchRegistrationResult.Registered)
                .registration

        registration.unregister()
        registration.unregister()
        saf.emit(LocalChangeEvent.Changed(5, "/storage/emulated/0/DCIM/photo.jpg"))

        assertTrue(events.isEmpty())
        assertTrue(saf.listeners.isEmpty())
        assertTrue(fileObserver.listeners.isEmpty())

        composite.shutdown()
        composite.shutdown()

        assertEquals(
            LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown),
            composite.register(pairId = 5) {},
        )
        assertTrue(saf.isShutdown)
        assertTrue(fileObserver.isShutdown)
    }

    private class FakeWatcher(
        override val capability: LocalChangeWatcherCapability = LocalChangeWatcherCapability.Available,
    ) : LocalChangeWatcher {
        val listeners = mutableListOf<(LocalChangeEvent) -> Unit>()
        var registerCalls = 0
        var result: LocalChangeWatchRegistrationResult? = null
        var isShutdown = false
            private set

        override fun register(
            pairId: Long,
            listener: (LocalChangeEvent) -> Unit,
        ): LocalChangeWatchRegistrationResult {
            registerCalls++
            result?.let { return it }
            if (isShutdown) {
                return LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown)
            }
            val unavailable = capability as? LocalChangeWatcherCapability.Unavailable
            if (unavailable != null) {
                return LocalChangeWatchRegistrationResult.Unavailable(unavailable)
            }
            listeners += listener
            return LocalChangeWatchRegistrationResult.Registered(
                LocalChangeWatchRegistration { listeners.remove(listener) },
            )
        }

        override fun shutdown() {
            isShutdown = true
            listeners.clear()
        }

        fun emit(event: LocalChangeEvent) {
            listeners.toList().forEach { it(event) }
            if (event is LocalChangeEvent.Failure) listeners.clear()
        }
    }
}
