package com.synckro.util.logging

import com.synckro.R
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.domain.model.SyncEventTaxonomy

/**
 * Converts a [SyncEvent] that is already visible to the Sync history list (see
 * [LogVisibilityConfig.isUserFacing]) into plain-language copy suitable for a
 * non-technical user, or `null` when there is no sensible plain-language
 * rendering — such events are omitted from the simplified Sync history rather
 * than shown with a raw taxonomy string.
 *
 * Only [SyncEventTag.INSTANT_WATCH], [SyncEventTag.INSTANT_DISPATCH], and
 * [SyncEventTag.INSTANT_OUTCOME] events carry the dotted [SyncEventTaxonomy]
 * identifiers this mapper rewrites. [SyncEventTag.SYNC_WORKER] and
 * [SyncEventTag.AUTH] events (and any other tag surfaced only via the
 * WARN/ERROR level bypass, see [LogVisibilityConfig.tagBypassMinLevel]) already
 * use plain, human-authored messages and pass through unchanged.
 *
 * This mapper never emits dotted taxonomy identifiers, raw reason tokens, or a
 * [SyncEvent.pairId] — output is either a curated, resource-backed [EventCopy]
 * or `null`. Persistence and [LogExporter] output are unaffected: every event
 * keeps its full raw [SyncEvent.message] and [SyncEvent.tag] in the
 * `sync_event` table and in exports; only the on-screen simplified Sync
 * history uses this mapper.
 */
object EventCopyMapper {
    /** Plain-language rendering of a [SyncEvent], resolved via [android.content.res.Resources]. */
    data class EventCopy(
        val resId: Int,
        val args: List<Any> = emptyList(),
    )

    /** Tags whose raw [SyncEvent.message] is dotted [SyncEventTaxonomy] rather than plain text. */
    private val taxonomyTags =
        setOf(
            SyncEventTag.INSTANT_WATCHER,
            SyncEventTag.INSTANT_WATCH,
            SyncEventTag.INSTANT_STABILITY,
            SyncEventTag.INSTANT_QUEUE,
            SyncEventTag.INSTANT_DISPATCH,
            SyncEventTag.INSTANT_APPLY,
            SyncEventTag.INSTANT_OUTCOME,
        )

    /** Maps [event] to plain-language copy, or `null` to exclude it from the simplified history. */
    fun map(event: SyncEvent): EventCopy? {
        val curated =
            when (event.tag) {
                SyncEventTag.INSTANT_WATCH -> mapWatch(event.message)
                SyncEventTag.INSTANT_DISPATCH -> mapDispatch(event.message)
                SyncEventTag.INSTANT_OUTCOME -> mapOutcome(event.message)
                SyncEventTag.SYNC_WORKER, SyncEventTag.AUTH -> passthrough(event.message)
                else -> null
            }
        if (curated != null) return curated
        // Any tag not covered above that still reached the Sync history did so via the
        // WARN/ERROR level bypass (see LogVisibilityConfig.isUserFacing). Taxonomy-tagged
        // events always use dotted identifiers, so they are excluded rather than shown raw;
        // every other tag's WARN/ERROR messages are hand-authored plain text and pass through.
        if (event.tag in taxonomyTags) return null
        return if (event.level.ordinal >= SyncEventLevel.WARN.ordinal) passthrough(event.message) else null
    }

    private fun mapWatch(message: String): EventCopy? {
        val (name, fields) = parse(message)
        return when (name) {
            SyncEventTaxonomy.WATCH_REGISTERED -> EventCopy(R.string.event_copy_watch_registered)
            SyncEventTaxonomy.WATCH_UNAVAILABLE ->
                when (fields["reason"]) {
                    "saf_access_lost", "security_exception" -> EventCopy(R.string.event_copy_watch_lost_access)
                    else -> null
                }
            else -> null
        }
    }

    private fun mapDispatch(message: String): EventCopy? {
        val (name, fields) = parse(message)
        return if (name == SyncEventTaxonomy.DISPATCH_ENQUEUED && fields["run"] == "instant") {
            EventCopy(R.string.event_copy_dispatch_instant)
        } else {
            null
        }
    }

    private fun mapOutcome(message: String): EventCopy? {
        val (name, _) = parse(message)
        return when (name) {
            SyncEventTaxonomy.OUTCOME_APPLIED -> EventCopy(R.string.event_copy_outcome_applied)
            SyncEventTaxonomy.OUTCOME_FAILED -> EventCopy(R.string.event_copy_outcome_failed)
            // OUTCOME_SKIPPED (dedup / no-op) has no actionable or reassuring copy; exclude it.
            else -> null
        }
    }

    private fun passthrough(message: String): EventCopy? =
        message.takeIf { it.isNotBlank() }?.let { EventCopy(R.string.event_copy_passthrough, listOf(it)) }

    /** Splits a [SyncEventTaxonomy.format]-built message into its event name and `key=value` fields. */
    private fun parse(message: String): Pair<String, Map<String, String>> {
        val tokens = message.trim().split(Regex("\\s+"))
        val name = tokens.firstOrNull().orEmpty()
        val fields =
            tokens.drop(1).mapNotNull { token ->
                val idx = token.indexOf('=')
                if (idx <= 0) null else token.substring(0, idx) to token.substring(idx + 1)
            }.toMap()
        return name to fields
    }
}
