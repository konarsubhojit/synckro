package com.synckro.data.watcher

import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.LocalChangeWatchFailure
import com.synckro.domain.sync.LocalChangeWatchRegistrationResult
import com.synckro.domain.sync.LocalChangeWatcherCapability
import com.synckro.domain.sync.LocalChangeWatcherFallback
import com.synckro.domain.sync.MediaStorePendingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreLocalChangeWatcherTest {
    private val factory = FakeMediaCollectionObserverFactory()
    private val metadata = FakeMediaItemMetadataReader()

    @Test
    fun `only completed items inside the pair subtree are reported`() {
        val events = mutableListOf<LocalChangeEvent>()
        metadata.items["content://media/external/images/media/1"] =
            MediaItemLookup.Found("DCIM/Camera/", MediaStorePendingState.NOT_PENDING, "photo.jpg")
        metadata.items["content://media/external/images/media/2"] =
            MediaItemLookup.Found("DCIM/Camera/", MediaStorePendingState.PENDING, "pending.jpg")
        metadata.items["content://media/external/images/media/3"] =
            MediaItemLookup.Found("Pictures/Other/", MediaStorePendingState.NOT_PENDING, "other.jpg")
        metadata.items["content://media/external/images/media/4"] =
            MediaItemLookup.Found(relativePath = null, MediaStorePendingState.NOT_PENDING)
        val watcher = watcher(mediaTree("DCIM/Camera/"))

        val result = watcher.register(pairId = 3, listener = events::add)
        (1..4).forEach { factory.change("content://media/external/images/media/$it") }
        factory.change(null)
        factory.change("content://media/external/images/media/999")

        assertTrue(result is LocalChangeWatchRegistrationResult.Registered)
        assertEquals(setOf(MediaCollection.IMAGES, MediaCollection.VIDEO), factory.observed.single())
        assertEquals(listOf(LocalChangeEvent.Changed(3, "DCIM/Camera/photo.jpg")), events)
    }

    @Test
    fun `nested subdirectories stay in scope`() {
        val events = mutableListOf<LocalChangeEvent>()
        metadata.items["content://media/external/images/media/1"] =
            MediaItemLookup.Found("DCIM/Camera/Trip/", MediaStorePendingState.NOT_PENDING, "a.jpg")
        watcher(mediaTree("DCIM/Camera/")).register(pairId = 3, listener = events::add)

        factory.change("content://media/external/images/media/1")

        assertEquals(listOf(LocalChangeEvent.Changed(3, "DCIM/Camera/Trip/a.jpg")), events)
    }

    @Test
    fun `temporary pending and inconclusive items are ignored but final rename is re-evaluated`() {
        val uri = "content://media/external/images/media/1"
        val events = mutableListOf<LocalChangeEvent>()
        val watcher = watcher(mediaTree("DCIM/Camera/"))
        watcher.register(pairId = 3, listener = events::add)

        metadata.items[uri] =
            MediaItemLookup.Found("DCIM/Camera/", MediaStorePendingState.NOT_PENDING, "photo.jpg.part")
        factory.change(uri)
        metadata.items[uri] =
            MediaItemLookup.Found("DCIM/Camera/", MediaStorePendingState.PENDING, "photo.jpg")
        factory.change(uri)
        metadata.items[uri] =
            MediaItemLookup.Found("DCIM/Camera/", MediaStorePendingState.UNAVAILABLE, "photo.jpg")
        factory.change(uri)
        metadata.items[uri] =
            MediaItemLookup.Found("DCIM/Camera/", MediaStorePendingState.NOT_PENDING, "photo.jpg")
        factory.change(uri)

        assertEquals(listOf(LocalChangeEvent.Changed(3, "DCIM/Camera/photo.jpg")), events)
    }

    @Test
    fun `direct path and unsupported trees fall back cleanly`() {
        val expected =
            LocalChangeWatchRegistrationResult.Unavailable(
                LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN),
            )

        assertEquals(expected, watcher(LocalTreeWatchSource.Unsupported).register(pairId = 3) {})
        assertEquals(
            expected,
            watcher(LocalTreeWatchSource.DirectPath("/storage/emulated/0/DCIM")).register(pairId = 3) {},
        )
        assertTrue(factory.observed.isEmpty())
    }

    @Test
    fun `denied metadata ends the registration so the pair can fall back`() {
        val events = mutableListOf<LocalChangeEvent>()
        metadata.items["content://media/external/images/media/1"] = MediaItemLookup.PermissionDenied
        watcher(mediaTree("DCIM/Camera/")).register(pairId = 3, listener = events::add)

        factory.change("content://media/external/images/media/1")
        factory.change("content://media/external/images/media/1")

        assertEquals(
            listOf(LocalChangeEvent.Failure(3, LocalChangeWatchFailure.PermissionDenied)),
            events,
        )
        assertTrue(factory.handles.single().isStopped)
    }

    @Test
    fun `unregister and shutdown stop observing and are idempotent`() {
        val events = mutableListOf<LocalChangeEvent>()
        metadata.items["content://media/external/images/media/1"] =
            MediaItemLookup.Found("DCIM/Camera/", MediaStorePendingState.NOT_PENDING, "photo.jpg")
        val watcher = watcher(mediaTree("DCIM/Camera/"))
        val registration =
            (watcher.register(pairId = 3, listener = events::add) as LocalChangeWatchRegistrationResult.Registered)
                .registration

        registration.unregister()
        registration.unregister()
        factory.change("content://media/external/images/media/1")
        watcher.shutdown()
        watcher.shutdown()

        assertTrue(events.isEmpty())
        assertTrue(factory.handles.single().isStopped)
        assertEquals(
            LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown),
            watcher.register(pairId = 3) {},
        )
    }

    private fun mediaTree(prefix: String) =
        LocalTreeWatchSource.MediaStoreTree(
            relativePathPrefix = prefix,
            collections = setOf(MediaCollection.IMAGES, MediaCollection.VIDEO),
        )

    private fun watcher(pairSource: LocalTreeWatchSource) =
        MediaStoreLocalChangeWatcher({ pairSource }, factory, metadata)

    private class FakeMediaItemMetadataReader : MediaItemMetadataReader {
        val items = mutableMapOf<String, MediaItemLookup>()

        override fun read(itemUri: String): MediaItemLookup = items[itemUri] ?: MediaItemLookup.Unknown
    }

    private class FakeMediaCollectionObserverFactory : MediaCollectionObserverFactory {
        val observed = mutableListOf<Set<MediaCollection>>()
        val handles = mutableListOf<FakeHandle>()
        private val listeners = mutableListOf<(String?) -> Unit>()

        override fun start(
            collections: Set<MediaCollection>,
            onChange: (String?) -> Unit,
        ): MediaObservationHandle {
            observed += collections
            listeners += onChange
            return FakeHandle().also { handles += it }
        }

        fun change(itemUri: String?) = listeners.toList().forEach { it(itemUri) }

        class FakeHandle : MediaObservationHandle {
            var isStopped = false
                private set

            override fun stop() {
                isStopped = true
            }
        }
    }
}
