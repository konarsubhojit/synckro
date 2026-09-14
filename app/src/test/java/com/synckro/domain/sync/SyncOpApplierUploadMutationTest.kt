package com.synckro.domain.sync

import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.domain.model.SyncPair
import com.synckro.domain.provider.CloudProvider
import com.synckro.providers.fake.FakeCloudProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Covers the post-upload metadata verification of [SyncOpApplier]: an upload whose local
 * source changed while its bytes were streaming must not be acknowledged as a success, the
 * uploaded remote result must be removed or invalidated, and the lazily computed content
 * hash of the last synced state must stay untouched.
 */
class SyncOpApplierUploadMutationTest {
    /**
     * In-memory [LocalFileAccess] that reports a different size/mtime after the file has
     * been read once, simulating a writer that mutates the file mid-upload.
     */
    private enum class PostRead { UNCHANGED, MUTATED, REMOVED }

    private class MutatingLocalFileAccess(
        private val path: String,
        private val bytes: ByteArray,
        private val postRead: PostRead,
    ) : LocalFileAccess {
        private var read = false

        override fun openRead(relativePath: String): InputStream? {
            if (relativePath != path) return null
            read = true
            return ByteArrayInputStream(bytes)
        }

        override fun write(
            relativePath: String,
            content: InputStream,
            mimeType: String?,
        ): LocalFileStat = error("not used")

        override fun delete(relativePath: String): Boolean = false

        override fun stat(relativePath: String): LocalFileStat? {
            if (relativePath != path) return null
            if (!read) return LocalFileStat(sizeBytes = bytes.size.toLong(), mtimeMs = 5_000L)
            return when (postRead) {
                PostRead.UNCHANGED -> LocalFileStat(sizeBytes = bytes.size.toLong(), mtimeMs = 5_000L)
                PostRead.MUTATED -> LocalFileStat(sizeBytes = bytes.size + 7L, mtimeMs = 9_000L)
                PostRead.REMOVED -> null
            }
        }
    }

    private lateinit var provider: FakeCloudProvider
    private lateinit var localIndexDao: LocalIndexDao
    private lateinit var conflictRepo: ConflictRepository
    private lateinit var eventRepo: SyncEventRepository

    @Before
    fun setUp() {
        provider = FakeCloudProvider()
        localIndexDao = mockk(relaxed = true)
        conflictRepo = mockk(relaxed = true)
        eventRepo = mockk(relaxed = true)
    }

