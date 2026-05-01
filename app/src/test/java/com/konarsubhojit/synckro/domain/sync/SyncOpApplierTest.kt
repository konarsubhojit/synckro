package com.konarsubhojit.synckro.domain.sync

import com.konarsubhojit.synckro.data.local.dao.LocalIndexDao
import com.konarsubhojit.synckro.data.local.entity.LocalIndexEntity
import com.konarsubhojit.synckro.data.repository.ConflictRepository
import com.konarsubhojit.synckro.data.repository.SyncEventRepository
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.ConflictRecord
import com.konarsubhojit.synckro.domain.model.SyncDirection
import com.konarsubhojit.synckro.domain.model.SyncEventLevel
import com.konarsubhojit.synckro.domain.model.SyncPair
import com.konarsubhojit.synckro.domain.provider.CloudProviderException
import com.konarsubhojit.synckro.domain.provider.RemoteFile
import com.konarsubhojit.synckro.providers.fake.FakeCloudProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SyncOpApplier] covering all [SyncOp] types and conflict policies.
 *
 * The cloud provider layer is backed by [FakeCloudProvider] (in-memory, no auth).
 * Local file I/O is handled by [InMemoryLocalFileAccess], a pure JVM fake.
 * [LocalIndexDao], [ConflictRepository], and [SyncEventRepository] are mocked with MockK.
 */
class SyncOpApplierTest {

    // -------------------------------------------------------------------------
    // Fake local file access
    // -------------------------------------------------------------------------

    /**
     * Pure in-memory [LocalFileAccess] backed by a [MutableMap].
     * Stores files as raw [ByteArray] and exposes a fixed [mtimeMs] for predictable tests.
     */
    private class InMemoryLocalFileAccess(
        private val nowMs: Long = 5_000L,
    ) : LocalFileAccess {
        private val files = mutableMapOf<String, ByteArray>()

        /** Seed a file with known content before the test. */
        fun put(path: String, bytes: ByteArray) {
            files[path] = bytes
        }

        fun get(path: String): ByteArray? = files[path]

        override fun openRead(path: String): InputStream? =
            files[path]?.let { ByteArrayInputStream(it) }

        override fun write(path: String, content: InputStream, mimeType: String?): LocalFileStat {
            val bytes = content.use { it.readBytes() }
            files[path] = bytes
            return LocalFileStat(sizeBytes = bytes.size.toLong(), mtimeMs = nowMs, mimeType = mimeType)
        }

        override fun delete(path: String): Boolean = files.remove(path) != null

        override fun stat(path: String): LocalFileStat? =
            files[path]?.let { LocalFileStat(sizeBytes = it.size.toLong(), mtimeMs = nowMs) }
    }

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private lateinit var fakeProvider: FakeCloudProvider
    private lateinit var localFs: InMemoryLocalFileAccess
    private lateinit var localIndexDao: LocalIndexDao
    private lateinit var conflictRepo: ConflictRepository
    private lateinit var eventRepo: SyncEventRepository

