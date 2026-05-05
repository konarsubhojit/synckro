package com.synckro.domain.sync

import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.FileIndexEntry
import com.synckro.domain.model.SyncDirection
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
        remoteId: String? = null,
    ) = FileIndexEntry(
        pairId = 1,
        relativePath = path,
        localSize = size,
        localLastModifiedMs = mtime,
        localHash = hash,
        remoteSize = remoteSize,
        remoteLastModifiedMs = remoteMtime,
        remoteId = remoteId,
    )

    @Test
    fun `initial sync local-only file uploads`() {
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val updateOps =
            SyncDiffer.diff(
                local = listOf(snap("a.txt")),
                remote = listOf(snap("a.txt", size = 20, mtime = 2_000)),
                lastIndex = listOf(idx("a.txt")),
                direction = SyncDirection.LOCAL_TO_REMOTE,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )
        val deleteOps =
            SyncDiffer.diff(
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
        val updateOps =
            SyncDiffer.diff(
                local = listOf(snap("a.txt", size = 20, mtime = 2_000)),
                remote = listOf(snap("a.txt")),
                lastIndex = listOf(idx("a.txt")),
                direction = SyncDirection.REMOTE_TO_LOCAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )
        val deleteOps =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
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
        val ops =
            SyncDiffer.diff(
                local =
                    listOf(
                        snap("local-only.txt"),
                        snap("delete-remote.txt", size = 10, mtime = 1_000),
                    ),
                remote =
                    listOf(
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
        val ops =
            SyncDiffer.diff(
                local = listOf(snap(path)),
                remote = emptyList(),
                lastIndex = emptyList(),
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(listOf<SyncOp>(SyncOp.UploadNew(path)), ops)
    }

    // -------------------------------------------------------------------------
    // Rename detection
    // -------------------------------------------------------------------------

    @Test
    fun `local rename via delete-old add-new with same hash emits delete-remote and upload-new`() {
        // A file was renamed locally: "old.txt" → "new.txt" (same content hash).
        // SyncDiffer handles each path independently, so a rename surfaces as two
        // independent ops: DeleteRemote for the old path and UploadNew for the new path.
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("new.txt", size = 10, mtime = 2_000, hash = "same-hash")),
                remote = listOf(snap("old.txt", size = 10, mtime = 1_000, hash = "same-hash")),
                lastIndex = listOf(idx("old.txt", size = 10, mtime = 1_000, hash = "same-hash")),
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(
            setOf<SyncOp>(
                SyncOp.DeleteRemote("old.txt"),
                SyncOp.UploadNew("new.txt"),
            ),
            ops.toSet(),
        )
    }

    @Test
    fun `remote rename via delete-old add-new emits delete-local and download-new`() {
        // A file was renamed remotely: "old.txt" → "new.txt".
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("old.txt", size = 10, mtime = 1_000)),
                remote = listOf(snap("new.txt", size = 10, mtime = 2_000)),
                lastIndex = listOf(idx("old.txt", size = 10, mtime = 1_000)),
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(
            setOf<SyncOp>(
                SyncOp.DeleteLocal("old.txt"),
                SyncOp.DownloadNew("new.txt"),
            ),
            ops.toSet(),
        )
    }

    // -------------------------------------------------------------------------
    // Case-only renames
    // -------------------------------------------------------------------------

    @Test
    fun `case-only rename treats old and new paths as distinct files`() {
        // On a case-sensitive filesystem, "File.txt" and "file.txt" are distinct paths.
        // SyncDiffer must treat them as different entries regardless of hash.
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("file.txt", hash = "abc")),
                remote = listOf(snap("File.txt", hash = "abc")),
                lastIndex = listOf(idx("File.txt", hash = "abc")),
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(
            setOf<SyncOp>(
                SyncOp.UploadNew("file.txt"),
                SyncOp.DeleteRemote("File.txt"),
            ),
            ops.toSet(),
        )
    }

    @Test
    fun `case-only remote rename emits delete-local and download-new`() {
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("README.txt", hash = "xyz")),
                remote = listOf(snap("readme.txt", hash = "xyz")),
                lastIndex = listOf(idx("README.txt", hash = "xyz")),
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(
            setOf<SyncOp>(
                SyncOp.DeleteLocal("README.txt"),
                SyncOp.DownloadNew("readme.txt"),
            ),
            ops.toSet(),
        )
    }

    // -------------------------------------------------------------------------
    // Concurrent deletes on both sides
    // -------------------------------------------------------------------------

    @Test
    fun `concurrent deletes on different files each generate independent ops`() {
        // "deleted-from-local.txt": in lastIndex, missing from local, still on remote → DeleteRemote
        // "deleted-from-remote.txt": in lastIndex, still on local, missing from remote → DeleteLocal
        // "deleted-from-both.txt": in lastIndex, missing from both → no-op (already converged)
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("deleted-from-remote.txt")),
                remote = listOf(snap("deleted-from-local.txt")),
                lastIndex =
                    listOf(
                        idx("deleted-from-local.txt"),
                        idx("deleted-from-remote.txt"),
                        idx("deleted-from-both.txt"),
                    ),
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(
            setOf<SyncOp>(
                SyncOp.DeleteRemote("deleted-from-local.txt"),
                SyncOp.DeleteLocal("deleted-from-remote.txt"),
            ),
            ops.toSet(),
        )
    }

    // -------------------------------------------------------------------------
    // Large file size (> 2 GiB)
    // -------------------------------------------------------------------------

    @Test
    fun `large file over 2 GiB size is handled as Long without overflow`() {
        val bigSize = Int.MAX_VALUE.toLong() + 1L // 2 GiB + 1 byte
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("large.bin", size = bigSize, mtime = 1_000)),
                remote = emptyList(),
                lastIndex = emptyList(),
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(listOf<SyncOp>(SyncOp.UploadNew("large.bin")), ops)
    }

    @Test
    fun `large file change detection works for sizes beyond 2 GiB`() {
        val sizeA = Int.MAX_VALUE.toLong() + 1L // 2 GiB + 1
        val sizeB = Int.MAX_VALUE.toLong() + 100L // 2 GiB + 100
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("large.bin", size = sizeB, mtime = 2_000)),
                remote = listOf(snap("large.bin", size = sizeA, mtime = 1_000)),
                lastIndex = listOf(idx("large.bin", size = sizeA, mtime = 1_000)),
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(listOf<SyncOp>(SyncOp.UpdateRemote("large.bin")), ops)
    }

    @Test
    fun `unicode and emoji paths are handled correctly`() {
        val emojiPath = "📁 emoji folder/résumé 日本語.txt"
        val ops =
            SyncDiffer.diff(
                local = listOf(snap(emojiPath)),
                remote = emptyList(),
                lastIndex = emptyList(),
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(listOf<SyncOp>(SyncOp.UploadNew(emojiPath)), ops)
    }

    // -------------------------------------------------------------------------
    // UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS – upload-only with retention
    // -------------------------------------------------------------------------

    @Test
    fun `upload-delete-local mode uploads new local file on first sync`() {
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("photo.jpg")),
                remote = emptyList(),
                lastIndex = emptyList(),
                direction = SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(listOf<SyncOp>(SyncOp.UploadNew("photo.jpg")), ops)
    }

    @Test
    fun `upload-delete-local mode skips remote-only file on first sync`() {
        val ops =
            SyncDiffer.diff(
                local = emptyList(),
                remote = listOf(snap("cloud-only.txt")),
                lastIndex = emptyList(),
                direction = SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `upload-delete-local mode updates remote when local file changes`() {
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("a.txt", size = 20, mtime = 2_000)),
                remote = listOf(snap("a.txt")),
                lastIndex = listOf(idx("a.txt")),
                direction = SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(listOf<SyncOp>(SyncOp.UpdateRemote("a.txt")), ops)
    }

    @Test
    fun `upload-delete-local mode does not update local when remote changes`() {
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("a.txt")),
                remote = listOf(snap("a.txt", size = 20, mtime = 2_000)),
                lastIndex = listOf(idx("a.txt")),
                direction = SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `upload-delete-local mode does not propagate local delete to remote`() {
        // Local file was deleted (perhaps by a prior retention cleanup).
        // The remote backup must NOT be deleted.
        val ops =
            SyncDiffer.diff(
                local = emptyList(),
                remote = listOf(snap("a.txt")),
                lastIndex = listOf(idx("a.txt")),
                direction = SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `upload-delete-local mode re-uploads file when remote is unexpectedly deleted`() {
        // Remote backup was deleted but local source still exists → re-upload.
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("a.txt")),
                remote = emptyList(),
                lastIndex = listOf(idx("a.txt")),
                direction = SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(listOf<SyncOp>(SyncOp.UploadNew("a.txt")), ops)
    }

    @Test
    fun `upload-delete-local mode does not delete local before retention period elapses`() {
        val nowMs = 10_000L
        val retentionDays = 1
        // File mtime is 1 ms before the threshold (should NOT be deleted).
        val thresholdMs = retentionDays.toLong() * 24 * 60 * 60 * 1000
        val fileMtime = nowMs - thresholdMs + 1

        val ops =
            SyncDiffer.diff(
                local = listOf(snap("photo.jpg", mtime = fileMtime)),
                remote = listOf(snap("photo.jpg", mtime = fileMtime)),
                lastIndex = listOf(idx("photo.jpg", mtime = fileMtime, remoteSize = 10, remoteMtime = fileMtime, remoteId = "r-photo")),
                direction = SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
                retentionDays = retentionDays,
                nowMs = nowMs,
            )

        assertTrue("No retention delete expected before threshold", ops.isEmpty())
    }

    @Test
    fun `upload-delete-local mode deletes local after retention period elapses`() {
        val retentionDays = 7
        val thresholdMs = retentionDays.toLong() * 24 * 60 * 60 * 1000
        val nowMs = thresholdMs + 1_000L
        // File was last modified exactly at the threshold boundary.
        val fileMtime = nowMs - thresholdMs

        val ops =
            SyncDiffer.diff(
                local = listOf(snap("photo.jpg", mtime = fileMtime)),
                remote = listOf(snap("photo.jpg", mtime = fileMtime)),
                lastIndex = listOf(idx("photo.jpg", mtime = fileMtime, remoteSize = 10, remoteMtime = fileMtime, remoteId = "r-photo")),
                direction = SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
                retentionDays = retentionDays,
                nowMs = nowMs,
            )

        assertEquals(listOf<SyncOp>(SyncOp.DeleteLocalRetention("photo.jpg")), ops)
    }

    @Test
    fun `upload-delete-local mode does not delete local when retentionDays is null`() {
        val nowMs = 1_000_000L
        val oldMtime = 1L // ancient file

        val ops =
            SyncDiffer.diff(
                local = listOf(snap("photo.jpg", mtime = oldMtime)),
                remote = listOf(snap("photo.jpg", mtime = oldMtime)),
                lastIndex = listOf(idx("photo.jpg", mtime = oldMtime, remoteSize = 10, remoteMtime = oldMtime, remoteId = "r-photo")),
                direction = SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
                retentionDays = null,
                nowMs = nowMs,
            )

        assertTrue("retentionDays=null must not produce any retention delete", ops.isEmpty())
    }

    @Test
    fun `upload-delete-local mode does not delete local when file is not yet in index`() {
        // File was just uploaded in this run (not in lastIndex); retention must NOT fire.
        val nowMs = 1_000_000L

        val ops =
            SyncDiffer.diff(
                local = listOf(snap("new.txt", mtime = 1L)),
                remote = emptyList(),
                lastIndex = emptyList(),
                direction = SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
                retentionDays = 0, // even zero days
                nowMs = nowMs,
            )

        // Only UploadNew should be present; no DeleteLocalRetention.
        assertEquals(listOf<SyncOp>(SyncOp.UploadNew("new.txt")), ops)
    }

    @Test
    fun `upload-delete-local mode does not generate retention delete for file already being updated`() {
        // File is in index (previously synced) but is also locally modified in this run.
        // The UpdateRemote op should win; no DeleteLocalRetention for the same path.
        val retentionDays = 0
        val fileMtime = 500L
        val nowMs = 1_000_000L

        val ops =
            SyncDiffer.diff(
                local = listOf(snap("a.txt", size = 99, mtime = fileMtime)),
                remote = listOf(snap("a.txt", size = 10, mtime = 1L)),
                lastIndex = listOf(idx("a.txt", size = 10, mtime = 1L, remoteSize = 10, remoteMtime = 1L, remoteId = "r-a")),
                direction = SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
                retentionDays = retentionDays,
                nowMs = nowMs,
            )

        // Should contain UpdateRemote but NOT DeleteLocalRetention.
        assertEquals(listOf<SyncOp>(SyncOp.UpdateRemote("a.txt")), ops)
    }

    // -------------------------------------------------------------------------
    // DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS – download-only with retention
    // -------------------------------------------------------------------------

    @Test
    fun `download-delete-remote mode downloads new remote file on first sync`() {
        val ops =
            SyncDiffer.diff(
                local = emptyList(),
                remote = listOf(snap("cloud.txt")),
                lastIndex = emptyList(),
                direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(listOf<SyncOp>(SyncOp.DownloadNew("cloud.txt")), ops)
    }

    @Test
    fun `download-delete-remote mode skips local-only file on first sync`() {
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("local-only.txt")),
                remote = emptyList(),
                lastIndex = emptyList(),
                direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `download-delete-remote mode updates local when remote file changes`() {
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("a.txt")),
                remote = listOf(snap("a.txt", size = 20, mtime = 2_000)),
                lastIndex = listOf(idx("a.txt")),
                direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(listOf<SyncOp>(SyncOp.UpdateLocal("a.txt")), ops)
    }

    @Test
    fun `download-delete-remote mode does not update remote when local changes`() {
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("a.txt", size = 20, mtime = 2_000)),
                remote = listOf(snap("a.txt")),
                lastIndex = listOf(idx("a.txt")),
                direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `download-delete-remote mode does not propagate remote delete to local`() {
        // Remote file was deleted (perhaps by a prior retention cleanup).
        // The local copy must NOT be deleted.
        val ops =
            SyncDiffer.diff(
                local = listOf(snap("a.txt")),
                remote = emptyList(),
                lastIndex = listOf(idx("a.txt")),
                direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `download-delete-remote mode re-downloads file when local is unexpectedly deleted`() {
        val ops =
            SyncDiffer.diff(
                local = emptyList(),
                remote = listOf(snap("a.txt")),
                lastIndex = listOf(idx("a.txt")),
                direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        assertEquals(listOf<SyncOp>(SyncOp.DownloadNew("a.txt")), ops)
    }

    @Test
    fun `download-delete-remote mode does not delete remote before retention period elapses`() {
        val nowMs = 10_000L
        val retentionDays = 1
        val thresholdMs = retentionDays.toLong() * 24 * 60 * 60 * 1000
        val remoteMtime = nowMs - thresholdMs + 1 // 1 ms before threshold

        val ops =
            SyncDiffer.diff(
                local = listOf(snap("cloud.txt", mtime = remoteMtime)),
                remote = listOf(snap("cloud.txt", mtime = remoteMtime)),
                lastIndex = listOf(idx("cloud.txt", mtime = remoteMtime, remoteSize = 10, remoteMtime = remoteMtime, remoteId = "r-cloud")),
                direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
                retentionDays = retentionDays,
                nowMs = nowMs,
            )

        assertTrue("No retention delete expected before threshold", ops.isEmpty())
    }

    @Test
    fun `download-delete-remote mode deletes remote after retention period elapses`() {
        val retentionDays = 7
        val thresholdMs = retentionDays.toLong() * 24 * 60 * 60 * 1000
        val nowMs = thresholdMs + 1_000L
        val remoteMtime = nowMs - thresholdMs

        val ops =
            SyncDiffer.diff(
                local = listOf(snap("cloud.txt", mtime = remoteMtime)),
                remote = listOf(snap("cloud.txt", mtime = remoteMtime)),
                lastIndex = listOf(idx("cloud.txt", mtime = remoteMtime, remoteSize = 10, remoteMtime = remoteMtime, remoteId = "r-cloud")),
                direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
                retentionDays = retentionDays,
                nowMs = nowMs,
            )

        assertEquals(listOf<SyncOp>(SyncOp.DeleteRemoteRetention("cloud.txt")), ops)
    }

    @Test
    fun `download-delete-remote mode does not delete remote when retentionDays is null`() {
        val nowMs = 1_000_000L
        val oldMtime = 1L

        val ops =
            SyncDiffer.diff(
                local = listOf(snap("cloud.txt", mtime = oldMtime)),
                remote = listOf(snap("cloud.txt", mtime = oldMtime)),
                lastIndex = listOf(idx("cloud.txt", mtime = oldMtime, remoteSize = 10, remoteMtime = oldMtime, remoteId = "r-cloud")),
                direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
                retentionDays = null,
                nowMs = nowMs,
            )

        assertTrue("retentionDays=null must not produce any retention delete", ops.isEmpty())
    }

    @Test
    fun `download-delete-remote mode does not generate retention delete for file not yet in index`() {
        val nowMs = 1_000_000L

        val ops =
            SyncDiffer.diff(
                local = emptyList(),
                remote = listOf(snap("new.txt", mtime = 1L)),
                lastIndex = emptyList(),
                direction = SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
                retentionDays = 0,
                nowMs = nowMs,
            )

        // Only DownloadNew; no DeleteRemoteRetention.
        assertEquals(listOf<SyncOp>(SyncOp.DownloadNew("new.txt")), ops)
    }

    @Test
    fun `retention does not fire for other directions even when retentionDays is set`() {
        val nowMs = 1_000_000L
        val oldMtime = 1L

        for (dir in listOf(SyncDirection.BIDIRECTIONAL, SyncDirection.LOCAL_TO_REMOTE, SyncDirection.REMOTE_TO_LOCAL)) {
            val ops =
                SyncDiffer.diff(
                    local = listOf(snap("a.txt", mtime = oldMtime)),
                    remote = listOf(snap("a.txt", mtime = oldMtime)),
                    lastIndex = listOf(idx("a.txt", mtime = oldMtime, remoteSize = 10, remoteMtime = oldMtime, remoteId = "r-a")),
                    direction = dir,
                    conflictPolicy = ConflictPolicy.NEWEST_WINS,
                    retentionDays = 0,
                    nowMs = nowMs,
                )

            assertTrue(
                "Direction $dir should produce no retention ops even with retentionDays=0",
                ops.none { it is SyncOp.DeleteLocalRetention || it is SyncOp.DeleteRemoteRetention },
            )
        }
    }
}