    private fun pair() =
        SyncPair(
            id = 1L,
            displayName = "Test pair",
            localTreeUri = "content://test",
            provider = CloudProviderType.FAKE,
            remoteFolderId = "root",
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

    private fun applier(
        localFileAccess: LocalFileAccess,
        cloudProvider: CloudProvider = provider,
    ) = SyncOpApplier(
        provider = cloudProvider,
        localIndexDao = localIndexDao,
        conflictRepository = conflictRepo,
        eventRepository = eventRepo,
        localFileAccess = localFileAccess,
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `UploadNew whose source mutates is not applied and the orphan remote is deleted`() =
        runTest {
            val fs = MutatingLocalFileAccess("file.txt", "hello".toByteArray(), postRead = PostRead.MUTATED)

            val result =
                applier(fs).apply(
                    ops = listOf(SyncOp.UploadNew("file.txt")),
                    pair = pair(),
                    remoteFilesByPath = emptyMap(),
                    localIndexByPath = emptyMap(),
                )

            assertEquals(0, result.applied)
            assertEquals(listOf("file.txt"), result.failedPaths)
            assertTrue(result.errors.single().contains("changed_during_upload"))
            assertTrue(provider.list("root").isEmpty())
            coVerify(exactly = 0) { localIndexDao.upsertSyncedRemoteState(any()) }
            coVerify(exactly = 0) { localIndexDao.upsert(any<LocalIndexEntity>()) }
            coVerify {
                eventRepo.log(1L, SyncEventLevel.WARN, SyncEventTag.OP_APPLIER, any())
            }
        }

    @Test
    fun `UploadNew cleanup failure is logged and keeps the remote linkage recoverable`() =
        runTest {
            val fs = MutatingLocalFileAccess("file.txt", "hello".toByteArray(), postRead = PostRead.MUTATED)
            val failingProvider = spyk(provider)
            coEvery { failingProvider.delete(any()) } throws IOException("delete failed")

            val result =
                applier(fs, failingProvider).apply(
                    ops = listOf(SyncOp.UploadNew("file.txt")),
                    pair = pair(),
                    remoteFilesByPath = emptyMap(),
                    localIndexByPath = emptyMap(),
                )

            assertEquals(0, result.applied)
            assertEquals(listOf("file.txt"), result.failedPaths)
            val entry = slot<LocalIndexEntity>()
            coVerify(exactly = 1) { localIndexDao.upsert(capture(entry)) }
            // The orphan stays linked so the retry updates it instead of uploading a duplicate,
            // while the impossible local mtime keeps the row from ever looking synced.
            assertEquals(provider.list("root").single().id, entry.captured.remoteId)
            assertEquals(-1L, entry.captured.mtimeMs)
            assertEquals(null, entry.captured.contentHash)
            coVerify(exactly = 0) { localIndexDao.upsertSyncedRemoteState(any()) }
            coVerify {
                eventRepo.log(1L, SyncEventLevel.ERROR, SyncEventTag.OP_APPLIER, any())
            }
        }

    @Test
    fun `UpdateRemote whose source mutates invalidates remote state without acknowledging the upload`() =
        runTest {
            val bytes = "hello".toByteArray()
            val remote =
                provider.uploadNew(
                    parentId = "root",
                    name = "file.txt",
                    content = ByteArrayInputStream("old".toByteArray()),
                    size = 3L,
                    mimeType = null,
                )
            val index =
                LocalIndexEntity(
                    pairId = 1L,
                    relativePath = "file.txt",
                    sizeBytes = 3L,
                    mtimeMs = 1_000L,
                    contentHash = "old-hash",
                    remoteId = remote.id,
                    remoteSizeBytes = remote.size,
                    remoteMtimeMs = remote.lastModifiedMs,
                    remoteEtag = remote.eTag,
                )
            val fs = MutatingLocalFileAccess("file.txt", bytes, postRead = PostRead.MUTATED)

            val result =
                applier(fs).apply(
                    ops = listOf(SyncOp.UpdateRemote("file.txt")),
                    pair = pair(),
                    remoteFilesByPath = emptyMap(),
                    localIndexByPath = mapOf("file.txt" to index),
                )

            assertEquals(0, result.applied)
            assertEquals(listOf("file.txt"), result.failedPaths)
            val entry = slot<LocalIndexEntity>()
            coVerify(exactly = 1) { localIndexDao.upsert(capture(entry)) }
            coVerify(exactly = 0) { localIndexDao.upsertSyncedRemoteState(any()) }
            // Local metadata (and its lazily computed hash) stay at the last synced state,
            // so the mutated file is still dirty; the remote metadata is refreshed.
            assertEquals(3L, entry.captured.sizeBytes)
            assertEquals(1_000L, entry.captured.mtimeMs)
            assertEquals("old-hash", entry.captured.contentHash)
            assertEquals(provider.getMetadata(remote.id).eTag, entry.captured.remoteEtag)
            assertEquals(bytes.size.toLong(), entry.captured.remoteSizeBytes)
        }

    @Test
    fun `UploadNew whose source is removed mid-flight is cleaned up and reported as removed`() =
        runTest {
            val fs = MutatingLocalFileAccess("file.txt", "hello".toByteArray(), postRead = PostRead.REMOVED)

            val result =
                applier(fs).apply(
                    ops = listOf(SyncOp.UploadNew("file.txt")),
                    pair = pair(),
                    remoteFilesByPath = emptyMap(),
                    localIndexByPath = emptyMap(),
                )

            assertEquals(0, result.applied)
            assertTrue(result.errors.single().contains("removed_during_upload"))
            assertTrue(provider.list("root").isEmpty())
            coVerify(exactly = 0) { localIndexDao.upsertSyncedRemoteState(any()) }
        }

    @Test
    fun `unchanged upload completes once`() =
        runTest {
            val fs = MutatingLocalFileAccess("file.txt", "hello".toByteArray(), postRead = PostRead.UNCHANGED)

            val result =
                applier(fs).apply(
                    ops = listOf(SyncOp.UploadNew("file.txt")),
                    pair = pair(),
                    remoteFilesByPath = emptyMap(),
                    localIndexByPath = emptyMap(),
                )

            assertEquals(1, result.applied)
            assertEquals(listOf("file.txt"), result.appliedPaths)
            assertEquals(1, provider.list("root").size)
            coVerify(exactly = 1) { localIndexDao.upsertSyncedRemoteState(any()) }
        }
}
