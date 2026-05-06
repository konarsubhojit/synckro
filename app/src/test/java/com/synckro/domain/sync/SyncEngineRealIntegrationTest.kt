package com.synckro.domain.sync

import android.content.Context
import android.net.Uri
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
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
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
import org.junit.Assert.assertNotNull
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
    ): SyncPair {
        val entity =
            SyncPairEntity(
                displayName = "Smoke Test Pair",
                localTreeUri = treeUri.toString(),
                provider = providerType,
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
            remoteFolderId = entity.remoteFolderId,
            direction = entity.direction,
            conflictPolicy = entity.conflictPolicy,
        )
    }

    /** Builds a [SyncEngine] wired with all the in-memory fakes. */
    private fun buildEngine(): SyncEngine =
        SyncEngine(
            conflictRepository = conflictRepository,
            providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
            localFsEnumerator = localFsEnumerator,
            remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to fakeRemoteEnumerator),
            syncPairDao = syncPairDao,
            localIndexDao = localIndexDao,
            eventRepository = eventRepository,
            localFileAccess = { _ -> localFileAccess },
        )

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
                    providers = mapOf(CloudProviderType.GOOGLE_DRIVE to fakeProvider),
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
                    providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
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
    // Download from remote
    // -------------------------------------------------------------------------

    @Test
    fun `runReal downloads new remote file on first sync`() =
        runTest {
            // Seed a remote file via fakeProvider, then sync with a deltaToken > 0
            // so the change appears in the enumerated delta.
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

            // Establish baseline (deltaToken = null → empty changes, newDeltaToken = "1")
            val baselineResult = engine.runOnce(pair)
            assertTrue("Baseline run should succeed", baselineResult is SyncEngine.Result.Success)
            assertEquals(0, (baselineResult as SyncEngine.Result.Success).applied)

            // Second run: upload another file so it appears in the delta
            val deltaContent = "delta file".toByteArray()
            fakeProvider.uploadNew(
                parentId = "remote-root",
                name = "delta.txt",
                content = deltaContent.inputStream(),
                size = deltaContent.size.toLong(),
                mimeType = "text/plain",
            )

            val storedToken = syncPairDao.getById(pair.id)!!.lastDeltaToken!!
            val pairWithToken = pair.copy(deltaToken = storedToken)
            val deltaResult = engine.runOnce(pairWithToken)

            assertTrue("Delta run should succeed", deltaResult is SyncEngine.Result.Success)
            assertEquals(
                "One new remote file should be downloaded",
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
                    providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
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
                    providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
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
                    providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
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
                    providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
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
                    providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
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
                    providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
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
                    providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
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
    // Nested download: remote files with hierarchical paths are downloaded
    // to the correct nested local path
    // -------------------------------------------------------------------------

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
}
