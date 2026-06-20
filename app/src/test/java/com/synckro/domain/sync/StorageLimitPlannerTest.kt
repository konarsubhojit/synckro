package com.synckro.domain.sync

import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.domain.provider.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StorageLimitPlanner].
 *
 * All tests run on the JVM with no Android dependencies.
 */
class StorageLimitPlannerTest {
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun remoteFile(
        relativePath: String,
        size: Long?,
        id: String = relativePath,
    ) = RemoteFile(
        id = id,
        name = relativePath.substringAfterLast('/'),
        parentId = null,
        isFolder = false,
        size = size,
        lastModifiedMs = 1_000L,
        eTag = null,
        mimeType = null,
    )

    private fun localIndexEntry(
        path: String,
        sizeBytes: Long,
    ) = LocalIndexEntity(
        pairId = 1L,
        relativePath = path,
        sizeBytes = sizeBytes,
        mtimeMs = 1_000L,
        contentHash = null,
        remoteId = "remote-$path",
    )

    // =========================================================================
    // 1. No limit: passthrough
    // =========================================================================

    @Test
    fun `no limit returns all ops unchanged`() {
        val ops =
            listOf(
                SyncOp.DownloadNew("a.txt"),
                SyncOp.UploadNew("b.txt"),
                SyncOp.UpdateLocal("c.txt"),
            )
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = mapOf("a.txt" to remoteFile("a.txt", 100L), "c.txt" to remoteFile("c.txt", 50L)),
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 0L,
                limitBytes = null,
            )

