package com.synckro.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.dao.PairRunLeaseDao
import com.synckro.data.local.dao.PendingUploadDao
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.entity.PendingUploadEntity
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.local.fs.LocalFolderAccessChecker
import com.synckro.data.local.fs.TargetedLocalFileResolution
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
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerInstantTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val syncPairDao = mockk<SyncPairDao>(relaxed = true)
    private val pendingUploadDao = mockk<PendingUploadDao>(relaxed = true)
    private val pairRunLeaseDao = mockk<PairRunLeaseDao>(relaxed = true)
    private val localIndexDao = mockk<LocalIndexDao>(relaxed = true)
    private val instantCandidateResolver = mockk<InstantCandidateResolver>()
    private val engine = mockk<SyncEngine>(relaxed = true)
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
        every { settingsRepository.globalAutoSyncEnabled } returns flowOf(true)
        every { settingsRepository.globalInstantSyncEnabled } returns flowOf(true)
        every { settingsRepository.maxConcurrentTransfers } returns flowOf(2)
        coEvery { pairRunLeaseDao.acquire(any(), any(), any(), any(), any()) } returns true
    }

    @Test
    fun `instant run claims targeted paths and reconciles each outcome`() =
        runTest {
            val pair = pair()
            val uploads = listOf(upload("uploaded.txt"), upload("retry.txt"), upload("skipped.txt"))
            coEvery { syncPairDao.getById(pair.id) } returns pair
            coEvery {
                pendingUploadDao.claimEligibleForPair(pair.id, any(), any(), SyncWorker.INSTANT_BATCH_SIZE)
            } returns uploads
            mockResolvedCandidates()
            coEvery {
                engine.runTargetedUploads(any(), any<Collection<String>>(), any(), 2)
            } returns
                SyncEngine.TargetedUploadResult(
                    result = SyncEngine.Result.PartialFailure(1, 0, listOf("retry")),
                    uploadedPaths = listOf("uploaded.txt"),
                    failedPaths = listOf("retry.txt"),
                    skippedPaths = listOf("skipped.txt"),
                )

            val result = worker(instant = true).doWork()

            assertEquals(ListenableWorker.Result.retry().javaClass, result.javaClass)
            coVerify(exactly = 1) {
                engine.runTargetedUploads(any(), uploads.map { it.relativePath }, any(), 2)
            }
            coVerify(exactly = 0) { engine.runOnce(any(), any(), any()) }
            coVerify(exactly = 1) { pendingUploadDao.complete(pair.id, "uploaded.txt", any()) }
            coVerify(exactly = 1) { pendingUploadDao.complete(pair.id, "skipped.txt", any()) }
            coVerify(exactly = 1) {
                pendingUploadDao.release(pair.id, "retry.txt", any(), any(), any())
            }
        }

    @Test
    fun `missing instant candidate is completed without invoking engine`() =
        runTest {
            val pair = pair()
            val upload = upload("deleted.txt")
            coEvery { syncPairDao.getById(pair.id) } returns pair
            coEvery {
                pendingUploadDao.claimEligibleForPair(pair.id, any(), any(), SyncWorker.INSTANT_BATCH_SIZE)
            } returns listOf(upload)
            coEvery { instantCandidateResolver.resolve(any(), any()) } returns
                TargetedLocalFileResolution.Missing

            val result = worker(instant = true).doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            coVerify(exactly = 1) { pendingUploadDao.complete(pair.id, upload.relativePath, any()) }
            coVerify(exactly = 0) { engine.runTargetedUploads(any(), any(), any(), any()) }
        }

    @Test
    fun `disabled instant pair retains queued rows`() =
        runTest {
            val pair = pair(instantSyncEnabled = false)
            coEvery { syncPairDao.getById(pair.id) } returns pair

            val result = worker(instant = true).doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            coVerify(exactly = 0) { pendingUploadDao.claimEligibleForPair(any(), any(), any(), any()) }
            coVerify(exactly = 0) { engine.runTargetedUploads(any(), any(), any(), any()) }
        }

    @Test
    fun `temporarily unavailable candidate is released and retried`() =
        runTest {
            val pair = pair()
            val upload = upload("busy.txt")
            coEvery { syncPairDao.getById(pair.id) } returns pair
            coEvery {
                pendingUploadDao.claimEligibleForPair(pair.id, any(), any(), SyncWorker.INSTANT_BATCH_SIZE)
            } returns listOf(upload)
            coEvery { instantCandidateResolver.resolve(any(), any()) } returns
                TargetedLocalFileResolution.Unavailable(
                    TargetedLocalFileResolution.Unavailable.Reason.METADATA_UNAVAILABLE,
                )

            val result = worker(instant = true).doWork()

            assertEquals(ListenableWorker.Result.retry().javaClass, result.javaClass)
            coVerify(exactly = 1) {
                pendingUploadDao.release(pair.id, upload.relativePath, any(), any(), any())
            }
            coVerify(exactly = 0) { engine.runTargetedUploads(any(), any(), any(), any()) }
        }

    @Test
    fun `dispatched row that did not complete is requeued with bounded backoff`() =
        runTest {
            val pair = pair()
            val upload = upload("mutated.txt").copy(attempts = 2)
            coEvery { syncPairDao.getById(pair.id) } returns pair
            coEvery {
                pendingUploadDao.claimEligibleForPair(pair.id, any(), any(), SyncWorker.INSTANT_BATCH_SIZE)
            } returns listOf(upload)
            mockResolvedCandidates()
            coEvery {
                engine.runTargetedUploads(any(), any<Collection<String>>(), any(), 2)
            } returns
                SyncEngine.TargetedUploadResult(
                    result = SyncEngine.Result.PartialFailure(0, 0, listOf("changed during upload")),
                    failedPaths = listOf("mutated.txt"),
                )

            val beforeMs = System.currentTimeMillis()
            worker(instant = true).doWork()

            val eligibleAtMs = slot<Long>()
            coVerify(exactly = 1) {
                pendingUploadDao.release(
                    pair.id,
                    upload.relativePath,
                    any(),
                    capture(eligibleAtMs),
                    any(),
                )
            }
            coVerify(exactly = 0) { pendingUploadDao.complete(pair.id, upload.relativePath, any()) }
            assertTrue(
                eligibleAtMs.captured >= beforeMs + SyncWorker.instantRetryDelayMs(upload.attempts),
            )
        }

    @Test
    fun `instant retry backoff grows and stays bounded`() {
        assertEquals(SyncWorker.INSTANT_RETRY_BASE_DELAY_MS, SyncWorker.instantRetryDelayMs(0))
        assertEquals(SyncWorker.INSTANT_RETRY_BASE_DELAY_MS * 4, SyncWorker.instantRetryDelayMs(2))
        assertEquals(SyncWorker.INSTANT_RETRY_MAX_DELAY_MS, SyncWorker.instantRetryDelayMs(99))
    }

    @Test
    fun `full instant batch schedules a follow-up`() =
        runTest {
            val pair = pair()
            val uploads = List(SyncWorker.INSTANT_BATCH_SIZE) { upload("file-$it.txt") }
            coEvery { syncPairDao.getById(pair.id) } returns pair
            coEvery {
                pendingUploadDao.claimEligibleForPair(pair.id, any(), any(), SyncWorker.INSTANT_BATCH_SIZE)
            } returns uploads
            mockResolvedCandidates()
            coEvery {
                engine.runTargetedUploads(any(), any<Collection<String>>(), any(), 2)
            } returns
                SyncEngine.TargetedUploadResult(
                    result = SyncEngine.Result.Success(uploads.size, 0),
                    uploadedPaths = uploads.map { it.relativePath },
                )

            val result = worker(instant = true).doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncWorker.instantName(pair.id)).get()
            assertTrue(infos.any { it.state == WorkInfo.State.ENQUEUED })
        }

    @Test
    fun `deleted pair exits without touching queue`() =
        runTest {
            coEvery { syncPairDao.getById(PAIR_ID) } returns null

            val result = worker(instant = true).doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            coVerify(exactly = 0) { pendingUploadDao.claimEligibleForPair(any(), any(), any(), any()) }
        }

    @Test
    fun `ordinary run bypasses durable queue`() =
        runTest {
            val pair = pair()
            coEvery { syncPairDao.getById(pair.id) } returns pair
            coEvery { engine.runOnce(any(), any(), 2) } returns SyncEngine.Result.Success(0, 0)

            val result = worker(instant = false).doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            coVerify(exactly = 1) { engine.runOnce(any(), any(), 2) }
            coVerify(exactly = 0) { pendingUploadDao.claimEligibleForPair(any(), any(), any(), any()) }
        }

    private fun mockResolvedCandidates() {
        coEvery { instantCandidateResolver.resolve(any(), any()) } returns
            mockk<TargetedLocalFileResolution.Resolved>()
    }

    private fun worker(instant: Boolean): SyncWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns
            workDataOf(
                SyncWorker.KEY_PAIR_ID to PAIR_ID,
                SyncWorker.KEY_IS_PERIODIC to false,
                SyncWorker.KEY_INSTANT to instant,
            )
        every { params.id } returns UUID.randomUUID()
        every { params.runAttemptCount } returns 0
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
            pairRunLeaseDao = pairRunLeaseDao,
            localFolderAccessChecker = mockk<LocalFolderAccessChecker>(relaxed = true),
        )
    }

    private fun pair(instantSyncEnabled: Boolean = true) =
        SyncPairEntity(
            id = PAIR_ID,
            displayName = "Instant pair",
            localTreeUri = "content://documents/tree/root",
            provider = CloudProviderType.FAKE,
            remoteFolderId = "remote",
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
            includeGlobs = "",
            excludeGlobs = "",
            wifiOnly = false,
            requiresCharging = false,
            instantSyncEnabled = instantSyncEnabled,
        )

    private fun upload(path: String) =
        PendingUploadEntity(
            pairId = PAIR_ID,
            relativePath = path,
            documentIdHint = "document-id",
            observedSizeBytes = 1,
            observedMtimeMs = 1,
            eligibleAtMs = 0,
            createdAtMs = 0,
            updatedAtMs = 0,
        )

    private companion object {
        const val PAIR_ID = 42L
    }
}
