package com.synckro.ui.screens.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.data.worker.SyncWorker
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockRepo: SyncPairRepository
    private lateinit var mockConflictRepo: ConflictRepository
    private lateinit var mockWorkManager: WorkManager
    private lateinit var mockScheduler: SyncScheduler
    private lateinit var context: Context
    private val pairsFlow = MutableStateFlow<List<SyncPair>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockRepo = mockk(relaxed = true)
        mockConflictRepo = mockk(relaxed = true)
        mockWorkManager = mockk(relaxed = true)
        mockScheduler = mockk(relaxed = true)
        context = ApplicationProvider.getApplicationContext()
        every { mockRepo.observeAll(any()) } returns pairsFlow
        every { mockConflictRepo.observeUnresolvedCount() } returns MutableStateFlow(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm() =
        HomeViewModel(
            context = context,
            syncPairRepository = mockRepo,
            conflictRepository = mockConflictRepo,
            workManager = mockWorkManager,
            syncScheduler = mockScheduler,
        )

    private fun pair(
        id: Long,
        name: String = "Pair $id",
        wifiOnly: Boolean = true,
        requiresCharging: Boolean = false,
    ) = SyncPair(
        id = id,
        displayName = name,
        localTreeUri = "content://test/$id",
        provider = CloudProviderType.FAKE,
        remoteFolderId = "root",
        direction = SyncDirection.BIDIRECTIONAL,
        conflictPolicy = ConflictPolicy.NEWEST_WINS,
        wifiOnly = wifiOnly,
        requiresCharging = requiresCharging,
    )

    // -------------------------------------------------------------------------
    // State observations
    // -------------------------------------------------------------------------

    @Test
    fun `initial state is loading`() {
        val vm = createVm()
        assertTrue(vm.state.value.isLoading)
    }

    @Test
    fun `state reflects pairs emitted by repository`() =
        runTest {
            val vm = createVm()
            val pairs = listOf(pair(1L), pair(2L))

            // Subscribe to activate the WhileSubscribed upstream collection.
            val collectJob = launch { vm.state.collect {} }
            pairsFlow.value = pairs
            advanceUntilIdle()

            val state = vm.state.value
            assertFalse(state.isLoading)
            assertEquals(2, state.pairs.size)
            assertEquals(1L, state.pairs[0].id)
            assertEquals(2L, state.pairs[1].id)
            collectJob.cancel()
        }

    @Test
    fun `empty list is reflected after pairs are cleared`() =
        runTest {
            pairsFlow.value = listOf(pair(1L))
            val vm = createVm()

            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(1, vm.state.value.pairs.size)

            pairsFlow.value = emptyList()
            advanceUntilIdle()
            assertEquals(0, vm.state.value.pairs.size)

            collectJob.cancel()
        }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    @Test
    fun `delete calls repository delete with correct id`() =
        runTest {
            val vm = createVm()
            vm.delete(42L)
            advanceUntilIdle()

            coVerify { mockRepo.delete(42L) }
        }

    @Test
    fun `delete cancels WorkManager jobs via SyncScheduler`() =
        runTest {
            val vm = createVm()
            vm.delete(42L)
            advanceUntilIdle()

            verify { mockScheduler.cancel(42L) }
        }

    @Test
    fun `delete cancels jobs before removing from repository`() =
        runTest {
            val cancelOrder = mutableListOf<String>()
            every { mockScheduler.cancel(any()) } answers { cancelOrder.add("cancel") }
            io.mockk.coEvery { mockRepo.delete(any()) } coAnswers { cancelOrder.add("delete") }

            val vm = createVm()
            vm.delete(7L)
            advanceUntilIdle()

            assertEquals(listOf("cancel", "delete"), cancelOrder)
        }

    // -------------------------------------------------------------------------
    // Sync now
    // -------------------------------------------------------------------------

    @Test
    fun `syncNow enqueues unique one-time work with correct name`() {
        val vm = createVm()
        val testPair = pair(5L)

        vm.syncNow(testPair)

        verify { mockWorkManager.enqueueUniqueWork(SyncWorker.syncNowUniqueName(5L), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `syncNow for different pairs enqueues with different work names`() {
        val vm = createVm()
        vm.syncNow(pair(1L))
        vm.syncNow(pair(2L))

        verify { mockWorkManager.enqueueUniqueWork(SyncWorker.syncNowUniqueName(1L), any(), any<OneTimeWorkRequest>()) }
        verify { mockWorkManager.enqueueUniqueWork(SyncWorker.syncNowUniqueName(2L), any(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `syncNow wifiOnly true applies UNMETERED network constraint`() {
        val vm = createVm()
        val reqSlot = slot<OneTimeWorkRequest>()
        every { mockWorkManager.enqueueUniqueWork(any(), any(), capture(reqSlot)) } returns mockk(relaxed = true)

        vm.syncNow(pair(1L, wifiOnly = true))

        assertEquals(NetworkType.UNMETERED, reqSlot.captured.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `syncNow wifiOnly false applies CONNECTED network constraint`() {
        val vm = createVm()
        val reqSlot = slot<OneTimeWorkRequest>()
        every { mockWorkManager.enqueueUniqueWork(any(), any(), capture(reqSlot)) } returns mockk(relaxed = true)

        vm.syncNow(pair(1L, wifiOnly = false))

        assertEquals(NetworkType.CONNECTED, reqSlot.captured.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `syncNow requiresCharging true applies charging constraint`() {
        val vm = createVm()
        val reqSlot = slot<OneTimeWorkRequest>()
        every { mockWorkManager.enqueueUniqueWork(any(), any(), capture(reqSlot)) } returns mockk(relaxed = true)

        vm.syncNow(pair(1L, requiresCharging = true))

        assertTrue(
            "Expected requiresCharging constraint",
            reqSlot.captured.workSpec.constraints.requiresCharging(),
        )
    }

    @Test
    fun `syncNow requiresCharging false does not require charging`() {
        val vm = createVm()
        val reqSlot = slot<OneTimeWorkRequest>()
        every { mockWorkManager.enqueueUniqueWork(any(), any(), capture(reqSlot)) } returns mockk(relaxed = true)

        vm.syncNow(pair(1L, requiresCharging = false))

        assertFalse(
            "Expected no requiresCharging constraint",
            reqSlot.captured.workSpec.constraints.requiresCharging(),
        )
    }

    @Test
    fun `syncNow always applies battery-not-low constraint`() {
        val vm = createVm()
        val reqSlot = slot<OneTimeWorkRequest>()
        every { mockWorkManager.enqueueUniqueWork(any(), any(), capture(reqSlot)) } returns mockk(relaxed = true)

        vm.syncNow(pair(1L))

        assertTrue(
            "battery-not-low must always be set",
            reqSlot.captured.workSpec.constraints.requiresBatteryNotLow(),
        )
    }

    @Test
    fun `syncNow always applies storage-not-low constraint`() {
        val vm = createVm()
        val reqSlot = slot<OneTimeWorkRequest>()
        every { mockWorkManager.enqueueUniqueWork(any(), any(), capture(reqSlot)) } returns mockk(relaxed = true)

        vm.syncNow(pair(1L))

        assertTrue(
            "storage-not-low must always be set",
            reqSlot.captured.workSpec.constraints.requiresStorageNotLow(),
        )
    }

    // -------------------------------------------------------------------------
    // Sync now visual feedback (#98)
    // -------------------------------------------------------------------------

    @Test
    fun `syncNow immediately marks pair as syncing in state`() =
        runTest {
            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.syncNow(pair(7L))
            advanceUntilIdle()

            assertTrue(7L in vm.state.value.syncingPairIds)
            collectJob.cancel()
        }

    @Test
    fun `syncNow tracks multiple pairs independently`() =
        runTest {
            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.syncNow(pair(1L))
            vm.syncNow(pair(2L))
            advanceUntilIdle()

            assertTrue(1L in vm.state.value.syncingPairIds)
            assertTrue(2L in vm.state.value.syncingPairIds)
            collectJob.cancel()
        }

    // -------------------------------------------------------------------------
    // Undo delete (#99)
    // -------------------------------------------------------------------------

    @Test
    fun `requestDelete hides the pair from state immediately`() =
        runTest {
            pairsFlow.value = listOf(pair(1L), pair(2L))
            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(2, vm.state.value.pairs.size)

            vm.requestDelete(pair(1L))
            // Use runCurrent so the VM's UNDO_WINDOW_MS delay does NOT elapse
            // and the pendingDelete state remains observable.
            runCurrent()

            assertEquals(1, vm.state.value.pairs.size)
            assertEquals(2L, vm.state.value.pairs[0].id)
            assertEquals(1L, vm.state.value.pendingDelete?.pair?.id)
            collectJob.cancel()
        }

    @Test
    fun `requestDelete does not call repository delete during undo window`() =
        runTest {
            pairsFlow.value = listOf(pair(1L))
            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.requestDelete(pair(1L))
            runCurrent()

            coVerify(exactly = 0) { mockRepo.delete(1L) }
            io.mockk.verify(exactly = 0) { mockScheduler.cancel(1L) }
            collectJob.cancel()
        }

    @Test
    fun `requestDelete commits delete after the undo window expires`() =
        runTest {
            pairsFlow.value = listOf(pair(1L))
            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.requestDelete(pair(1L))
            // Let everything (including the UNDO_WINDOW_MS delay) drain.
            advanceUntilIdle()

            io.mockk.verify { mockScheduler.cancel(1L) }
            coVerify { mockRepo.delete(1L) }
            assertEquals(null, vm.state.value.pendingDelete)
            collectJob.cancel()
        }

    @Test
    fun `undoDelete restores the pair and prevents commit`() =
        runTest {
            pairsFlow.value = listOf(pair(1L))
            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.requestDelete(pair(1L))
            // Don't advance past the undo window so the pending delete is still alive.
            runCurrent()
            assertEquals(0, vm.state.value.pairs.size)

            vm.undoDelete()
            advanceUntilIdle()

            assertEquals(1, vm.state.value.pairs.size)
            assertEquals(null, vm.state.value.pendingDelete)
            // No commit fires because we cancelled the timer before it elapsed.
            coVerify(exactly = 0) { mockRepo.delete(1L) }
            io.mockk.verify(exactly = 0) { mockScheduler.cancel(1L) }
            collectJob.cancel()
        }

    @Test
    fun `finalizePendingDelete commits immediately`() =
        runTest {
            pairsFlow.value = listOf(pair(1L))
            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.requestDelete(pair(1L))
            runCurrent()

            vm.finalizePendingDelete()
            advanceUntilIdle()

            io.mockk.verify { mockScheduler.cancel(1L) }
            coVerify { mockRepo.delete(1L) }
            assertEquals(null, vm.state.value.pendingDelete)
            collectJob.cancel()
        }

    @Test
    fun `requesting a second delete commits the first pending one immediately`() =
        runTest {
            pairsFlow.value = listOf(pair(1L), pair(2L))
            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.requestDelete(pair(1L))
            runCurrent()
            vm.requestDelete(pair(2L))
            runCurrent()

            // Pair 1's delete should have been flushed when pair 2 was queued.
            io.mockk.verify { mockScheduler.cancel(1L) }
            coVerify { mockRepo.delete(1L) }
            // Pair 2 is the new pending one and shouldn't be committed yet.
            io.mockk.verify(exactly = 0) { mockScheduler.cancel(2L) }
            assertEquals(2L, vm.state.value.pendingDelete?.pair?.id)
            collectJob.cancel()
        }
}
