package com.synckro.data.watcher

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.local.fs.LocalFolderAccessChecker
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.LocalChangeWatchFailure
import com.synckro.domain.sync.LocalChangeWatchRegistrationResult
import com.synckro.domain.sync.LocalChangeWatcherCapability
import com.synckro.domain.sync.LocalChangeWatcherFallback
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafContentObserverWatcherTest {
    private lateinit var db: SynckroDatabase
    private lateinit var watcher: SafContentObserverWatcher
    private lateinit var accessChecker: FakeLocalFolderAccessChecker
    private lateinit var observerRegistry: FakeContentObserverRegistry
    private lateinit var recordingTree: RecordingTree

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, SynckroDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        accessChecker = FakeLocalFolderAccessChecker()
        observerRegistry = FakeContentObserverRegistry()
        recordingTree = RecordingTree()
        Timber.uprootAll()
        Timber.plant(recordingTree)
        watcher =
            SafContentObserverWatcher(
                syncPairDao = db.syncPairDao(),
                localFolderAccessChecker = accessChecker,
                observerRegistry = observerRegistry,
                observerHandler = Handler(Looper.getMainLooper()),
            )
    }

    @After
    fun tearDown() {
        watcher.shutdown()
        Timber.uproot(recordingTree)
        db.close()
    }

    @Test
    fun `register observes pair tree URI with descendant notifications`() =
        runTest {
            val treeUri = "content://com.example/tree/root"
            val pairId = insertPair(localTreeUri = treeUri)
            accessChecker.grant(treeUri)

            val result = watcher.register(pairId = pairId) {}

            assertTrue(result is LocalChangeWatchRegistrationResult.Registered)
            assertEquals(1, observerRegistry.registrations.size)
            assertEquals(Uri.parse(treeUri), observerRegistry.registrations.single().uri)
            assertTrue(observerRegistry.registrations.single().notifyForDescendants)
        }

    @Test
    fun `duplicate coarse and null callbacks are emitted as safe coarse changed events`() =
        runTest {
            val treeUri = "content://com.example/tree/root"
            val changedUri = Uri.parse("content://com.example/document/root%3Achild.txt")
            val pairId = insertPair(localTreeUri = treeUri)
            accessChecker.grant(treeUri)
            val events = mutableListOf<LocalChangeEvent>()

            watcher.register(pairId = pairId, listener = events::add)
            observerRegistry.dispatch(changedUri)
            observerRegistry.dispatch(changedUri)
            observerRegistry.dispatch(Uri.parse(treeUri))
            observerRegistry.dispatch(null)

            assertEquals(
                listOf(
                    LocalChangeEvent.Changed(pairId, changedUri.toString()),
                    LocalChangeEvent.Changed(pairId, changedUri.toString()),
                    LocalChangeEvent.Changed(pairId, treeUri, isCoarse = true),
                    LocalChangeEvent.Changed(pairId, null, isCoarse = true),
                ),
                events,
            )
        }

    @Test
    fun `callback entry is logged for null root and descendant URIs`() =
        runTest {
            val treeUri = "content://com.example/tree/root"
            val pairId = insertPair(localTreeUri = treeUri)
            accessChecker.grant(treeUri)
            watcher.register(pairId = pairId) {}

            observerRegistry.dispatch(null)
            observerRegistry.dispatch(Uri.parse(treeUri))
            observerRegistry.dispatch(Uri.parse("content://com.example/tree/root/document/child"))

            val callbackLogs =
                recordingTree.infoMessages.filter { it.startsWith("instant.watch.callback ") }
            assertEquals(3, callbackLogs.size)
            assertTrue(callbackLogs[0].contains("uriNull=true"))
            assertTrue(callbackLogs[1].contains("treeRoot=true"))
            assertTrue(callbackLogs[2].contains("authority=com.example"))
            assertTrue(callbackLogs.none { it.contains("child") })
        }

    @Test
    fun `callbacks never invoke uploads and only notify listeners`() =
        runTest {
            val treeUri = "content://com.example/tree/root"
            val pairId = insertPair(localTreeUri = treeUri)
            accessChecker.grant(treeUri)
            val events = mutableListOf<LocalChangeEvent>()

            val registration =
                (
                    watcher.register(pairId = pairId, listener = events::add)
                        as LocalChangeWatchRegistrationResult.Registered
                ).registration

            observerRegistry.dispatch(Uri.parse("content://com.example/document/root%3Afile.txt"))
            registration.unregister()
            observerRegistry.dispatch(Uri.parse("content://com.example/document/root%3Aignored.txt"))

            assertEquals(1, events.size)
            assertEquals(1, observerRegistry.unregisteredObservers.size)
        }

    @Test
    fun `missing permission reports periodic-scan fallback`() =
        runTest {
            val treeUri = "content://com.example/tree/root"
            val pairId = insertPair(localTreeUri = treeUri)

            val result = watcher.register(pairId = pairId) {}

            assertEquals(
                LocalChangeWatchRegistrationResult.Unavailable(
                    LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN),
                ),
                result,
            )
            assertTrue(observerRegistry.registrations.isEmpty())
        }

    @Test
    fun `permission loss during callback emits failure and unregisters watcher`() =
        runTest {
            val treeUri = "content://com.example/tree/root"
            val pairId = insertPair(localTreeUri = treeUri)
            accessChecker.grant(treeUri)
            val events = mutableListOf<LocalChangeEvent>()

            watcher.register(pairId = pairId, listener = events::add)
            accessChecker.revoke(treeUri)
            observerRegistry.dispatch(Uri.parse("content://com.example/document/root%3Achild.txt"))
            observerRegistry.dispatch(Uri.parse("content://com.example/document/root%3Asecond.txt"))

            assertEquals(
                listOf(LocalChangeEvent.Failure(pairId, LocalChangeWatchFailure.PermissionDenied)),
                events,
            )
            assertEquals(1, observerRegistry.unregisteredObservers.size)
        }

    @Test
    fun `non upload directions are not watched and report fallback`() =
        runTest {
            val treeUri = "content://com.example/tree/root"
            val pairId = insertPair(localTreeUri = treeUri, direction = SyncDirection.REMOTE_TO_LOCAL)
            accessChecker.grant(treeUri)

            val result = watcher.register(pairId = pairId) {}

            assertEquals(
                LocalChangeWatchRegistrationResult.Unavailable(
                    LocalChangeWatcherCapability.Unavailable(LocalChangeWatcherFallback.PERIODIC_SCAN),
                ),
                result,
            )
            assertTrue(observerRegistry.registrations.isEmpty())
        }

    @Test
    fun `refresh moves an active registration to the saved tree URI`() =
        runTest {
            val oldUri = "content://com.example/tree/old"
            val newUri = "content://com.example/tree/new"
            val pairId = insertPair(localTreeUri = oldUri, instantSyncEnabled = true)
            accessChecker.grant(oldUri)
            accessChecker.grant(newUri)
            watcher.register(pairId = pairId) {}
            val saved = checkNotNull(db.syncPairDao().getById(pairId))
            db.syncPairDao().upsert(saved.copy(localTreeUri = newUri))

            watcher.refresh(pairId)

            assertEquals(Uri.parse(newUri), observerRegistry.registrations.single().uri)
            assertEquals(1, observerRegistry.unregisteredObservers.size)
        }

    @Test
    fun `refresh stops an active registration when auto sync is disabled`() =
        runTest {
            val treeUri = "content://com.example/tree/root"
            val pairId = insertPair(localTreeUri = treeUri)
            accessChecker.grant(treeUri)

            val result = watcher.register(pairId = pairId) {}

            assertTrue(result is LocalChangeWatchRegistrationResult.Registered)
            assertEquals(1, observerRegistry.registrations.size)
            val observer = observerRegistry.registrations.single().observer
            val saved = checkNotNull(db.syncPairDao().getById(pairId))
            db.syncPairDao().upsert(saved.copy(autoSyncEnabled = false))

            watcher.refresh(pairId)

            assertTrue(observerRegistry.unregisteredObservers.contains(observer))
            assertTrue(observerRegistry.registrations.isEmpty())
        }

    @Test
    fun `refresh unregisters a live registration when instant sync is turned off`() =
        runTest {
            val treeUri = "content://com.example/tree/root"
            val pairId = insertPair(localTreeUri = treeUri)
            accessChecker.grant(treeUri)

            val result = watcher.register(pairId = pairId) {}

            assertTrue(result is LocalChangeWatchRegistrationResult.Registered)
            assertEquals(1, observerRegistry.registrations.size)
            val observer = observerRegistry.registrations.single().observer
            val saved = checkNotNull(db.syncPairDao().getById(pairId))
            db.syncPairDao().upsert(saved.copy(instantSyncEnabled = false))

            watcher.refresh(pairId)

            assertTrue(observerRegistry.unregisteredObservers.contains(observer))
            assertTrue(observerRegistry.registrations.isEmpty())
        }

    @Test
    fun `register enforces every watchability gate`() =
        runTest {
            val directions = listOf(SyncDirection.BIDIRECTIONAL, SyncDirection.REMOTE_TO_LOCAL)
            for (autoSyncEnabled in listOf(false, true)) {
                for (instantSyncEnabled in listOf(false, true)) {
                    for (direction in directions) {
                        // Reset the registry each iteration so assertions below observe only the
                        // current combination instead of registrations accumulated by earlier ones.
                        observerRegistry.registrations.clear()
                        observerRegistry.unregisteredObservers.clear()
                        val treeUri =
                            "content://com.example/tree/$autoSyncEnabled-$instantSyncEnabled-${direction.name}"
                        val pairId =
                            insertPair(
                                localTreeUri = treeUri,
                                direction = direction,
                                autoSyncEnabled = autoSyncEnabled,
                                instantSyncEnabled = instantSyncEnabled,
                            )
                        accessChecker.grant(treeUri)
                        val shouldRegister =
                            autoSyncEnabled &&
                                instantSyncEnabled &&
                                direction == SyncDirection.BIDIRECTIONAL

                        val result = watcher.register(pairId = pairId) {}

                        assertEquals(
                            "register result for auto=$autoSyncEnabled instant=$instantSyncEnabled direction=$direction",
                            shouldRegister,
                            result is LocalChangeWatchRegistrationResult.Registered,
                        )
                        assertEquals(
                            "observer state for auto=$autoSyncEnabled instant=$instantSyncEnabled direction=$direction",
                            shouldRegister,
                            observerRegistry.registrations.any { it.uri == Uri.parse(treeUri) },
                        )
                    }
                }
            }
        }

    private suspend fun insertPair(
        localTreeUri: String,
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        autoSyncEnabled: Boolean = true,
        instantSyncEnabled: Boolean = true,
    ): Long =
        db.syncPairDao().insert(
            SyncPairEntity(
                displayName = "Pair",
                localTreeUri = localTreeUri,
                provider = CloudProviderType.FAKE,
                remoteFolderId = "root",
                direction = direction,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                includeGlobs = "",
                excludeGlobs = "",
                wifiOnly = true,
                requiresCharging = false,
                autoSyncEnabled = autoSyncEnabled,
                instantSyncEnabled = instantSyncEnabled,
            ),
        )

    private class FakeLocalFolderAccessChecker : LocalFolderAccessChecker {
        private val granted = mutableSetOf<String>()

        override fun hasReadWriteAccess(treeUri: String): Boolean = treeUri in granted

        fun grant(treeUri: String) {
            granted += treeUri
        }

        fun revoke(treeUri: String) {
            granted -= treeUri
        }
    }

    private class FakeContentObserverRegistry : ContentObserverRegistry {
        data class Registration(
            val uri: Uri,
            val notifyForDescendants: Boolean,
            val observer: ContentObserver,
        )

        val registrations = mutableListOf<Registration>()
        val unregisteredObservers = mutableListOf<ContentObserver>()

        override fun registerContentObserver(
            uri: Uri,
            notifyForDescendants: Boolean,
            observer: ContentObserver,
        ) {
            registrations.add(Registration(uri, notifyForDescendants, observer))
        }

        override fun unregisterContentObserver(observer: ContentObserver) {
            unregisteredObservers.add(observer)
            registrations.removeAll { it.observer === observer }
        }

        fun dispatch(uri: Uri?) {
            registrations.toList().forEach { registration ->
                registration.observer.onChange(false, uri)
            }
        }
    }

    private class RecordingTree : Timber.Tree() {
        val infoMessages = mutableListOf<String>()

        override fun log(
            priority: Int,
            tag: String?,
            message: String,
            t: Throwable?,
        ) {
            if (priority == Log.INFO) infoMessages += message
        }
    }
}
