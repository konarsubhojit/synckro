package com.synckro.domain.sync

import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncPair
import com.synckro.providers.fake.FakeCloudProvider
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Tests for the retention-delete safety checks in [SyncOpApplier].
 *
 * Verifies that:
 * - [SyncOp.DeleteLocalRetention] is skipped (with a WARN log) when the index entry
 *   does not have a confirmed remoteId (i.e. the file is not confirmed on remote).
 * - [SyncOp.DeleteLocalRetention] proceeds and logs an INFO event including retention
 *   days when the remote copy is confirmed (remoteId present in index).
 * - [SyncOp.DeleteRemoteRetention] is skipped (with a WARN log) when the local file
 *   cannot be found via [LocalFileAccess.stat].
 * - [SyncOp.DeleteRemoteRetention] proceeds and logs an INFO event including retention
 *   days when the local file exists.
 */
class SyncOpApplierRetentionSafetyTest {
    // -------------------------------------------------------------------------
    // Fake local file access
    // -------------------------------------------------------------------------

    private class InMemoryLocalFileAccess(
        private val nowMs: Long = 5_000L,
    ) : LocalFileAccess {
        private val files = mutableMapOf<String, ByteArray>()

        fun put(
            path: String,
            bytes: ByteArray,
        ) {
            files[path] = bytes
        }

        fun get(path: String): ByteArray? = files[path]

        override fun openRead(path: String): InputStream? = files[path]?.let { ByteArrayInputStream(it) }

        override fun write(
            path: String,
            content: InputStream,
            mimeType: String?,
        ): LocalFileStat {
            val bytes = content.use { it.readBytes() }
            files[path] = bytes
            return LocalFileStat(sizeBytes = bytes.size.toLong(), mtimeMs = nowMs, mimeType = mimeType)
        }

        override fun delete(path: String): Boolean = files.remove(path) != null

        override fun stat(path: String): LocalFileStat? = files[path]?.let { LocalFileStat(sizeBytes = it.size.toLong(), mtimeMs = nowMs) }
    }

    // -------------------------------------------------------------------------
    // Test fixtures
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

    private fun retentionPair(
        retentionDays: Int? = 30,
        direction: SyncDirection = SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
    ) = SyncPair(
        id = 1L,
        displayName = "My Pair",
        localTreeUri = "content://test",
        provider = CloudProviderType.FAKE,
        remoteFolderId = "root",
        direction = direction,
        conflictPolicy = ConflictPolicy.NEWEST_WINS,
        retentionDays = retentionDays,
    )

    private fun indexEntry(
        path: String,
        remoteId: String? = null,
    ) = LocalIndexEntity(
        pairId = 1L,
        relativePath = path,
        sizeBytes = 10L,
        mtimeMs = 1_000L,
        contentHash = null,
        remoteId = remoteId,
    )

