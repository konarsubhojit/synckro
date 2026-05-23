package com.synckro.domain.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.local.dao.ConflictRecordDao
import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.dao.SyncEventDao
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.local.fs.LocalFsEnumerator
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.data.scanner.DocumentChildrenQuery
import com.synckro.data.scanner.RawDocChild
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.ConflictRecord
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import com.synckro.domain.provider.CloudProvider
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.provider.CloudProviderException
import com.synckro.domain.sync.RemoteChange
import com.synckro.domain.sync.RemoteChangeType
import com.synckro.domain.sync.RemoteEnumerator
import com.synckro.domain.sync.RemoteSnapshot
import com.synckro.providers.fake.FakeCloudProvider
import com.synckro.providers.fake.FakeRemoteEnumerator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Robolectric integration smoke test for [SyncEngine.runReal].
 *
 * All I/O is kept in-memory:
 * - [FakeCloudProvider] / [FakeRemoteEnumerator] stand in for the remote provider.
 * - [DocumentChildrenQuery] is replaced with a configurable in-memory fake.
 * - [LocalFileAccess] is replaced with [InMemoryLocalFileAccess].
 * - Room is backed by an in-memory database.
 *
 * These tests exercise the full [runReal] pipeline (enumerate → diff → apply →
 * persist) without touching the Android file system, network, or WorkManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineRealIntegrationTest {
    // -------------------------------------------------------------------------
    // In-memory fakes
    // -------------------------------------------------------------------------

    /**
     * A [DocumentChildrenQuery] backed by a [MutableMap] from (parentDocId → children).
     * The root doc ID is fixed at `"root"` (matching [treeUri]).
     */
    private class InMemoryDocumentChildren : DocumentChildrenQuery {
        private val tree = mutableMapOf<String, List<RawDocChild>>()

        /** Registers [children] as the direct children of [parentDocId]. */
        fun set(parentDocId: String, children: List<RawDocChild>) {
            tree[parentDocId] = children
        }

        override fun invoke(
            resolver: android.content.ContentResolver,
            treeUri: Uri,
            parentDocId: String,
        ): List<RawDocChild> = tree[parentDocId] ?: emptyList()
    }

    /** Pure in-memory [LocalFileAccess] backed by a [MutableMap]. */
    private class InMemoryLocalFileAccess(
        private val nowMs: Long = 5_000L,
    ) : LocalFileAccess {
        private val files = mutableMapOf<String, ByteArray>()

        fun put(path: String, bytes: ByteArray) {
            files[path] = bytes
        }

        override fun openRead(path: String): InputStream? =
            files[path]?.let { ByteArrayInputStream(it) }

        override fun write(path: String, content: InputStream, mimeType: String?): LocalFileStat {
            val bytes = content.use { it.readBytes() }
            files[path] = bytes
            return LocalFileStat(sizeBytes = bytes.size.toLong(), mtimeMs = nowMs, mimeType = mimeType)
        }

        override fun delete(path: String): Boolean = files.remove(path) != null

        override fun stat(path: String): LocalFileStat? =
            files[path]?.let { LocalFileStat(sizeBytes = it.size.toLong(), mtimeMs = nowMs) }
    }

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private val treeUri = Uri.parse("content://com.example.test/tree/root")

    private lateinit var db: SynckroDatabase
    private lateinit var syncPairDao: SyncPairDao
    private lateinit var localIndexDao: LocalIndexDao
    private lateinit var conflictRecordDao: ConflictRecordDao
    private lateinit var syncEventDao: SyncEventDao

    private lateinit var conflictRepository: ConflictRepository
    private lateinit var eventRepository: SyncEventRepository

    private lateinit var fakeProvider: FakeCloudProvider
    private lateinit var fakeRemoteEnumerator: FakeRemoteEnumerator

    private lateinit var inMemoryChildren: InMemoryDocumentChildren
    private lateinit var localFileAccess: InMemoryLocalFileAccess
    private lateinit var localFsEnumerator: LocalFsEnumerator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, SynckroDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        syncPairDao = db.syncPairDao()
        localIndexDao = db.localIndexDao()
        conflictRecordDao = db.conflictRecordDao()
        syncEventDao = db.syncEventDao()

        conflictRepository = ConflictRepository(conflictRecordDao)
        eventRepository = SyncEventRepository(syncEventDao)

        fakeProvider = FakeCloudProvider()
        fakeRemoteEnumerator = FakeRemoteEnumerator(fakeProvider)

        inMemoryChildren = InMemoryDocumentChildren()
        localFileAccess = InMemoryLocalFileAccess()

        localFsEnumerator =
            LocalFsEnumerator(
                resolver = context.contentResolver,
                localIndexDao = localIndexDao,
                childrenQuery = inMemoryChildren,
                // FsAccess opens file streams for SHA-256 hashing; returning null disables
                // hashing so tests focus on size/mtime diffing without needing real streams.
                fsAccess = { _, _ -> null },
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Inserts a [SyncPairEntity] of provider type [providerType] and returns its domain model. */
    private suspend fun insertPair(
        providerType: CloudProviderType = CloudProviderType.ONEDRIVE,
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        conflictPolicy: ConflictPolicy = ConflictPolicy.NEWEST_WINS,
        accountId: String = "test-account",
    ): SyncPair {
        val entity =
            SyncPairEntity(
                displayName = "Smoke Test Pair",
                localTreeUri = treeUri.toString(),
                provider = providerType,
                accountId = accountId,
                remoteFolderId = "remote-root",
                direction = direction,
                conflictPolicy = conflictPolicy,
                includeGlobs = "",
                excludeGlobs = "",
                wifiOnly = false,
                requiresCharging = false,
            )
        val id = syncPairDao.insert(entity)
        return SyncPair(
            id = id,
            displayName = entity.displayName,
            localTreeUri = entity.localTreeUri,
            provider = entity.provider,
            accountId = entity.accountId,
            remoteFolderId = entity.remoteFolderId,
            direction = entity.direction,
            conflictPolicy = entity.conflictPolicy,
        )
    }

    /** Builds a [SyncEngine] wired with all the in-memory fakes. */
    private fun buildEngine(): SyncEngine =
        SyncEngine(
            conflictRepository = conflictRepository,
            providers = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(fakeProvider)),
            localFsEnumerator = localFsEnumerator,
            remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to fakeRemoteEnumerator),
            syncPairDao = syncPairDao,
            localIndexDao = localIndexDao,
            eventRepository = eventRepository,
            localFileAccess = { _ -> localFileAccess },
        )

    private fun singleProviderFactory(provider: CloudProvider): CloudProviderFactory =
        object : CloudProviderFactory {
            override fun providerFor(accountId: String): CloudProvider = provider
        }

    // -------------------------------------------------------------------------
    // Smoke tests
    // -------------------------------------------------------------------------

    @Test
    fun `runReal with empty local and empty remote returns Success`() =
        runTest {
            // Empty local FS — InMemoryDocumentChildren returns nothing
            val pair = insertPair()
            val engine = buildEngine()

            val result = engine.runOnce(pair)

            assertTrue(
                "Expected Success for empty sync, got: $result",
                result is SyncEngine.Result.Success,
            )
        }

    @Test
    fun `runReal with empty local and empty remote returns zero applied and conflicts`() =
        runTest {
            val pair = insertPair()
            val engine = buildEngine()

            val result = engine.runOnce(pair) as SyncEngine.Result.Success

            assertEquals(0, result.applied)
            assertEquals(0, result.conflicts)
        }

    @Test
    fun `runReal emits structured log for each sync pipeline step`() =
        runTest {
            val pair = insertPair()

            val result = buildEngine().runOnce(pair)

            assertTrue("Expected Success for empty sync, got: $result", result is SyncEngine.Result.Success)
            val messages = eventRepository.getAll().map { it.message }
            for (step in 0..8) {
                assertTrue(
                    "Expected structured log for step $step",
                    messages.any { it.startsWith("Step $step/8:") },
                )
            }
        }

    @Test
    fun `runReal persists delta token after successful run`() =
        runTest {
            val pair = insertPair()
            val engine = buildEngine()

            engine.runOnce(pair)

            val updated = syncPairDao.getById(pair.id)
            assertNotNull("SyncPair should still exist after sync", updated)
            assertNotNull(
                "deltaToken should be persisted after a successful runReal",
                updated!!.lastDeltaToken,
            )
            // FakeCloudProvider returns "0" as the initial delta token (changeLog.size = 0)
            assertEquals("0", updated.lastDeltaToken)
        }

    @Test
    fun `runReal persists lastFullScanAtMs after successful run`() =
        runTest {
            val pair = insertPair()
            val engine = buildEngine()

            val beforeMs = System.currentTimeMillis()
            engine.runOnce(pair)
            val afterMs = System.currentTimeMillis()

            val updated = syncPairDao.getById(pair.id)
            assertNotNull(updated)
            val scanAt = updated!!.lastFullScanAtMs
            assertNotNull("lastFullScanAtMs should be persisted after runReal", scanAt)
            assertTrue(
                "lastFullScanAtMs ($scanAt) should be within the test window [$beforeMs, $afterMs]",
                scanAt!! in beforeMs..afterMs,
            )
        }

    @Test
    fun `runReal uploads new local file on first sync`() =
        runTest {
            // Seed one local file via the in-memory children query + localFileAccess
            val fileContent = "hello, world".toByteArray()
            localFileAccess.put("readme.txt", fileContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-readme",
                        name = "readme.txt",
                        mimeType = "text/plain",
                        size = fileContent.size.toLong(),
                        lastModifiedMs = 1_000L,
                    ),
                ),
            )

            val pair = insertPair()
            val engine = buildEngine()

            val result = engine.runOnce(pair)

            assertTrue(
                "Expected Success after uploading one new file, got: $result",
                result is SyncEngine.Result.Success,
            )
            val success = result as SyncEngine.Result.Success
            assertEquals("One file should have been applied (uploaded)", 1, success.applied)
            assertEquals(0, success.conflicts)
        }

    @Test
    fun `runReal second run with unchanged files is a no-op`() =
        runTest {
            // First run: upload a file.
            // Use lastModifiedMs = 5_000L to match InMemoryLocalFileAccess.nowMs (= 5_000L),
            // so SyncOpApplier's stat().mtimeMs and LocalFsEnumerator's mtime agree and the file
            // is not treated as "locally modified" on the second run.
            val fileContent = "hello".toByteArray()
            localFileAccess.put("hello.txt", fileContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-hello",
                        name = "hello.txt",
                        mimeType = "text/plain",
                        size = fileContent.size.toLong(),
                        lastModifiedMs = 5_000L, // must match InMemoryLocalFileAccess.nowMs
                    ),
                ),
            )

            val pair = insertPair()
            val engine = buildEngine()

            val firstResult = engine.runOnce(pair)
            assertTrue("First run should succeed", firstResult is SyncEngine.Result.Success)

            // Retrieve the stored delta token
            val storedToken = syncPairDao.getById(pair.id)!!.lastDeltaToken!!
            val pairWithToken = pair.copy(deltaToken = storedToken)

            // Second run: same local FS, same remote state — nothing should be applied
            val secondResult = engine.runOnce(pairWithToken)

            assertTrue("Second run should succeed", secondResult is SyncEngine.Result.Success)
            assertEquals(
                "Second run with unchanged files should have zero applied ops",
                0,
                (secondResult as SyncEngine.Result.Success).applied,
            )
        }

    @Test
    fun `runReal returns Terminal when RemoteEnumerator is not registered`() =
        runTest {
            val pair = insertPair(providerType = CloudProviderType.GOOGLE_DRIVE)
            // Engine has no GOOGLE_DRIVE remote enumerator
            val engine =
                SyncEngine(
                    conflictRepository = conflictRepository,
                    providers = mapOf(CloudProviderType.GOOGLE_DRIVE to singleProviderFactory(fakeProvider)),
                    localFsEnumerator = localFsEnumerator,
                    remoteEnumerators = emptyMap(), // no GOOGLE_DRIVE enumerator
                    syncPairDao = syncPairDao,
                    localIndexDao = localIndexDao,
                    eventRepository = eventRepository,
                    localFileAccess = { _ -> localFileAccess },
                )

            val result = engine.runOnce(pair)

            assertTrue(
                "Should return Terminal when RemoteEnumerator is missing, got: $result",
                result is SyncEngine.Result.Terminal,
            )
        }

    @Test
    fun `runReal does not persist token on CancellationException`() =
        runTest {
            val pair = insertPair()

            // A broken RemoteEnumerator that throws CancellationException
            val cancellingEnumerator =
                object : RemoteEnumerator {
                    override suspend fun enumerate(deltaToken: String?, rootFolderId: String): RemoteSnapshot = throw kotlinx.coroutines.CancellationException("test cancellation")
                }

            val engine =
                SyncEngine(
                    conflictRepository = conflictRepository,
                    providers = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(fakeProvider)),
                    localFsEnumerator = localFsEnumerator,
                    remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to cancellingEnumerator),
                    syncPairDao = syncPairDao,
                    localIndexDao = localIndexDao,
                    eventRepository = eventRepository,
                    localFileAccess = { _ -> localFileAccess },
                )

            var caughtCancellation = false
            try {
                engine.runOnce(pair)
            } catch (c: kotlinx.coroutines.CancellationException) {
                caughtCancellation = true
            }

            assertTrue("CancellationException should propagate out of runOnce", caughtCancellation)

            // Delta token must NOT have been written
            val entity = syncPairDao.getById(pair.id)
            assertNotNull(entity)
            assertTrue(
                "deltaToken must NOT be persisted after cancellation",
                entity!!.lastDeltaToken == null,
            )
        }

    // -------------------------------------------------------------------------
    // Download from remote — initial full scan for new pairs
    // -------------------------------------------------------------------------

    @Test
    fun `runReal on new pair downloads existing remote files on first sync`() =
        runTest {
            // Seed a remote file that already exists before the pair was created.
            val fileContent = "remote content".toByteArray()
            fakeProvider.uploadNew(
                parentId = "remote-root",
                name = "remote.txt",
                content = fileContent.inputStream(),
                size = fileContent.size.toLong(),
                mimeType = "text/plain",
            )

            val pair = insertPair()
            val engine = buildEngine()

            // First sync: enumerateFull() returns the existing remote file → DownloadNew.
            val result = engine.runOnce(pair)

            assertTrue("First run should succeed", result is SyncEngine.Result.Success)
            assertEquals(
                "Existing remote file should be downloaded on first sync",
                1,
                (result as SyncEngine.Result.Success).applied,
            )
            assertNotNull(
                "Downloaded file should be readable from local storage",
                localFileAccess.openRead("remote.txt"),
            )
        }

    @Test
    fun `runReal on new pair with multiple existing remote files downloads all of them`() =
        runTest {
            // Seed several files in the remote folder before the pair is created.
            val content1 = "alpha".toByteArray()
            val content2 = "beta".toByteArray()
            val content3 = "gamma".toByteArray()
            fakeProvider.uploadNew("remote-root", "a.txt", content1.inputStream(), content1.size.toLong(), "text/plain")
            fakeProvider.uploadNew("remote-root", "b.txt", content2.inputStream(), content2.size.toLong(), "text/plain")
            fakeProvider.uploadNew("remote-root", "c.txt", content3.inputStream(), content3.size.toLong(), "text/plain")

            val pair = insertPair()
            val engine = buildEngine()

            val result = engine.runOnce(pair)

            assertTrue("First run should succeed", result is SyncEngine.Result.Success)
            assertEquals(
                "All three remote files should be downloaded on first sync",
                3,
                (result as SyncEngine.Result.Success).applied,
            )
            assertNotNull(localFileAccess.openRead("a.txt"))
            assertNotNull(localFileAccess.openRead("b.txt"))
            assertNotNull(localFileAccess.openRead("c.txt"))
        }

    @Test
    fun `runReal on new pair with existing remote and local files syncs both directions`() =
        runTest {
            // Remote has "remote-only.txt"; local has "local-only.txt".
            val remoteContent = "from cloud".toByteArray()
            fakeProvider.uploadNew(
                parentId = "remote-root",
                name = "remote-only.txt",
                content = remoteContent.inputStream(),
                size = remoteContent.size.toLong(),
                mimeType = "text/plain",
            )

            val localContent = "from device".toByteArray()
            localFileAccess.put("local-only.txt", localContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-local",
                        name = "local-only.txt",
                        mimeType = "text/plain",
                        size = localContent.size.toLong(),
                        lastModifiedMs = 5_000L,
                    ),
                ),
            )

            val pair = insertPair(direction = SyncDirection.BIDIRECTIONAL)
            val engine = buildEngine()

            val result = engine.runOnce(pair)

            assertTrue("First run should succeed", result is SyncEngine.Result.Success)
            assertEquals(
                "Both remote-only and local-only file should be synced",
                2,
                (result as SyncEngine.Result.Success).applied,
            )
            // Remote file downloaded locally
            assertNotNull(
                "Remote-only file should have been downloaded",
                localFileAccess.openRead("remote-only.txt"),
            )
            // Local file uploaded to remote
            val remoteFiles = fakeProvider.list("remote-root").filter { !it.isFolder }
            assertTrue(
                "local-only.txt should have been uploaded to remote",
                remoteFiles.any { it.name == "local-only.txt" },
            )
        }

    @Test
    fun `runReal on cold-start relink seeds index for matching local and remote file`() =
        runTest {
            val fileContent = "same content".toByteArray()
            val remoteFile =
                fakeProvider.uploadNew(
                    parentId = "remote-root",
                    name = "same.txt",
                    content = fileContent.inputStream(),
                    size = fileContent.size.toLong(),
                    mimeType = "text/plain",
                )

            localFileAccess.put("same.txt", fileContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-same",
                        name = "same.txt",
                        mimeType = "text/plain",
                        size = fileContent.size.toLong(),
                        lastModifiedMs = remoteFile.lastModifiedMs ?: 5_000L,
                    ),
                ),
            )

            val pair = insertPair()
            val result = buildEngine().runOnce(pair)

            assertTrue("Cold-start relink should succeed", result is SyncEngine.Result.Success)
            assertEquals(
                "Matching files should be reconciled without applying upload/download ops",
                0,
                (result as SyncEngine.Result.Success).applied,
            )

            val remoteFiles = fakeProvider.list("remote-root").filter { !it.isFolder && it.name == "same.txt" }
            assertEquals("Remote file must not be duplicated", 1, remoteFiles.size)

            val indexEntry = localIndexDao.getForPair(pair.id).single { it.relativePath == "same.txt" }
            assertEquals(remoteFile.id, indexEntry.remoteId)
            assertEquals(remoteFile.size, indexEntry.remoteSizeBytes)
            assertEquals(remoteFile.lastModifiedMs, indexEntry.remoteMtimeMs)
            assertEquals(remoteFile.eTag, indexEntry.remoteEtag)

            val events = eventRepository.getAll()
            assertTrue(
                "Cold-start reconciliation should emit an INFO log entry",
                events.any { it.message == "Reconciled existing remote file: same.txt" },
            )
        }

    @Test
    fun `runReal on cold-start relink with local-newer file updates existing remote instead of uploading duplicate`() =
        runTest {
            val remoteContent = "remote".toByteArray()
            val remoteFile =
                fakeProvider.uploadNew(
                    parentId = "remote-root",
                    name = "notes.txt",
                    content = remoteContent.inputStream(),
                    size = remoteContent.size.toLong(),
                    mimeType = "text/plain",
                )

            val localContent = "local-newer".toByteArray()
            localFileAccess.put("notes.txt", localContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-notes",
                        name = "notes.txt",
                        mimeType = "text/plain",
                        size = localContent.size.toLong(),
                        lastModifiedMs = (remoteFile.lastModifiedMs ?: 5_000L) + 1_000L,
                    ),
                ),
            )

            val pair = insertPair(conflictPolicy = ConflictPolicy.NEWEST_WINS)
            val result = buildEngine().runOnce(pair)

            assertTrue("Cold-start relink with local-newer content should succeed", result is SyncEngine.Result.Success)
            assertEquals(
                "The existing remote file should be updated in place",
                1,
                (result as SyncEngine.Result.Success).applied,
            )

            val remoteFiles = fakeProvider.list("remote-root").filter { !it.isFolder && it.name == "notes.txt" }
            assertEquals("Updating an existing remote file must not create a duplicate", 1, remoteFiles.size)
            assertEquals(
                "The remote content should now match the local newer version",
                "local-newer",
                fakeProvider.download(remoteFiles.single().id).readBytes().toString(Charsets.UTF_8),
            )

            val indexEntry = localIndexDao.getForPair(pair.id).single { it.relativePath == "notes.txt" }
            assertEquals(
                "The sync should keep the original remote ID when updating in place",
                remoteFile.id,
                indexEntry.remoteId,
            )
        }

    @Test
    fun `runReal on new pair with nested existing remote files downloads all of them`() =
        runTest {
            // Seed a nested structure: remote-root/docs/report.txt
            val subFolder = fakeProvider.createFolder("remote-root", "docs")
            val fileContent = "nested report".toByteArray()
            fakeProvider.uploadNew(
                parentId = subFolder.id,
                name = "report.txt",
                content = fileContent.inputStream(),
                size = fileContent.size.toLong(),
                mimeType = "text/plain",
            )

            val pair = insertPair()
            val engine = buildEngine()

            val result = engine.runOnce(pair)

            assertTrue("First run should succeed", result is SyncEngine.Result.Success)
            assertEquals(
                "Nested remote file should be downloaded on first sync",
                1,
                (result as SyncEngine.Result.Success).applied,
            )
            assertNotNull(
                "Nested file should be readable at its full relative path",
                localFileAccess.openRead("docs/report.txt"),
            )
        }

    @Test
    fun `runReal downloads new remote file added after baseline`() =
        runTest {
            // Start with empty remote — first sync establishes the baseline token.
            val pair = insertPair()
            val engine = buildEngine()

            val baselineResult = engine.runOnce(pair)
            assertTrue("Baseline run should succeed", baselineResult is SyncEngine.Result.Success)
            assertEquals(
                "No files to apply in empty baseline run",
                0,
                (baselineResult as SyncEngine.Result.Success).applied,
            )

            // Add a new remote file AFTER the baseline token was stored.
            val fileContent = "new remote content".toByteArray()
            fakeProvider.uploadNew(
                parentId = "remote-root",
                name = "new-remote.txt",
                content = fileContent.inputStream(),
                size = fileContent.size.toLong(),
                mimeType = "text/plain",
            )

            val storedToken = syncPairDao.getById(pair.id)!!.lastDeltaToken!!
            val pairWithToken = pair.copy(deltaToken = storedToken)
            val deltaResult = engine.runOnce(pairWithToken)

            assertTrue("Delta run should succeed", deltaResult is SyncEngine.Result.Success)
            assertEquals(
                "New remote file added after baseline should be downloaded",
                1,
                (deltaResult as SyncEngine.Result.Success).applied,
            )
        }

    // -------------------------------------------------------------------------
    // Retriable / Terminal outcomes from remote enumeration failures
    // -------------------------------------------------------------------------

    @Test
    fun `runReal returns Retriable when RemoteEnumerator throws AuthenticationFailed`() =
        runTest {
            val pair = insertPair()

            // AuthenticationFailed indicates a transient failure during token refresh
            // (e.g., a network blip while contacting the token endpoint). CloudExceptionMapper
            // maps this to Retriable so WorkManager can retry with backoff and succeed once
            // the transient issue resolves — unlike AuthenticationRequired, which needs
            // interactive user action and is therefore mapped to Terminal.
            val failingEnumerator =
                object : RemoteEnumerator {
                    override suspend fun enumerate(deltaToken: String?, rootFolderId: String): RemoteSnapshot = throw CloudProviderException.AuthenticationFailed(
                        "Token refresh failed (transient network error)",
                    )
                }

            val engine =
                SyncEngine(
                    conflictRepository = conflictRepository,
                    providers = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(fakeProvider)),
                    localFsEnumerator = localFsEnumerator,
                    remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to failingEnumerator),
                    syncPairDao = syncPairDao,
                    localIndexDao = localIndexDao,
                    eventRepository = eventRepository,
                    localFileAccess = { _ -> localFileAccess },
                )

            val result = engine.runOnce(pair)

            assertTrue(
                "Expected Retriable when AuthenticationFailed, got: $result",
                result is SyncEngine.Result.Retriable,
            )
        }

    @Test
    fun `runReal returns Terminal when RemoteEnumerator throws AuthenticationRequired`() =
        runTest {
            val pair = insertPair()

            val failingEnumerator =
                object : RemoteEnumerator {
                    override suspend fun enumerate(deltaToken: String?, rootFolderId: String): RemoteSnapshot = throw CloudProviderException.AuthenticationRequired(
                        "Access token expired — interactive sign-in required",
                    )
                }

            val engine =
                SyncEngine(
                    conflictRepository = conflictRepository,
                    providers = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(fakeProvider)),
                    localFsEnumerator = localFsEnumerator,
                    remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to failingEnumerator),
                    syncPairDao = syncPairDao,
                    localIndexDao = localIndexDao,
                    eventRepository = eventRepository,
                    localFileAccess = { _ -> localFileAccess },
                )

            val result = engine.runOnce(pair)

            assertTrue(
                "Expected Terminal when AuthenticationRequired, got: $result",
                result is SyncEngine.Result.Terminal,
            )
            assertTrue(
                "Terminal result should have needsReauth = true",
                (result as SyncEngine.Result.Terminal).needsReauth,
            )
        }

    @Test
    fun `runReal returns Terminal when RemoteEnumerator throws NotConfigured`() =
        runTest {
            val pair = insertPair()

            val failingEnumerator =
                object : RemoteEnumerator {
                    override suspend fun enumerate(deltaToken: String?, rootFolderId: String): RemoteSnapshot = throw CloudProviderException.NotConfigured("Client ID not configured")
                }

            val engine =
                SyncEngine(
                    conflictRepository = conflictRepository,
                    providers = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(fakeProvider)),
                    localFsEnumerator = localFsEnumerator,
                    remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to failingEnumerator),
                    syncPairDao = syncPairDao,
                    localIndexDao = localIndexDao,
                    eventRepository = eventRepository,
                    localFileAccess = { _ -> localFileAccess },
                )

            val result = engine.runOnce(pair)

            assertTrue(
                "Expected Terminal when NotConfigured, got: $result",
                result is SyncEngine.Result.Terminal,
            )
            assertTrue(
                "Terminal result should have needsReauth = true for NotConfigured",
                (result as SyncEngine.Result.Terminal).needsReauth,
            )
        }

    // -------------------------------------------------------------------------
    // PartialFailure: some ops succeed, others fail with non-auth errors
    // -------------------------------------------------------------------------

    @Test
    fun `runReal returns PartialFailure when one upload fails and another succeeds`() =
        runTest {
            // "ok.txt" is present in both inMemoryChildren AND localFileAccess → upload succeeds.
            // "fail.txt" is in inMemoryChildren but NOT in localFileAccess → stat() returns null
            //   → applyUploadNew throws → caught as non-auth error → PartialFailure.
            val okContent = "hello".toByteArray()
            localFileAccess.put("ok.txt", okContent)
            // "fail.txt" intentionally NOT added to localFileAccess

            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-ok",
                        name = "ok.txt",
                        mimeType = "text/plain",
                        size = okContent.size.toLong(),
                        lastModifiedMs = 5_000L,
                    ),
                    RawDocChild(
                        docId = "doc-fail",
                        name = "fail.txt",
                        mimeType = "text/plain",
                        size = 99L,
                        lastModifiedMs = 5_000L,
                    ),
                ),
            )

            val pair = insertPair()
            val engine = buildEngine()

            val result = engine.runOnce(pair)

            assertTrue(
                "Expected PartialFailure when one upload fails, got: $result",
                result is SyncEngine.Result.PartialFailure,
            )
            val partial = result as SyncEngine.Result.PartialFailure
            assertEquals("One file should have been applied", 1, partial.applied)
            assertEquals("One file should have failed", 1, partial.errors.size)
        }

    // -------------------------------------------------------------------------
    // Conflict policy: KEEP_BOTH
    // -------------------------------------------------------------------------

    @Test
    fun `runReal with KEEP_BOTH policy records conflict for concurrent modifications`() =
        runTest {
            // Arrange: a custom RemoteEnumerator that advertises "conflict.txt" as a remote change
            // with different size/mtime from the local file, so SyncDiffer emits a Conflict op.
            val conflictEnumerator =
                object : RemoteEnumerator {
                    override suspend fun enumerate(deltaToken: String?, rootFolderId: String): RemoteSnapshot =
                        RemoteSnapshot(
                            changes =
                                listOf(
                                    RemoteChange(
                                        relativePath = "conflict.txt",
                                        type = RemoteChangeType.MODIFY,
                                        remoteId = "remote-conflict-id",
                                        sizeBytes = 999L,
                                        mtimeMs = 9_000L,
                                        etag = "etag-remote",
                                    ),
                                ),
                            newDeltaToken = "1",
                        )
                }

            // Local file exists with different mtime (simulates concurrent modification)
            val localContent = "local version".toByteArray()
            localFileAccess.put("conflict.txt", localContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-conflict",
                        name = "conflict.txt",
                        mimeType = "text/plain",
                        size = localContent.size.toLong(),
                        lastModifiedMs = 1_000L, // different from remote's 9_000L
                    ),
                ),
            )

            val pair = insertPair(conflictPolicy = ConflictPolicy.KEEP_BOTH)
            val engine =
                SyncEngine(
                    conflictRepository = conflictRepository,
                    providers = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(fakeProvider)),
                    localFsEnumerator = localFsEnumerator,
                    remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to conflictEnumerator),
                    syncPairDao = syncPairDao,
                    localIndexDao = localIndexDao,
                    eventRepository = eventRepository,
                    localFileAccess = { _ -> localFileAccess },
                )

            val result = engine.runOnce(pair)

            assertTrue("Expected Success (with conflicts), got: $result", result is SyncEngine.Result.Success)
            val success = result as SyncEngine.Result.Success
            assertEquals("No ops should be applied for a KEEP_BOTH conflict", 0, success.applied)
            assertEquals("One conflict should be recorded", 1, success.conflicts)
        }

    // -------------------------------------------------------------------------
    // Repeated-upload regression: mtime-only change must not re-upload
    // -------------------------------------------------------------------------

    /**
     * Regression test for the "uploading same file every time" bug.
     *
     * Root cause: [LocalFsEnumerator] used to clear remote metadata
     * (remoteSizeBytes/remoteMtimeMs/remoteEtag) when updating a local index entry
     * for a modified file.  If the file's content hash was unchanged (mtime-only
     * change) the diff produced no upload op, so SyncOpApplier never ran to restore
     * the remote metadata.  On the NEXT sync run the missing remoteSizeBytes caused
     * the file to be absent from syntheticRemote, making it look remote-deleted →
     * UploadNew (for LOCAL_TO_REMOTE) or DeleteLocal (for BIDIRECTIONAL).
     *
     * The fix is: [LocalFsEnumerator] must preserve remote metadata columns when
     * writing a "modified" entry so that the remote state is never implicitly
     * reset from a local filesystem event.
     */
    @Test
    fun `mtime-only change does not cause re-upload on subsequent sync runs`() =
        runTest {
            // Arrange: upload a file successfully with matching mtimes.
            // Both InMemoryLocalFileAccess.stat() and the filesystem child report 5_000L,
            // so after run 1 the index has mtimeMs = 5_000L and remoteSizeBytes is set.
            val fileContent = "stable content".toByteArray()
            localFileAccess.put("stable.txt", fileContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-stable",
                        name = "stable.txt",
                        mimeType = "text/plain",
                        size = fileContent.size.toLong(),
                        lastModifiedMs = 5_000L, // matches InMemoryLocalFileAccess.nowMs
                    ),
                ),
            )

            val pair = insertPair(direction = SyncDirection.LOCAL_TO_REMOTE)
            val engine = buildEngine()

            // Run 1: upload the file. Index stores remoteId + remoteSizeBytes.
            val firstResult = engine.runOnce(pair)
            assertTrue("First run should succeed", firstResult is SyncEngine.Result.Success)
            assertEquals("First run must upload 1 file", 1, (firstResult as SyncEngine.Result.Success).applied)

            // Simulate a mtime-only change: the OS updates the file's mtime from 5_000L
            // to 3_000L without changing its content.
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-stable",
                        name = "stable.txt",
                        mimeType = "text/plain",
                        size = fileContent.size.toLong(),
                        lastModifiedMs = 3_000L, // mtime changed but content is unchanged
                    ),
                ),
            )

            val storedToken2 = syncPairDao.getById(pair.id)!!.lastDeltaToken!!
            val pairWithToken2 = pair.copy(deltaToken = storedToken2)

            // Run 2: LocalFsEnumerator detects "modified" (3_000 ≠ 5_000).
            // Without the fix, remoteSizeBytes is wiped here, so syntheticRemote would
            // be empty on run 3 and the file would be classified as remote-deleted →
            // UploadNew, creating a second remote copy.
            // With the fix, remoteSizeBytes is preserved.
            engine.runOnce(pairWithToken2)

            // Critical assertion: there must be exactly ONE remote copy.
            // Without the fix a second UploadNew would spawn a duplicate remote file.
            val remoteFiles = fakeProvider.list("remote-root").filter { !it.isFolder }
            assertEquals(
                "There must be exactly one remote copy (no duplicates from spurious UploadNew)",
                1,
                remoteFiles.size,
            )
        }


    // -------------------------------------------------------------------------
    // Conflict policy: NEWEST_WINS (remote wins)
    // -------------------------------------------------------------------------

    @Test
    fun `runReal with NEWEST_WINS downloads remote file when remote is newer`() =
        runTest {
            // Upload a file to fakeProvider so its ID is known and download will succeed.
            val remoteContent = "remote version".toByteArray()
            val uploadedFile =
                fakeProvider.uploadNew(
                    parentId = "remote-root",
                    name = "doc.txt",
                    content = remoteContent.inputStream(),
                    size = remoteContent.size.toLong(),
                    mimeType = "text/plain",
                )

            // Custom enumerator that uses the actual uploaded file's ID so
            // SyncOpApplier.applyUpdateLocal can call provider.download(id).
            val conflictEnumerator =
                object : RemoteEnumerator {
                    override suspend fun enumerate(deltaToken: String?, rootFolderId: String): RemoteSnapshot =
                        RemoteSnapshot(
                            changes =
                                listOf(
                                    RemoteChange(
                                        relativePath = "doc.txt",
                                        type = RemoteChangeType.MODIFY,
                                        remoteId = uploadedFile.id, // real ID in fakeProvider
                                        sizeBytes = remoteContent.size.toLong(),
                                        mtimeMs = 9_000L, // remote is newer
                                        etag = uploadedFile.eTag,
                                    ),
                                ),
                            newDeltaToken = "1",
                        )
                }

            // Local file exists with an older mtime — remote should win.
            val localContent = "local old version".toByteArray()
            localFileAccess.put("doc.txt", localContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-local",
                        name = "doc.txt",
                        mimeType = "text/plain",
                        size = localContent.size.toLong(),
                        lastModifiedMs = 1_000L, // local is older than remote's 9_000L
                    ),
                ),
            )

            val pair = insertPair(conflictPolicy = ConflictPolicy.NEWEST_WINS)
            val engine =
                SyncEngine(
                    conflictRepository = conflictRepository,
                    providers = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(fakeProvider)),
                    localFsEnumerator = localFsEnumerator,
                    remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to conflictEnumerator),
                    syncPairDao = syncPairDao,
                    localIndexDao = localIndexDao,
                    eventRepository = eventRepository,
                    localFileAccess = { _ -> localFileAccess },
                )

            val result = engine.runOnce(pair)

            assertTrue("Expected Success, got: $result", result is SyncEngine.Result.Success)
            val success = result as SyncEngine.Result.Success
            assertEquals("Remote-wins op should be applied (download)", 1, success.applied)
            assertEquals(0, success.conflicts)
        }

    // -------------------------------------------------------------------------
    // Include-glob scope filtering
    // -------------------------------------------------------------------------

    @Test
    fun `scopeFiltersFor caches compiled globs per config`() =
        runTest {
            val engine = buildEngine()
            val pair = insertPair()

            val cachedOnce = engine.scopeFiltersFor(pair.copy(includeGlobs = listOf("*.kt"), excludeGlobs = listOf("build/**")))
            val cachedTwice = engine.scopeFiltersFor(pair.copy(includeGlobs = listOf("*.kt"), excludeGlobs = listOf("build/**")))
            val changedConfig = engine.scopeFiltersFor(pair.copy(includeGlobs = listOf("*.txt"), excludeGlobs = listOf("build/**")))

            assertSame(cachedOnce, cachedTwice)
            assertNotSame(cachedOnce, changedConfig)
        }

    @Test
    fun `previously-synced file outside includeGlobs does not generate DeleteRemote`() =
        runTest {
            // Seed local_index as if "report.log" was synced in a previous run:
            // remoteId + remoteSizeBytes + remoteMtimeMs are all set, so the engine
            // would normally place it in syntheticRemote and SyncDiffer would see
            // "file in remote, absent from local" → DeleteRemote.
            val pair = insertPair()
            localIndexDao.upsert(
                LocalIndexEntity(
                    pairId = pair.id,
                    relativePath = "report.log",
                    sizeBytes = 512L,
                    mtimeMs = 1_000L,
                    remoteId = "remote-log-id",
                    remoteSizeBytes = 512L,
                    remoteMtimeMs = 1_000L,
                ),
            )

            // Local FS is empty — the file no longer exists locally.
            // inMemoryChildren returns nothing by default.

            // Configure includeGlobs to only *.kt files; "report.log" is outside scope.
            val pairWithGlobs = pair.copy(includeGlobs = listOf("*.kt"))
            val engine = buildEngine()

            val result = engine.runOnce(pairWithGlobs)

            assertTrue("Expected Success, got: $result", result is SyncEngine.Result.Success)
            assertEquals(
                "No ops should be applied — out-of-scope file must not trigger DeleteRemote",
                0,
                (result as SyncEngine.Result.Success).applied,
            )
        }

    // -------------------------------------------------------------------------
    // Nested upload: local files in sub-folders are uploaded with full path
    // -------------------------------------------------------------------------

    @Test
    fun `runReal uploads nested local file and creates remote folder hierarchy`() =
        runTest {
            val fileContent = "nested content".toByteArray()
            localFileAccess.put("docs/subdir/report.txt", fileContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-docs",
                        name = "docs",
                        mimeType = "vnd.android.document/directory",
                        size = 0L,
                        lastModifiedMs = 1_000L,
                    ),
                ),
            )
            inMemoryChildren.set(
                "doc-docs",
                listOf(
                    RawDocChild(
                        docId = "doc-subdir",
                        name = "subdir",
                        mimeType = "vnd.android.document/directory",
                        size = 0L,
                        lastModifiedMs = 1_000L,
                    ),
                ),
            )
            inMemoryChildren.set(
                "doc-subdir",
                listOf(
                    RawDocChild(
                        docId = "doc-report",
                        name = "report.txt",
                        mimeType = "text/plain",
                        size = fileContent.size.toLong(),
                        lastModifiedMs = 5_000L,
                    ),
                ),
            )

            val pair = insertPair()
            val eng = buildEngine()
            val result = eng.runOnce(pair)

            assertTrue("Expected Success, got: $result", result is SyncEngine.Result.Success)
            assertEquals(
                "Nested file should be uploaded",
                1,
                (result as SyncEngine.Result.Success).applied,
            )

            // Verify remote structure: remote-root → docs → subdir → report.txt
            val rootChildren = fakeProvider.list("remote-root")
            val docsFolder = rootChildren.singleOrNull { it.isFolder && it.name == "docs" }
            assertNotNull("docs folder should exist under remote root", docsFolder)
            val docsChildren = fakeProvider.list(docsFolder!!.id)
            val subdirFolder = docsChildren.singleOrNull { it.isFolder && it.name == "subdir" }
            assertNotNull("subdir folder should exist under docs", subdirFolder)
            val subdirChildren = fakeProvider.list(subdirFolder!!.id)
            assertEquals(1, subdirChildren.size)
            assertEquals("report.txt", subdirChildren.single().name)
        }

    @Test
    fun `runReal nested upload reuses existing remote folder on second sync`() =
        runTest {
            val fileContent = "report".toByteArray()
            localFileAccess.put("docs/report.txt", fileContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-docs",
                        name = "docs",
                        mimeType = "vnd.android.document/directory",
                        size = 0L,
                        lastModifiedMs = 1_000L,
                    ),
                ),
            )
            inMemoryChildren.set(
                "doc-docs",
                listOf(
                    RawDocChild(
                        docId = "doc-report",
                        name = "report.txt",
                        mimeType = "text/plain",
                        size = fileContent.size.toLong(),
                        lastModifiedMs = 5_000L,
                    ),
                ),
            )

            val pair = insertPair()
            val eng = buildEngine()
            val firstResult = eng.runOnce(pair)
            assertTrue("First run should succeed", firstResult is SyncEngine.Result.Success)
            assertEquals(1, (firstResult as SyncEngine.Result.Success).applied)

            // Check exactly one "docs" folder under the remote root.
            val docsCount = fakeProvider.list("remote-root").count { it.isFolder && it.name == "docs" }
            assertEquals("Exactly one docs folder after first upload", 1, docsCount)

            // Second run with stored token: nothing has changed, should be a no-op.
            val storedToken = syncPairDao.getById(pair.id)!!.lastDeltaToken!!
            val pairWithToken = pair.copy(deltaToken = storedToken)
            val secondResult = eng.runOnce(pairWithToken)

            assertTrue("Second run should succeed", secondResult is SyncEngine.Result.Success)
            assertEquals("Second run should be a no-op", 0, (secondResult as SyncEngine.Result.Success).applied)
            // Still exactly one "docs" folder — no duplicate created.
            val docsCountAfter = fakeProvider.list("remote-root").count { it.isFolder && it.name == "docs" }
            assertEquals("Should not create a duplicate docs folder on second run", 1, docsCountAfter)
        }

    // -------------------------------------------------------------------------
    // Remote delete: stable remote ID lookup via pre-scan index
    // -------------------------------------------------------------------------

    @Test
    fun `runReal deletes local file when remote delete arrives via stable remote ID`() =
        runTest {
            // First run: upload a local file so it gets a remoteId in the local index.
            val fileContent = "delete me".toByteArray()
            localFileAccess.put("todelete.txt", fileContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-del",
                        name = "todelete.txt",
                        mimeType = "text/plain",
                        size = fileContent.size.toLong(),
                        lastModifiedMs = 5_000L,
                    ),
                ),
            )
            val pair = insertPair()
            val eng = buildEngine()
            val firstResult = eng.runOnce(pair)
            assertTrue("First run should succeed", firstResult is SyncEngine.Result.Success)
            assertEquals(1, (firstResult as SyncEngine.Result.Success).applied)

            // Retrieve the stable token and the remoteId assigned during upload.
            val storedToken = syncPairDao.getById(pair.id)!!.lastDeltaToken!!
            val uploadedFile = fakeProvider.list("remote-root").single { it.name == "todelete.txt" }

            // Delete the file on the remote only. The local tree is intentionally left
            // unchanged so that SyncDiffer sees "remote deleted, local unchanged" and
            // produces DeleteLocal("todelete.txt").
            fakeProvider.delete(uploadedFile.id)
            // inMemoryChildren still has "todelete.txt" — local file is still present.

            // FakeRemoteEnumerator emits DELETE with remoteId = uploadedFile.id and
            // relativePath = uploadedFile.id (item is gone from the store).
            // SyncEngine resolves "todelete.txt" from the pre-scan index via remoteId.
            val pairWithToken = pair.copy(deltaToken = storedToken)
            val secondResult = eng.runOnce(pairWithToken)

            assertTrue("Second run should succeed", secondResult is SyncEngine.Result.Success)
            assertEquals(
                "DeleteLocal op should be applied",
                1,
                (secondResult as SyncEngine.Result.Success).applied,
            )
        }

    // -------------------------------------------------------------------------
    // Remote rename/move: old path evicted from synthetic remote, new name downloaded
    // -------------------------------------------------------------------------

    @Test
    fun `runReal handles remote rename by evicting old path and downloading new name`() =
        runTest {
            // First run: upload "original.txt" from local so it is indexed with a remoteId.
            val fileContent = "rename me".toByteArray()
            localFileAccess.put("original.txt", fileContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-orig",
                        name = "original.txt",
                        mimeType = "text/plain",
                        size = fileContent.size.toLong(),
                        lastModifiedMs = 5_000L,
                    ),
                ),
            )

            val pair = insertPair()
            val eng = buildEngine()
            val firstResult = eng.runOnce(pair)
            assertTrue("First run should succeed", firstResult is SyncEngine.Result.Success)
            assertEquals(1, (firstResult as SyncEngine.Result.Success).applied)

            val storedToken = syncPairDao.getById(pair.id)!!.lastDeltaToken!!
            // Retrieve the remoteId that was assigned during upload.
            val originalRemoteFile = fakeProvider.list("remote-root").single { it.name == "original.txt" }

            // Simulate a rename on the remote: delete the old item and upload the new name.
            fakeProvider.delete(originalRemoteFile.id)
            val renamedFile =
                fakeProvider.uploadNew(
                    parentId = "remote-root",
                    name = "renamed.txt",
                    content = fileContent.inputStream(),
                    size = fileContent.size.toLong(),
                    mimeType = "text/plain",
                )

            // Emit a delta that models a rename: DELETE the old stable ID + MODIFY
            // the new file under the new name. The local tree still has "original.txt".
            val renameEnumerator =
                object : RemoteEnumerator {
                    override suspend fun enumerate(deltaToken: String?, rootFolderId: String): RemoteSnapshot =
                        RemoteSnapshot(
                            changes =
                                listOf(
                                    // DELETE carries the old stable ID so SyncEngine can
                                    // look up "original.txt" via the pre-scan index.
                                    RemoteChange(
                                        relativePath = originalRemoteFile.id,
                                        type = RemoteChangeType.DELETE,
                                        remoteId = originalRemoteFile.id,
                                    ),
                                    // MODIFY carries the new file's ID and new path.
                                    RemoteChange(
                                        relativePath = "renamed.txt",
                                        type = RemoteChangeType.MODIFY,
                                        remoteId = renamedFile.id,
                                        sizeBytes = fileContent.size.toLong(),
                                        mtimeMs = 6_000L,
                                        etag = renamedFile.eTag,
                                    ),
                                ),
                            newDeltaToken = "3",
                        )
                }

            val renameEngine =
                SyncEngine(
                    conflictRepository = conflictRepository,
                    providers = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(fakeProvider)),
                    localFsEnumerator = localFsEnumerator,
                    remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to renameEnumerator),
                    syncPairDao = syncPairDao,
                    localIndexDao = localIndexDao,
                    eventRepository = eventRepository,
                    localFileAccess = { _ -> localFileAccess },
                )
            val renameResult = renameEngine.runOnce(pair.copy(deltaToken = storedToken))

            assertTrue("Rename run should succeed", renameResult is SyncEngine.Result.Success)
            // Expect 2 ops: DeleteLocal("original.txt") + DownloadNew("renamed.txt").
            assertEquals(
                "Rename should produce DeleteLocal + DownloadNew (2 ops total)",
                2,
                (renameResult as SyncEngine.Result.Success).applied,
            )
        }

    @Test
    fun `runReal handles rename via same stable remoteId with different relativePath`() =
        runTest {
            // First run: upload "original.txt" from local so it is indexed with a known remoteId.
            val fileContent = "stable rename".toByteArray()
            localFileAccess.put("original.txt", fileContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-orig",
                        name = "original.txt",
                        mimeType = "text/plain",
                        size = fileContent.size.toLong(),
                        lastModifiedMs = 5_000L,
                    ),
                ),
            )
            val pair = insertPair()
            val eng = buildEngine()
            val firstResult = eng.runOnce(pair)
            assertTrue("First run should succeed", firstResult is SyncEngine.Result.Success)
            assertEquals(1, (firstResult as SyncEngine.Result.Success).applied)

            val storedToken = syncPairDao.getById(pair.id)!!.lastDeltaToken!!
            val originalRemoteFile = fakeProvider.list("remote-root").single { it.name == "original.txt" }

            // Simulate a provider-level rename: emit MODIFY with the SAME stable remoteId
            // but a different relativePath ("renamed.txt"). This exercises the rename-by-stable-ID
            // detection in SyncEngine — the old path is evicted from syntheticRemote and the new
            // path is inserted, causing SyncDiffer to produce DeleteLocal + DownloadNew.
            //
            // Unlike a real rename (where the file is moved on the server), for the FakeProvider
            // we keep the original file in place so download succeeds via originalRemoteFile.id.
            // The stable-ID contract is: the provider signals "this ID is now at this new path",
            // and the sync engine evicts the old path and downloads to the new one.
            val sameIdRenameEnumerator =
                object : RemoteEnumerator {
                    override suspend fun enumerate(deltaToken: String?, rootFolderId: String): RemoteSnapshot =
                        RemoteSnapshot(
                            changes =
                                listOf(
                                    // Same remoteId as the original upload, but new relativePath.
                                    // This is the canonical stable-ID rename signal.
                                    RemoteChange(
                                        relativePath = "renamed.txt",
                                        type = RemoteChangeType.MODIFY,
                                        remoteId = originalRemoteFile.id,
                                        sizeBytes = fileContent.size.toLong(),
                                        mtimeMs = 6_000L,
                                        etag = originalRemoteFile.eTag,
                                    ),
                                ),
                            newDeltaToken = "2",
                        )
                }

            val renameEngine =
                SyncEngine(
                    conflictRepository = conflictRepository,
                    providers = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(fakeProvider)),
                    localFsEnumerator = localFsEnumerator,
                    remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to sameIdRenameEnumerator),
                    syncPairDao = syncPairDao,
                    localIndexDao = localIndexDao,
                    eventRepository = eventRepository,
                    localFileAccess = { _ -> localFileAccess },
                )
            val renameResult = renameEngine.runOnce(pair.copy(deltaToken = storedToken))

            assertTrue("Rename run should succeed", renameResult is SyncEngine.Result.Success)
            // SyncDiffer sees: "original.txt" absent from syntheticRemote → DeleteLocal
            //                  "renamed.txt" present in syntheticRemote, absent locally → DownloadNew
            assertEquals(
                "Same-stable-ID rename should produce DeleteLocal + DownloadNew (2 ops)",
                2,
                (renameResult as SyncEngine.Result.Success).applied,
            )
        }

    // -------------------------------------------------------------------------
    // KEEP_BOTH resolution: both-modified and modify-delete
    // -------------------------------------------------------------------------

    /**
     * Both-modified conflict resolved with KEEP_BOTH:
     * - The local file keeps the local version at the original path.
     * - A conflict copy (with the remote version) is created locally and uploaded to remote.
     * - The remote original is overwritten with the local version.
     * - After resolution both versions are accessible on both sides.
     */
    @Test
    fun `KEEP_BOTH resolution for both-modified conflict preserves both versions locally and remotely`() =
        runTest {
            val detectedAtMs = CONFLICT_DETECTED_AT_MS
            val localContent = "local content".toByteArray()
            val remoteContent = "remote content".toByteArray()

            // Upload the remote version to fakeProvider so we have a real remoteId.
            val remoteFile =
                fakeProvider.uploadNew(
                    parentId = "remote-root",
                    name = "document.txt",
                    content = remoteContent.inputStream(),
                    size = remoteContent.size.toLong(),
                    mimeType = "text/plain",
                )

            // Local file with the local version.
            localFileAccess.put("document.txt", localContent)

            // SAF tree reports the local file so the enumerator sees it.
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-document",
                        name = "document.txt",
                        mimeType = "text/plain",
                        size = localContent.size.toLong(),
                        lastModifiedMs = 5_000L, // matches InMemoryLocalFileAccess.nowMs
                    ),
                ),
            )

            val pair = insertPair(conflictPolicy = ConflictPolicy.KEEP_BOTH)

            // Seed the local index with the pre-conflict state: local version at sizeBytes
            // and the remote file's metadata so the diff sees no new changes.
            localIndexDao.upsert(
                LocalIndexEntity(
                    pairId = pair.id,
                    relativePath = "document.txt",
                    sizeBytes = localContent.size.toLong(),
                    mtimeMs = 5_000L,
                    contentHash = null,
                    remoteId = remoteFile.id,
                    remoteSizeBytes = remoteFile.size,
                    remoteMtimeMs = remoteFile.lastModifiedMs,
                    remoteEtag = remoteFile.eTag,
                ),
            )

            // Record the KEEP_BOTH resolution chosen by the user.
            val conflictId =
                conflictRepository.insert(
                    ConflictRecord(
                        id = 0,
                        pairId = pair.id,
                        relativePath = "document.txt",
                        localLastModifiedMs = 5_000L,
                        remoteLastModifiedMs = remoteFile.lastModifiedMs ?: 0L,
                        detectedAtMs = detectedAtMs,
                    ),
                )
            conflictRepository.resolve(conflictId, ConflictRecord.RESOLUTION_KEEP_BOTH)

            val result = buildEngine().runOnce(pair)

            assertTrue("Expected Success, got: $result", result is SyncEngine.Result.Success)
            val success = result as SyncEngine.Result.Success
            // The resolved conflict counts as 1 applied op.
            assertEquals("Resolved conflict should count as 1 applied op", 1, success.applied)
            assertEquals("No new conflicts should be detected", 0, success.conflicts)

            // Local: original file still has local content.
            val localOriginal = localFileAccess.openRead("document.txt")?.readBytes()
            assertNotNull("Original local file should still exist", localOriginal)
            assertEquals(
                "Original local file should still contain local content",
                "local content",
                localOriginal!!.toString(Charsets.UTF_8),
            )

            // Local: conflict copy exists with the remote content.
            val copyPath = SyncEngine.conflictCopyPath("document.txt", detectedAtMs)
            val localCopy = localFileAccess.openRead(copyPath)?.readBytes()
            assertNotNull("Conflict copy should exist locally at $copyPath", localCopy)
            assertEquals(
                "Conflict copy should contain the remote content",
                "remote content",
                localCopy!!.toString(Charsets.UTF_8),
            )

            // Remote: original file should now contain the local version.
            val remoteOriginalContent = fakeProvider.download(remoteFile.id).readBytes()
            assertEquals(
                "Remote original should be overwritten with local content",
                "local content",
                remoteOriginalContent.toString(Charsets.UTF_8),
            )

            // Remote: conflict copy should exist as a new file.
            val remoteFiles = fakeProvider.list("remote-root").filter { !it.isFolder }
            assertTrue(
                "Remote should contain the conflict copy",
                remoteFiles.any { it.name == copyPath },
            )

            // Conflict record should be deleted after successful resolution.
            val remainingResolved = conflictRepository.getResolvedForPair(pair.id)
            assertTrue("Conflict record should be removed after resolution", remainingResolved.isEmpty())
        }

    /**
     * Modify-delete conflict resolved with KEEP_BOTH:
     * - The remote file was deleted while the local file was modified.
     * - KEEP_BOTH re-uploads the surviving local file to restore it on the remote.
     */
    @Test
    fun `KEEP_BOTH resolution for modify-delete conflict re-uploads surviving local file`() =
        runTest {
            val detectedAtMs = CONFLICT_DETECTED_AT_MS
            val localContent = "local modified content".toByteArray()

            // Simulate a file that was previously synced (has a remoteId in the index)
            // but since then the remote copy was deleted.
            val deletedRemoteFile =
                fakeProvider.uploadNew(
                    parentId = "remote-root",
                    name = "document.txt",
                    content = localContent.inputStream(),
                    size = localContent.size.toLong(),
                    mimeType = "text/plain",
                )
            // Delete the remote file to simulate the modify-delete scenario.
            fakeProvider.delete(deletedRemoteFile.id)

            localFileAccess.put("document.txt", localContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-document",
                        name = "document.txt",
                        mimeType = "text/plain",
                        size = localContent.size.toLong(),
                        lastModifiedMs = 5_000L,
                    ),
                ),
            )

            val pair = insertPair(conflictPolicy = ConflictPolicy.KEEP_BOTH)

            // Seed the index with the stale remoteId (the remote was deleted after this was set).
            localIndexDao.upsert(
                LocalIndexEntity(
                    pairId = pair.id,
                    relativePath = "document.txt",
                    sizeBytes = localContent.size.toLong(),
                    mtimeMs = 5_000L,
                    contentHash = null,
                    remoteId = deletedRemoteFile.id,
                    remoteSizeBytes = deletedRemoteFile.size,
                    remoteMtimeMs = deletedRemoteFile.lastModifiedMs,
                    remoteEtag = deletedRemoteFile.eTag,
                ),
            )

            val conflictId =
                conflictRepository.insert(
                    ConflictRecord(
                        id = 0,
                        pairId = pair.id,
                        relativePath = "document.txt",
                        localLastModifiedMs = 5_000L,
                        remoteLastModifiedMs = 0L, // remote was deleted
                        detectedAtMs = detectedAtMs,
                    ),
                )
            conflictRepository.resolve(conflictId, ConflictRecord.RESOLUTION_KEEP_BOTH)

            val result = buildEngine().runOnce(pair)

            assertTrue("Expected Success, got: $result", result is SyncEngine.Result.Success)
            val success = result as SyncEngine.Result.Success
            assertEquals("Resolved conflict should count as 1 applied op", 1, success.applied)
            assertEquals("No new conflicts should be detected", 0, success.conflicts)

            // Local file should still exist with the original content.
            val localFile = localFileAccess.openRead("document.txt")?.readBytes()
            assertNotNull("Local file should still exist", localFile)
            assertEquals(
                "Local file content should be unchanged",
                "local modified content",
                localFile!!.toString(Charsets.UTF_8),
            )

            // The local file should have been re-uploaded to remote.
            val remoteFiles = fakeProvider.list("remote-root").filter { !it.isFolder }
            assertEquals("Remote should have exactly one file (re-uploaded local)", 1, remoteFiles.size)
            assertEquals("document.txt", remoteFiles.single().name)
            val reuploadedContent = fakeProvider.download(remoteFiles.single().id).readBytes()
            assertEquals(
                "Re-uploaded remote file should contain the local content",
                "local modified content",
                reuploadedContent.toString(Charsets.UTF_8),
            )

            // Conflict record should be deleted.
            val remainingResolved = conflictRepository.getResolvedForPair(pair.id)
            assertTrue("Conflict record should be removed after resolution", remainingResolved.isEmpty())
        }

    /**
     * Two conflicts are both resolved with KEEP_LOCAL in a single sync run.
     *
     * Validates that the single shared [SyncOpApplier] instance (created once per sync run in
     * [SyncEngine.runRealImpl]) is correctly reused for each conflict resolution, as required by
     * issue #95 ("SyncOpApplier should be reused within a sync run").
     */
    @Test
    fun `multiple KEEP_LOCAL resolutions in a single sync run are all applied correctly`() =
        runTest {
            val localContent1 = "local-alpha".toByteArray()
            val localContent2 = "local-beta".toByteArray()

            // Seed two remote files that are "outdated" compared to the local versions.
            val remoteFile1 =
                fakeProvider.uploadNew(
                    parentId = "remote-root",
                    name = "alpha.txt",
                    content = "remote-alpha".toByteArray().inputStream(),
                    size = "remote-alpha".length.toLong(),
                    mimeType = "text/plain",
                )
            val remoteFile2 =
                fakeProvider.uploadNew(
                    parentId = "remote-root",
                    name = "beta.txt",
                    content = "remote-beta".toByteArray().inputStream(),
                    size = "remote-beta".length.toLong(),
                    mimeType = "text/plain",
                )

            localFileAccess.put("alpha.txt", localContent1)
            localFileAccess.put("beta.txt", localContent2)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-alpha",
                        name = "alpha.txt",
                        mimeType = "text/plain",
                        size = localContent1.size.toLong(),
                        lastModifiedMs = 5_000L,
                    ),
                    RawDocChild(
                        docId = "doc-beta",
                        name = "beta.txt",
                        mimeType = "text/plain",
                        size = localContent2.size.toLong(),
                        lastModifiedMs = 5_000L,
                    ),
                ),
            )

            val pair = insertPair(conflictPolicy = ConflictPolicy.KEEP_BOTH)

            // Seed the index for both files with their remote metadata.
            localIndexDao.upsert(
                LocalIndexEntity(
                    pairId = pair.id,
                    relativePath = "alpha.txt",
                    sizeBytes = localContent1.size.toLong(),
                    mtimeMs = 5_000L,
                    contentHash = null,
                    remoteId = remoteFile1.id,
                    remoteSizeBytes = remoteFile1.size,
                    remoteMtimeMs = remoteFile1.lastModifiedMs,
                    remoteEtag = remoteFile1.eTag,
                ),
            )
            localIndexDao.upsert(
                LocalIndexEntity(
                    pairId = pair.id,
                    relativePath = "beta.txt",
                    sizeBytes = localContent2.size.toLong(),
                    mtimeMs = 5_000L,
                    contentHash = null,
                    remoteId = remoteFile2.id,
                    remoteSizeBytes = remoteFile2.size,
                    remoteMtimeMs = remoteFile2.lastModifiedMs,
                    remoteEtag = remoteFile2.eTag,
                ),
            )

            // Insert and resolve two KEEP_LOCAL conflicts.
            val conflictId1 =
                conflictRepository.insert(
                    ConflictRecord(
                        id = 0,
                        pairId = pair.id,
                        relativePath = "alpha.txt",
                        localLastModifiedMs = 5_000L,
                        remoteLastModifiedMs = remoteFile1.lastModifiedMs ?: 0L,
                        detectedAtMs = CONFLICT_DETECTED_AT_MS,
                    ),
                )
            conflictRepository.resolve(conflictId1, ConflictRecord.RESOLUTION_KEEP_LOCAL)

            val conflictId2 =
                conflictRepository.insert(
                    ConflictRecord(
                        id = 0,
                        pairId = pair.id,
                        relativePath = "beta.txt",
                        localLastModifiedMs = 5_000L,
                        remoteLastModifiedMs = remoteFile2.lastModifiedMs ?: 0L,
                        detectedAtMs = CONFLICT_DETECTED_AT_MS,
                    ),
                )
            conflictRepository.resolve(conflictId2, ConflictRecord.RESOLUTION_KEEP_LOCAL)

            val result = buildEngine().runOnce(pair)

            assertTrue("Expected Success, got: $result", result is SyncEngine.Result.Success)
            val success = result as SyncEngine.Result.Success
            assertEquals("Both resolved conflicts should count as 2 applied ops", 2, success.applied)
            assertEquals("No new conflicts should be detected", 0, success.conflicts)

            // Remote files should now have the local content uploaded as new files.
            // (KEEP_LOCAL resolution calls UploadNew which creates a new remote file.)
            val remoteFiles = fakeProvider.list("remote-root").filter { !it.isFolder }
            val remoteAlpha = remoteFiles.filter { it.name == "alpha.txt" }
            assertTrue(
                "At least one remote file named alpha.txt should exist after KEEP_LOCAL",
                remoteAlpha.isNotEmpty(),
            )
            val alphaHasLocalContent =
                remoteAlpha.any { rf ->
                    fakeProvider.download(rf.id).readBytes().toString(Charsets.UTF_8) == "local-alpha"
                }
            assertTrue(
                "One of the remote alpha.txt files should contain local-alpha content",
                alphaHasLocalContent,
            )

            val remoteBeta = remoteFiles.filter { it.name == "beta.txt" }
            assertTrue(
                "At least one remote file named beta.txt should exist after KEEP_LOCAL",
                remoteBeta.isNotEmpty(),
            )
            val betaHasLocalContent =
                remoteBeta.any { rf ->
                    fakeProvider.download(rf.id).readBytes().toString(Charsets.UTF_8) == "local-beta"
                }
            assertTrue(
                "One of the remote beta.txt files should contain local-beta content",
                betaHasLocalContent,
            )

            // Both conflict records should be deleted after resolution.
            val remaining = conflictRepository.getResolvedForPair(pair.id)
            assertTrue("All conflict records should be removed after resolution", remaining.isEmpty())
        }

    @Test
    fun `runReal downloads nested remote file to correct local path`() =
        runTest {
            val pair = insertPair()
            val eng = buildEngine()

            // Baseline run (deltaToken = null → empty change list, empty remote).
            val baselineResult = eng.runOnce(pair)
            assertTrue("Baseline should succeed", baselineResult is SyncEngine.Result.Success)
            assertEquals(0, (baselineResult as SyncEngine.Result.Success).applied)
            val storedToken = syncPairDao.getById(pair.id)!!.lastDeltaToken!!

            // Create the nested remote file AFTER the baseline so FakeRemoteEnumerator
            // includes it in the next delta (changes since storedToken).
            val docsFolder = fakeProvider.createFolder("remote-root", "docs")
            val subdirFolder = fakeProvider.createFolder(docsFolder.id, "subdir")
            val remoteContent = "nested remote content".toByteArray()
            val remoteFile =
                fakeProvider.uploadNew(
                    parentId = subdirFolder.id,
                    name = "notes.txt",
                    content = remoteContent.inputStream(),
                    size = remoteContent.size.toLong(),
                    mimeType = "text/plain",
                )

            // Delta run: FakeRemoteEnumerator emits MODIFY changes for docs, subdir, notes.txt.
            // FakeCloudProvider.resolvePath resolves "docs/subdir/notes.txt" for notes.txt.
            // Local tree is empty — SyncDiffer produces DownloadNew("docs/subdir/notes.txt").
            val deltaResult = eng.runOnce(pair.copy(deltaToken = storedToken))

            assertTrue("Delta run should succeed", deltaResult is SyncEngine.Result.Success)
            assertTrue(
                "At least one op should be applied (nested download)",
                (deltaResult as SyncEngine.Result.Success).applied >= 1,
            )

            // The DownloadNew op must write to the correct nested local path.
            val localIndex = localIndexDao.getForPair(pair.id)
            val indexEntry = localIndex.find { it.relativePath == "docs/subdir/notes.txt" }
            assertNotNull("Local index should contain 'docs/subdir/notes.txt'", indexEntry)
            assertEquals(remoteFile.id, indexEntry!!.remoteId)
        }

    // -------------------------------------------------------------------------
    // excludeSubfolders
    // -------------------------------------------------------------------------

    @Test
    fun `runReal with excludeSubfolders=true uploads only root-level local files`() =
        runTest {
            // Two files: one at root, one inside a subdirectory.
            val rootContent = "root file".toByteArray()
            val subContent = "sub file".toByteArray()
            localFileAccess.put("root.txt", rootContent)
            localFileAccess.put("sub/child.txt", subContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-root",
                        name = "root.txt",
                        mimeType = "text/plain",
                        size = rootContent.size.toLong(),
                        lastModifiedMs = 1_000L,
                    ),
                    RawDocChild(
                        docId = "doc-sub",
                        name = "sub",
                        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                        size = 0L,
                        lastModifiedMs = 0L,
                    ),
                ),
            )
            inMemoryChildren.set(
                "doc-sub",
                listOf(
                    RawDocChild(
                        docId = "doc-child",
                        name = "child.txt",
                        mimeType = "text/plain",
                        size = subContent.size.toLong(),
                        lastModifiedMs = 2_000L,
                    ),
                ),
            )

            val pair = insertPair().copy(excludeSubfolders = true)
            val engine = buildEngine()

            val result = engine.runOnce(pair)

            assertTrue("Expected Success, got: $result", result is SyncEngine.Result.Success)
            assertEquals(
                "Only the root-level file should be uploaded",
                1,
                (result as SyncEngine.Result.Success).applied,
            )

            // Only root.txt should be in the local index.
            val index = localIndexDao.getForPair(pair.id)
            assertEquals(1, index.size)
            assertEquals("root.txt", index.single().relativePath)
        }

    @Test
    fun `runReal with excludeSubfolders=true ignores remote files in subdirectories`() =
        runTest {
            // Remote has a nested file; local is empty.
            val docsFolder = fakeProvider.createFolder("remote-root", "docs")
            val remoteContent = "remote doc".toByteArray()
            fakeProvider.uploadNew(
                parentId = docsFolder.id,
                name = "report.txt",
                content = remoteContent.inputStream(),
                size = remoteContent.size.toLong(),
                mimeType = "text/plain",
            )

            val pair = insertPair().copy(excludeSubfolders = true)
            val engine = buildEngine()

            val result = engine.runOnce(pair)

            assertTrue("Expected Success, got: $result", result is SyncEngine.Result.Success)
            // The nested remote file is out of scope when excludeSubfolders=true; nothing to apply.
            assertEquals(
                "Nested remote file should be excluded from scope",
                0,
                (result as SyncEngine.Result.Success).applied,
            )
        }

    @Test
    fun `runReal with excludeSubfolders=true bidirectional syncs only root-level files`() =
        runTest {
            // Root-level local file + nested local file; also a root-level remote file.
            val rootLocalContent = "root local".toByteArray()
            localFileAccess.put("local-root.txt", rootLocalContent)
            inMemoryChildren.set(
                "root",
                listOf(
                    RawDocChild(
                        docId = "doc-local-root",
                        name = "local-root.txt",
                        mimeType = "text/plain",
                        size = rootLocalContent.size.toLong(),
                        lastModifiedMs = 1_000L,
                    ),
                    RawDocChild(
                        docId = "doc-nested-dir",
                        name = "nested",
                        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                        size = 0L,
                        lastModifiedMs = 0L,
                    ),
                ),
            )
            val nestedContent = "nested local".toByteArray()
            localFileAccess.put("nested/file.txt", nestedContent)
            inMemoryChildren.set(
                "doc-nested-dir",
                listOf(
                    RawDocChild(
                        docId = "doc-nested-file",
                        name = "file.txt",
                        mimeType = "text/plain",
                        size = nestedContent.size.toLong(),
                        lastModifiedMs = 2_000L,
                    ),
                ),
            )

            val pair = insertPair(direction = SyncDirection.BIDIRECTIONAL).copy(excludeSubfolders = true)
            val engine = buildEngine()

            // Establish a remote baseline (uploads root-level local file).
            val firstResult = engine.runOnce(pair)
            assertTrue("First run should succeed", firstResult is SyncEngine.Result.Success)

            // Only root-level file should have been synced.
            val index = localIndexDao.getForPair(pair.id)
            val paths = index.map { it.relativePath }.toSet()
            assertTrue("root-level file must be in index", "local-root.txt" in paths)
            assertTrue("nested file must NOT be in index", "nested/file.txt" !in paths)
        }

    // -------------------------------------------------------------------------
    // excludeEmptyFolders
    // -------------------------------------------------------------------------

    @Test
    fun `runReal with excludeEmptyFolders=true filters empty remote folders from delta`() =
        runTest {
            // Establish baseline (empty local + remote).
            val pair = insertPair().copy(excludeEmptyFolders = true)
            val engine = buildEngine()

            val baselineResult = engine.runOnce(pair)
            assertTrue("Baseline should succeed", baselineResult is SyncEngine.Result.Success)
            val storedToken = syncPairDao.getById(pair.id)!!.lastDeltaToken!!

            // Create an empty folder on the remote — this appears in the delta
            // as a MODIFY entry with isFolder = true and size = null.
            fakeProvider.createFolder("remote-root", "empty-dir")

            // Delta run: the empty folder should be filtered out.
            val deltaResult = engine.runOnce(pair.copy(deltaToken = storedToken))

            assertTrue("Delta run should succeed", deltaResult is SyncEngine.Result.Success)
            assertEquals(
                "Empty folder should not produce any applied ops",
                0,
                (deltaResult as SyncEngine.Result.Success).applied,
            )
        }

    @Test
    fun `runReal with excludeEmptyFolders=false does not filter remote folders`() =
        runTest {
            // With excludeEmptyFolders=false (default), folder entries pass through
            // the delta pipeline.  They are still filtered by SyncEngine's implicit
            // size/mtime null check, so they produce zero applied ops — but the key
            // difference is that the filter logic in the SyncEngine does NOT remove
            // them before processing begins.
            val pair = insertPair().copy(excludeEmptyFolders = false)
            val engine = buildEngine()

            val baselineResult = engine.runOnce(pair)
            assertTrue("Baseline should succeed", baselineResult is SyncEngine.Result.Success)
            val storedToken = syncPairDao.getById(pair.id)!!.lastDeltaToken!!

            // Create an empty folder on the remote.
            fakeProvider.createFolder("remote-root", "empty-dir")

            val deltaResult = engine.runOnce(pair.copy(deltaToken = storedToken))

            assertTrue("Delta run should succeed", deltaResult is SyncEngine.Result.Success)
            // Still 0 applied because folders have size=null, but the test
            // validates the excludeEmptyFolders=false path doesn't break.
            assertEquals(0, (deltaResult as SyncEngine.Result.Success).applied)
        }

    @Test
    fun `runReal with excludeEmptyFolders=true still processes files inside folders`() =
        runTest {
            // excludeEmptyFolders should only filter out empty (folder-type) entries,
            // not files that happen to be inside folders.
            val pair = insertPair().copy(excludeEmptyFolders = true)
            val engine = buildEngine()

            val baselineResult = engine.runOnce(pair)
            assertTrue("Baseline should succeed", baselineResult is SyncEngine.Result.Success)
            val storedToken = syncPairDao.getById(pair.id)!!.lastDeltaToken!!

            // Create a folder with a file inside it.
            val folder = fakeProvider.createFolder("remote-root", "docs")
            val content = "report content".toByteArray()
            fakeProvider.uploadNew(
                parentId = folder.id,
                name = "report.txt",
                content = content.inputStream(),
                size = content.size.toLong(),
                mimeType = "text/plain",
            )

            val deltaResult = engine.runOnce(pair.copy(deltaToken = storedToken))

            assertTrue("Delta run should succeed", deltaResult is SyncEngine.Result.Success)
            // The file inside the folder should still be processed.
            assertTrue(
                "At least one file op should be applied",
                (deltaResult as SyncEngine.Result.Success).applied >= 1,
            )
        }

    @Test
    fun `runReal excludeEmptyFolders filters folder entries but keeps DELETE entries`() =
        runTest {
            // Even with excludeEmptyFolders=true, DELETE entries for folders should
            // not be filtered — they are harmless (removing an absent key from
            // syntheticRemote is a no-op) and must be kept for correctness.
            val pair = insertPair().copy(excludeEmptyFolders = true)
            val engine = buildEngine()

            val baselineResult = engine.runOnce(pair)
            assertTrue("Baseline should succeed", baselineResult is SyncEngine.Result.Success)
            val storedToken = syncPairDao.getById(pair.id)!!.lastDeltaToken!!

            // Create and then delete a folder — produces two change entries:
            // a MODIFY (folder creation) and a DELETE.
            val folder = fakeProvider.createFolder("remote-root", "temp-dir")
            fakeProvider.delete(folder.id)

            val deltaResult = engine.runOnce(pair.copy(deltaToken = storedToken))

            assertTrue("Delta run should succeed", deltaResult is SyncEngine.Result.Success)
            assertEquals(0, (deltaResult as SyncEngine.Result.Success).applied)
        }

    @Test
    fun `FakeRemoteEnumerator sets isFolder=true for folder entries`() =
        runTest {
            // Verify that FakeRemoteEnumerator correctly populates isFolder on
            // RemoteChange entries for folders.
            fakeProvider.createFolder("remote-root", "test-folder")

            val snapshot = fakeRemoteEnumerator.enumerate(deltaToken = "0", rootFolderId = "remote-root")

            val folderChange = snapshot.changes.find { it.relativePath == "test-folder" }
            assertNotNull("Folder change should be in the snapshot", folderChange)
            assertTrue("Folder entry must have isFolder=true", folderChange!!.isFolder)
        }

    @Test
    fun `FakeRemoteEnumerator sets isFolder=false for file entries`() =
        runTest {
            // Verify that FakeRemoteEnumerator correctly sets isFolder=false for files.
            val content = "file content".toByteArray()
            fakeProvider.uploadNew(
                parentId = "remote-root",
                name = "test-file.txt",
                content = content.inputStream(),
                size = content.size.toLong(),
                mimeType = "text/plain",
            )

            val snapshot = fakeRemoteEnumerator.enumerate(deltaToken = "0", rootFolderId = "remote-root")

            val fileChange = snapshot.changes.find { it.relativePath == "test-file.txt" }
            assertNotNull("File change should be in the snapshot", fileChange)
            assertFalse("File entry must have isFolder=false", fileChange!!.isFolder)
        }

    companion object {
        /**
         * Fixed epoch-ms timestamp used for keep-both conflict detection in tests.
         * Corresponds to 2023-11-14T22:13:20 UTC, producing a conflict-copy date label of "2023-11-14".
         */
        private const val CONFLICT_DETECTED_AT_MS = 1_700_000_000_000L
    }
}
