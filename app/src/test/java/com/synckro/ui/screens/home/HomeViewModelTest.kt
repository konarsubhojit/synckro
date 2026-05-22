package com.synckro.ui.screens.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.synckro.data.repository.AccountRepository
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncEventRepository
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    private lateinit var mockAccountRepository: AccountRepository
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockSyncEventRepository: SyncEventRepository
    private lateinit var context: Context
    private val pairsFlow = MutableStateFlow<List<SyncPair>>(emptyList())
    private val eventsFlow = MutableStateFlow<List<com.synckro.domain.model.SyncEvent>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockRepo = mockk(relaxed = true)
        mockConflictRepo = mockk(relaxed = true)
        mockWorkManager = mockk(relaxed = true)
        mockScheduler = mockk(relaxed = true)
        mockAccountRepository =
            mockk {
                every { observeAll() } returns flowOf(emptyList())
            }
        mockSettingsRepository =
            mockk {
                every { globalAutoSyncEnabled } returns flowOf(true)
                every { onboardingCompletedAtMs } returns flowOf(null)
                every { seenTooltips } returns flowOf(emptySet())
            }
        mockSyncEventRepository =
            mockk {
                every { observeAll(any()) } returns eventsFlow
            }
        context = ApplicationProvider.getApplicationContext()
        every { mockRepo.observeAll(any()) } returns pairsFlow
        every { mockConflictRepo.observeUnresolvedCount() } returns MutableStateFlow(0)
        // Default WorkManager flow that never emits — keeps the syncNow watcher
        // suspended unless a specific test overrides this. Tests that exercise the
        // watcher's failure path should override with a flow that throws.
        every { mockWorkManager.getWorkInfosForUniqueWorkFlow(any<String>()) } returns
            flow { awaitCancellation() }
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
            accountRepository = mockAccountRepository,
            settingsRepository = mockSettingsRepository,
            syncEventRepository = mockSyncEventRepository,
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
            // Use runCurrent so the watcher coroutine does NOT drain — otherwise
            // the mocked WorkManager returns an empty flow that completes
            // immediately and the watcher would clear the syncing flag.
            runCurrent()

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
            runCurrent()

            assertTrue(1L in vm.state.value.syncingPairIds)
            assertTrue(2L in vm.state.value.syncingPairIds)
            collectJob.cancel()
        }

    @Test
    fun `syncNow clears syncing flag when watcher fails`() =
        runTest {
            // Override the default suspending flow with one that emits nothing and
            // completes — `first { }` then throws NoSuchElementException, which
            // used to leave the flag set and disable Sync now indefinitely.
            every { mockWorkManager.getWorkInfosForUniqueWorkFlow(any<String>()) } returns
                emptyFlow()

            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.syncNow(pair(3L))
            advanceUntilIdle()

            assertFalse(3L in vm.state.value.syncingPairIds)
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

    @Test
    fun `re-requesting delete for the same pair restarts the undo window`() =
        runTest {
            pairsFlow.value = listOf(pair(1L))
            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.requestDelete(pair(1L))
            runCurrent()

            // Advance partway into the original window, then re-request the same pair.
            // The original timer must be cancelled so it cannot fire at its earlier
            // deadline once the new window's commit fires.
            testDispatcher.scheduler.advanceTimeBy(HomeViewModel.UNDO_WINDOW_MS - 100L)
            vm.requestDelete(pair(1L))
            runCurrent()

            // Past the original deadline but well before the new one — no commit yet.
            testDispatcher.scheduler.advanceTimeBy(200L)
            runCurrent()
            coVerify(exactly = 0) { mockRepo.delete(1L) }
            io.mockk.verify(exactly = 0) { mockScheduler.cancel(1L) }
            assertEquals(1L, vm.state.value.pendingDelete?.pair?.id)

            // Now drain the new window — exactly one commit should fire.
            advanceUntilIdle()
            coVerify(exactly = 1) { mockRepo.delete(1L) }
            io.mockk.verify(exactly = 1) { mockScheduler.cancel(1L) }
            collectJob.cancel()
        }

    // -------------------------------------------------------------------------
    // Phase 5a — pair-card maps (nextRunByPairId, lastSummaryByPairId)
    // -------------------------------------------------------------------------

    @Test
    fun `nextRunByPairId is populated for healthy pairs from observed events`() =
        runTest {
            val healthy = pair(1L).copy(lastSyncAtMs = 0L, scheduleIntervalMinutes = 30L)
            val paused = pair(2L).copy(lastSyncAtMs = 0L, autoSyncEnabled = false)
            pairsFlow.value = listOf(healthy, paused)
            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            val nextRun = vm.state.value.nextRunByPairId
            // Healthy pair has an ETA, paused pair is absent from the map.
            assertTrue(1L in nextRun)
            assertFalse(2L in nextRun)
            collectJob.cancel()
        }

    @Test
    fun `lastSummaryByPairId is parsed from the newest terminal SyncWorker event per pair`() =
        runTest {
            pairsFlow.value = listOf(pair(1L), pair(2L))
            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            eventsFlow.value = listOf(
                com.synckro.domain.model.SyncEvent(
                    pairId = 1L,
                    timestampMs = 200L,
                    level = com.synckro.domain.model.SyncEventLevel.INFO,
                    tag = com.synckro.domain.model.SyncEventTag.SyncWorker,
                    message = "Sync succeeded: 12 applied, 3 conflicts",
                ),
                com.synckro.domain.model.SyncEvent(
                    pairId = 2L,
                    timestampMs = 100L,
                    level = com.synckro.domain.model.SyncEventLevel.WARN,
                    tag = com.synckro.domain.model.SyncEventTag.SyncWorker,
                    message = "Sync partial failure: 5 applied, 2 errors — boom",
                ),
            )
            advanceUntilIdle()

            val summaries = vm.state.value.lastSummaryByPairId
            assertEquals(PairSummary.Outcome.SUCCESS, summaries[1L]?.outcome)
            assertEquals(12, summaries[1L]?.applied)
            assertEquals(3, summaries[1L]?.conflicts)
            assertEquals(PairSummary.Outcome.PARTIAL_FAILURE, summaries[2L]?.outcome)
            assertEquals(2, summaries[2L]?.errors)
            collectJob.cancel()
        }

    // -------------------------------------------------------------------------
    // Phase 5b — syncAllNow (bulk sync + pull-to-refresh)
    // -------------------------------------------------------------------------

    @Test
    fun `partitionForSyncAll skips unhealthy pairs and pairs already in flight`() {
        val healthy = pair(1L)
        val needsReLink = pair(2L).copy(needsReLink = true)
        val needsReauth = pair(3L).copy(lastSyncResult = "NEEDS_REAUTH")
        val inFlight = pair(4L)
        val (eligible, skipped) = HomeViewModel.partitionForSyncAll(
            pairs = listOf(healthy, needsReLink, needsReauth, inFlight),
            syncingPairIds = setOf(inFlight.id),
        )
        assertEquals(listOf(1L), eligible.map { it.id })
        assertEquals(3, skipped)
    }

    @Test
    fun `partitionForSyncAll returns empty when no pairs exist`() {
        val (eligible, skipped) = HomeViewModel.partitionForSyncAll(emptyList(), emptySet())
        assertTrue(eligible.isEmpty())
        assertEquals(0, skipped)
    }

    @Test
    fun `syncAllNow enqueues a one-shot for each healthy pair and emits a result`() =
        runTest {
            val healthy1 = pair(1L)
            val healthy2 = pair(2L)
            val needsReauth = pair(3L).copy(lastSyncResult = "NEEDS_REAUTH")
            pairsFlow.value = listOf(healthy1, healthy2, needsReauth)
            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            val results = mutableListOf<HomeViewModel.SyncAllResult>()
            val resultsJob = launch { vm.syncAllResults.collect { results += it } }
            // Give the collector a chance to subscribe before we emit, otherwise the
            // SharedFlow's buffered value lands without a live subscriber.
            advanceUntilIdle()

            vm.syncAllNow()
            advanceUntilIdle()

            verify {
                mockWorkManager.enqueueUniqueWork(
                    SyncWorker.syncNowUniqueName(1L), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>(),
                )
            }
            verify {
                mockWorkManager.enqueueUniqueWork(
                    SyncWorker.syncNowUniqueName(2L), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>(),
                )
            }
            io.mockk.verify(exactly = 0) {
                mockWorkManager.enqueueUniqueWork(
                    SyncWorker.syncNowUniqueName(3L), any(), any<OneTimeWorkRequest>(),
                )
            }
            assertEquals(1, results.size)
            assertEquals(2, results[0].synced)
            assertEquals(1, results[0].skipped)
            resultsJob.cancel()
            collectJob.cancel()
        }

    @Test
    fun `syncAllNow on an empty list emits a zero result without enqueuing`() =
        runTest {
            pairsFlow.value = emptyList()
            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            val results = mutableListOf<HomeViewModel.SyncAllResult>()
            val resultsJob = launch { vm.syncAllResults.collect { results += it } }
            advanceUntilIdle()

            vm.syncAllNow()
            advanceUntilIdle()

            io.mockk.verify(exactly = 0) {
                mockWorkManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>())
            }
            assertEquals(1, results.size)
            assertEquals(0, results[0].synced)
            assertEquals(0, results[0].skipped)
            resultsJob.cancel()
            collectJob.cancel()
        }
}