        assertEquals(ops, plan.allowedOps)
        assertTrue(plan.skipped.isEmpty())
    }

    // =========================================================================
    // 2. File fits within limit
    // =========================================================================

    @Test
    fun `DownloadNew smaller than limit is allowed`() {
        val ops = listOf(SyncOp.DownloadNew("photo.jpg"))
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = mapOf("photo.jpg" to remoteFile("photo.jpg", 500_000L)),
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 0L,
                limitBytes = 1_000_000L,
            )

        assertEquals(listOf(SyncOp.DownloadNew("photo.jpg")), plan.allowedOps)
        assertTrue(plan.skipped.isEmpty())
    }

    // =========================================================================
    // 3. File would exceed limit → skipped
    // =========================================================================

    @Test
    fun `DownloadNew larger than limit is skipped`() {
        val ops = listOf(SyncOp.DownloadNew("video.mp4"))
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = mapOf("video.mp4" to remoteFile("video.mp4", 2_000_000L)),
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 0L,
                limitBytes = 1_000_000L,
            )

        assertTrue(plan.allowedOps.isEmpty())
        assertEquals(1, plan.skipped.size)
        val s = plan.skipped.first()
        assertEquals("video.mp4", s.relativePath)
        assertEquals(StorageLimitPlanner.SkippedDownload.Reason.WOULD_EXCEED_LIMIT, s.reason)
        assertEquals(2_000_000L, s.sizeBytes)
    }

    // =========================================================================
    // 4. Multiple files, deterministic stable ordering
    // =========================================================================

    @Test
    fun `multiple files are evaluated in relative-path ascending order`() {
        // Limit allows only 1.5 MB. Files: c.txt=1MB, a.txt=600KB, b.txt=700KB.
        // Sorted order: a.txt → b.txt → c.txt.
        // After a.txt (600KB): used=600KB — fits.
        // After b.txt (700KB): projected=1300KB > 1500KB? No, 1300<1500 — fits.
        // After c.txt (1MB=1000KB): projected=2300KB > 1500KB — skipped.
        val limitBytes = 1_500_000L // 1.5 MB
        val ops =
            listOf(
                SyncOp.DownloadNew("c.txt"),
                SyncOp.DownloadNew("a.txt"),
                SyncOp.DownloadNew("b.txt"),
            )
        val remote =
            mapOf(
                "a.txt" to remoteFile("a.txt", 600_000L),
                "b.txt" to remoteFile("b.txt", 700_000L),
                "c.txt" to remoteFile("c.txt", 1_000_000L),
            )
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = remote,
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 0L,
                limitBytes = limitBytes,
            )

        // a.txt and b.txt are allowed; c.txt is skipped.
        assertEquals(
            listOf(SyncOp.DownloadNew("a.txt"), SyncOp.DownloadNew("b.txt")),
            plan.allowedOps,
        )
        assertEquals(1, plan.skipped.size)
        assertEquals("c.txt", plan.skipped.first().relativePath)
    }

    // =========================================================================
    // 5. Unknown remote size → skipped with UNKNOWN_SIZE reason
    // =========================================================================

    @Test
    fun `DownloadNew with null remote size is skipped with UNKNOWN_SIZE`() {
        val ops = listOf(SyncOp.DownloadNew("mystery.bin"))
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = mapOf("mystery.bin" to remoteFile("mystery.bin", null)),
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 0L,
                limitBytes = 1_000_000L,
            )

        assertTrue(plan.allowedOps.isEmpty())
        assertEquals(1, plan.skipped.size)
        val s = plan.skipped.first()
        assertEquals("mystery.bin", s.relativePath)
        assertEquals(StorageLimitPlanner.SkippedDownload.Reason.UNKNOWN_SIZE, s.reason)
        assertEquals(null, s.sizeBytes)
    }

    @Test
    fun `DownloadNew not in remoteFilesByPath is skipped with UNKNOWN_SIZE`() {
        val ops = listOf(SyncOp.DownloadNew("ghost.txt"))
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = emptyMap(),
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 0L,
                limitBytes = 1_000_000L,
            )

        assertTrue(plan.allowedOps.isEmpty())
        assertEquals(1, plan.skipped.size)
        assertEquals(StorageLimitPlanner.SkippedDownload.Reason.UNKNOWN_SIZE, plan.skipped.first().reason)
    }

    // =========================================================================
    // 6. Existing local usage counts towards the limit
    // =========================================================================

    @Test
    fun `existing local usage is counted before allowing new downloads`() {
        // Limit: 1 MB. Already using 800 KB. New file 300 KB → projected 1100 KB > 1 MB.
        val ops = listOf(SyncOp.DownloadNew("new.txt"))
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = mapOf("new.txt" to remoteFile("new.txt", 300_000L)),
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 800_000L,
                limitBytes = 1_000_000L,
            )

        assertTrue(plan.allowedOps.isEmpty())
        assertEquals(1, plan.skipped.size)
        assertEquals(StorageLimitPlanner.SkippedDownload.Reason.WOULD_EXCEED_LIMIT, plan.skipped.first().reason)
    }

    @Test
    fun `download allowed when remaining capacity is sufficient`() {
        // Limit: 1 MB. Already using 600 KB. New file 300 KB → projected 900 KB < 1 MB.
        val ops = listOf(SyncOp.DownloadNew("ok.txt"))
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = mapOf("ok.txt" to remoteFile("ok.txt", 300_000L)),
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 600_000L,
                limitBytes = 1_000_000L,
            )

        assertEquals(listOf(SyncOp.DownloadNew("ok.txt")), plan.allowedOps)
        assertTrue(plan.skipped.isEmpty())
    }

    // =========================================================================
    // 7. UpdateLocal accounting (avoid double-counting existing local file)
    // =========================================================================

    @Test
    fun `UpdateLocal subtracts existing local size before adding remote size`() {
        // Existing local file: 800 KB. Replacement remote file: 400 KB.
        // Limit: 1 MB. Current usage: 800 KB (the existing file).
        // Projected: 800 - 800 + 400 = 400 KB → allowed.
        val ops = listOf(SyncOp.UpdateLocal("doc.docx"))
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = mapOf("doc.docx" to remoteFile("doc.docx", 400_000L)),
                localIndexByPath = mapOf("doc.docx" to localIndexEntry("doc.docx", 800_000L)),
                currentLocalUsageBytes = 800_000L,
                limitBytes = 1_000_000L,
            )

        assertEquals(listOf(SyncOp.UpdateLocal("doc.docx")), plan.allowedOps)
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun `UpdateLocal is skipped when replacement file would exceed limit even after subtraction`() {
        // Existing local file: 200 KB. Replacement: 900 KB.
        // Limit: 1 MB. Current usage: 200 KB.
        // Projected: 200 - 200 + 900 = 900 KB → allowed.
        // Extend: current usage = 400 KB (200 existing + 200 other file).
        // Projected: 400 - 200 + 900 = 1100 KB > 1 MB → skipped.
        val ops = listOf(SyncOp.UpdateLocal("big.bin"))
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = mapOf("big.bin" to remoteFile("big.bin", 900_000L)),
                localIndexByPath = mapOf("big.bin" to localIndexEntry("big.bin", 200_000L)),
                currentLocalUsageBytes = 400_000L, // 200 other file + 200 existing
                limitBytes = 1_000_000L,
            )

        assertTrue(plan.allowedOps.isEmpty())
        assertEquals(1, plan.skipped.size)
        assertEquals(StorageLimitPlanner.SkippedDownload.Reason.WOULD_EXCEED_LIMIT, plan.skipped.first().reason)
    }

    @Test
    fun `UpdateLocal with missing index entry uses zero as existing size`() {
        // No index entry → treat existing size as 0 (safe over-estimate).
        // Replacement: 500 KB. Current usage: 0. Limit: 1 MB. Projected: 500 KB → allowed.
        val ops = listOf(SyncOp.UpdateLocal("orphan.txt"))
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = mapOf("orphan.txt" to remoteFile("orphan.txt", 500_000L)),
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 0L,
                limitBytes = 1_000_000L,
            )

        assertEquals(listOf(SyncOp.UpdateLocal("orphan.txt")), plan.allowedOps)
        assertTrue(plan.skipped.isEmpty())
    }

    // =========================================================================
    // 8. Non-download ops are always allowed and retain their order
    // =========================================================================

    @Test
    fun `non-download ops always pass through unchanged`() {
        val ops =
            listOf(
                SyncOp.UploadNew("upload.txt"),
                SyncOp.DeleteRemote("old.txt"),
                SyncOp.DeleteLocal("gone.txt"),
            )
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = emptyMap(),
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 0L,
                limitBytes = 1L, // tiny limit — but no downloads to check
            )

        // Non-download ops pass through (in their original order, appended after
        // any download batch, which is empty here).
        assertEquals(ops, plan.allowedOps)
        assertTrue(plan.skipped.isEmpty())
    }

    // =========================================================================
    // 9. Mixed batch: downloads sorted, non-downloads preserved
    // =========================================================================

    @Test
    fun `download ops sorted by path and non-download ops follow after`() {
        // Downloads: z.txt (100B), a.txt (100B). Non-download: UploadNew("m.txt").
        // Limit: 150B. Current usage: 0.
        // Sorted downloads: a.txt (100B) → fits; z.txt (100B) → projected 200B > 150B → skipped.
        val limitBytes = 150L
        val ops =
            listOf(
                SyncOp.DownloadNew("z.txt"),
                SyncOp.UploadNew("m.txt"),
                SyncOp.DownloadNew("a.txt"),
            )
        val remote =
            mapOf(
                "a.txt" to remoteFile("a.txt", 100L),
                "z.txt" to remoteFile("z.txt", 100L),
            )
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = remote,
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 0L,
                limitBytes = limitBytes,
            )

        // a.txt allowed, then UploadNew (non-download), z.txt skipped.
        assertEquals(
            listOf(SyncOp.DownloadNew("a.txt"), SyncOp.UploadNew("m.txt")),
            plan.allowedOps,
        )
        assertEquals(1, plan.skipped.size)
        assertEquals("z.txt", plan.skipped.first().relativePath)
    }

    // =========================================================================
    // 10. Retention safety: skipped downloads must not be treated as synced
    //     (verified at planner level — the op is absent from allowedOps)
    // =========================================================================

    @Test
    fun `skipped DownloadNew is absent from allowedOps so it cannot be marked synced`() {
        val ops = listOf(SyncOp.DownloadNew("large.bin"))
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = mapOf("large.bin" to remoteFile("large.bin", 999_999_999L)),
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 0L,
                limitBytes = 1_000L,
            )

        assertFalse(plan.allowedOps.any { it.relativePath == "large.bin" })
        assertEquals(1, plan.skipped.size)
    }

    // =========================================================================
    // 11. File exactly at limit boundary
    // =========================================================================

    @Test
    fun `file exactly at limit boundary is allowed`() {
        val ops = listOf(SyncOp.DownloadNew("exact.txt"))
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = mapOf("exact.txt" to remoteFile("exact.txt", 1_000_000L)),
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 0L,
                limitBytes = 1_000_000L,
            )

        assertEquals(listOf(SyncOp.DownloadNew("exact.txt")), plan.allowedOps)
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun `file one byte over limit is skipped`() {
        val ops = listOf(SyncOp.DownloadNew("oversize.txt"))
        val plan =
            StorageLimitPlanner.plan(
                ops = ops,
                remoteFilesByPath = mapOf("oversize.txt" to remoteFile("oversize.txt", 1_000_001L)),
                localIndexByPath = emptyMap(),
                currentLocalUsageBytes = 0L,
                limitBytes = 1_000_000L,
            )

        assertTrue(plan.allowedOps.isEmpty())
        assertEquals(1, plan.skipped.size)
    }
}
