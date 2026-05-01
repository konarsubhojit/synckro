package com.konarsubhojit.synckro.ui.screens.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.konarsubhojit.synckro.data.repository.SyncPairRepository
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.SyncDirection
import com.konarsubhojit.synckro.domain.model.SyncPair
import androidx.work.OneTimeWorkRequest
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
    private lateinit var mockWorkManager: WorkManager
    private lateinit var context: Context
    private val pairsFlow = MutableStateFlow<List<SyncPair>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockRepo = mockk(relaxed = true)
        mockWorkManager = mockk(relaxed = true)
        context = ApplicationProvider.getApplicationContext()
        every { mockRepo.observeAll(any()) } returns pairsFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm() = HomeViewModel(
        context = context,
        syncPairRepository = mockRepo,
        workManager = mockWorkManager,
    )

    private fun pair(id: Long, name: String = "Pair $id") = SyncPair(
        id = id,
        displayName = name,
        localTreeUri = "content://test/$id",
        provider = CloudProviderType.FAKE,
        remoteFolderId = "root",
        direction = SyncDirection.BIDIRECTIONAL,
        conflictPolicy = ConflictPolicy.NEWEST_WINS,
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
    fun `state reflects pairs emitted by repository`() = runTest {
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
    fun `empty list is reflected after pairs are cleared`() = runTest {
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
    fun `delete calls repository delete with correct id`() = runTest {
        val vm = createVm()
        vm.delete(42L)
        advanceUntilIdle()

        coVerify { mockRepo.delete(42L) }
    }

    // -------------------------------------------------------------------------
    // Sync now
    // -------------------------------------------------------------------------

    @Test
    fun `syncNow enqueues unique one-time work with correct name`() {
        val vm = createVm()
        val testPair = pair(5L)

        vm.syncNow(testPair)

        verify { mockWorkManager.enqueueUniqueWork("syncnow-5", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `syncNow for different pairs enqueues with different work names`() {
        val vm = createVm()
        vm.syncNow(pair(1L))
        vm.syncNow(pair(2L))

        verify { mockWorkManager.enqueueUniqueWork("syncnow-1", any(), any<OneTimeWorkRequest>()) }
        verify { mockWorkManager.enqueueUniqueWork("syncnow-2", any(), any<OneTimeWorkRequest>()) }
    }
}
