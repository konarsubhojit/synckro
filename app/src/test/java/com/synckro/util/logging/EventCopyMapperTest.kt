package com.synckro.util.logging

import com.synckro.R
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.domain.model.SyncEventTaxonomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [EventCopyMapper] never leaks raw [SyncEventTaxonomy] identifiers, reason
 * tokens, or a [SyncEvent.pairId] into its output, and that every whitelisted
 * tag/level combination either produces a non-empty plain-language string or is
 * explicitly excluded (`null`).
 */
class EventCopyMapperTest {
    // -------------------------------------------------------------------------
    // instant.watch.*
    // -------------------------------------------------------------------------

    @Test
    fun `watch registered maps to a reassuring plain-language message`() {
        val event = instantEvent(SyncEventTag.INSTANT_WATCH, SyncEventTaxonomy.watchRegistered("saf"))

        val copy = EventCopyMapper.map(event)

        assertNotNull(copy)
        assertEquals(R.string.event_copy_watch_registered, copy!!.resId)
        assertNoRawDetail(copy)
    }

    @Test
    fun `watch unavailable due to lost folder access is actionable`() {
        val event = instantEvent(SyncEventTag.INSTANT_WATCH, SyncEventTaxonomy.watchUnavailable("saf_access_lost"))

        val copy = EventCopyMapper.map(event)

        assertNotNull(copy)
        assertEquals(R.string.event_copy_watch_lost_access, copy!!.resId)
        assertNoRawDetail(copy)
    }

    @Test
    fun `watch unavailable due to security exception is actionable`() {
        val event = instantEvent(SyncEventTag.INSTANT_WATCH, SyncEventTaxonomy.watchUnavailable("security_exception"))

        assertEquals(R.string.event_copy_watch_lost_access, EventCopyMapper.map(event)?.resId)
    }

    @Test
    fun `watch unavailable for a non-actionable reason is excluded rather than shown raw`() {
        val reasons = listOf("pair_not_found", "pair_not_watchable", "no_delegate_available")
        reasons.forEach { reason ->
            val event = instantEvent(SyncEventTag.INSTANT_WATCH, SyncEventTaxonomy.watchUnavailable(reason))
            assertNull("reason=$reason must be excluded, not shown raw", EventCopyMapper.map(event))
        }
    }

    // -------------------------------------------------------------------------
    // instant.dispatch.*
    // -------------------------------------------------------------------------

    @Test
    fun `instant dispatch maps to a change-detected message`() {
        val event = instantEvent(SyncEventTag.INSTANT_DISPATCH, SyncEventTaxonomy.dispatchEnqueued("instant"))

        val copy = EventCopyMapper.map(event)

        assertNotNull(copy)
        assertEquals(R.string.event_copy_dispatch_instant, copy!!.resId)
        assertNoRawDetail(copy)
    }

    @Test
    fun `periodic and manual dispatch are excluded to avoid duplicating the sync-started message`() {
        listOf("periodic", "manual", "targeted_upload").forEach { run ->
            val event = instantEvent(SyncEventTag.INSTANT_DISPATCH, SyncEventTaxonomy.dispatchEnqueued(run))
            assertNull(EventCopyMapper.map(event))
        }
    }

    @Test
    fun `dispatch quota fallback is excluded rather than shown as raw taxonomy`() {
        val event =
            instantEvent(
                SyncEventTag.INSTANT_DISPATCH,
                SyncEventTaxonomy.dispatchQuotaFallback("unknown_remote_size"),
                level = SyncEventLevel.WARN,
            )

        assertNull(
            "Taxonomy-tagged WARN events must not fall back to raw passthrough",
            EventCopyMapper.map(event),
        )
    }

    // -------------------------------------------------------------------------
    // instant.outcome.*
    // -------------------------------------------------------------------------

    @Test
    fun `outcome applied maps to a backed-up message`() {
        val event = instantEvent(SyncEventTag.INSTANT_OUTCOME, SyncEventTaxonomy.outcomeApplied("upload"))

        val copy = EventCopyMapper.map(event)

        assertNotNull(copy)
        assertEquals(R.string.event_copy_outcome_applied, copy!!.resId)
    }

    @Test
    fun `outcome failed maps to an actionable retry message`() {
        val event =
            instantEvent(
                SyncEventTag.INSTANT_OUTCOME,
                SyncEventTaxonomy.outcomeFailed("upload", "network_error"),
                level = SyncEventLevel.WARN,
            )

        val copy = EventCopyMapper.map(event)

        assertNotNull(copy)
        assertEquals(R.string.event_copy_outcome_failed, copy!!.resId)
        assertNoRawDetail(copy)
    }

    @Test
    fun `outcome skipped is excluded`() {
        val event = instantEvent(SyncEventTag.INSTANT_OUTCOME, SyncEventTaxonomy.outcomeSkipped("upload", "duplicate"))

        assertNull(EventCopyMapper.map(event))
    }

    // -------------------------------------------------------------------------
    // SyncWorker / Auth passthrough (already plain-language, hand-authored)
    // -------------------------------------------------------------------------

    @Test
    fun `sync worker messages pass through unchanged`() {
        val message = "Sync succeeded: 4 applied, 0 conflicts"
        val event =
            SyncEvent(
                pairId = 7L,
                timestampMs = 0L,
                level = SyncEventLevel.INFO,
                tag = SyncEventTag.SYNC_WORKER,
                message = message,
            )

        val copy = EventCopyMapper.map(event)

        assertNotNull(copy)
        assertEquals(R.string.event_copy_passthrough, copy!!.resId)
        assertEquals(listOf(message), copy.args)
    }

