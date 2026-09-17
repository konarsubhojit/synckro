package com.synckro.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.synckro.data.local.dao.PairRunLeaseDao
import com.synckro.data.local.dao.PendingUploadDao
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.local.fs.LocalFolderAccessChecker
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.provider.CloudProvider
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.sync.SyncEngine
import com.synckro.domain.sync.TransferProgress
import com.synckro.providers.fake.FakeCloudProvider
import com.synckro.util.notification.SyncStatusNotifier
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncWorkerPreflightTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val syncPairDao = mockk<SyncPairDao>(relaxed = true)
    private val engine = mockk<SyncEngine>(relaxed = true)
    private val eventRepository = mockk<SyncEventRepository>(relaxed = true)
    private val leaseDao = mockk<PairRunLeaseDao>(relaxed = true)
    private val accessChecker = mockk<LocalFolderAccessChecker>()
    private val provider = spyk(FakeCloudProvider())
    private lateinit var remoteFolderId: String

    @Before
    fun setUp() =
        runTest {
            runCatching { WorkManager.getInstance(context) }
                .onFailure {
                    WorkManager.initialize(
                        context,
                        Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build(),
                    )
                }
            remoteFolderId =
                provider.createFolder("root", "Synckro")
                    .id
            every { accessChecker.hasReadWriteAccess(any()) } returns true
            coEvery { leaseDao.acquire(any(), any(), any(), any(), any()) } returns true
            coEvery { engine.runOnce(any(), any(), any()) } returns SyncEngine.Result.Success(0, 0)
        }

    @Test
    fun `healthy preflight proceeds to normal sync`() =
        runTest {
            coEvery { syncPairDao.getById(PAIR_ID) } returns pair()

            val result = worker().doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            coVerify(exactly = 1) { provider.ensureAuthenticated() }
            coVerify(exactly = 1) { provider.getMetadata(remoteFolderId) }
            coVerify(exactly = 1) { engine.runOnce(any(), any(), any()) }
        }

    @Test
    fun `out of order progress records the largest transferred byte total`() =
        runTest {
            coEvery { syncPairDao.getById(PAIR_ID) } returns pair()
            coEvery { engine.runOnce(any(), any(), any()) } coAnswers {
                secondArg<suspend (TransferProgress) -> Unit>()(
                    TransferProgress(1, 2, bytesTransferred = 1_024L, totalBytes = 2_048L),
                )
                secondArg<suspend (TransferProgress) -> Unit>()(
                    TransferProgress(2, 2, bytesTransferred = 512L, totalBytes = 2_048L),
                )
                SyncEngine.Result.Success(2, 0)
            }

            val syncWorker = spyk(worker())
            coJustRun { syncWorker.setProgress(any()) }
            syncWorker.doWork()

            coVerify {
                eventRepository.log(
                    PAIR_ID,
                    SyncEventLevel.INFO,
                    "SyncWorker",
                    "Sync succeeded: 2 applied, 0 conflicts",
                    1_024L,
                )
            }
        }

    @Test
    fun `revoked SAF permission fails before provider or engine access`() =
        runTest {
            every { accessChecker.hasReadWriteAccess(any()) } returns false
            coEvery { syncPairDao.getById(PAIR_ID) } returns pair()

            val result = worker().doWork()

            assertEquals(ListenableWorker.Result.failure().javaClass, result.javaClass)
            coVerify(exactly = 1) {
                syncPairDao.updateLastSyncResult(PAIR_ID, any(), SyncWorker.RESULT_NEEDS_RELINK)
            }
            coVerify(exactly = 0) { provider.ensureAuthenticated() }
            coVerify(exactly = 0) { provider.getMetadata(any()) }
            coVerify(exactly = 0) { engine.runOnce(any(), any(), any()) }
        }

    @Test
    fun `deleted remote folder retries before engine access`() =
        runTest {
            provider.delete(remoteFolderId)
            coEvery { syncPairDao.getById(PAIR_ID) } returns pair()

            val result = worker().doWork()

            assertEquals(ListenableWorker.Result.retry().javaClass, result.javaClass)
            coVerify(exactly = 1) { provider.ensureAuthenticated() }
            coVerify(exactly = 1) { provider.getMetadata(remoteFolderId) }
            coVerify(exactly = 0) { engine.runOnce(any(), any(), any()) }
            coVerify(exactly = 1) {
                eventRepository.log(
                    PAIR_ID,
                    SyncEventLevel.WARN,
                    "SyncWorker",
                    "Sync retriable, will retry (attempt 1/${SyncWorker.MAX_RETRY_ATTEMPTS}): Not found: $remoteFolderId",
                )
            }
        }

    private fun worker(): SyncWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns
            workDataOf(
                SyncWorker.KEY_PAIR_ID to PAIR_ID,
                SyncWorker.KEY_IS_PERIODIC to false,
            )
        every { params.id } returns UUID.randomUUID()
        every { params.runAttemptCount } returns 0
        val settingsRepository = mockk<SettingsRepository>()
        every { settingsRepository.maxConcurrentTransfers } returns flowOf(1)
        return SyncWorker(
            appContext = context,
            params = params,
            syncPairDao = syncPairDao,
            providerFactories = mapOf(CloudProviderType.ONEDRIVE to singleProviderFactory(provider)),
            engine = engine,
            syncEventRepository = eventRepository,
            syncStatusNotifier = mockk<SyncStatusNotifier>(relaxed = true),
            settingsRepository = settingsRepository,
            pendingUploadDao = mockk<PendingUploadDao>(relaxed = true),
            instantCandidateResolver = mockk(relaxed = true),
            pairRunLeaseDao = leaseDao,
            localFolderAccessChecker = accessChecker,
        )
    }

    private fun pair() =
        SyncPairEntity(
            id = PAIR_ID,
            displayName = "Documents",
            localTreeUri = "content://documents/tree/local",
            provider = CloudProviderType.ONEDRIVE,
            accountId = "account",
            remoteFolderId = remoteFolderId,
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
            includeGlobs = "",
            excludeGlobs = "",
            wifiOnly = false,
            requiresCharging = false,
        )

    private fun singleProviderFactory(provider: CloudProvider): CloudProviderFactory =
        object : CloudProviderFactory {
            override fun providerFor(accountId: String): CloudProvider = provider
        }

    private companion object {
        const val PAIR_ID = 42L
    }
}
