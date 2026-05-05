package com.synckro.domain.sync

import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import com.synckro.domain.provider.RemoteFile
import com.synckro.providers.fake.FakeCloudProvider
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Tests for the [TransferProgress] callback in [SyncOpApplier.apply].
 *
 * Verifies:
 * - [TransferProgress] is emitted once per op.
 * - [TransferProgress.filesCompleted] increments monotonically from 1 to totalFiles.
 * - [TransferProgress.totalFiles] equals ops.size.
 * - [TransferProgress.totalBytes] is the sum of known remote/local sizes.
 * - [TransferProgress.bytesTransferred] accumulates correctly op-by-op.
 * - Falls back to file-count mode (totalBytes == 0) when no sizes are available.
 */
class SyncOpApplierProgressTest {

    // -------------------------------------------------------------------------
    // In-memory fakes (mirrors SyncOpApplierTest.InMemoryLocalFileAccess)
    // -------------------------------------------------------------------------

    private class InMemoryLocalFileAccess(
        private val nowMs: Long = 5_000L,
    ) : LocalFileAccess {
        private val files = mutableMapOf<String, ByteArray>()

        fun put(path: String, bytes: ByteArray) {
            files[path] = bytes
        }

        override fun openRead(path: String): InputStream? = files[path]?.let { ByteArrayInputStream(it) }

        override fun write(path: String, content: InputStream, mimeType: String?): LocalFileStat {
            val bytes = content.use { it.readBytes() }
            files[path] = bytes
            return LocalFileStat(sizeBytes = bytes.size.toLong(), mtimeMs = nowMs, mimeType = mimeType)
        }

        override fun delete(path: String): Boolean = files.remove(path) != null

        override fun stat(path: String): LocalFileStat? = files[path]?.let { LocalFileStat(sizeBytes = it.size.toLong(), mtimeMs = nowMs) }
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private lateinit var fakeProvider: FakeCloudProvider
    private lateinit var localFs: InMemoryLocalFileAccess
    private lateinit var localIndexDao: LocalIndexDao
    private lateinit var conflictRepo: ConflictRepository
    private lateinit var eventRepo: SyncEventRepository

    private fun buildApplier() =
        SyncOpApplier(
            provider = fakeProvider,
            localIndexDao = localIndexDao,
            conflictRepository = conflictRepo,
            eventRepository = eventRepo,
            localFileAccess = localFs,
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun pair(conflictPolicy: ConflictPolicy = ConflictPolicy.NEWEST_WINS) =
        SyncPair(
            id = 1L,
            displayName = "Test pair",
            localTreeUri = "content://test",
            provider = CloudProviderType.FAKE,
            remoteFolderId = "root",
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = conflictPolicy,
        )

    private fun indexEntry(
        path: String,
        remoteId: String? = "remote-$path",
        sizeBytes: Long = 10L,
        mtimeMs: Long = 1_000L,
    ) = LocalIndexEntity(
        pairId = 1L,
        relativePath = path,
        sizeBytes = sizeBytes,
        mtimeMs = mtimeMs,
        contentHash = null,
        remoteId = remoteId,
    )

    private suspend fun seedRemote(name: String, content: ByteArray): RemoteFile =
        fakeProvider.uploadNew(
            parentId = "root",
            name = name,
            content = ByteArrayInputStream(content),
            size = content.size.toLong(),
            mimeType = "application/octet-stream",
        )

    @Before
    fun setUp() {
        fakeProvider = FakeCloudProvider()
        localFs = InMemoryLocalFileAccess()
        localIndexDao = mockk(relaxed = true)
        conflictRepo = mockk(relaxed = true)
        eventRepo = mockk(relaxed = true)
    }

    // =========================================================================
    // Callback invocation count and filesCompleted progression
    // =========================================================================

    @Test
    fun `onProgress is called once per op`() =
        runTest {
            val content = "hello".toByteArray()
            localFs.put("a.txt", content)
            localFs.put("b.txt", content)

            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = listOf(SyncOp.UploadNew("a.txt"), SyncOp.UploadNew("b.txt")),
                pair = pair(),
                remoteFilesByPath = emptyMap(),
                localIndexByPath = emptyMap(),
                onProgress = { events += it },
            )

            assertEquals("exactly one event per op", 2, events.size)
        }

    @Test
    fun `filesCompleted increments monotonically and equals totalFiles on last event`() =
        runTest {
            val content = "data".toByteArray()
            localFs.put("x.txt", content)
            localFs.put("y.txt", content)
            localFs.put("z.txt", content)

            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = listOf(
                    SyncOp.UploadNew("x.txt"),
                    SyncOp.UploadNew("y.txt"),
                    SyncOp.UploadNew("z.txt"),
                ),
                pair = pair(),
                remoteFilesByPath = emptyMap(),
                localIndexByPath = emptyMap(),
                onProgress = { events += it },
            )

            assertEquals(listOf(1, 2, 3), events.map { it.filesCompleted })
            assertEquals(3, events.last().totalFiles)
        }

