package com.synckro.ui.screens.home

import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for the [PairSummary] aggregator and [parsePairSummary] message
 * parser (Phase 5a). No Android dependencies — these run under the default
 * unit-test source set without Robolectric.
 */
class PairSummaryTest {
    @Test
    fun `parses succeeded message into SUCCESS outcome with applied and conflicts`() {
        val event = event(
            pairId = 1L,
            tag = SyncEventTag.SyncWorker,
            level = SyncEventLevel.INFO,
            message = "Sync succeeded: 12 applied, 3 conflicts",
        )
        val summary = parsePairSummary(event)!!
        assertEquals(PairSummary.Outcome.SUCCESS, summary.outcome)
        assertEquals(12, summary.applied)
        assertEquals(3, summary.conflicts)
        assertEquals(0, summary.errors)
    }

    @Test
    fun `parses partial failure message into PARTIAL_FAILURE outcome with errors`() {
        val event = event(
            pairId = 2L,
            tag = SyncEventTag.SyncWorker,
            level = SyncEventLevel.WARN,
            message = "Sync partial failure: 4 applied, 2 errors — boom; bang",
        )
        val summary = parsePairSummary(event)!!
        assertEquals(PairSummary.Outcome.PARTIAL_FAILURE, summary.outcome)
        assertEquals(4, summary.applied)
        assertEquals(2, summary.errors)
        assertEquals(0, summary.conflicts)
    }

    @Test
    fun `parses retry exhaustion message into FAILURE outcome`() {
        val event = event(
            pairId = 3L,
            tag = SyncEventTag.SyncWorker,
            level = SyncEventLevel.ERROR,
            message = "Sync failed after 5 attempt(s), giving up: timeout",
        )
        val summary = parsePairSummary(event)!!
        assertEquals(PairSummary.Outcome.FAILURE, summary.outcome)
    }

    @Test
    fun `parses needs-reauth message into NEEDS_REAUTH outcome`() {
        val event = event(
            pairId = 4L,
            tag = SyncEventTag.Auth,
            level = SyncEventLevel.ERROR,
            message = "Re-authentication required: token revoked",
        )
        val summary = parsePairSummary(event)!!
        assertEquals(PairSummary.Outcome.NEEDS_REAUTH, summary.outcome)
    }

    @Test
    fun `parses needs-relink message into NEEDS_RELINK outcome`() {
        val event = event(
            pairId = 5L,
            tag = SyncEventTag.SyncWorker,
            level = SyncEventLevel.ERROR,
            message = "Local folder access lost, re-link required: revoked",
        )
        val summary = parsePairSummary(event)!!
        assertEquals(PairSummary.Outcome.NEEDS_RELINK, summary.outcome)
    }

    @Test
    fun `non-terminal messages return null`() {
        assertNull(
            parsePairSummary(
                event(
                    pairId = 1L,
                    tag = SyncEventTag.SyncWorker,
                    level = SyncEventLevel.INFO,
                    message = "Sync started for \"My pair\" (attempt 1)",
                ),
            ),
        )
        assertNull(
            parsePairSummary(
                event(
                    pairId = 1L,
                    tag = SyncEventTag.SyncWorker,
                    level = SyncEventLevel.WARN,
                    message = "Sync retriable, will retry (attempt 2/5): network",
                ),
            ),
        )
    }

    @Test
    fun `events from unrelated tags are ignored`() {
        assertNull(
            parsePairSummary(
                event(
                    pairId = 1L,
                    tag = SyncEventTag.UI,
                    level = SyncEventLevel.INFO,
                    message = "Sync succeeded: 1 applied, 0 conflicts",
                ),
            ),
        )
    }

    @Test
    fun `aggregator picks the newest terminal event per pair`() {
        // Newest first, matching SyncEventRepository.observeAll() order.
        val events = listOf(
            event(1L, SyncEventTag.SyncWorker, SyncEventLevel.INFO, "Sync succeeded: 5 applied, 0 conflicts", t = 300L),
            event(1L, SyncEventTag.SyncWorker, SyncEventLevel.WARN, "Sync partial failure: 1 applied, 2 errors — x", t = 200L),
            event(2L, SyncEventTag.SyncWorker, SyncEventLevel.INFO, "Sync succeeded: 7 applied, 1 conflicts", t = 100L),
            event(pairId = null, tag = SyncEventTag.UI, level = SyncEventLevel.INFO, message = "global noise", t = 400L),
        )
        val map = aggregatePairSummaries(events)
        assertEquals(2, map.size)
        assertEquals(5, map[1L]!!.applied)
        assertEquals(PairSummary.Outcome.SUCCESS, map[1L]!!.outcome)
        assertEquals(300L, map[1L]!!.timestampMs)
        assertEquals(7, map[2L]!!.applied)
    }

    @Test
    fun `aggregator skips non-terminal events and walks until a terminal one is found`() {
        val events = listOf(
            event(1L, SyncEventTag.SyncWorker, SyncEventLevel.INFO, "Sync started for \"x\" (attempt 1)", t = 300L),
            event(1L, SyncEventTag.SyncWorker, SyncEventLevel.WARN, "Sync retriable, will retry (attempt 2/5): n", t = 250L),
            event(1L, SyncEventTag.SyncWorker, SyncEventLevel.INFO, "Sync succeeded: 9 applied, 0 conflicts", t = 200L),
        )
        val summary = aggregatePairSummaries(events)[1L]!!
        assertEquals(PairSummary.Outcome.SUCCESS, summary.outcome)
        assertEquals(9, summary.applied)
        assertEquals(200L, summary.timestampMs)
    }

    private fun event(
        pairId: Long?,
        tag: String,
        level: SyncEventLevel,
        message: String,
        t: Long = 0L,
    ) = SyncEvent(pairId = pairId, timestampMs = t, level = level, tag = tag, message = message)
}
