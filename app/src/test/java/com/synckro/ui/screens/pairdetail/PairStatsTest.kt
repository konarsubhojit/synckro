package com.synckro.ui.screens.pairdetail

import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for [aggregatePairStats] (issue #375). Fixture rows mirror the
 * shape of [com.synckro.data.local.entity.SyncEventEntity] rows loaded through
 * [com.synckro.data.repository.SyncEventRepository.observeForPair] — newest first.
 */
class PairStatsTest {
    @Test
    fun `empty event list produces empty stats`() {
        val stats = aggregatePairStats(emptyList())
        assertEquals(0, stats.runsConsidered)
        assertEquals(0, stats.successCount)
        assertEquals(0, stats.failureCount)
        assertEquals(0L, stats.totalBytesTransferred)
        assertNull(stats.lastSuccessAtMs)
        assertEquals(0f, stats.successRate)
    }

    @Test
    fun `computes totals success-rate and last success timestamp across mixed outcomes`() {
        val events =
            listOf(
                event(SyncEventLevel.WARN, "Sync partial failure: 2 applied, 1 errors — boom", t = 400L, bytes = 20L),
                event(SyncEventLevel.INFO, "Sync succeeded: 5 applied, 0 conflicts", t = 300L, bytes = 50L),
                event(SyncEventLevel.ERROR, "Sync failed after 5 attempt(s), giving up: timeout", t = 200L),
                event(SyncEventLevel.INFO, "Sync succeeded: 3 applied, 1 conflicts", t = 100L, bytes = 30L),
            )

        val stats = aggregatePairStats(events, windowSize = 10)

        assertEquals(4, stats.runsConsidered)
        assertEquals(2, stats.successCount)
        assertEquals(2, stats.failureCount)
        assertEquals(100L, stats.totalBytesTransferred)
        // Newest-first input — the first SUCCESS encountered is the most recent one.
        assertEquals(300L, stats.lastSuccessAtMs)
        assertEquals(0.5f, stats.successRate)
    }

    @Test
    fun `window size limits the number of terminal runs folded in`() {
        val events =
            listOf(
                event(SyncEventLevel.INFO, "Sync succeeded: 1 applied, 0 conflicts", t = 500L),
                event(SyncEventLevel.INFO, "Sync succeeded: 1 applied, 0 conflicts", t = 400L),
                event(SyncEventLevel.ERROR, "Sync failed after 5 attempt(s), giving up: x", t = 300L),
            )

        val stats = aggregatePairStats(events, windowSize = 2)

        assertEquals(2, stats.runsConsidered)
        assertEquals(2, stats.successCount)
        assertEquals(0, stats.failureCount)
        assertEquals(500L, stats.lastSuccessAtMs)
    }

    @Test
    fun `non-terminal rows are skipped without counting against the window`() {
        val events =
            listOf(
                event(SyncEventLevel.INFO, "Sync started for \"x\" (attempt 1)", t = 300L),
                event(SyncEventLevel.WARN, "Sync retriable, will retry (attempt 2/5): n", t = 250L),
                event(SyncEventLevel.INFO, "Sync succeeded: 9 applied, 0 conflicts", t = 200L),
            )

        val stats = aggregatePairStats(events, windowSize = 1)

        assertEquals(1, stats.runsConsidered)
        assertEquals(1, stats.successCount)
        assertEquals(0L, stats.totalBytesTransferred)
        assertEquals(200L, stats.lastSuccessAtMs)
    }

    @Test
    fun `no success within window leaves lastSuccessAtMs null`() {
        val events =
            listOf(
                event(SyncEventLevel.ERROR, "Sync failed after 5 attempt(s), giving up: x", t = 200L),
                event(SyncEventLevel.WARN, "Sync partial failure: 1 applied, 1 errors — y", t = 100L),
            )

        val stats = aggregatePairStats(events, windowSize = 5)

        assertEquals(2, stats.runsConsidered)
        assertEquals(0, stats.successCount)
        assertEquals(2, stats.failureCount)
        assertNull(stats.lastSuccessAtMs)
    }

    private fun event(
        level: SyncEventLevel,
        message: String,
        t: Long,
        pairId: Long = 1L,
        bytes: Long? = null,
    ) = SyncEvent(
        pairId = pairId,
        timestampMs = t,
        level = level,
        tag = SyncEventTag.SYNC_WORKER,
        message = message,
        bytesTransferred = bytes,
    )
}
