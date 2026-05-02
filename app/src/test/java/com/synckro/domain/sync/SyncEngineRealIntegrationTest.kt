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
import java.io.ByteArrayInputStream
import java.io.InputStream
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

        fun put(path: String, bytes: ByteArray) { files[path] = bytes }

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
        db = Room.inMemoryDatabaseBuilder(context, SynckroDatabase::class.java)
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

        localFsEnumerator = LocalFsEnumerator(
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
        val entity = SyncPairEntity(
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
    private fun buildEngine(): SyncEngine = SyncEngine(
        conflictRepository = conflictRepository,
        providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
        localFsEnumerator = localFsEnumerator,
        remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to fakeRemoteEnumerator),
        syncPairDao = syncPairDao,
        localIndexDao = localIndexDao,
        eventRepository = eventRepository,
        localFileAccess = localFileAccess,
    )

    // -------------------------------------------------------------------------
    // Smoke tests
    // -------------------------------------------------------------------------

    @Test
    fun `runReal with empty local and empty remote returns Success`() = runTest {
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
    fun `runReal with empty local and empty remote returns zero applied and conflicts`() = runTest {
        val pair = insertPair()
        val engine = buildEngine()

        val result = engine.runOnce(pair) as SyncEngine.Result.Success

        assertEquals(0, result.applied)
        assertEquals(0, result.conflicts)
    }

    @Test
    fun `runReal persists delta token after successful run`() = runTest {
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
    fun `runReal persists lastFullScanAtMs after successful run`() = runTest {
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
    fun `runReal uploads new local file on first sync`() = runTest {
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
    fun `runReal second run with unchanged files is a no-op`() = runTest {
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
    fun `runReal returns Terminal when RemoteEnumerator is not registered`() = runTest {
        val pair = insertPair(providerType = CloudProviderType.GOOGLE_DRIVE)
        // Engine has no GOOGLE_DRIVE remote enumerator
        val engine = SyncEngine(
            conflictRepository = conflictRepository,
            providers = mapOf(CloudProviderType.GOOGLE_DRIVE to fakeProvider),
            localFsEnumerator = localFsEnumerator,
            remoteEnumerators = emptyMap(), // no GOOGLE_DRIVE enumerator
            syncPairDao = syncPairDao,
            localIndexDao = localIndexDao,
            eventRepository = eventRepository,
            localFileAccess = localFileAccess,
        )

        val result = engine.runOnce(pair)

        assertTrue(
            "Should return Terminal when RemoteEnumerator is missing, got: $result",
            result is SyncEngine.Result.Terminal,
        )
    }

    @Test
    fun `runReal does not persist token on CancellationException`() = runTest {
        val pair = insertPair()

        // A broken RemoteEnumerator that throws CancellationException
        val cancellingEnumerator = object : RemoteEnumerator {
            override suspend fun enumerate(deltaToken: String?): RemoteSnapshot {
                throw kotlinx.coroutines.CancellationException("test cancellation")
            }
        }

        val engine = SyncEngine(
            conflictRepository = conflictRepository,
            providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
            localFsEnumerator = localFsEnumerator,
            remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to cancellingEnumerator),
            syncPairDao = syncPairDao,
            localIndexDao = localIndexDao,
            eventRepository = eventRepository,
            localFileAccess = localFileAccess,
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
    fun `runReal downloads new remote file on first sync`() = runTest {
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
    fun `runReal returns Retriable when RemoteEnumerator throws AuthenticationFailed`() = runTest {
        val pair = insertPair()

        // AuthenticationFailed indicates a transient failure during token refresh
        // (e.g., a network blip while contacting the token endpoint). CloudExceptionMapper
        // maps this to Retriable so WorkManager can retry with backoff and succeed once
        // the transient issue resolves — unlike AuthenticationRequired, which needs
        // interactive user action and is therefore mapped to Terminal.
        val failingEnumerator = object : RemoteEnumerator {
            override suspend fun enumerate(deltaToken: String?): RemoteSnapshot {
                throw CloudProviderException.AuthenticationFailed(
                    "Token refresh failed (transient network error)",
                )
            }
        }

        val engine = SyncEngine(
            conflictRepository = conflictRepository,
            providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
            localFsEnumerator = localFsEnumerator,
            remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to failingEnumerator),
            syncPairDao = syncPairDao,
            localIndexDao = localIndexDao,
            eventRepository = eventRepository,
            localFileAccess = localFileAccess,
        )

        val result = engine.runOnce(pair)

        assertTrue(
            "Expected Retriable when AuthenticationFailed, got: $result",
            result is SyncEngine.Result.Retriable,
        )
    }

    @Test
    fun `runReal returns Terminal when RemoteEnumerator throws AuthenticationRequired`() = runTest {
        val pair = insertPair()

        val failingEnumerator = object : RemoteEnumerator {
            override suspend fun enumerate(deltaToken: String?): RemoteSnapshot {
                throw CloudProviderException.AuthenticationRequired(
                    "Access token expired — interactive sign-in required",
                )
            }
        }

        val engine = SyncEngine(
            conflictRepository = conflictRepository,
            providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
            localFsEnumerator = localFsEnumerator,
            remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to failingEnumerator),
            syncPairDao = syncPairDao,
            localIndexDao = localIndexDao,
            eventRepository = eventRepository,
            localFileAccess = localFileAccess,
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
    fun `runReal returns Terminal when RemoteEnumerator throws NotConfigured`() = runTest {
        val pair = insertPair()

        val failingEnumerator = object : RemoteEnumerator {
            override suspend fun enumerate(deltaToken: String?): RemoteSnapshot {
                throw CloudProviderException.NotConfigured("Client ID not configured")
            }
        }

        val engine = SyncEngine(
            conflictRepository = conflictRepository,
            providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
            localFsEnumerator = localFsEnumerator,
            remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to failingEnumerator),
            syncPairDao = syncPairDao,
            localIndexDao = localIndexDao,
            eventRepository = eventRepository,
            localFileAccess = localFileAccess,
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
    fun `runReal returns PartialFailure when one upload fails and another succeeds`() = runTest {
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
    fun `runReal with KEEP_BOTH policy records conflict for concurrent modifications`() = runTest {
        // Arrange: a custom RemoteEnumerator that advertises "conflict.txt" as a remote change
        // with different size/mtime from the local file, so SyncDiffer emits a Conflict op.
        val conflictEnumerator = object : RemoteEnumerator {
            override suspend fun enumerate(deltaToken: String?): RemoteSnapshot = RemoteSnapshot(
                changes = listOf(
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
        val engine = SyncEngine(
            conflictRepository = conflictRepository,
            providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
            localFsEnumerator = localFsEnumerator,
            remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to conflictEnumerator),
            syncPairDao = syncPairDao,
            localIndexDao = localIndexDao,
            eventRepository = eventRepository,
            localFileAccess = localFileAccess,
        )

        val result = engine.runOnce(pair)

        assertTrue("Expected Success (with conflicts), got: $result", result is SyncEngine.Result.Success)
        val success = result as SyncEngine.Result.Success
        assertEquals("No ops should be applied for a KEEP_BOTH conflict", 0, success.applied)
        assertEquals("One conflict should be recorded", 1, success.conflicts)
    }

    // -------------------------------------------------------------------------
    // Conflict policy: NEWEST_WINS (remote wins)
    // -------------------------------------------------------------------------

    @Test
    fun `runReal with NEWEST_WINS downloads remote file when remote is newer`() = runTest {
        // Upload a file to fakeProvider so its ID is known and download will succeed.
        val remoteContent = "remote version".toByteArray()
        val uploadedFile = fakeProvider.uploadNew(
            parentId = "remote-root",
            name = "doc.txt",
            content = remoteContent.inputStream(),
            size = remoteContent.size.toLong(),
            mimeType = "text/plain",
        )

        // Custom enumerator that uses the actual uploaded file's ID so
        // SyncOpApplier.applyUpdateLocal can call provider.download(id).
        val conflictEnumerator = object : RemoteEnumerator {
            override suspend fun enumerate(deltaToken: String?): RemoteSnapshot = RemoteSnapshot(
                changes = listOf(
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
        val engine = SyncEngine(
            conflictRepository = conflictRepository,
            providers = mapOf(CloudProviderType.ONEDRIVE to fakeProvider),
            localFsEnumerator = localFsEnumerator,
            remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to conflictEnumerator),
            syncPairDao = syncPairDao,
            localIndexDao = localIndexDao,
            eventRepository = eventRepository,
            localFileAccess = localFileAccess,
        )

        val result = engine.runOnce(pair)

        assertTrue("Expected Success, got: $result", result is SyncEngine.Result.Success)
        val success = result as SyncEngine.Result.Success
        assertEquals("Remote-wins op should be applied (download)", 1, success.applied)
        assertEquals(0, success.conflicts)
    }
}