    @Test
    fun `totalFiles equals ops size`() =
        runTest {
            val content = "x".toByteArray()
            localFs.put("f1.txt", content)
            localFs.put("f2.txt", content)

            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = listOf(SyncOp.UploadNew("f1.txt"), SyncOp.UploadNew("f2.txt")),
                pair = pair(),
                remoteFilesByPath = emptyMap(),
                localIndexByPath = emptyMap(),
                onProgress = { events += it },
            )

            events.forEach { assertEquals(2, it.totalFiles) }
        }

    // =========================================================================
    // Byte-level progress: downloads (size from remoteFilesByPath)
    // =========================================================================

    @Test
    fun `totalBytes is sum of remote file sizes for DownloadNew ops`() =
        runTest {
            val content100 = ByteArray(100) { 0x41 }
            val content200 = ByteArray(200) { 0x42 }
            val remote1 = seedRemote("file1.bin", content100)
            val remote2 = seedRemote("file2.bin", content200)

            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = listOf(SyncOp.DownloadNew("file1.bin"), SyncOp.DownloadNew("file2.bin")),
                pair = pair(),
                remoteFilesByPath = mapOf("file1.bin" to remote1, "file2.bin" to remote2),
                localIndexByPath = emptyMap(),
                onProgress = { events += it },
            )

            assertEquals(300L, events.last().totalBytes)
        }

    @Test
    fun `bytesTransferred accumulates after each DownloadNew`() =
        runTest {
            val content100 = ByteArray(100) { 0x41 }
            val content200 = ByteArray(200) { 0x42 }
            val remote1 = seedRemote("file1.bin", content100)
            val remote2 = seedRemote("file2.bin", content200)

            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = listOf(SyncOp.DownloadNew("file1.bin"), SyncOp.DownloadNew("file2.bin")),
                pair = pair(),
                remoteFilesByPath = mapOf("file1.bin" to remote1, "file2.bin" to remote2),
                localIndexByPath = emptyMap(),
                onProgress = { events += it },
            )

            assertEquals(100L, events[0].bytesTransferred)
            assertEquals(300L, events[1].bytesTransferred)
        }

    // =========================================================================
    // Byte-level progress: uploads (size from localIndexByPath)
    // =========================================================================

    @Test
    fun `totalBytes includes upload sizes from localIndexByPath`() =
        runTest {
            val content50 = ByteArray(50) { 0x01 }
            localFs.put("up.bin", content50)
            val indexWithSize = indexEntry("up.bin", remoteId = "rid", sizeBytes = 50L)

            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = listOf(SyncOp.UpdateRemote("up.bin")),
                pair = pair(),
                remoteFilesByPath = emptyMap(),
                localIndexByPath = mapOf("up.bin" to indexWithSize),
                onProgress = { events += it },
            )

            assertEquals(50L, events.last().totalBytes)
        }

    // =========================================================================
    // Fallback to file-count mode when sizes are unknown
    // =========================================================================

    @Test
    fun `totalBytes is zero when no sizes are available (indeterminate fallback)`() =
        runTest {
            val content = "no-size".toByteArray()
            localFs.put("new.txt", content)

            // UploadNew with empty localIndexByPath → size unknown → totalBytes = 0
            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = listOf(SyncOp.UploadNew("new.txt")),
                pair = pair(),
                remoteFilesByPath = emptyMap(),
                localIndexByPath = emptyMap(),
                onProgress = { events += it },
            )

            assertEquals(0L, events.last().totalBytes)
            // filesCompleted / totalFiles should still be correct
            assertEquals(1, events.last().filesCompleted)
            assertEquals(1, events.last().totalFiles)
        }

    // =========================================================================
    // Delete ops do not contribute to totalBytes
    // =========================================================================

    @Test
    fun `delete ops do not inflate totalBytes`() =
        runTest {
            val content = ByteArray(500) { 0xFF.toByte() }
            val remote = seedRemote("keep.bin", content)

            // Mix: 1 download (500 bytes) + 1 delete (0 bytes)
            val deleteIndex = indexEntry("gone.txt", remoteId = "del-id")
            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = listOf(SyncOp.DownloadNew("keep.bin"), SyncOp.DeleteLocal("gone.txt")),
                pair = pair(),
                remoteFilesByPath = mapOf("keep.bin" to remote),
                localIndexByPath = mapOf("gone.txt" to deleteIndex),
                onProgress = { events += it },
            )

            assertEquals(500L, events.last().totalBytes)
        }

    // =========================================================================
    // onProgress still fires after a failed op (error-tolerance)
    // =========================================================================

    @Test
    fun `onProgress fires even when an op fails`() =
        runTest {
            // DownloadNew for a path with no entry in remoteFilesByPath → error collected
            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = listOf(SyncOp.DownloadNew("missing.txt")),
                pair = pair(),
                remoteFilesByPath = emptyMap(), // intentionally missing
                localIndexByPath = emptyMap(),
                onProgress = { events += it },
            )

            assertEquals("progress event fired despite error", 1, events.size)
            assertEquals(1, events[0].filesCompleted)
        }

    @Test
    fun `bytesTransferred does not advance for failed op with known size`() =
        runTest {
            // UpdateRemote has a known expected size from localIndexByPath, but the
            // local file is absent so the op fails before any upload occurs.
            val indexWithSize = indexEntry("missing-local.bin", remoteId = "remote-id", sizeBytes = 123L)

            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = listOf(SyncOp.UpdateRemote("missing-local.bin")),
                pair = pair(),
                remoteFilesByPath = emptyMap(),
                localIndexByPath = mapOf("missing-local.bin" to indexWithSize),
                onProgress = { events += it },
            )

            assertEquals(123L, events.single().totalBytes)
            assertEquals(0L, events.single().bytesTransferred)
        }

    // =========================================================================
    // Conflict ops estimate transfer bytes from the selected resolution path
    // =========================================================================

    @Test
    fun `conflict with PREFER_REMOTE uses remote size for byte progress`() =
        runTest {
            val content = ByteArray(300) { 0x42 }
            val remote = seedRemote("conflict.bin", content)

            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = listOf(SyncOp.Conflict("conflict.bin", localNewerThanRemote = false)),
                pair = pair(conflictPolicy = ConflictPolicy.PREFER_REMOTE),
                remoteFilesByPath = mapOf("conflict.bin" to remote),
                localIndexByPath = emptyMap(),
                onProgress = { events += it },
            )

            assertEquals(300L, events.single().totalBytes)
            assertEquals(300L, events.single().bytesTransferred)
        }

    @Test
    fun `conflict with local-wins policy uses local indexed size for byte progress`() =
        runTest {
            val content = ByteArray(200) { 0x24 }
            val remote = seedRemote("conflict-local.bin", ByteArray(10) { 0x01 })
            localFs.put("conflict-local.bin", content)
            val localIndex = indexEntry(
                "conflict-local.bin",
                remoteId = remote.id,
                sizeBytes = content.size.toLong(),
            )

            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = listOf(SyncOp.Conflict("conflict-local.bin", localNewerThanRemote = true)),
                pair = pair(conflictPolicy = ConflictPolicy.NEWEST_WINS),
                remoteFilesByPath = mapOf("conflict-local.bin" to remote),
                localIndexByPath = mapOf("conflict-local.bin" to localIndex),
                onProgress = { events += it },
            )

            assertEquals(200L, events.single().totalBytes)
            assertEquals(200L, events.single().bytesTransferred)
        }

    // =========================================================================
    // Empty op list
    // =========================================================================

    @Test
    fun `onProgress is not called for empty op list`() =
        runTest {
            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = emptyList(),
                pair = pair(),
                remoteFilesByPath = emptyMap(),
                localIndexByPath = emptyMap(),
                onProgress = { events += it },
            )

            assertTrue("no events for empty batch", events.isEmpty())
        }

    // =========================================================================
    // Mixed success/failure batch — progress fires for every op regardless
    // =========================================================================

    @Test
    fun `filesCompleted advances past failed ops in a mixed success-failure batch`() =
        runTest {
            val content = ByteArray(50) { 0x01 }
            // "good.bin" exists locally; "bad.bin" does not → DownloadNew with missing
            // remote entry will be collected as an error, not abort the batch.
            val goodRemote = seedRemote("good.bin", content)

            val events = mutableListOf<TransferProgress>()
            buildApplier().apply(
                ops = listOf(
                    SyncOp.DownloadNew("good.bin"),
                    SyncOp.DownloadNew("bad.bin"),   // no entry in remoteFilesByPath → error
                    SyncOp.DownloadNew("good.bin"),  // 2nd successful download
                ),
                pair = pair(),
                remoteFilesByPath = mapOf("good.bin" to goodRemote),
                localIndexByPath = emptyMap(),
                onProgress = { events += it },
            )

            // All three ops fire a progress event, regardless of success/failure.
            assertEquals(3, events.size)
            // filesCompleted is strictly monotonically increasing.
            assertEquals(listOf(1, 2, 3), events.map { it.filesCompleted })
            // totalBytes is only the two known-size ops (bad.bin size unknown → 0).
            assertEquals(goodRemote.size!! * 2, events.last().totalBytes)
        }
}
