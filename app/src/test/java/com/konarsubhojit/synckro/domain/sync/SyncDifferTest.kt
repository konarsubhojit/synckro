package com.konarsubhojit.synckro.domain.sync

import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.FileIndexEntry
import com.konarsubhojit.synckro.domain.model.SyncDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDifferTest {

    private fun snap(path: String, size: Long = 10, mtime: Long = 1_000) =
        FileSnapshot(path, size, mtime)

    private fun idx(path: String, size: Long = 10, mtime: Long = 1_000, remoteSize: Long? = 10, remoteMtime: Long? = 1_000) =
        FileIndexEntry(
            pairId = 1,
            relativePath = path,
            localSize = size,
            localLastModifiedMs = mtime,
            remoteSize = remoteSize,
            remoteLastModifiedMs = remoteMtime,
        )

    @Test
    fun `new local file triggers upload when bidirectional`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt")),
            remote = emptyList(),
            lastIndex = emptyList(),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )
        assertEquals(listOf<SyncOp>(SyncOp.UploadNew("a.txt")), ops)
    }

    @Test
    fun `new remote file triggers download when bidirectional`() {
        val ops = SyncDiffer.diff(
            local = emptyList(),
            remote = listOf(snap("b.txt")),
            lastIndex = emptyList(),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )
        assertEquals(listOf<SyncOp>(SyncOp.DownloadNew("b.txt")), ops)
    }

    @Test
    fun `unchanged file produces no ops`() {
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
    fun `local edit only triggers UpdateRemote`() {
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
    fun `remote edit only triggers UpdateLocal`() {
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
    fun `local deletion propagates as DeleteRemote`() {
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
    fun `both sides changed resolves by newest wins - local newer`() {
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
    fun `both sides changed resolves by newest wins - remote newer`() {
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
    fun `keep both policy yields Conflict op`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 20, mtime = 3_000)),
            remote = listOf(snap("a.txt", size = 30, mtime = 2_000)),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
        )
        assertEquals(1, ops.size)
        assertTrue(ops.first() is SyncOp.Conflict)
    }

    @Test
    fun `LOCAL_TO_REMOTE direction ignores remote-originating changes`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt")),
            remote = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.LOCAL_TO_REMOTE,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )
        assertTrue("expected no ops for remote-only change in LOCAL_TO_REMOTE", ops.isEmpty())
    }

    @Test
    fun `initial sync with identical snapshots on both sides is a no-op`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 10, mtime = 1_000)),
            remote = listOf(snap("a.txt", size = 10, mtime = 1_000)),
            lastIndex = emptyList(),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )
        assertTrue("expected no ops when both sides are already equivalent", ops.isEmpty())
    }

    @Test
    fun `initial sync with divergent snapshots falls through conflict resolution`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 20, mtime = 3_000)),
            remote = listOf(snap("a.txt", size = 30, mtime = 2_000)),
            lastIndex = emptyList(),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )
        assertEquals(listOf<SyncOp>(SyncOp.UpdateRemote("a.txt")), ops)
    }

    @Test
    fun `PREFER_LOCAL always updates remote on conflict`() {
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
    fun `PREFER_REMOTE always updates local on conflict`() {
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
    fun `NEWEST_WINS prefers local on exact-tie timestamps`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            remote = listOf(snap("a.txt", size = 30, mtime = 2_000)),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )
        assertEquals(listOf<SyncOp>(SyncOp.UpdateRemote("a.txt")), ops)
    }

    @Test
    fun `local changed remote deleted prefers local by recreating remote`() {
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
    fun `local changed remote deleted prefers remote by deleting local`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            remote = emptyList(),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.PREFER_REMOTE,
        )
        assertEquals(listOf<SyncOp>(SyncOp.DeleteLocal("a.txt")), ops)
    }

    @Test
    fun `local changed remote deleted uses changed side for newest wins`() {
        val ops = SyncDiffer.diff(
            local = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            remote = emptyList(),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )
        assertEquals(listOf<SyncOp>(SyncOp.UploadNew("a.txt")), ops)
    }

    @Test
    fun `remote changed local deleted prefers remote by recreating local`() {
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
    fun `remote changed local deleted prefers local by deleting remote`() {
        val ops = SyncDiffer.diff(
            local = emptyList(),
            remote = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.PREFER_LOCAL,
        )
        assertEquals(listOf<SyncOp>(SyncOp.DeleteRemote("a.txt")), ops)
    }

    @Test
    fun `remote changed local deleted uses changed side for newest wins`() {
        val ops = SyncDiffer.diff(
            local = emptyList(),
            remote = listOf(snap("a.txt", size = 20, mtime = 2_000)),
            lastIndex = listOf(idx("a.txt")),
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
        )
        assertEquals(listOf<SyncOp>(SyncOp.DownloadNew("a.txt")), ops)
    }
}
