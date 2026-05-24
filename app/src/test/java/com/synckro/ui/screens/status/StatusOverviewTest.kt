package com.synckro.ui.screens.status

import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.SyncPair
import com.synckro.domain.sync.ActiveTransfer
import com.synckro.domain.sync.TransferDirection
import com.synckro.domain.sync.TransferProgress
import com.synckro.ui.screens.home.PairSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Phase 2 Status-screen aggregator. No Android imports
 * — runs under the default unit-test source set.
 */
class StatusOverviewTest {
    @Test
    fun `empty inputs produce empty overview`() {
        val overview =
            buildStatusOverview(
                pairs = emptyList(),
                syncingPairIds = emptySet(),
                progressByPairId = emptyMap(),
                lastSummaryByPairId = emptyMap(),
                accountEmailById = emptyMap(),
                accountProviderById = emptyMap(),
                pendingConflictCount = 0,
            )
        assertEquals(0, overview.syncStatus.totalPairs)
        assertFalse(overview.syncStatus.isSyncing)
        assertNull(overview.syncStatus.fraction)
        assertFalse(overview.recentChanges.hasAnyHistory)
        assertTrue(overview.accountRows.isEmpty())
        assertFalse(overview.warnings.hasAny)
    }

    @Test
    fun `sums progress across syncing pairs and prefers byte fraction`() {
        val pairs = listOf(pair(1L), pair(2L))
        val progress =
            mapOf(
                1L to
                    TransferProgress(
                        filesCompleted = 2,
                        totalFiles = 5,
                        bytesTransferred = 100L,
                        totalBytes = 400L,
                        activeTransfers =
                            listOf(
                                ActiveTransfer("a.txt", TransferDirection.UPLOAD, 100L, 200L),
                            ),
                    ),
                2L to
                    TransferProgress(
                        filesCompleted = 3,
                        totalFiles = 3,
                        bytesTransferred = 300L,
                        totalBytes = 400L,
                    ),
            )
        val overview =
            buildStatusOverview(
                pairs = pairs,
                syncingPairIds = setOf(1L, 2L),
                progressByPairId = progress,
                lastSummaryByPairId = emptyMap(),
                accountEmailById = emptyMap(),
                accountProviderById = emptyMap(),
                pendingConflictCount = 0,
            )
        val s = overview.syncStatus
        assertEquals(2, s.totalPairs)
        assertEquals(2, s.syncingPairs)
        assertEquals(5, s.filesCompleted)
        assertEquals(8, s.totalFiles)
        assertEquals(400L, s.bytesTransferred)
        assertEquals(800L, s.totalBytes)
        assertEquals(0.5f, s.fraction!!, 1e-3f)
    }

    @Test
    fun `byte progress falls back to file fraction when total bytes is zero`() {
        val overview =
            buildStatusOverview(
                pairs = listOf(pair(1L)),
                syncingPairIds = setOf(1L),
                progressByPairId =
                    mapOf(
                        1L to
                            TransferProgress(
                                filesCompleted = 1,
                                totalFiles = 4,
                                bytesTransferred = 0L,
                                totalBytes = 0L,
                            ),
                    ),
                lastSummaryByPairId = emptyMap(),
                accountEmailById = emptyMap(),
                accountProviderById = emptyMap(),
                pendingConflictCount = 0,
            )
        assertEquals(0.25f, overview.syncStatus.fraction!!, 1e-3f)
    }

    @Test
    fun `recent changes sum applied conflicts errors and track latest timestamp`() {
        val overview =
            buildStatusOverview(
                pairs = emptyList(),
                syncingPairIds = emptySet(),
                progressByPairId = emptyMap(),
                lastSummaryByPairId =
                    mapOf(
                        1L to
                            PairSummary(
                                applied = 3,
                                conflicts = 1,
                                errors = 0,
                                timestampMs = 1_000L,
                                outcome = PairSummary.Outcome.SUCCESS,
                            ),
                        2L to
                            PairSummary(
                                applied = 5,
                                conflicts = 0,
                                errors = 2,
                                timestampMs = 9_000L,
                                outcome = PairSummary.Outcome.PARTIAL_FAILURE,
                            ),
                    ),
                accountEmailById = emptyMap(),
                accountProviderById = emptyMap(),
                pendingConflictCount = 0,
            )
        val r = overview.recentChanges
        assertEquals(8, r.applied)
        assertEquals(1, r.conflicts)
        assertEquals(2, r.errors)
        assertEquals(2, r.pairsWithChanges)
        assertEquals(9_000L, r.lastTimestampMs)
        assertTrue(r.hasAnyHistory)
    }