    @Test
    fun `auth messages pass through unchanged`() {
        val message = "Google Drive account needs re-authentication"
        val event =
            SyncEvent(
                pairId = null,
                timestampMs = 0L,
                level = SyncEventLevel.WARN,
                tag = SyncEventTag.AUTH,
                message = message,
            )

        val copy = EventCopyMapper.map(event)

        assertEquals(listOf(message), copy?.args)
    }

    @Test
    fun `blank messages are excluded rather than producing an empty row`() {
        val event =
            SyncEvent(
                pairId = null,
                timestampMs = 0L,
                level = SyncEventLevel.INFO,
                tag = SyncEventTag.SYNC_WORKER,
                message = "   ",
            )

        assertNull(EventCopyMapper.map(event))
    }

    // -------------------------------------------------------------------------
    // WARN/ERROR bypass for other, non-taxonomy tags
    // -------------------------------------------------------------------------

    @Test
    fun `WARN and ERROR events from non-taxonomy tags pass through as-is`() {
        val message = "Sync failed after 5 attempt(s), giving up: timeout"
        val warn =
            SyncEvent(pairId = 1L, timestampMs = 0L, level = SyncEventLevel.WARN, tag = SyncEventTag.OP_APPLIER, message = message)
        val error =
            SyncEvent(pairId = 1L, timestampMs = 0L, level = SyncEventLevel.ERROR, tag = SyncEventTag.OP_APPLIER, message = message)

        assertEquals(listOf(message), EventCopyMapper.map(warn)?.args)
        assertEquals(listOf(message), EventCopyMapper.map(error)?.args)
    }

    @Test
    fun `INFO and DEBUG events from unlisted tags are excluded`() {
        val info =
            SyncEvent(pairId = 1L, timestampMs = 0L, level = SyncEventLevel.INFO, tag = SyncEventTag.SCHEDULER, message = "info")
        val debug =
            SyncEvent(pairId = 1L, timestampMs = 0L, level = SyncEventLevel.DEBUG, tag = SyncEventTag.UI, message = "debug")

        assertNull(EventCopyMapper.map(info))
        assertNull(EventCopyMapper.map(debug))
    }

    // -------------------------------------------------------------------------
    // Full whitelisted tag x level matrix: no raw taxonomy/reason/pairId leaks.
    // -------------------------------------------------------------------------

    @Test
    fun `every whitelisted tag and level combination is safe or excluded`() {
        val whitelistedTags =
            setOf(
                SyncEventTag.SYNC_WORKER,
                SyncEventTag.INSTANT_QUEUE,
                SyncEventTag.INSTANT_WATCH,
                SyncEventTag.INSTANT_DISPATCH,
                SyncEventTag.INSTANT_APPLY,
                SyncEventTag.INSTANT_OUTCOME,
                SyncEventTag.AUTH,
            )
        val sampleMessagesByTag =
            mapOf(
                SyncEventTag.SYNC_WORKER to "Sync started for \"Photos\" (attempt 1)",
                SyncEventTag.INSTANT_QUEUE to SyncEventTaxonomy.queueEnqueued("change"),
                SyncEventTag.INSTANT_WATCH to SyncEventTaxonomy.watchRegistered("saf"),
                SyncEventTag.INSTANT_DISPATCH to SyncEventTaxonomy.dispatchEnqueued("instant"),
                SyncEventTag.INSTANT_APPLY to SyncEventTaxonomy.applyStarted(),
                SyncEventTag.INSTANT_OUTCOME to SyncEventTaxonomy.outcomeApplied("upload"),
                SyncEventTag.AUTH to "Account needs re-authentication",
            )

        for (tag in whitelistedTags) {
            for (level in SyncEventLevel.entries) {
                val event =
                    SyncEvent(
                        pairId = 99L,
                        timestampMs = 0L,
                        level = level,
                        tag = tag,
                        message = requireNotNull(sampleMessagesByTag[tag]),
                    )
                val copy = EventCopyMapper.map(event)
                // Either explicitly excluded (null) or a non-empty, safe plain-language string.
                if (copy != null) {
                    assertTrue("Copy for $tag/$level must not be blank", copy.args.none { it == "" })
                    assertNoRawDetail(copy)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun instantEvent(
        tag: String,
        message: String,
        pairId: Long? = 42L,
        level: SyncEventLevel = SyncEventLevel.INFO,
    ) = SyncEvent(pairId = pairId, timestampMs = 0L, level = level, tag = tag, message = message)

    /** Asserts [copy]'s resolved args never contain a dotted taxonomy id, a reason token, or a pairId. */
    private fun assertNoRawDetail(copy: EventCopyMapper.EventCopy) {
        copy.args.forEach { arg ->
            val text = arg.toString()
            assertFalse("must not contain dotted taxonomy id: $text", Regex("instant\\.[a-z_]+\\.[a-z_]+").containsMatchIn(text))
            assertFalse("must not contain a raw reason token", text.contains("reason="))
            assertFalse("must not contain a pairId token", text.contains("pairId", ignoreCase = true))
            assertFalse("must not contain pair id 42", text.contains("=42"))
        }
    }
}
