package com.konarsubhojit.synckro.domain.sync

import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.FileIndexEntry
import com.konarsubhojit.synckro.domain.model.SyncDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDifferTest {

    private fun snap(
        path: String,
        size: Long = 10,
        mtime: Long = 1_000,
        hash: String? = null,
    ) = FileSnapshot(path, size, mtime, hash)

    private fun idx(
        path: String,
        size: Long = 10,
        mtime: Long = 1_000,
        hash: String? = null,
        remoteSize: Long? = size,
        remoteMtime: Long? = mtime,
    ) = FileIndexEntry(
        pairId = 1,
        relativePath = path,
        localSize = size,
        localLastModifiedMs = mtime,
        localHash = hash,
        remoteSize = remoteSize,
        remoteLastModifiedMs = remoteMtime,
    )

    @Test
    fun `initial sync local-only file uploads`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("local.txt")),
            remote = emptyList(),
            lastIndex = emptyList(),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UploadNew("local.txt")), ops)
    }

    @Test
    fun `initial sync remote-only file downloads`() {
        val ops = SyncDiffer.diff(
            local = emptyList(),
            remote = listOf(snap("remote.txt")),
            lastIndex = emptyList(),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.DownloadNew("remote.txt")), ops)
    }

    @Test
    fun `initial sync same file with matching hash is no-op`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("same.txt", size = 10, mtime = 1_000, hash = "abc")),
            remote = listOf(snap("same.txt", size = 999, mtime = 9_999, hash = "abc")),
            lastIndex = emptyList(),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `initial sync same file with matching size and mtime but no hash is no-op`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("same.txt", size = 10, mtime = 1_000)),
            remote = listOf(snap("same.txt", size = 10, mtime = 1_000)),
            lastIndex = emptyList(),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `initial sync same file with different content resolves per policy`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("same.txt", size = 10, mtime = 1_000, hash = "local")),
            remote = listOf(snap("same.txt", size = 10, mtime = 1_000, hash = "remote")),
            lastIndex = emptyList(),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.PREFER_REMOTE,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UpdateLocal("same.txt")), ops)
    }

    @Test
    fun `steady state local modified remote unchanged updates remote`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            remote = listOf(snap("a.txt")),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UpdateRemote("a.txt")), ops)
    }

    @Test
    fun `steady state remote modified local unchanged updates local`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt")),
            remote = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UpdateLocal("a.txt")), ops)
    }

    @Test
    fun `steady state both unchanged is no-op`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt")),
            remote = listOf(snap("a.txt")),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `steady state both deleted is no-op`() {
        val ops = SyncDiffer.diff(
            local = emptyList(),
            remote = emptyList(),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `local deleted remote unchanged deletes remote`() {
        val ops = SyncDiffer.diff(
            local = emptyList(),
            remote = listOf(snap("a.txt")),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.DeleteRemote("a.txt")), ops)
    }

    @Test
    fun `remote deleted local unchanged deletes local`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt")),
            remote = emptyList(),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.DeleteLocal("a.txt")), ops)
    }

    @Test
    fun `local deleted remote modified resolves modify-delete conflict per policy`() {
        val ops = SyncDiffer.diff(
            local = emptyList(),
            remote = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.PREFER_REMOTE,
        )

        assertEquals(listOf<SyncOp>(SyncOp.DownloadNew("a.txt")), ops)
    }

    @Test
    fun `remote deleted local modified resolves modify-delete conflict per policy`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            remote = emptyList(),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.PREFER_LOCAL,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UploadNew("a.txt")), ops)
    }

    @Test
    fun `both modified prefer local updates remote`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            remote = listOf(snap("a.txt", size = 30, mtime = 3_000)),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.PREFER_LOCAL,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UpdateRemote("a.txt")), ops)
    }

    @Test
    fun `both modified prefer remote updates local`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 20, mtime = 3_000)),
            remote = listOf(snap("a.txt", size = 30, mtime = 2_000)),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.PREFER_REMOTE,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UpdateLocal("a.txt")), ops)
    }

    @Test
    fun `both modified newest wins prefers local when newer`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 20, mtime = 3_000)),
            remote = listOf(snap("a.txt", size = 30, mtime = 2_000)),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UpdateRemote("a.txt")), ops)
    }

    @Test
    fun `both modified newest wins prefers remote when newer`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            remote = listOf(snap("a.txt", size = 30, mtime = 3_000)),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UpdateLocal("a.txt")), ops)
    }

    @Test
    fun `both modified keep both emits conflict`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 20, mtime = 3_000)),
            remote = listOf(snap("a.txt", size = 30, mtime = 2_000)),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
        )

        assertEquals(listOf<SyncOp>(SyncOp.Conflict("a.txt", localNewerThanRemote = true)), ops)
    }

    @Test
    fun `bidirectional sync emits upload and download for new files on each side`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("local.txt")),
            remote = listOf(snap("remote.txt")),
            lastIndex = emptyList(),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(
            setOf<SyncOp>(SyncOp.UploadNew("local.txt"), SyncOp.DownloadNew("remote.txt")),
            ops.toSet(),
        )
    }

    @Test
    fun `local to remote sync uploads local-only file and skips remote-only file`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("local.txt")),
            remote = listOf(snap("remote.txt")),
            lastIndex = emptyList(),
            direction = SyncDirection.LOCAL_TO_REMOTE,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UploadNew("local.txt")), ops)
    }

    @Test
    fun `remote to local sync downloads remote-only file and skips local-only file`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("local.txt")),
            remote = listOf(snap("remote.txt")),
            lastIndex = emptyList(),
            direction = SyncDirection.REMOTE_TO_LOCAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.DownloadNew("remote.txt")), ops)
    }

    @Test
    fun `local to remote suppresses remote-originating updates and heals remote deletions`() {
        val updateOps = SyncDiffer.diff(
            local = listOf(snap("a.txt")),
            remote = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.LOCAL_TO_REMOTE,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )
        val deleteOps = SyncDiffer.diff(
            local = listOf(snap("b.txt")),
            remote = emptyList(),
            lastIndex = listOf(idx("b.txt")),
            direction = SyncDirection.LOCAL_TO_REMOTE,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertTrue(updateOps.isEmpty())
        assertEquals(listOf<SyncOp>(SyncOp.UploadNew("b.txt")), deleteOps)
    }

    @Test
    fun `remote to local suppresses local-originating updates and heals local deletions`() {
        val updateOps = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            remote = listOf(snap("a.txt")),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.REMOTE_TO_LOCAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )
        val deleteOps = SyncDiffer.diff(
            local = emptyList(),
            remote = listOf(snap("b.txt")),
            lastIndex = listOf(idx("b.txt")),
            direction = SyncDirection.REMOTE_TO_LOCAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertTrue(updateOps.isEmpty())
        assertEquals(listOf<SyncOp>(SyncOp.DownloadNew("b.txt")), deleteOps)
    }

    @Test
    fun `matching local hash suppresses change detection even when size and mtime differ`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 99, mtime = 9_999, hash = "same")),
            remote = listOf(snap("a.txt", size = 10, mtime = 1_000)),
            lastIndex = listOf(idx("a.txt", size = 10, mtime = 1_000, hash = "same")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `different local hash triggers change detection`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 10, mtime = 1_000, hash = "new")),
            remote = listOf(snap("a.txt", size = 10, mtime = 1_000)),
            lastIndex = listOf(idx("a.txt", size = 10, mtime = 1_000, hash = "old")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UpdateRemote("a.txt")), ops)
    }

    @Test
    fun `missing hash falls back to size and mtime for equality`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 10, mtime = 1_000)),
            remote = listOf(snap("a.txt", size = 10, mtime = 1_000)),
            lastIndex = listOf(idx("a.txt", size = 10, mtime = 1_000)),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `missing hash falls back to size and mtime for change detection`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 11, mtime = 1_000)),
            remote = listOf(snap("a.txt", size = 10, mtime = 1_000)),
            lastIndex = listOf(idx("a.txt", size = 10, mtime = 1_000)),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UpdateRemote("a.txt")), ops)
    }

    @Test
    fun `empty file can be uploaded on initial sync`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("empty.txt", size = 0, mtime = 0)),
            remote = emptyList(),
            lastIndex = emptyList(),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UploadNew("empty.txt")), ops)
    }

    @Test
    fun `multiple files can be reconciled in one diff call`() {
        val ops = SyncDiffer.diff(
            local = listOf(
                snap("local-only.txt"),
                snap("delete-remote.txt", size = 10, mtime = 1_000),
            ),
            remote = listOf(
                snap("remote-only.txt"),
                snap("delete-remote.txt", size = 10, mtime = 1_000),
                snap("delete-local.txt", size = 10, mtime = 1_000),
            ),
            lastIndex = listOf(idx("delete-local.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(
            setOf<SyncOp>(
                SyncOp.UploadNew("local-only.txt"),
                SyncOp.DownloadNew("remote-only.txt"),
                SyncOp.DeleteRemote("delete-local.txt"),
            ),
            ops.toSet(),
        )
    }

    @Test
    fun `special characters in relative paths are preserved in ops`() {
        val path = "sub dir/ümlaut #1.txt"
        val ops = SyncDiffer.diff(
            local = listOf(snap(path)),
            remote = emptyList(),
            lastIndex = emptyList(),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )

        assertEquals(listOf<SyncOp>(SyncOp.UploadNew(path)), ops)
    }
}
