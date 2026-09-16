package com.synckro.data.watcher

import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.LocalChangeWatchFailure
import com.synckro.domain.sync.LocalChangeWatchRegistrationResult
import com.synckro.domain.sync.LocalChangeWatcherCapability
import com.synckro.domain.sync.LocalChangeWatcherFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileObserverLocalChangeWatcherTest {
    private val factory = FakeDirectoryObserverFactory()

    @Test
    fun `direct path pairs receive coarse change prompts with best effort hints`() {
        val watcher = watcher(pairSource = LocalTreeWatchSource.DirectPath("/storage/emulated/0/DCIM"))
        val events = mutableListOf<LocalChangeEvent>()

        val result = watcher.register(pairId = 7, listener = events::add)
        factory.signal(DirectoryWatchSignal.ContentChanged("photo.jpg"))
        factory.signal(DirectoryWatchSignal.ContentChanged(null))
        factory.signal(DirectoryWatchSignal.Overflow)

        assertTrue(result is LocalChangeWatchRegistrationResult.Registered)
        assertEquals("/storage/emulated/0/DCIM", factory.startedPaths.single())
        assertEquals(
            listOf(
                LocalChangeEvent.Changed(7, "/storage/emulated/0/DCIM/photo.jpg"),
                LocalChangeEvent.Changed(7, "/storage/emulated/0/DCIM"),
                LocalChangeEvent.Changed(7, null),
            ),
            events,
        )
    }

    @Test
    fun `unsupported and MediaStore trees fall back cleanly`() {
        val expected =
            LocalChangeWatchRegistrationResult.Unavailable(
                LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN),
            )

        assertEquals(expected, watcher(LocalTreeWatchSource.Unsupported).register(pairId = 7) {})
        assertEquals(
            expected,
            watcher(LocalTreeWatchSource.MediaStoreTree("DCIM/", setOf(MediaCollection.IMAGES)))
                .register(pairId = 7) {},
        )
        assertTrue(factory.startedPaths.isEmpty())
    }

    @Test
    fun `unmount and watch removal end the registration as failures`() {
        val unmountEvents = mutableListOf<LocalChangeEvent>()
        val unmountWatcher = watcher(LocalTreeWatchSource.DirectPath("/storage/emulated/0/DCIM"))
        unmountWatcher.register(pairId = 7, listener = unmountEvents::add)

        factory.signal(DirectoryWatchSignal.VolumeUnmounted)
        factory.signal(DirectoryWatchSignal.ContentChanged("ignored.jpg"))

        assertEquals(
            listOf(LocalChangeEvent.Failure(7, LocalChangeWatchFailure.VolumeUnavailable)),
            unmountEvents,
        )
        assertTrue(factory.handles.single().isStopped)

        val removalFactory = FakeDirectoryObserverFactory()
        val removalEvents = mutableListOf<LocalChangeEvent>()
        FileObserverLocalChangeWatcher({ LocalTreeWatchSource.DirectPath("/dir") }, removalFactory)
            .register(pairId = 8, listener = removalEvents::add)
        removalFactory.signal(DirectoryWatchSignal.WatchInvalidated)

        assertEquals(
            listOf(
                LocalChangeEvent.Failure(
                    8,
                    LocalChangeWatchFailure.Unknown("file observer watch was removed"),
                ),
            ),
            removalEvents,
        )
    }

    @Test
    fun `observation start failures are reported at registration time`() {
        val denying =
            DirectoryObserverFactory { _, _ -> throw SecurityException("denied") }
        val failing =
            DirectoryObserverFactory { _, _ -> throw IllegalStateException("boom") }
        val source = LocalTreeWatchSource.DirectPath("/storage/emulated/0/DCIM")

        assertEquals(
            LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.PermissionDenied),
            FileObserverLocalChangeWatcher({ source }, denying).register(pairId = 7) {},
        )
        assertEquals(
            LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Unknown("boom")),
            FileObserverLocalChangeWatcher({ source }, failing).register(pairId = 7) {},
        )
    }

    @Test
    fun `unregister and shutdown stop observing and are idempotent`() {
        val watcher = watcher(LocalTreeWatchSource.DirectPath("/storage/emulated/0/DCIM"))
        val events = mutableListOf<LocalChangeEvent>()
        val registration =
            (watcher.register(pairId = 7, listener = events::add) as LocalChangeWatchRegistrationResult.Registered)
                .registration
        watcher.register(pairId = 8) {}

        registration.unregister()
        registration.unregister()
        factory.signal(DirectoryWatchSignal.ContentChanged("photo.jpg"))

        assertTrue(events.isEmpty())
        assertTrue(factory.handles.first().isStopped)
        assertFalse(factory.handles.last().isStopped)

        watcher.shutdown()
        watcher.shutdown()

        assertTrue(factory.handles.all { it.isStopped })
        assertEquals(
            LocalChangeWatchRegistrationResult.Failed(LocalChangeWatchFailure.Shutdown),
            watcher.register(pairId = 7) {},
        )
    }

    @Test
    fun `recursive registration observes nested and newly created directories`() {
        val treeReader =
            FakeDirectoryTreeReader(
                mapOf(
                    "/root" to listOf("/root/existing"),
                    "/root/existing" to emptyList(),
                    "/root/new" to listOf("/root/new/deep"),
                    "/root/new/deep" to emptyList(),
                ),
            )
        val watcher =
            FileObserverLocalChangeWatcher(
                { LocalTreeWatchSource.DirectPath("/root") },
                factory,
                treeReader,
            )
        val events = mutableListOf<LocalChangeEvent>()
        watcher.register(7, events::add)

        factory.signal("/root/existing", DirectoryWatchSignal.ContentChanged("nested.txt"))
        factory.signal("/root", DirectoryWatchSignal.ContentChanged("new", isDirectory = true))

        assertEquals(
            listOf("/root", "/root/existing", "/root/new", "/root/new/deep"),
            factory.startedPaths,
        )
        assertEquals(LocalChangeEvent.Changed(7, "/root/existing/nested.txt"), events.first())
    }

    @Test
    fun `exclude subfolders observes only the root directory`() {
        val sourceProvider =
            object : LocalTreeWatchSourceProvider {
                override fun sourceFor(pairId: Long) = LocalTreeWatchSource.DirectPath("/root")

                override fun excludeSubfolders(pairId: Long) = true
            }
        val watcher =
            FileObserverLocalChangeWatcher(
                sourceProvider,
                factory,
                FakeDirectoryTreeReader(mapOf("/root" to listOf("/root/child"))),
            )

        watcher.register(7) {}

        assertEquals(listOf("/root"), factory.startedPaths)
    }

    @Test
    fun `watch limit falls through as unavailable`() {
        val watcher =
            FileObserverLocalChangeWatcher(
                { LocalTreeWatchSource.DirectPath("/root") },
                factory,
                FakeDirectoryTreeReader(mapOf("/root" to listOf("/root/child"))),
                maxWatches = 1,
            )

        val result = watcher.register(7) {}

        assertTrue(result is LocalChangeWatchRegistrationResult.Unavailable)
        assertTrue(factory.handles.single().isStopped)
    }

    private fun watcher(pairSource: LocalTreeWatchSource) =
        FileObserverLocalChangeWatcher({ pairSource }, factory)

    private class FakeDirectoryObserverFactory : DirectoryObserverFactory {
        val startedPaths = mutableListOf<String>()
        val handles = mutableListOf<FakeHandle>()
        private val listeners = mutableListOf<(DirectoryWatchSignal) -> Unit>()

        override fun start(
            path: String,
            onSignal: (DirectoryWatchSignal) -> Unit,
        ): DirectoryWatchHandle {
            startedPaths += path
            listeners += onSignal
            return FakeHandle().also { handles += it }
        }

        fun signal(
            path: String,
            signal: DirectoryWatchSignal,
        ) {
            startedPaths
                .withIndex()
                .filter { it.value == path }
                .forEach { listeners[it.index](signal) }
        }

        fun signal(signal: DirectoryWatchSignal) = listeners.toList().forEach { it(signal) }

        class FakeHandle : DirectoryWatchHandle {
            var isStopped = false
                private set

            override fun stop() {
                isStopped = true
            }
        }
    }

    private class FakeDirectoryTreeReader(
        private val children: Map<String, List<String>>,
    ) : DirectoryTreeReader {
        override fun subdirectories(path: String): List<String> = children[path].orEmpty()

        override fun canonicalPath(path: String): String? = path
    }
}