    private suspend fun seedRemote(
        name: String,
        content: ByteArray,
    ) = fakeProvider.uploadNew(
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
    // DeleteLocalRetention — safety checks
    // =========================================================================

    @Test
    fun `DeleteLocalRetention is skipped and logs WARN when index has no remoteId`() =
        runTest {
            // Local file exists but no index entry → not confirmed on remote.
            localFs.put("photo.jpg", "data".toByteArray())

            val result =
                buildApplier().apply(
                    ops = listOf(SyncOp.DeleteLocalRetention("photo.jpg")),
                    pair = retentionPair(),
                    remoteFilesByPath = emptyMap(),
                    localIndexByPath = emptyMap(), // no index entry at all
                )

            // Delete must be skipped — not applied.
            assertEquals(0, result.applied)
            assertEquals(0, result.errors.size)
            // Local file must still exist.
            assertEquals("data".toByteArray().toList(), localFs.get("photo.jpg")?.toList())
            // A WARN event must have been emitted.
            coVerify { eventRepo.log(1L, SyncEventLevel.WARN, "SyncOpApplier", match { it.contains("not confirmed") }) }
        }

    @Test
    fun `DeleteLocalRetention is skipped and logs WARN when index entry has null remoteId`() =
        runTest {
            localFs.put("doc.txt", "content".toByteArray())
            val idx = indexEntry("doc.txt", remoteId = null) // explicitly null remoteId

            val result =
                buildApplier().apply(
                    ops = listOf(SyncOp.DeleteLocalRetention("doc.txt")),
                    pair = retentionPair(),
                    remoteFilesByPath = emptyMap(),
                    localIndexByPath = mapOf("doc.txt" to idx),
                )

            assertEquals(0, result.applied)
            assertEquals(0, result.errors.size)
            // File must still be present.
            assertEquals("content".toByteArray().toList(), localFs.get("doc.txt")?.toList())
            coVerify { eventRepo.log(1L, SyncEventLevel.WARN, "SyncOpApplier", match { it.contains("not confirmed") }) }
        }

    @Test
    fun `DeleteLocalRetention deletes file and logs INFO with retention days when remote is confirmed`() =
        runTest {
            localFs.put("old.txt", "old data".toByteArray())
            val remote = seedRemote("old.txt", "old data".toByteArray())
            val idx = indexEntry("old.txt", remoteId = remote.id)

            val result =
                buildApplier().apply(
                    ops = listOf(SyncOp.DeleteLocalRetention("old.txt")),
                    pair = retentionPair(retentionDays = 7),
                    remoteFilesByPath = emptyMap(),
                    localIndexByPath = mapOf("old.txt" to idx),
                )

            assertEquals(1, result.applied)
            assertEquals(0, result.errors.size)
            // Local file must be removed.
            assertEquals(null, localFs.get("old.txt"))
            // INFO event must include retention days info.
            val logSlot = slot<String>()
            coVerify { eventRepo.log(1L, SyncEventLevel.INFO, "SyncOpApplier", capture(logSlot)) }
            val msg = logSlot.captured
            assert(msg.contains("7d")) { "Expected '7d' in log message but got: $msg" }
            assert(msg.contains("local")) { "Expected 'local' in log message but got: $msg" }
            assert(msg.contains("My Pair")) { "Expected pair name in log message but got: $msg" }
        }

    @Test
    fun `DeleteLocalRetention log message includes pair displayName`() =
        runTest {
            val remote = seedRemote("file.txt", "bytes".toByteArray())
            localFs.put("file.txt", "bytes".toByteArray())
            val idx = indexEntry("file.txt", remoteId = remote.id)

            buildApplier().apply(
                ops = listOf(SyncOp.DeleteLocalRetention("file.txt")),
                pair = retentionPair(retentionDays = 30),
                remoteFilesByPath = emptyMap(),
                localIndexByPath = mapOf("file.txt" to idx),
            )

            coVerify { eventRepo.log(1L, SyncEventLevel.INFO, "SyncOpApplier", match { it.contains("My Pair") }) }
        }

    // =========================================================================
    // DeleteRemoteRetention — safety checks
    // =========================================================================

    @Test
    fun `DeleteRemoteRetention is skipped and logs WARN when local file does not exist`() =
        runTest {
            val remote = seedRemote("remote.txt", "data".toByteArray())
            val idx = indexEntry("remote.txt", remoteId = remote.id)
            // No local file seeded — localFs.stat("remote.txt") returns null.

            val result =
                buildApplier().apply(
                    ops = listOf(SyncOp.DeleteRemoteRetention("remote.txt")),
                    pair = retentionPair(direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS),
                    remoteFilesByPath = emptyMap(),
                    localIndexByPath = mapOf("remote.txt" to idx),
                )

            assertEquals(0, result.applied)
            assertEquals(0, result.errors.size)
            // Remote file must still exist.
            val remoteChildren = fakeProvider.list("root")
            assertEquals(1, remoteChildren.size)
            // A WARN event must have been emitted.
            coVerify { eventRepo.log(1L, SyncEventLevel.WARN, "SyncOpApplier", match { it.contains("local copy not found") }) }
        }

    @Test
    fun `DeleteRemoteRetention deletes remote file and logs INFO with retention days when local exists`() =
        runTest {
            val remote = seedRemote("archive.zip", "zip".toByteArray())
            val idx = indexEntry("archive.zip", remoteId = remote.id)
            localFs.put("archive.zip", "zip".toByteArray())

            val result =
                buildApplier().apply(
                    ops = listOf(SyncOp.DeleteRemoteRetention("archive.zip")),
                    pair = retentionPair(retentionDays = 14, direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS),
                    remoteFilesByPath = emptyMap(),
                    localIndexByPath = mapOf("archive.zip" to idx),
                )

            assertEquals(1, result.applied)
            assertEquals(0, result.errors.size)
            // Remote file must be gone.
            val remoteChildren = fakeProvider.list("root")
            assertEquals(0, remoteChildren.size)
            // INFO event must include retention days info.
            val logSlot = slot<String>()
            coVerify { eventRepo.log(1L, SyncEventLevel.INFO, "SyncOpApplier", capture(logSlot)) }
            val msg = logSlot.captured
            assert(msg.contains("14d")) { "Expected '14d' in log message but got: $msg" }
            assert(msg.contains("remote")) { "Expected 'remote' in log message but got: $msg" }
            assert(msg.contains("My Pair")) { "Expected pair name in log message but got: $msg" }
        }

    @Test
    fun `DeleteRemoteRetention log message includes pair displayName`() =
        runTest {
            val remote = seedRemote("data.bin", "bytes".toByteArray())
            val idx = indexEntry("data.bin", remoteId = remote.id)
            localFs.put("data.bin", "bytes".toByteArray())

            buildApplier().apply(
                ops = listOf(SyncOp.DeleteRemoteRetention("data.bin")),
                pair = retentionPair(retentionDays = 30, direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS),
                remoteFilesByPath = emptyMap(),
                localIndexByPath = mapOf("data.bin" to idx),
            )

            coVerify { eventRepo.log(1L, SyncEventLevel.INFO, "SyncOpApplier", match { it.contains("My Pair") }) }
        }
}
