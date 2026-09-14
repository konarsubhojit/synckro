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
    fun `opportunistic events dedupe with SAF events for the same file`() {
        val saf = FakeWatcher()
        val fileObserver = FakeWatcher()
        val watcher = CompositeLocalChangeWatcher(listOf(saf, fileObserver), deduper)
        val events = mutableListOf<LocalChangeEvent>()

        val result = watcher.register(pairId = 5, listener = events::add)
        saf.emit(
            LocalChangeEvent.Changed(
                5,
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM/" +
                    "document/primary%3ADCIM%2Fphoto.jpg",
            ),
        )
        fileObserver.emit(LocalChangeEvent.Changed(5, "/storage/emulated/0/DCIM/photo.jpg"))
        fileObserver.emit(LocalChangeEvent.Changed(5, "/storage/emulated/0/DCIM/other.jpg"))
        now += 2_000
        fileObserver.emit(LocalChangeEvent.Changed(5, "/storage/emulated/0/DCIM/photo.jpg"))

        assertTrue(result is LocalChangeWatchRegistrationResult.Registered)
        assertEquals(
            listOf(
                LocalChangeEvent.Changed(
                    5,
                    "content://com.android.externalstorage.documents/tree/primary%3ADCIM/" +
                        "document/primary%3ADCIM%2Fphoto.jpg",
                ),
                LocalChangeEvent.Changed(5, "/storage/emulated/0/DCIM/other.jpg"),
                LocalChangeEvent.Changed(5, "/storage/emulated/0/DCIM/photo.jpg"),
            ),
            events,
        )
    }

    @Test
    fun `coarse prompts dedupe per pair and never across pairs`() {
        val firstPairSource = FakeWatcher()
        val secondPairSource = FakeWatcher()
        val firstPair = CompositeLocalChangeWatcher(listOf(firstPairSource), deduper)
        val secondPair = CompositeLocalChangeWatcher(listOf(secondPairSource), deduper)
        val events = mutableListOf<LocalChangeEvent>()
        firstPair.register(pairId = 5, listener = events::add)
        secondPair.register(pairId = 6, listener = events::add)

        firstPairSource.emit(LocalChangeEvent.Changed(5))
        firstPairSource.emit(LocalChangeEvent.Changed(5))
        secondPairSource.emit(LocalChangeEvent.Changed(6))

        assertEquals(
            listOf(LocalChangeEvent.Changed(5), LocalChangeEvent.Changed(6)),
            events,
        )
    }

    @Test
    fun `losing one source keeps the remaining source registered`() {
        val saf = FakeWatcher()
        val fileObserver = FakeWatcher()
        val composite = CompositeLocalChangeWatcher(listOf(saf, fileObserver), deduper)
        val events = mutableListOf<LocalChangeEvent>()
        composite.register(pairId = 5, listener = events::add)

        fileObserver.emit(LocalChangeEvent.Failure(5, LocalChangeWatchFailure.VolumeUnavailable))
        saf.emit(LocalChangeEvent.Changed(5, "/storage/emulated/0/DCIM/photo.jpg"))
        saf.emit(LocalChangeEvent.Failure(5, LocalChangeWatchFailure.PermissionDenied))
        saf.emit(LocalChangeEvent.Changed(5, "/storage/emulated/0/DCIM/late.jpg"))

        assertEquals(
            listOf(
                LocalChangeEvent.Changed(5, "/storage/emulated/0/DCIM/photo.jpg"),
                LocalChangeEvent.Failure(5, LocalChangeWatchFailure.PermissionDenied),
            ),
            events,
        )
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
        var isShutdown = false
            private set

        override fun register(
            pairId: Long,
            listener: (LocalChangeEvent) -> Unit,
        ): LocalChangeWatchRegistrationResult {
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
