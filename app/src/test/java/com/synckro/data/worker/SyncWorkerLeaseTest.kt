package com.synckro.data.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.dao.PairRunLeaseDao
import com.synckro.data.local.dao.PendingUploadDao
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.local.fs.LocalFolderAccessChecker
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.sync.SyncEngine
import com.synckro.util.notification.SyncStatusNotifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Verifies that instant, manual, and periodic runs are serialized per pair. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerLeaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val syncPairDao = mockk<SyncPairDao>(relaxed = true)
    private val pendingUploadDao = mockk<PendingUploadDao>(relaxed = true)
    private val localIndexDao = mockk<LocalIndexDao>(relaxed = true)
    private val instantCandidateResolver = mockk<InstantCandidateResolver>(relaxed = true)
    private val engine = mockk<SyncEngine>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>()
    private lateinit var db: SynckroDatabase
    private lateinit var leaseDao: PairRunLeaseDao

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
        leaseDao = db.pairRunLeaseDao()
        // The lease table cascades from `sync_pair`, so a real row must exist.
        runBlocking { db.syncPairDao().insert(pair()) }
        every { settingsRepository.globalAutoSyncEnabled } returns flowOf(true)
        every { settingsRepository.globalInstantSyncEnabled } returns flowOf(true)
        every { settingsRepository.maxConcurrentTransfers } returns flowOf(2)
        coEvery { syncPairDao.getById(PAIR_ID) } returns pair()
        coEvery { engine.runOnce(any(), any(), any()) } returns SyncEngine.Result.Success(0, 0)
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
    }

    @Test
    fun `run acquires and releases the pair lease`() =
        runTest {
            val result = worker(instant = false).doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            assertNull(leaseDao.get(PAIR_ID))
        }

    @Test
    fun `instant run defers while another run owns the pair`() =
        runTest {
            acquireForeignLease()

            val result = worker(instant = true).doWork()

            assertEquals(ListenableWorker.Result.retry().javaClass, result.javaClass)
            coVerify(exactly = 0) { engine.runOnce(any(), any(), any()) }
            coVerify(exactly = 0) { pendingUploadDao.claimEligibleForPair(any(), any(), any(), any()) }
            assertEquals("periodic", leaseDao.get(PAIR_ID)?.ownerKind)
        }

    @Test
    fun `busy run gives up without failing once retries are exhausted`() =
        runTest {
            acquireForeignLease()

            val result = worker(instant = false, runAttemptCount = SyncWorker.MAX_RETRY_ATTEMPTS - 1).doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            coVerify(exactly = 0) { engine.runOnce(any(), any(), any()) }
            assertNotNull(leaseDao.get(PAIR_ID))
        }

    @Test
    fun `stale ownership is taken over by the next run`() =
        runTest {
            leaseDao.acquire(
                pairId = PAIR_ID,
                ownerToken = "dead-run",
                ownerKind = "instant",
                nowMs = System.currentTimeMillis() - SyncWorker.LEASE_STALE_AFTER_MS,
                staleAfterMs = SyncWorker.LEASE_STALE_AFTER_MS,
            )

            val result = worker(instant = false).doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            coVerify(exactly = 1) { engine.runOnce(any(), any(), any()) }
            assertNull(leaseDao.get(PAIR_ID))
        }

    @Test
    fun `failed run still releases the pair lease`() =
        runTest {
            coEvery { engine.runOnce(any(), any(), any()) } throws IllegalStateException("boom")

            val result = worker(instant = false).doWork()

            assertEquals(ListenableWorker.Result.retry().javaClass, result.javaClass)
            assertNull(leaseDao.get(PAIR_ID))
        }

    @Test
    fun `losing the lease to a takeover aborts the in-flight pass`() =
        runTest {
            coEvery { engine.runOnce(any(), any(), any()) } coAnswers {
                delay(SyncWorker.LEASE_HEARTBEAT_INTERVAL_MS * 4)
                SyncEngine.Result.Success(0, 0)
            }

            val result =
                coroutineScope {
                    launch {
                        delay(SyncWorker.LEASE_HEARTBEAT_INTERVAL_MS / 2)
                        leaseDao.acquire(
                            pairId = PAIR_ID,
                            ownerToken = "thief-run",
                            ownerKind = "manual",
                            nowMs = System.currentTimeMillis(),
                            staleAfterMs = 0L,
                        )
                    }
                    worker(instant = false).doWork()
                }

            assertEquals(ListenableWorker.Result.retry().javaClass, result.javaClass)
            assertEquals("thief-run", leaseDao.get(PAIR_ID)?.ownerToken)
        }

    private suspend fun acquireForeignLease() {
        leaseDao.acquire(
            pairId = PAIR_ID,
            ownerToken = "other-run",
            ownerKind = "periodic",
            nowMs = System.currentTimeMillis(),
            staleAfterMs = SyncWorker.LEASE_STALE_AFTER_MS,
        )
    }

    private fun worker(
        instant: Boolean,
        runAttemptCount: Int = 0,
    ): SyncWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns
            workDataOf(
                SyncWorker.KEY_PAIR_ID to PAIR_ID,
                SyncWorker.KEY_IS_PERIODIC to !instant,
                SyncWorker.KEY_INSTANT to instant,
            )
        every { params.id } returns UUID.randomUUID()
        every { params.runAttemptCount } returns runAttemptCount
        return SyncWorker(
            appContext = context,
            params = params,
            syncPairDao = syncPairDao,
            providerFactories = emptyMap<CloudProviderType, CloudProviderFactory>(),
            engine = engine,
            syncEventRepository = mockk<SyncEventRepository>(relaxed = true),
            syncStatusNotifier = mockk<SyncStatusNotifier>(relaxed = true),
            settingsRepository = settingsRepository,
            localIndexDao = localIndexDao,
            pendingUploadDao = pendingUploadDao,
            instantCandidateResolver = instantCandidateResolver,
            pairRunLeaseDao = leaseDao,
            localFolderAccessChecker = mockk<LocalFolderAccessChecker>(relaxed = true),
        )
    }

    private fun pair() =
        SyncPairEntity(
            id = PAIR_ID,
            displayName = "Lease pair",
            localTreeUri = "content://documents/tree/root",
            provider = CloudProviderType.FAKE,
            remoteFolderId = "remote",
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
            includeGlobs = "",
            excludeGlobs = "",
            wifiOnly = false,
            requiresCharging = false,
            instantSyncEnabled = true,
        )

    private companion object {
        const val PAIR_ID = 7L
    }
}
