package com.synckro.ui.screens.conflictinbox

import com.synckro.data.local.dao.FileIndexDao
import com.synckro.data.local.entity.FileIndexEntity
import com.synckro.data.repository.AccountRepository
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.domain.auth.Account
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictRecord
import com.synckro.domain.model.SyncPair
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConflictInboxViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var conflictRepository: ConflictRepository
    private lateinit var fileIndexDao: FileIndexDao
    private lateinit var syncPairRepository: SyncPairRepository
    private lateinit var accountRepository: AccountRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        conflictRepository = mockk(relaxed = true)
        fileIndexDao = mockk(relaxed = true)
        syncPairRepository = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `state projects metadata and remote account on conflict rows`() =
        runTest {
            val conflicts =
                MutableStateFlow(
                    listOf(
                        ConflictRecord(
                            id = 7L,
                            pairId = 42L,
                            relativePath = "docs/plan.pdf",
                            localLastModifiedMs = 1_000L,
                            remoteLastModifiedMs = 2_000L,
                            detectedAtMs = 3_000L,
                        ),
                    ),
                )
            every { conflictRepository.observeUnresolved() } returns conflicts
            coEvery { fileIndexDao.getForPair(42L) } returns
                listOf(
                    FileIndexEntity(
                        pairId = 42L,
                        relativePath = "docs/plan.pdf",
                        localSize = 1234L,
                        localLastModifiedMs = 10_000L,
                        localHash = null,
                        remoteId = "remote-1",
                        remoteETag = "etag",
                        remoteSize = 5678L,
                        remoteLastModifiedMs = 20_000L,
                        mimeType = "application/pdf",
                    ),
                )
            coEvery { syncPairRepository.getById(42L) } returns
                SyncPair(
                    id = 42L,
                    displayName = "Docs",
                    localTreeUri = "content://tree/docs",
                    provider = CloudProviderType.GOOGLE_DRIVE,
                    accountId = "acc-1",
                    remoteFolderId = "root",
                )
            coEvery { accountRepository.getAll() } returns
                listOf(
                    Account(
                        id = "acc-1",
                        provider = CloudProviderType.GOOGLE_DRIVE,
                        displayName = "Docs account",
                        email = "docs@example.com",
                    ),
                )

            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            val state = vm.state.value
            assertFalse(state.isLoading)
            assertEquals(1, state.conflicts.size)
            val row = state.conflicts.single()
            assertEquals(1234L, row.localSizeBytes)
            assertEquals(5678L, row.remoteSizeBytes)
            assertEquals(10_000L, row.localLastModifiedMs)
            assertEquals(20_000L, row.remoteLastModifiedMs)
            assertEquals("docs@example.com", row.remoteAccountEmail)
            assertEquals(ConflictInboxViewModel.FileTypeIcon.DOCUMENT, row.fileType)
            // PDF is not an image type: thumbnail fields should be null
            assertEquals(null, row.localDocumentId)
            assertEquals(null, row.localTreeUri)
            assertEquals(null, row.remoteThumbnailUrl)

            collectJob.cancel()
        }

    @Test
    fun `image conflict exposes local thumbnail uri when localDocumentId is available`() =
        runTest {
            val conflicts =
                MutableStateFlow(
                    listOf(
                        ConflictRecord(
                            id = 8L,
                            pairId = 99L,
                            relativePath = "photos/sunset.jpg",
                            localLastModifiedMs = 1_000L,
                            remoteLastModifiedMs = 2_000L,
                            detectedAtMs = 3_000L,
                        ),
                    ),
                )
            every { conflictRepository.observeUnresolved() } returns conflicts
            coEvery { fileIndexDao.getForPair(99L) } returns
                listOf(
                    FileIndexEntity(
                        pairId = 99L,
                        relativePath = "photos/sunset.jpg",
                        localSize = 512_000L,
                        localLastModifiedMs = 1_000L,
                        localHash = null,
                        remoteId = "drive-img-1",
                        remoteETag = null,
                        remoteSize = 520_000L,
                        remoteLastModifiedMs = 2_000L,
                        mimeType = "image/jpeg",
                        localDocumentId = "primary:Photos/sunset.jpg",
                        remoteThumbnailUrl = "https://example.com/thumb.jpg",
                    ),
                )
            coEvery { syncPairRepository.getById(99L) } returns
                SyncPair(
                    id = 99L,
                    displayName = "Photos",
                    localTreeUri = "content://com.android.externalstorage.documents/tree/primary%3APhotos",
                    provider = CloudProviderType.GOOGLE_DRIVE,
                    accountId = "acc-2",
                    remoteFolderId = "root",
                )
            coEvery { accountRepository.getAll() } returns emptyList()

            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            val row = vm.state.value.conflicts.single()
            assertEquals(ConflictInboxViewModel.FileTypeIcon.IMAGE, row.fileType)
            // localDocumentId and localTreeUri should be populated for image types
            assertEquals("primary:Photos/sunset.jpg", row.localDocumentId)
            assertEquals(
                "content://com.android.externalstorage.documents/tree/primary%3APhotos",
                row.localTreeUri,
            )
            // remoteThumbnailUrl should be passed through unchanged
            assertEquals("https://example.com/thumb.jpg", row.remoteThumbnailUrl)

            collectJob.cancel()
        }

    @Test
    fun `image conflict without localDocumentId has null localThumbnailUri`() =
        runTest {
            val conflicts =
                MutableStateFlow(
                    listOf(
                        ConflictRecord(
                            id = 9L,
                            pairId = 77L,
                            relativePath = "images/photo.png",
                            localLastModifiedMs = 1_000L,
                            remoteLastModifiedMs = 2_000L,
                            detectedAtMs = 3_000L,
                        ),
                    ),
                )
            every { conflictRepository.observeUnresolved() } returns conflicts
            coEvery { fileIndexDao.getForPair(77L) } returns
                listOf(
                    FileIndexEntity(
                        pairId = 77L,
                        relativePath = "images/photo.png",
                        localSize = 100_000L,
                        localLastModifiedMs = 1_000L,
                        localHash = null,
                        remoteId = "remote-img",
                        remoteETag = null,
                        remoteSize = null,
                        remoteLastModifiedMs = null,
                        mimeType = "image/png",
                        localDocumentId = null,
                        remoteThumbnailUrl = null,
                    ),
                )
            coEvery { syncPairRepository.getById(77L) } returns
                SyncPair(
                    id = 77L,
                    displayName = "Images",
                    localTreeUri = "content://tree/images",
                    provider = CloudProviderType.ONEDRIVE,
                    accountId = "acc-3",
                    remoteFolderId = "root",
                )
            coEvery { accountRepository.getAll() } returns emptyList()

            val vm = createVm()
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            val row = vm.state.value.conflicts.single()
            assertEquals(ConflictInboxViewModel.FileTypeIcon.IMAGE, row.fileType)
            assertEquals(null, row.localDocumentId)
            assertEquals(null, row.localTreeUri)
            assertEquals(null, row.remoteThumbnailUrl)

            collectJob.cancel()
        }

    @Test
    fun `fileTypeIconForPath maps extensions into expected icon groups`() {
        assertEquals(ConflictInboxViewModel.FileTypeIcon.IMAGE, fileTypeIconForPath("images/photo.JPG"))
        assertEquals(ConflictInboxViewModel.FileTypeIcon.DOCUMENT, fileTypeIconForPath("notes/readme.md"))
        assertEquals(ConflictInboxViewModel.FileTypeIcon.DOCUMENT, fileTypeIconForPath("README"))
        assertEquals(ConflictInboxViewModel.FileTypeIcon.FOLDER, fileTypeIconForPath("photos/"))
        assertEquals(ConflictInboxViewModel.FileTypeIcon.GENERIC, fileTypeIconForPath("archive.bin"))
    }

    private fun createVm() =
        ConflictInboxViewModel(
            conflictRepository = conflictRepository,
            fileIndexDao = fileIndexDao,
            syncPairRepository = syncPairRepository,
            accountRepository = accountRepository,
        )
}
