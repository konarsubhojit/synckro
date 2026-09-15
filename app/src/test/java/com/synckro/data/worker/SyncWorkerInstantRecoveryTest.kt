package com.synckro.data.worker

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.common.util.concurrent.Futures
import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.dao.PairRunLeaseDao
import com.synckro.data.local.dao.PendingUploadDao
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.PendingUploadEntity
import com.synckro.data.local.entity.PendingUploadState
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.local.fs.LocalFileEntry
import com.synckro.data.local.fs.LocalFsEnumerator
import com.synckro.data.local.fs.TargetedLocalFileResolution
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.data.scanner.DocumentChildrenQuery
import com.synckro.data.scanner.RawDocChild
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.provider.CloudProvider
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.sync.LocalFileAccess
import com.synckro.domain.sync.LocalFileStat
import com.synckro.domain.sync.RemoteEnumerator
import com.synckro.domain.sync.RemoteSnapshot
import com.synckro.domain.sync.SyncEngine
import com.synckro.providers.fake.FakeCloudProvider
import com.synckro.util.notification.SyncStatusNotifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID

/**
 * Integration tests for the instant-sync dispatch path of [SyncWorker] running
 * against the **real** durable queue, run-lease, and local-index DAOs plus a real
 * [SyncEngine] backed by [FakeCloudProvider].
 *
 * Unlike [SyncWorkerInstantTest] (which mocks the queue and the engine to assert
 * the worker's decisions) these tests exercise the durability contract end to end:
 * a run that dies mid-dispatch, a stale claim recovered by the next run, and the
 * exactly-once completion of a candidate that is only ever uploaded once.
 *
 * The engine is deliberately wired with *recording* enumerators so the tests can
 * prove that a targeted run never walks the SAF tree nor calls the remote delta
 * endpoint.
 *
 * These tests use `runBlocking` rather than `runTest`: the worker runs the lease
 * heartbeat and the foreground-promotion timer as unbounded `delay` loops, which
 * never settle on a virtual-time scheduler while the real engine performs actual
 * database work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerInstantRecoveryTest {
    /** Records SAF child queries so a test can assert the tree was never walked. */
    private class RecordingChildrenQuery : DocumentChildrenQuery {
        var invocations = 0
            private set

        override fun invoke(
            resolver: ContentResolver,
            treeUri: Uri,
            parentDocId: String,
        ): List<RawDocChild> {
            invocations++
            return emptyList()
        }
    }

    /** Records delta calls so a test can assert remote enumeration never ran. */
    private class RecordingRemoteEnumerator : RemoteEnumerator {
        var invocations = 0
            private set

        override suspend fun enumerate(
            deltaToken: String?,
            rootFolderId: String,
        ): RemoteSnapshot {
            invocations++
            return RemoteSnapshot(changes = emptyList(), newDeltaToken = "token-next")
        }
    }

    /** Pure in-memory [LocalFileAccess] backed by a [MutableMap]. */
    private class InMemoryLocalFileAccess(
        private val nowMs: Long = 5_000L,
    ) : LocalFileAccess {
        private val files = mutableMapOf<String, ByteArray>()

        fun put(
            path: String,
            bytes: ByteArray,
        ) {
            files[path] = bytes
        }

        fun bytesOf(path: String): ByteArray? = files[path]

        override fun openRead(path: String): InputStream? = files[path]?.let { ByteArrayInputStream(it) }

        override fun write(
            path: String,
            content: InputStream,
            mimeType: String?,
        ): LocalFileStat {
            val bytes = content.use { it.readBytes() }
            files[path] = bytes
            return LocalFileStat(sizeBytes = bytes.size.toLong(), mtimeMs = nowMs, mimeType = mimeType)
        }

        override fun delete(path: String): Boolean = files.remove(path) != null

        override fun stat(path: String): LocalFileStat? =
            files[path]?.let { LocalFileStat(sizeBytes = it.size.toLong(), mtimeMs = nowMs) }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: SynckroDatabase
    private lateinit var syncPairDao: SyncPairDao
    private lateinit var pendingUploadDao: PendingUploadDao
    private lateinit var pairRunLeaseDao: PairRunLeaseDao
    private lateinit var localIndexDao: LocalIndexDao

    private lateinit var fakeProvider: FakeCloudProvider
    private lateinit var localFileAccess: InMemoryLocalFileAccess
    private lateinit var childrenQuery: RecordingChildrenQuery
    private lateinit var remoteEnumerator: RecordingRemoteEnumerator
    private lateinit var engine: SyncEngine

    private val instantCandidateResolver = mockk<InstantCandidateResolver>()
    private val settingsRepository = mockk<SettingsRepository>()

    @Before
    fun setUp() {
        runCatching { WorkManager.getInstance(context) }
            .onFailure {
                WorkManager.initialize(
                    context,
                    Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build(),
                )
            }
        db =
            Room
                .inMemoryDatabaseBuilder(context, SynckroDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        syncPairDao = db.syncPairDao()
        pendingUploadDao = db.pendingUploadDao()
        pairRunLeaseDao = db.pairRunLeaseDao()
        localIndexDao = db.localIndexDao()

        fakeProvider = FakeCloudProvider()
        localFileAccess = InMemoryLocalFileAccess()
        childrenQuery = RecordingChildrenQuery()
        remoteEnumerator = RecordingRemoteEnumerator()
        engine =
            SyncEngine(
                conflictRepository = ConflictRepository(db.conflictRecordDao()),
                providers = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(fakeProvider)),
                localFsEnumerator =
                    LocalFsEnumerator(
                        resolver = context.contentResolver,
                        localIndexDao = localIndexDao,
                        childrenQuery = childrenQuery,
                        fsAccess = { _, _ -> null },
                    ),
                remoteEnumerators = mapOf(CloudProviderType.ONEDRIVE to remoteEnumerator),
                syncPairDao = syncPairDao,
                localIndexDao = localIndexDao,
                eventRepository = SyncEventRepository(db.syncEventDao()),
                localFileAccess = { _ -> localFileAccess },
            )

        every { settingsRepository.globalAutoSyncEnabled } returns flowOf(true)
        every { settingsRepository.globalInstantSyncEnabled } returns flowOf(true)
        every { settingsRepository.maxConcurrentTransfers } returns flowOf(1)
        coEvery { instantCandidateResolver.resolve(any(), any()) } answers {
            val upload = secondArg<PendingUploadEntity>()
            val bytes = localFileAccess.bytesOf(upload.relativePath)
            if (bytes == null) {
                TargetedLocalFileResolution.Missing
            } else {
                TargetedLocalFileResolution.Resolved(
                    entry =
                        LocalFileEntry(
                            relativePath = upload.relativePath,
                            sizeBytes = bytes.size.toLong(),
                            mtimeMs = upload.observedMtimeMs,
                        ),
                    documentId = "doc-${upload.relativePath}",
                    openReadStream = { localFileAccess.openRead(upload.relativePath) },
                )
            }
        }
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
    }

    @Test
    fun `stale claim left by a dead run is recovered and uploaded`() =
        runBlocking {
            val pairId = insertPair()
            localFileAccess.put("notes.txt", "hello".toByteArray())
            insertStaleClaim(pairId, "notes.txt")
            takeStaleLease(pairId)

            val result = worker(pairId).doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            assertEquals(listOf("notes.txt"), fakeProvider.list(REMOTE_FOLDER_ID).map { it.name })
            assertTrue("Recovered candidate must leave the queue", pendingUploadDao.getForPair(pairId).isEmpty())
            assertNull("The run must release its lease", pairRunLeaseDao.get(pairId))
        }

    @Test
    fun `recovered candidate completes exactly once across repeated runs`() =
        runBlocking {
            val pairId = insertPair()
            localFileAccess.put("notes.txt", "hello".toByteArray())
            insertStaleClaim(pairId, "notes.txt")

            val first = worker(pairId).doWork()
            val second = worker(pairId).doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, first.javaClass)
            assertEquals(ListenableWorker.Result.success().javaClass, second.javaClass)
            assertEquals(
                "The candidate must be uploaded exactly once",
                1,
                fakeProvider.list(REMOTE_FOLDER_ID).size,
            )
            assertEquals(
                listOf("notes.txt"),
                localIndexDao.getForPair(pairId).map { it.relativePath },
            )
            assertTrue(pendingUploadDao.getForPair(pairId).isEmpty())
        }

    @Test
    fun `claim held by a live run is not stolen`() =
        runBlocking {
            val pairId = insertPair()
            localFileAccess.put("busy.txt", "hello".toByteArray())
            insertClaim(pairId, "busy.txt", claimToken = "live-run", claimedAtMs = System.currentTimeMillis())

            val result = worker(pairId).doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            assertTrue("No upload may happen for a foreign claim", fakeProvider.list(REMOTE_FOLDER_ID).isEmpty())
            val rows = pendingUploadDao.getForPair(pairId)
            assertEquals(1, rows.size)
            assertEquals(PendingUploadState.CLAIMED, rows.single().state)
            assertEquals("live-run", rows.single().claimToken)
        }

    @Test
    fun `instant run defers while another live run owns the pair`() =
        runBlocking {
            val pairId = insertPair()
            localFileAccess.put("queued.txt", "hello".toByteArray())
            insertPending(pairId, "queued.txt")
            pairRunLeaseDao.acquire(
                pairId = pairId,
                ownerToken = "other-run",
                ownerKind = SyncWorker.RUN_KIND_PERIODIC,
                nowMs = System.currentTimeMillis(),
                staleAfterMs = SyncWorker.LEASE_STALE_AFTER_MS,
            )

            val result = worker(pairId).doWork()

            assertEquals(ListenableWorker.Result.retry().javaClass, result.javaClass)
            assertTrue(fakeProvider.list(REMOTE_FOLDER_ID).isEmpty())
            val rows = pendingUploadDao.getForPair(pairId)
            assertEquals(PendingUploadState.PENDING, rows.single().state)
            assertEquals("other-run", pairRunLeaseDao.get(pairId)?.ownerToken)
        }

    @Test
    fun `targeted run performs no local or remote enumeration`() =
        runBlocking {
            val pairId = insertPair()
            localFileAccess.put("notes.txt", "hello".toByteArray())
            insertPending(pairId, "notes.txt")

            worker(pairId).doWork()

            assertEquals(listOf("notes.txt"), fakeProvider.list(REMOTE_FOLDER_ID).map { it.name })
            assertEquals("Targeted run must not walk the SAF tree", 0, childrenQuery.invocations)
            assertEquals("Targeted run must not enumerate the remote", 0, remoteEnumerator.invocations)
        }

    @Test
    fun `targeted run leaves the pair delta checkpoint untouched`() =
        runBlocking {
            val pairId = insertPair()
            localFileAccess.put("notes.txt", "hello".toByteArray())
            insertPending(pairId, "notes.txt")

            worker(pairId).doWork()

            val stored = pendingCheckpoint(pairId)
            assertEquals("token-42", stored.first)
            assertEquals(1_000L, stored.second)
        }

    private suspend fun pendingCheckpoint(pairId: Long): Pair<String?, Long?> =
        syncPairDao.getById(pairId)!!.let { it.lastDeltaToken to it.lastFullScanAtMs }

    private suspend fun insertPair(): Long =
        syncPairDao.insert(
            SyncPairEntity(
                displayName = "Instant recovery pair",
                localTreeUri = "content://com.example.test/tree/root",
                provider = CloudProviderType.ONEDRIVE,
                accountId = "test-account",
                remoteFolderId = REMOTE_FOLDER_ID,
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
                includeGlobs = "",
                excludeGlobs = "",
                wifiOnly = false,
                requiresCharging = false,
                instantSyncEnabled = true,
                lastDeltaToken = "token-42",
                lastFullScanAtMs = 1_000L,
            ),
        )

    private suspend fun insertPending(
        pairId: Long,
        path: String,
    ) = pendingUploadDao.upsert(pendingRow(pairId, path))

    /** Seeds a row that a previous run claimed and then died before completing. */
    private suspend fun insertStaleClaim(
        pairId: Long,
        path: String,
    ) = insertClaim(
        pairId = pairId,
        path = path,
        claimToken = "dead-run",
        claimedAtMs = System.currentTimeMillis() - SyncWorker.INSTANT_CLAIM_TIMEOUT_MS - 1,
    )

    private suspend fun insertClaim(
        pairId: Long,
        path: String,
        claimToken: String,
        claimedAtMs: Long,
    ) = pendingUploadDao.insertIfAbsent(
        pendingRow(pairId, path).copy(
            state = PendingUploadState.CLAIMED,
            claimToken = claimToken,
            claimedAtMs = claimedAtMs,
        ),
    )

    private fun pendingRow(
        pairId: Long,
        path: String,
    ) = PendingUploadEntity(
        pairId = pairId,
        relativePath = path,
        documentIdHint = "doc-$path",
        observedSizeBytes = 5L,
        observedMtimeMs = 5_000L,
        eligibleAtMs = 0L,
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )

    /** Leaves behind the run lease of a process that died mid-dispatch. */
    private suspend fun takeStaleLease(pairId: Long) {
        pairRunLeaseDao.acquire(
            pairId = pairId,
            ownerToken = "dead-run",
            ownerKind = SyncWorker.RUN_KIND_INSTANT,
            nowMs = System.currentTimeMillis() - SyncWorker.LEASE_STALE_AFTER_MS - 1,
            staleAfterMs = SyncWorker.LEASE_STALE_AFTER_MS,
        )
    }

    private fun worker(pairId: Long): SyncWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns
            workDataOf(
                SyncWorker.KEY_PAIR_ID to pairId,
                SyncWorker.KEY_IS_PERIODIC to false,
                SyncWorker.KEY_INSTANT to true,
            )
        every { params.id } returns UUID.randomUUID()
        every { params.runAttemptCount } returns 0
        every { params.progressUpdater } returns ProgressUpdater { _, _, _ -> Futures.immediateFuture(null) }
        every { params.foregroundUpdater } returns ForegroundUpdater { _, _, _ -> Futures.immediateFuture(null) }
        return SyncWorker(
            appContext = context,
            params = params,
            syncPairDao = syncPairDao,
            providerFactories = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(fakeProvider)),
            engine = engine,
            syncEventRepository = SyncEventRepository(db.syncEventDao()),
            syncStatusNotifier = mockk<SyncStatusNotifier>(relaxed = true),
            settingsRepository = settingsRepository,
            localIndexDao = localIndexDao,
            pendingUploadDao = pendingUploadDao,
            instantCandidateResolver = instantCandidateResolver,
            pairRunLeaseDao = pairRunLeaseDao,
        )
    }

    private fun singleProviderFactory(provider: CloudProvider): CloudProviderFactory =
        object : CloudProviderFactory {
            override fun providerFor(accountId: String): CloudProvider = provider
        }

    private companion object {
        const val REMOTE_FOLDER_ID = "remote-root"
    }
}