    @Test
    fun `accounts are grouped by provider and accountId with pair counts and emails`() {
        val pairs =
            listOf(
                pair(1L, provider = CloudProviderType.GOOGLE_DRIVE, accountId = "acc-1"),
                pair(2L, provider = CloudProviderType.GOOGLE_DRIVE, accountId = "acc-1"),
                pair(3L, provider = CloudProviderType.ONEDRIVE, accountId = "acc-2"),
                // Unlinked pair — accountId null surfaces as "needs re-link".
                pair(4L, provider = CloudProviderType.ONEDRIVE, accountId = null),
            )
        val overview =
            buildStatusOverview(
                pairs = pairs,
                syncingPairIds = emptySet(),
                progressByPairId = emptyMap(),
                lastSummaryByPairId = emptyMap(),
                accountEmailById =
                    mapOf(
                        "acc-1" to "alice@example.com",
                        "acc-2" to "bob@example.com",
                    ),
                accountProviderById =
                    mapOf(
                        "acc-1" to CloudProviderType.GOOGLE_DRIVE,
                        "acc-2" to CloudProviderType.ONEDRIVE,
                    ),
                pendingConflictCount = 0,
            )
        assertEquals(3, overview.accountRows.size)
        val gdrive = overview.accountRows[0]
        assertEquals(CloudProviderType.GOOGLE_DRIVE, gdrive.provider)
        assertEquals("acc-1", gdrive.accountId)
        assertEquals("alice@example.com", gdrive.email)
        assertEquals(2, gdrive.pairCount)
        val unlinked = overview.accountRows.first { it.accountId == null }
        assertNull(unlinked.email)
        assertEquals(1, unlinked.pairCount)
    }

    @Test
    fun `warnings count conflicts reauth and relink`() {
        val pairs =
            listOf(
                pair(1L, needsReLink = true),
                pair(2L, lastSyncResult = "NEEDS_REAUTH"),
                pair(3L, lastSyncResult = "SUCCESS"),
            )
        val overview =
            buildStatusOverview(
                pairs = pairs,
                syncingPairIds = emptySet(),
                progressByPairId = emptyMap(),
                lastSummaryByPairId = emptyMap(),
                accountEmailById = emptyMap(),
                accountProviderById = emptyMap(),
                pendingConflictCount = 4,
            )
        val w = overview.warnings
        assertEquals(4, w.pendingConflicts)
        assertEquals(1, w.pairsNeedingReauth)
        assertEquals(1, w.pairsNeedingRelink)
        assertTrue(w.hasAny)
    }

    @Test
    fun `warnings hasAny is false when nothing needs attention`() {
        val overview =
            buildStatusOverview(
                pairs = listOf(pair(1L, lastSyncResult = "SUCCESS")),
                syncingPairIds = emptySet(),
                progressByPairId = emptyMap(),
                lastSummaryByPairId = emptyMap(),
                accountEmailById = emptyMap(),
                accountProviderById = emptyMap(),
                pendingConflictCount = 0,
            )
        assertFalse(overview.warnings.hasAny)
    }

    @Test
    fun `accounts without sync pairs are still surfaced in account rows`() {
        val overview =
            buildStatusOverview(
                pairs = emptyList(),
                syncingPairIds = emptySet(),
                progressByPairId = emptyMap(),
                lastSummaryByPairId = emptyMap(),
                accountEmailById =
                    mapOf(
                        "acc-1" to "alice@example.com",
                    ),
                accountProviderById =
                    mapOf(
                        "acc-1" to CloudProviderType.GOOGLE_DRIVE,
                    ),
                pendingConflictCount = 0,
            )
        assertEquals(1, overview.accountRows.size)
        val row = overview.accountRows.single()
        assertEquals(CloudProviderType.GOOGLE_DRIVE, row.provider)
        assertEquals("acc-1", row.accountId)
        assertEquals("alice@example.com", row.email)
        assertEquals(0, row.pairCount)
    }

    private fun pair(
        id: Long,
        provider: CloudProviderType = CloudProviderType.ONEDRIVE,
        accountId: String? = "acc-$id",
        needsReLink: Boolean = false,
        lastSyncResult: String? = null,
    ) = SyncPair(
        id = id,
        displayName = "pair-$id",
        localTreeUri = "content://local/$id",
        provider = provider,
        accountId = accountId,
        remoteFolderId = "remote-$id",
        needsReLink = needsReLink,
        lastSyncResult = lastSyncResult,
    )
}
