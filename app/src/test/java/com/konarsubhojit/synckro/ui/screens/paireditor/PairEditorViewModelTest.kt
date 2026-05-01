package com.konarsubhojit.synckro.ui.screens.paireditor

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.konarsubhojit.synckro.data.repository.SyncPairRepository
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.SyncPair
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class PairEditorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockRepo: SyncPairRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockRepo = mockk(relaxed = true)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm(pairId: Long = 0L): PairEditorViewModel = PairEditorViewModel(
        savedStateHandle = SavedStateHandle(mapOf("pairId" to pairId)),
        context = context,
        syncPairRepository = mockRepo,
    )

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    fun `default state has FAKE provider and empty fields`() {
        val vm = createVm()
        val state = vm.state.value
        assertEquals(CloudProviderType.FAKE, state.provider)
        assertEquals("", state.displayName)
        assertEquals("", state.localTreeUri)
        assertFalse(state.isSaving)
        assertNull(state.saveError)
    }

    // -------------------------------------------------------------------------
    // Field mutations
    // -------------------------------------------------------------------------

    @Test
    fun `form field changes update state`() {
        val vm = createVm()
        vm.onDisplayNameChange("My Pair")
        vm.onRemoteFolderIdChange("remote/folder")
        vm.onWifiOnlyChange(false)
        vm.onRequiresChargingChange(true)
        vm.onConflictPolicyChange(ConflictPolicy.PREFER_LOCAL)

        val state = vm.state.value
        assertEquals("My Pair", state.displayName)
        assertEquals("remote/folder", state.remoteFolderId)
        assertFalse(state.wifiOnly)
        assertTrue(state.requiresCharging)
        assertEquals(ConflictPolicy.PREFER_LOCAL, state.conflictPolicy)
    }

    // -------------------------------------------------------------------------
    // Save validation
    // -------------------------------------------------------------------------

    @Test
    fun `save with blank displayName sets saveError`() = runTest {
        val vm = createVm()
        vm.onDisplayNameChange("   ")
        var savedId: Long? = null
        vm.save { savedId = it }
        advanceUntilIdle()

        assertNull("onSaved must not be called on validation failure", savedId)
        assertTrue(vm.state.value.saveError?.isNotEmpty() == true)
    }

    @Test
    fun `save with valid state calls repository upsert`() = runTest {
        coEvery { mockRepo.upsert(any()) } returns 42L

        val vm = createVm()
        vm.onDisplayNameChange("Test Pair")
        var savedId: Long? = null
        vm.save { savedId = it }
        advanceUntilIdle()

        assertEquals(42L, savedId)
        coVerify { mockRepo.upsert(any()) }
        assertNull(vm.state.value.saveError)
        assertFalse(vm.state.value.isSaving)
    }

    // -------------------------------------------------------------------------
    // Folder URI from navigation back-stack
    // -------------------------------------------------------------------------

    @Test
    fun `folderUri from savedStateHandle updates localTreeUri`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("pairId" to 0L))
        val vm = PairEditorViewModel(
            savedStateHandle = savedStateHandle,
            context = context,
            syncPairRepository = mockRepo,
        )

        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads"
        savedStateHandle[PairEditorViewModel.KEY_LOCAL_TREE_URI] = uri
        advanceUntilIdle()

        assertEquals(uri, vm.state.value.localTreeUri)
    }

    // -------------------------------------------------------------------------
    // Edit mode
    // -------------------------------------------------------------------------

    @Test
    fun `edit mode loads existing pair from repository`() = runTest {
        val existingPair = SyncPair(
            id = 7L,
            displayName = "Existing Pair",
            localTreeUri = "content://test",
            provider = CloudProviderType.FAKE,
            remoteFolderId = "remote",
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
        )
        coEvery { mockRepo.getById(7L) } returns existingPair

        val vm = createVm(pairId = 7L)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("Existing Pair", state.displayName)
        assertEquals("content://test", state.localTreeUri)
        assertEquals("remote", state.remoteFolderId)
        assertEquals(ConflictPolicy.KEEP_BOTH, state.conflictPolicy)
        assertFalse(state.isLoading)
    }

    @Test
    fun `edit mode with unknown pairId leaves defaults intact`() = runTest {
        coEvery { mockRepo.getById(99L) } returns null

        val vm = createVm(pairId = 99L)
        advanceUntilIdle()

        assertFalse(vm.state.value.isLoading)
        // displayName should still be empty (fallback to defaults)
        assertEquals("", vm.state.value.displayName)
    }

    // -------------------------------------------------------------------------
    // Error management
    // -------------------------------------------------------------------------

    @Test
    fun `clearSaveError removes error from state`() {
        val vm = createVm()
        vm.onDisplayNameChange("   ")
        vm.save {}
        assertTrue(vm.state.value.saveError?.isNotEmpty() == true)

        vm.clearSaveError()
        assertNull(vm.state.value.saveError)
    }
}