    private fun buildApplier() = SyncOpApplier(
        provider = fakeProvider,
        localIndexDao = localIndexDao,
        conflictRepository = conflictRepo,
        eventRepository = eventRepo,
        localFileAccess = localFs,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun pair(
        conflictPolicy: ConflictPolicy = ConflictPolicy.NEWEST_WINS,
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
    ) = SyncPair(
        id = 1L,
        displayName = "Test pair",
        localTreeUri = "content://test",
        provider = CloudProviderType.FAKE,
        remoteFolderId = "root",
        direction = direction,
        conflictPolicy = conflictPolicy,
    )

    private fun indexEntry(
        path: String,
        remoteId: String? = null,
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

    /** Seeds a file into [FakeCloudProvider] and returns its [RemoteFile]. */
    private suspend fun seedRemote(name: String, content: ByteArray): RemoteFile {
        return fakeProvider.uploadNew(
            parentId = "root",
            name = name,
            content = ByteArrayInputStream(content),
            size = content.size.toLong(),
            mimeType = "application/octet-stream",
        )
    }

    @Before
    fun setUp() {
        fakeProvider = FakeCloudProvider()
        localFs = InMemoryLocalFileAccess()
        localIndexDao = mockk(relaxed = true)
        conflictRepo = mockk(relaxed = true)
        eventRepo = mockk(relaxed = true)
    }

    // =========================================================================
    // UploadNew
    // =========================================================================

    @Test
    fun `UploadNew uploads local file to provider`() = runTest {
        val content = "hello upload".toByteArray()
        localFs.put("docs/hello.txt", content)

        val result = buildApplier().apply(
            ops = listOf(SyncOp.UploadNew("docs/hello.txt")),
            pair = pair(),
            remoteFilesByPath = emptyMap(),
            localIndexByPath = emptyMap(),
        )

        assertEquals(1, result.applied)
        assertEquals(0, result.errors.size)
        // Verify provider received the bytes
        val remotes = fakeProvider.list("root")
        assertEquals(1, remotes.size)
        assertEquals("hello.txt", remotes.single().name)
    }

    @Test
    fun `UploadNew upserts local_index with remoteId`() = runTest {
        val content = "data".toByteArray()
        localFs.put("file.txt", content)

        buildApplier().apply(
            ops = listOf(SyncOp.UploadNew("file.txt")),
            pair = pair(),
            remoteFilesByPath = emptyMap(),
            localIndexByPath = emptyMap(),
        )

        val slot = slot<LocalIndexEntity>()
        coVerify { localIndexDao.upsert(capture(slot)) }
        assertNotNull(slot.captured.remoteId)
        assertEquals("file.txt", slot.captured.relativePath)
        assertEquals(1L, slot.captured.pairId)
    }

    @Test
    fun `UploadNew emits INFO event on success`() = runTest {
        localFs.put("a.txt", "data".toByteArray())

        buildApplier().apply(
            ops = listOf(SyncOp.UploadNew("a.txt")),
            pair = pair(),
            remoteFilesByPath = emptyMap(),
            localIndexByPath = emptyMap(),
        )

        coVerify { eventRepo.log(1L, SyncEventLevel.INFO, "SyncOpApplier", any()) }
    }

    @Test
    fun `UploadNew records error when local file missing`() = runTest {
        // No file seeded in localFs
        val result = buildApplier().apply(
            ops = listOf(SyncOp.UploadNew("missing.txt")),
            pair = pair(),
            remoteFilesByPath = emptyMap(),
            localIndexByPath = emptyMap(),
        )

        assertEquals(0, result.applied)
        assertEquals(1, result.errors.size)
    }

    // =========================================================================
    // DownloadNew
    // =========================================================================

    @Test
    fun `DownloadNew downloads remote file to local`() = runTest {
        val content = "remote content".toByteArray()
        val remote = seedRemote("remote.txt", content)

        val result = buildApplier().apply(
            ops = listOf(SyncOp.DownloadNew("remote.txt")),
            pair = pair(),
            remoteFilesByPath = mapOf("remote.txt" to remote),
            localIndexByPath = emptyMap(),
        )

        assertEquals(1, result.applied)
        assertTrue(result.errors.isEmpty())
        val local = localFs.get("remote.txt")
        assertNotNull(local)
        assertEquals(content.toList(), local!!.toList())
    }

    @Test
    fun `DownloadNew upserts local_index with correct remoteId`() = runTest {
        val remote = seedRemote("file.txt", "abc".toByteArray())

        buildApplier().apply(
            ops = listOf(SyncOp.DownloadNew("file.txt")),
            pair = pair(),
            remoteFilesByPath = mapOf("file.txt" to remote),
            localIndexByPath = emptyMap(),
        )

        val slot = slot<LocalIndexEntity>()
        coVerify { localIndexDao.upsert(capture(slot)) }
        assertEquals(remote.id, slot.captured.remoteId)
        assertEquals("file.txt", slot.captured.relativePath)
    }

    @Test
    fun `DownloadNew records error when remote not in snapshot`() = runTest {
        val result = buildApplier().apply(
            ops = listOf(SyncOp.DownloadNew("ghost.txt")),
            pair = pair(),
            remoteFilesByPath = emptyMap(),
            localIndexByPath = emptyMap(),
        )

        assertEquals(0, result.applied)
        assertEquals(1, result.errors.size)
    }

    // =========================================================================
    // UpdateRemote
    // =========================================================================

    @Test
    fun `UpdateRemote overwrites remote content`() = runTest {
        val original = seedRemote("doc.txt", "old content".toByteArray())
        val newContent = "updated content".toByteArray()
        localFs.put("doc.txt", newContent)
        val idx = indexEntry("doc.txt", remoteId = original.id)

        buildApplier().apply(
            ops = listOf(SyncOp.UpdateRemote("doc.txt")),
            pair = pair(),
            remoteFilesByPath = mapOf("doc.txt" to original),
            localIndexByPath = mapOf("doc.txt" to idx),
        )

        val downloaded = fakeProvider.download(original.id).use { it.readBytes() }
        assertEquals(newContent.toList(), downloaded.toList())
    }

    @Test
    fun `UpdateRemote upserts index preserving pairId and relativePath`() = runTest {
        val remote = seedRemote("f.txt", "x".toByteArray())
        localFs.put("f.txt", "y".toByteArray())
        val idx = indexEntry("f.txt", remoteId = remote.id)

        buildApplier().apply(
            ops = listOf(SyncOp.UpdateRemote("f.txt")),
            pair = pair(),
            remoteFilesByPath = mapOf("f.txt" to remote),
            localIndexByPath = mapOf("f.txt" to idx),
        )

        val slot = slot<LocalIndexEntity>()
        coVerify { localIndexDao.upsert(capture(slot)) }
        assertEquals(1L, slot.captured.pairId)
        assertEquals("f.txt", slot.captured.relativePath)
        assertEquals(remote.id, slot.captured.remoteId)
    }

    @Test
    fun `UpdateRemote records error when no index entry`() = runTest {
        localFs.put("x.txt", "data".toByteArray())

        val result = buildApplier().apply(
            ops = listOf(SyncOp.UpdateRemote("x.txt")),
            pair = pair(),
            remoteFilesByPath = emptyMap(),
            localIndexByPath = emptyMap(),
        )

        assertEquals(0, result.applied)
        assertEquals(1, result.errors.size)
    }

    // =========================================================================
    // UpdateLocal
    // =========================================================================

    @Test
    fun `UpdateLocal overwrites local content`() = runTest {
        localFs.put("note.txt", "old".toByteArray())
        val remote = seedRemote("note.txt", "newer version".toByteArray())

        buildApplier().apply(
            ops = listOf(SyncOp.UpdateLocal("note.txt")),
            pair = pair(),
            remoteFilesByPath = mapOf("note.txt" to remote),
            localIndexByPath = emptyMap(),
        )

        val local = localFs.get("note.txt")
        assertNotNull(local)
        assertEquals("newer version", local!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `UpdateLocal upserts index with correct remoteId`() = runTest {
        val remote = seedRemote("n.txt", "v2".toByteArray())
        localFs.put("n.txt", "v1".toByteArray())

        buildApplier().apply(
            ops = listOf(SyncOp.UpdateLocal("n.txt")),
            pair = pair(),
            remoteFilesByPath = mapOf("n.txt" to remote),
            localIndexByPath = emptyMap(),
        )

        val slot = slot<LocalIndexEntity>()
        coVerify { localIndexDao.upsert(capture(slot)) }
        assertEquals(remote.id, slot.captured.remoteId)
    }

    // =========================================================================
    // DeleteRemote
    // =========================================================================

    @Test
    fun `DeleteRemote removes file from provider`() = runTest {
        val remote = seedRemote("bye.txt", "x".toByteArray())
        val idx = indexEntry("bye.txt", remoteId = remote.id)

        val result = buildApplier().apply(
            ops = listOf(SyncOp.DeleteRemote("bye.txt")),
            pair = pair(),
            remoteFilesByPath = mapOf("bye.txt" to remote),
            localIndexByPath = mapOf("bye.txt" to idx),
        )

        assertEquals(1, result.applied)
        assertTrue(fakeProvider.list("root").isEmpty())
    }

    @Test
    fun `DeleteRemote deletes local_index row`() = runTest {
        val remote = seedRemote("r.txt", "d".toByteArray())
        val idx = indexEntry("r.txt", remoteId = remote.id)

        buildApplier().apply(
            ops = listOf(SyncOp.DeleteRemote("r.txt")),
            pair = pair(),
            remoteFilesByPath = mapOf("r.txt" to remote),
            localIndexByPath = mapOf("r.txt" to idx),
        )

        coVerify { localIndexDao.delete(1L, "r.txt") }
    }

    @Test
    fun `DeleteRemote records error when no index entry`() = runTest {
        val result = buildApplier().apply(
            ops = listOf(SyncOp.DeleteRemote("missing.txt")),
            pair = pair(),
            remoteFilesByPath = emptyMap(),
            localIndexByPath = emptyMap(),
        )

        assertEquals(0, result.applied)
        assertEquals(1, result.errors.size)
    }

    // =========================================================================
    // DeleteLocal
    // =========================================================================

    @Test
    fun `DeleteLocal removes local file`() = runTest {
        localFs.put("old.txt", "data".toByteArray())

        val result = buildApplier().apply(
            ops = listOf(SyncOp.DeleteLocal("old.txt")),
            pair = pair(),
            remoteFilesByPath = emptyMap(),
            localIndexByPath = emptyMap(),
        )

        assertEquals(1, result.applied)
        assertFalse(localFs.delete("old.txt")) // already gone
    }

    @Test
    fun `DeleteLocal deletes local_index row`() = runTest {
        localFs.put("gone.txt", "x".toByteArray())

        buildApplier().apply(
            ops = listOf(SyncOp.DeleteLocal("gone.txt")),
            pair = pair(),
            remoteFilesByPath = emptyMap(),
            localIndexByPath = emptyMap(),
        )

        coVerify { localIndexDao.delete(1L, "gone.txt") }
    }

    // =========================================================================
    // Conflict — KEEP_BOTH
    // =========================================================================

    @Test
    fun `Conflict with KEEP_BOTH inserts ConflictRecord`() = runTest {
        val remote = seedRemote("shared.txt", "remote".toByteArray())
        localFs.put("shared.txt", "local".toByteArray())

        val recordSlot = slot<ConflictRecord>()
        coEvery { conflictRepo.insert(capture(recordSlot)) } returns 1L

        val result = buildApplier().apply(
            ops = listOf(SyncOp.Conflict("shared.txt", localNewerThanRemote = true)),
            pair = pair(ConflictPolicy.KEEP_BOTH),
            remoteFilesByPath = mapOf("shared.txt" to remote),
            localIndexByPath = emptyMap(),
        )

        assertEquals(0, result.applied)
        assertEquals(1, result.conflicts)
        coVerify { conflictRepo.insert(any()) }
        assertEquals("shared.txt", recordSlot.captured.relativePath)
        assertEquals(1L, recordSlot.captured.pairId)
    }

    @Test
    fun `Conflict with KEEP_BOTH does NOT modify local or remote file`() = runTest {
        val original = "remote original".toByteArray()
        val remote = seedRemote("c.txt", original)
        localFs.put("c.txt", "local original".toByteArray())
        coEvery { conflictRepo.insert(any()) } returns 1L

        buildApplier().apply(
            ops = listOf(SyncOp.Conflict("c.txt", localNewerThanRemote = true)),
            pair = pair(ConflictPolicy.KEEP_BOTH),
            remoteFilesByPath = mapOf("c.txt" to remote),
            localIndexByPath = emptyMap(),
        )

        // Remote unchanged
        val downloaded = fakeProvider.download(remote.id).use { it.readBytes() }
        assertEquals(original.toList(), downloaded.toList())
        // Local unchanged
        val local = localFs.get("c.txt")
        assertEquals("local original", local!!.toString(Charsets.UTF_8))
    }

    // =========================================================================
    // Conflict — PREFER_LOCAL
    // =========================================================================

    @Test
    fun `Conflict with PREFER_LOCAL overwrites remote with local content`() = runTest {
        val remote = seedRemote("f.txt", "remote version".toByteArray())
        localFs.put("f.txt", "local wins".toByteArray())
        val idx = indexEntry("f.txt", remoteId = remote.id)

        val result = buildApplier().apply(
            ops = listOf(SyncOp.Conflict("f.txt", localNewerThanRemote = false)),
            pair = pair(ConflictPolicy.PREFER_LOCAL),
            remoteFilesByPath = mapOf("f.txt" to remote),
            localIndexByPath = mapOf("f.txt" to idx),
        )

        assertEquals(1, result.conflicts)
        val downloaded = fakeProvider.download(remote.id).use { it.readBytes() }
        assertEquals("local wins", downloaded.toString(Charsets.UTF_8))
    }

    @Test
    fun `Conflict with PREFER_LOCAL emits INFO event`() = runTest {
        val remote = seedRemote("g.txt", "r".toByteArray())
        localFs.put("g.txt", "l".toByteArray())
        val idx = indexEntry("g.txt", remoteId = remote.id)

        buildApplier().apply(
            ops = listOf(SyncOp.Conflict("g.txt", localNewerThanRemote = true)),
            pair = pair(ConflictPolicy.PREFER_LOCAL),
            remoteFilesByPath = mapOf("g.txt" to remote),
            localIndexByPath = mapOf("g.txt" to idx),
        )

        coVerify {
            eventRepo.log(1L, SyncEventLevel.INFO, "SyncOpApplier", match { it.contains("local wins") })
        }
    }

    // =========================================================================
    // Conflict — PREFER_REMOTE
    // =========================================================================

    @Test
    fun `Conflict with PREFER_REMOTE overwrites local with remote content`() = runTest {
        val remote = seedRemote("p.txt", "remote wins".toByteArray())
        localFs.put("p.txt", "local version".toByteArray())

        val result = buildApplier().apply(
            ops = listOf(SyncOp.Conflict("p.txt", localNewerThanRemote = true)),
            pair = pair(ConflictPolicy.PREFER_REMOTE),
            remoteFilesByPath = mapOf("p.txt" to remote),
            localIndexByPath = emptyMap(),
        )

        assertEquals(1, result.conflicts)
        val local = localFs.get("p.txt")
        assertEquals("remote wins", local!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `Conflict with PREFER_REMOTE emits INFO event`() = runTest {
        val remote = seedRemote("q.txt", "rv".toByteArray())
        localFs.put("q.txt", "lv".toByteArray())

        buildApplier().apply(
            ops = listOf(SyncOp.Conflict("q.txt", localNewerThanRemote = false)),
            pair = pair(ConflictPolicy.PREFER_REMOTE),
            remoteFilesByPath = mapOf("q.txt" to remote),
            localIndexByPath = emptyMap(),
        )

        coVerify {
            eventRepo.log(1L, SyncEventLevel.INFO, "SyncOpApplier", match { it.contains("remote wins") })
        }
    }

    // =========================================================================
    // Conflict — NEWEST_WINS
    // =========================================================================

    @Test
    fun `Conflict with NEWEST_WINS and localNewer uploads local`() = runTest {
        val remote = seedRemote("t.txt", "old remote".toByteArray())
        localFs.put("t.txt", "new local".toByteArray())
        val idx = indexEntry("t.txt", remoteId = remote.id)

        buildApplier().apply(
            ops = listOf(SyncOp.Conflict("t.txt", localNewerThanRemote = true)),
            pair = pair(ConflictPolicy.NEWEST_WINS),
            remoteFilesByPath = mapOf("t.txt" to remote),
            localIndexByPath = mapOf("t.txt" to idx),
        )

        val downloaded = fakeProvider.download(remote.id).use { it.readBytes() }
        assertEquals("new local", downloaded.toString(Charsets.UTF_8))
    }

    @Test
    fun `Conflict with NEWEST_WINS and remoteNewer downloads remote`() = runTest {
        val remote = seedRemote("u.txt", "new remote".toByteArray())
        localFs.put("u.txt", "old local".toByteArray())

        buildApplier().apply(
            ops = listOf(SyncOp.Conflict("u.txt", localNewerThanRemote = false)),
            pair = pair(ConflictPolicy.NEWEST_WINS),
            remoteFilesByPath = mapOf("u.txt" to remote),
            localIndexByPath = emptyMap(),
        )

        val local = localFs.get("u.txt")
        assertEquals("new remote", local!!.toString(Charsets.UTF_8))
    }

    // =========================================================================
    // Batch — partial failure does not abort remaining ops
    // =========================================================================

    @Test
    fun `mid-batch error leaves index consistent with applied ops`() = runTest {
        // Op 1: UploadNew("ok.txt") — should succeed
        // Op 2: UploadNew("missing.txt") — will fail (file not in localFs)
        // Op 3: DownloadNew("another.txt") — should succeed
        val content = "existing".toByteArray()
        localFs.put("ok.txt", content)
        val remoteAnother = seedRemote("another.txt", "remote data".toByteArray())

        val result = buildApplier().apply(
            ops = listOf(
                SyncOp.UploadNew("ok.txt"),
                SyncOp.UploadNew("missing.txt"),
                SyncOp.DownloadNew("another.txt"),
            ),
            pair = pair(),
            remoteFilesByPath = mapOf("another.txt" to remoteAnother),
            localIndexByPath = emptyMap(),
        )

        assertEquals(2, result.applied)
        assertEquals(1, result.errors.size)
        // Index was upserted for the two successful ops
        coVerify(exactly = 2) { localIndexDao.upsert(any()) }
    }

    @Test
    fun `terminal auth exception propagates immediately`() = runTest {
        // Replace provider with one that always throws AuthenticationRequired
        val failingProvider = mockk<com.konarsubhojit.synckro.domain.provider.CloudProvider>(relaxed = true)
        coEvery {
            failingProvider.uploadNew(any(), any(), any(), any(), any())
        } throws CloudProviderException.AuthenticationRequired("token expired")
        localFs.put("x.txt", "data".toByteArray())

        val applier = SyncOpApplier(
            provider = failingProvider,
            localIndexDao = localIndexDao,
            conflictRepository = conflictRepo,
            eventRepository = eventRepo,
            localFileAccess = localFs,
            ioDispatcher = Dispatchers.Unconfined,
        )

        var threw = false
        try {
            applier.apply(
                ops = listOf(SyncOp.UploadNew("x.txt")),
                pair = pair(),
                remoteFilesByPath = emptyMap(),
                localIndexByPath = emptyMap(),
            )
        } catch (e: CloudProviderException.AuthenticationRequired) {
            threw = true
        }
        assertTrue(threw)
    }

    // =========================================================================
    // Retry WARN events
    // =========================================================================

    @Test
    fun `DownloadNew emits WARN when provider retries`() = runTest {
        val remote = fakeProvider.uploadNew(
            "root", "retry.txt",
            ByteArrayInputStream("data".toByteArray()), 4, null,
        )

        // Wrap fakeProvider to fail on the first download attempt
        var callCount = 0
        val retryingProvider = mockk<com.konarsubhojit.synckro.domain.provider.CloudProvider>(relaxed = true)
        coEvery { retryingProvider.download(remote.id) } coAnswers {
            callCount++
            if (callCount < 2) throw RuntimeException("transient error")
            fakeProvider.download(remote.id)
        }

        val applier = SyncOpApplier(
            provider = retryingProvider,
            localIndexDao = localIndexDao,
            conflictRepository = conflictRepo,
            eventRepository = eventRepo,
            localFileAccess = localFs,
            ioDispatcher = Dispatchers.Unconfined,
        )

        applier.apply(
            ops = listOf(SyncOp.DownloadNew("retry.txt")),
            pair = pair(),
            remoteFilesByPath = mapOf("retry.txt" to remote),
            localIndexByPath = emptyMap(),
        )

        coVerify {
            eventRepo.log(1L, SyncEventLevel.WARN, "SyncOpApplier", any())
        }
    }

    // =========================================================================
    // ApplyResult counts
    // =========================================================================

    @Test
    fun `empty ops list returns zero applied and zero conflicts`() = runTest {
        val result = buildApplier().apply(
            ops = emptyList(),
            pair = pair(),
            remoteFilesByPath = emptyMap(),
            localIndexByPath = emptyMap(),
        )

        assertEquals(0, result.applied)
        assertEquals(0, result.conflicts)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `multiple successful ops all counted`() = runTest {
        localFs.put("a.txt", "a".toByteArray())
        localFs.put("b.txt", "b".toByteArray())

        val result = buildApplier().apply(
            ops = listOf(
                SyncOp.UploadNew("a.txt"),
                SyncOp.UploadNew("b.txt"),
            ),
            pair = pair(),
            remoteFilesByPath = emptyMap(),
            localIndexByPath = emptyMap(),
        )

        assertEquals(2, result.applied)
        assertEquals(0, result.conflicts)
        assertTrue(result.errors.isEmpty())
    }
}
