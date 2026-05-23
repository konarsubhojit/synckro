package com.synckro.ui.screens.pairdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncWorker
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairDetailViewModelProgressTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var syncPairRepository: SyncPairRepository
    private lateinit var syncEventRepository: SyncEventRepository
    private lateinit var conflictRepository: ConflictRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var workManager: WorkManager
    private lateinit var context: Context
    private val pairsFlow = MutableStateFlow<List<SyncPair>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        syncPairRepository = mockk(relaxed = true)
        syncEventRepository =
            mockk {
                every { observeForPair(any(), any()) } returns flowOf(emptyList())
            }
        conflictRepository =
            mockk {
                every { observeForPair(any()) } returns flowOf(emptyList())
            }
        settingsRepository =
            mockk {
                every { globalAutoSyncEnabled } returns flowOf(true)
            }
        workManager = mockk(relaxed = true)
        context = ApplicationProvider.getApplicationContext()
        every { syncPairRepository.observeAll(any()) } returns pairsFlow
        every { workManager.getWorkInfosForUniqueWorkFlow(any<String>()) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `state isSyncing is true and progress is populated when periodic work is running`() =
        runTest {
            val pair = pair(1L)
            pairsFlow.value = listOf(pair)
            every {
                workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.uniqueName(pair.id))
            } returns flowOf(listOf(runningWorkInfo("IMG_20240101.jpg")))
            every {
                workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.syncNowUniqueName(pair.id))
            } returns flowOf(emptyList())

            val vm = createVm(pair.id)
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertTrue(vm.state.value.isSyncing)
            assertEquals(3, vm.state.value.progress?.filesCompleted)
            assertEquals(12, vm.state.value.progress?.totalFiles)
            assertEquals("IMG_20240101.jpg", vm.state.value.progress?.currentFileName)
            collectJob.cancel()
        }

    @Test
    fun `state isSyncing is true and progress is populated when sync now work is running`() =
        runTest {
            val pair = pair(2L)
            pairsFlow.value = listOf(pair)
            every {
                workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.uniqueName(pair.id))
            } returns flowOf(emptyList())
            every {
                workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.syncNowUniqueName(pair.id))
            } returns flowOf(listOf(runningWorkInfo("video.mp4")))

            val vm = createVm(pair.id)
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertTrue(vm.state.value.isSyncing)
            assertEquals(3, vm.state.value.progress?.filesCompleted)
            assertEquals(12, vm.state.value.progress?.totalFiles)
            assertEquals("video.mp4", vm.state.value.progress?.currentFileName)
            collectJob.cancel()
        }

    @Test
    fun `state isSyncing returns to false and progress becomes null when work finishes`() =
        runTest {
            val pair = pair(3L)
            val periodicFlow = MutableStateFlow(listOf(runningWorkInfo("file.txt")))
            pairsFlow.value = listOf(pair)
            every {
                workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.uniqueName(pair.id))
            } returns periodicFlow
            every {
                workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.syncNowUniqueName(pair.id))
            } returns flowOf(emptyList())

            val vm = createVm(pair.id)
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertTrue(vm.state.value.isSyncing)
            assertNotNull(vm.state.value.progress)

            periodicFlow.value = listOf(finishedWorkInfo())
            advanceUntilIdle()

            assertFalse(vm.state.value.isSyncing)
            assertNull(vm.state.value.progress)
            collectJob.cancel()
        }

    @Test
    fun `WorkManager flow throwing does not crash the ViewModel`() =
        runTest {
            val pair = pair(4L)
            pairsFlow.value = listOf(pair)
            every {
                workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.uniqueName(pair.id))
            } returns flow { throw RuntimeException("boom") }
            every {
                workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.syncNowUniqueName(pair.id))
            } returns flowOf(emptyList())

            val vm = createVm(pair.id)
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertFalse(vm.state.value.isLoading)
            assertFalse(vm.state.value.isSyncing)
            assertNull(vm.state.value.progress)
            collectJob.cancel()
        }

    private fun createVm(pairId: Long) =
        PairDetailViewModel(
            context = context,
            savedStateHandle = SavedStateHandle(mapOf(PairDetailViewModel.KEY_PAIR_ID to pairId)),
            syncPairRepository = syncPairRepository,
            syncEventRepository = syncEventRepository,
            conflictRepository = conflictRepository,
            settingsRepository = settingsRepository,
            workManager = workManager,
        )

    private fun pair(id: Long) =
        SyncPair(
            id = id,
            displayName = "Pair $id",
            localTreeUri = "content://test/$id",
            provider = CloudProviderType.FAKE,
            remoteFolderId = "root",
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

    private fun runningWorkInfo(currentFileName: String): WorkInfo =
        mockk {
            every { state } returns WorkInfo.State.RUNNING
            every { progress } returns
                Data.Builder()
                    .putInt(SyncWorker.PROGRESS_FILES_COMPLETED, 3)
                    .putInt(SyncWorker.PROGRESS_TOTAL_FILES, 12)
                    .putLong(SyncWorker.PROGRESS_BYTES_XFERRED, 300L)
                    .putLong(SyncWorker.PROGRESS_TOTAL_BYTES, 1200L)
                    .putString(SyncWorker.PROGRESS_CURRENT_FILE, currentFileName)
                    .build()
        }

    private fun finishedWorkInfo(): WorkInfo =
        mockk {
            every { state } returns WorkInfo.State.SUCCEEDED
            every { progress } returns Data.EMPTY
        }
}
