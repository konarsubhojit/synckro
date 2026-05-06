package com.synckro.ui.screens.paireditor

import androidx.lifecycle.SavedStateHandle
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncPair
import com.synckro.util.StringProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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

@OptIn(ExperimentalCoroutinesApi::class)
class PairEditorViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockRepo: SyncPairRepository
    private lateinit var mockStrings: StringProvider
    private lateinit var mockSyncScheduler: SyncScheduler

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockRepo = mockk(relaxed = true)
        mockStrings =
            mockk {
                every { getString(any()) } returns "error"
                every { getString(any(), *anyVararg()) } returns "error"
            }
        mockSyncScheduler = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm(pairId: Long = 0L): PairEditorViewModel =
        PairEditorViewModel(
            savedStateHandle = SavedStateHandle(mapOf("pairId" to pairId)),
            strings = mockStrings,
            syncPairRepository = mockRepo,
            syncScheduler = mockSyncScheduler,
        )

    /**
     * Creates a [PairEditorViewModel] that already has a local folder URI set in the
     * [SavedStateHandle], simulating what happens after the user picks a folder from
     * [PickLocalFolderScreen]. The caller should still call `advanceUntilIdle()` inside
     * a `runTest` block so the StateFlow emission is processed.
     */
    private fun createVmWithFolder(
        folderUri: String = "content://com.android.externalstorage.documents/tree/primary%3ADownloads",
        pairId: Long = 0L,
    ): PairEditorViewModel =
        PairEditorViewModel(
            savedStateHandle =
                SavedStateHandle(
                    mapOf(
                        "pairId" to pairId,
                        PairEditorViewModel.KEY_LOCAL_TREE_URI to folderUri,
                    ),
                ),
            strings = mockStrings,
            syncPairRepository = mockRepo,
            syncScheduler = mockSyncScheduler,
        )

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    fun `default state has google drive provider and empty fields`() {
        val vm = createVm()
        val state = vm.state.value
        assertEquals(CloudProviderType.GOOGLE_DRIVE, state.provider)
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
        vm.onRemoteFolderPicked("remote-folder-id", "My Remote Folder")
        vm.onWifiOnlyChange(false)
        vm.onRequiresChargingChange(true)
        vm.onConflictPolicyChange(ConflictPolicy.PREFER_LOCAL)

        val state = vm.state.value
        assertEquals("My Pair", state.displayName)
        assertEquals("remote-folder-id", state.remoteFolderId)
        assertEquals("My Remote Folder", state.remoteFolderName)
        assertFalse(state.wifiOnly)
        assertTrue(state.requiresCharging)
        assertEquals(ConflictPolicy.PREFER_LOCAL, state.conflictPolicy)
    }

    // -------------------------------------------------------------------------
    // Save validation
    // -------------------------------------------------------------------------

    @Test
    fun `save with blank displayName sets saveError`() =
        runTest {
            val vm = createVm()
            vm.onDisplayNameChange("   ")
            var savedId: Long? = null
            vm.save { savedId = it }
            advanceUntilIdle()

            assertNull("onSaved must not be called on validation failure", savedId)
            assertTrue(
                vm.state.value.saveError
                    ?.isNotEmpty() == true,
            )
        }

    @Test
    fun `save with blank localTreeUri sets saveError`() =
        runTest {
            val vm = createVm()
            vm.onDisplayNameChange("Test Pair")
            // localTreeUri is empty by default
            var savedId: Long? = null
            vm.save { savedId = it }
            advanceUntilIdle()

            assertNull("onSaved must not be called when local folder is missing", savedId)
            assertTrue(
                vm.state.value.saveError
                    ?.isNotEmpty() == true,
            )
        }

    @Test
    fun `save with valid state calls repository upsert`() =
        runTest {
            coEvery { mockRepo.upsert(any()) } returns 42L

            val vm = createVmWithFolder()
            vm.onDisplayNameChange("Test Pair")
            advanceUntilIdle()

            var savedId: Long? = null
            vm.save { savedId = it }
            advanceUntilIdle()

            assertEquals(42L, savedId)
            coVerify { mockRepo.upsert(any()) }
            assertNull(vm.state.value.saveError)
            assertFalse(vm.state.value.isSaving)
        }

    @Test
    fun `save with valid state schedules periodic sync`() =
        runTest {
            coEvery { mockRepo.upsert(any()) } returns 42L

            val vm = createVmWithFolder()
            vm.onDisplayNameChange("Test Pair")
            advanceUntilIdle()

            vm.save {}
            advanceUntilIdle()

            coVerify { mockSyncScheduler.scheduleOrCancel(any()) }
        }

    @Test
    fun `save with autoSyncEnabled false cancels periodic sync`() =
        runTest {
            coEvery { mockRepo.upsert(any()) } returns 42L

            val vm = createVmWithFolder()
            vm.onDisplayNameChange("Test Pair")
            vm.onAutoSyncEnabledChange(false)
            advanceUntilIdle()

            vm.save {}
            advanceUntilIdle()

            coVerify {
                mockSyncScheduler.scheduleOrCancel(
                    match { !it.autoSyncEnabled },
                )
            }
        }

    @Test
    fun `save sets error state when scheduleOrCancel throws`() =
        runTest {
            coEvery { mockRepo.upsert(any()) } returns 42L
            every { mockSyncScheduler.scheduleOrCancel(any()) } throws RuntimeException("WorkManager unavailable")

            val vm = createVmWithFolder()
            vm.onDisplayNameChange("Test Pair")
            advanceUntilIdle()

            var savedId: Long? = null
            vm.save { savedId = it }
            advanceUntilIdle()

            // scheduleOrCancel is inside runCatching so the exception is caught and
            // onSaved must NOT be called; an error message should be set instead.
            assertNull("onSaved must not be called when scheduling fails", savedId)
            assertTrue(
                vm.state.value.saveError
                    ?.isNotEmpty() == true,
            )
        }

    // -------------------------------------------------------------------------
    // Folder URI from navigation back-stack
    // -------------------------------------------------------------------------

    @Test
    fun `onLocalFolderPicked updates localTreeUri`() =
        runTest {
            val vm = createVm()
            val uri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads"

            vm.onLocalFolderPicked(uri)
            advanceUntilIdle()

            assertEquals(uri, vm.state.value.localTreeUri)
        }

    @Test
    fun `onLocalFolderPicked ignores blank input`() =
        runTest {
            val vm = createVm()
            vm.onLocalFolderPicked("")
            advanceUntilIdle()

            assertEquals("", vm.state.value.localTreeUri)
        }

    @Test
    fun `restored localTreeUri from SavedStateHandle survives VM recreation`() =
        runTest {
            val uri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads"
            val savedStateHandle =
                SavedStateHandle(
                    mapOf(
                        "pairId" to 0L,
                        PairEditorViewModel.KEY_LOCAL_TREE_URI to uri,
                    ),
                )
            val vm =
                PairEditorViewModel(
                    savedStateHandle = savedStateHandle,
                    strings = mockStrings,
                    syncPairRepository = mockRepo,
                    syncScheduler = mockSyncScheduler,
                )
            advanceUntilIdle()

            assertEquals(uri, vm.state.value.localTreeUri)
        }

    @Test
    fun `freshly picked folder URI is not overwritten by loadExisting`() =
        runTest {
            val freshUri = "content://com.android.externalstorage.documents/tree/primary%3ANewFolder"
            val savedStateHandle =
                SavedStateHandle(
                    mapOf(
                        "pairId" to 7L,
                        PairEditorViewModel.KEY_LOCAL_TREE_URI to freshUri,
                    ),
                )
            val existingPair =
                SyncPair(
                    id = 7L,
                    displayName = "Existing Pair",
                    localTreeUri = "content://old/uri",
                    provider = CloudProviderType.FAKE,
                    remoteFolderId = "remote",
                )
            coEvery { mockRepo.getById(7L) } returns existingPair

            val vm =
                PairEditorViewModel(
                    savedStateHandle = savedStateHandle,
                    strings = mockStrings,
                    syncPairRepository = mockRepo,
                    syncScheduler = mockSyncScheduler,
                )
            advanceUntilIdle()

            // The freshly picked URI in the savedStateHandle must take priority over the
            // stored URI loaded by loadExisting().
            assertEquals(freshUri, vm.state.value.localTreeUri)
        }

    // -------------------------------------------------------------------------
    // Edit mode
    // -------------------------------------------------------------------------

    @Test
    fun `edit mode loads existing pair from repository`() =
        runTest {
            val existingPair =
                SyncPair(
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
    fun `edit mode with unknown pairId leaves defaults intact`() =
        runTest {
            coEvery { mockRepo.getById(99L) } returns null

            val vm = createVm(pairId = 99L)
            advanceUntilIdle()

            assertFalse(vm.state.value.isLoading)
            // displayName should still be empty (fallback to defaults)
            assertEquals("", vm.state.value.displayName)
        }

    // -------------------------------------------------------------------------
    // Retention days
    // -------------------------------------------------------------------------

    @Test
    fun `onRetentionDaysChange updates retentionDaysText`() {
        val vm = createVm()
        vm.onRetentionDaysChange("14")
        assertEquals("14", vm.state.value.retentionDaysText)
    }

    @Test
    fun `onRetentionDaysChange accepts empty string to clear retention`() {
        val vm = createVm()
        vm.onRetentionDaysChange("7")
        vm.onRetentionDaysChange("")
        assertEquals("", vm.state.value.retentionDaysText)
    }

    @Test
    fun `onRetentionDaysChange filters non-digit input`() {
        val vm = createVm()
        vm.onRetentionDaysChange("1a2-3")
        assertEquals("123", vm.state.value.retentionDaysText)
    }

    @Test
    fun `edit mode loads retentionDays from existing pair`() =
        runTest {
            val existingPair =
                SyncPair(
                    id = 7L,
                    displayName = "Backup",
                    localTreeUri = "content://test",
                    provider = CloudProviderType.FAKE,
                    remoteFolderId = "remote",
                    retentionDays = 30,
                )
            coEvery { mockRepo.getById(7L) } returns existingPair

            val vm = createVm(pairId = 7L)
            advanceUntilIdle()

            assertEquals("30", vm.state.value.retentionDaysText)
        }

    @Test
    fun `edit mode shows empty retentionDaysText when pair has no retention`() =
        runTest {
            val existingPair =
                SyncPair(
                    id = 7L,
                    displayName = "Pair",
                    localTreeUri = "content://test",
                    provider = CloudProviderType.FAKE,
                    remoteFolderId = "remote",
                    retentionDays = null,
                )
            coEvery { mockRepo.getById(7L) } returns existingPair

            val vm = createVm(pairId = 7L)
            advanceUntilIdle()

            assertEquals("", vm.state.value.retentionDaysText)
        }

    @Test
    fun `save passes retentionDays to repository when set`() =
        runTest {
            var savedPair: SyncPair? = null
            coEvery { mockRepo.upsert(any()) } answers {
                savedPair = firstArg()
                1L
            }

            val vm = createVmWithFolder()
            vm.onDisplayNameChange("Backup Pair")
            vm.onRetentionDaysChange("7")
            advanceUntilIdle()

            vm.save {}
            advanceUntilIdle()

            assertEquals(7, savedPair?.retentionDays)
        }

    @Test
    fun `save passes null retentionDays to repository when field is blank`() =
        runTest {
            var savedPair: SyncPair? = null
            coEvery { mockRepo.upsert(any()) } answers {
                savedPair = firstArg()
                1L
            }

            val vm = createVmWithFolder()
            vm.onDisplayNameChange("Pair")
            vm.onRetentionDaysChange("")
            advanceUntilIdle()

            vm.save {}
            advanceUntilIdle()

            assertNull(savedPair?.retentionDays)
        }

    @Test
    fun `save rejects retentionDays above maximum`() =
        runTest {
            coEvery { mockRepo.upsert(any()) } returns 1L

            val vm = createVmWithFolder()
            vm.onDisplayNameChange("Pair")
            vm.onRetentionDaysChange((PairEditorViewModel.MAX_RETENTION_DAYS + 1).toString())
            advanceUntilIdle()

            var savedId: Long? = null
            vm.save { savedId = it }
            advanceUntilIdle()

            assertNull(savedId)
            assertTrue(vm.state.value.saveError?.isNotEmpty() == true)
            coVerify(exactly = 0) { mockRepo.upsert(any()) }
        }

    // -------------------------------------------------------------------------
    // Error management
    // -------------------------------------------------------------------------

    @Test
    fun `clearSaveError removes error from state`() {
        val vm = createVm()
        vm.onDisplayNameChange("   ")
        vm.save {}
        assertTrue(
            vm.state.value.saveError
                ?.isNotEmpty() == true,
        )

        vm.clearSaveError()
        assertNull(vm.state.value.saveError)
    }
}
