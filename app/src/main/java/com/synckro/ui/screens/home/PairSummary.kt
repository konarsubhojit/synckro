package com.synckro.ui.screens.home

import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag

/**
 * Last-result summary surfaced on a pair card (Phase 5a).
 *
 * Parsed in-memory from the most-recent terminal [SyncEvent] for a pair.
 * Kept platform-free so it can be unit-tested without Android dependencies.
 *
 * @param applied    Number of operations applied (uploads + downloads + deletes).
 * @param conflicts  Number of conflicts detected during the run.
 * @param errors     Number of partial-failure errors during the run.
 * @param timestampMs Event time used to render the "x minutes ago" label.
 * @param outcome    Coarse outcome category derived from the message.
 */
data class PairSummary(
    val applied: Int,
    val conflicts: Int,
    val errors: Int,
    val timestampMs: Long,
    val outcome: Outcome,
) {
    enum class Outcome { SUCCESS, PARTIAL_FAILURE, FAILURE, NEEDS_REAUTH, NEEDS_RELINK }
}

/**
 * Pure-Kotlin aggregator that walks the global event stream (newest first) and
 * picks, per `pairId`, the most-recent **terminal** event — i.e. the line written
 * by `SyncWorker` at the end of a run.  Non-terminal events ("Sync started…",
 * "Retrying sync…") are skipped so the card never flips between "in-progress"
 * and "success" messages mid-sync.
 *
 * Returning a plain `Map<Long, PairSummary>` makes this trivial to test from a
 * JVM unit test; no flows or dispatchers required.
 *
 * @param events Events newest-first (the natural order from
 *   [com.synckro.data.repository.SyncEventRepository.observeAll]).
 */
fun aggregatePairSummaries(events: List<SyncEvent>): Map<Long, PairSummary> {
    val out = LinkedHashMap<Long, PairSummary>()
    for (e in events) {
        val pairId = e.pairId ?: continue
        if (pairId in out) continue
        val summary = parsePairSummary(e) ?: continue
        out[pairId] = summary
    }
    return out
}

/**
 * Maps a single [SyncEvent] to a [PairSummary], or `null` when the event is not
 * a terminal sync result.  Messages are produced by
 * [com.synckro.data.worker.SyncWorker.doWork]; the format is stable and tested
 * by `HomeViewModelTest`.
 */
fun parsePairSummary(event: SyncEvent): PairSummary? {
    val tag = event.tag
    if (tag != SyncEventTag.SyncWorker && tag != SyncEventTag.Auth) return null
    val msg = event.message
    val outcome =
        when {
            msg.startsWith("Sync succeeded:") -> PairSummary.Outcome.SUCCESS
            msg.startsWith("Sync partial failure:") -> PairSummary.Outcome.PARTIAL_FAILURE
            msg.startsWith("Sync failed after") -> PairSummary.Outcome.FAILURE
            msg.startsWith("Sync failed (terminal):") -> PairSummary.Outcome.FAILURE
            msg.startsWith("Re-authentication required:") -> PairSummary.Outcome.NEEDS_REAUTH
            msg.startsWith("Local folder access lost") -> PairSummary.Outcome.NEEDS_RELINK
            else -> return null
        }
    // Severity sanity-check so a stray INFO row with one of the above prefixes
    // (test fixtures, manual log writes) doesn't masquerade as a real outcome.
    when (outcome) {
        PairSummary.Outcome.SUCCESS -> if (event.level != SyncEventLevel.INFO) return null
        PairSummary.Outcome.PARTIAL_FAILURE -> if (event.level != SyncEventLevel.WARN) return null
        else -> if (event.level != SyncEventLevel.ERROR) return null
    }
    return PairSummary(
        applied = extractCount(msg, "applied"),
        conflicts = extractCount(msg, "conflicts"),
        errors = extractCount(msg, "errors"),
        timestampMs = event.timestampMs,
        outcome = outcome,
    )
}

/**
 * Extracts the integer immediately preceding [keyword] in [msg], or `0` when
 * the message does not contain a matching `<digits> <keyword>` token.
 */
private fun extractCount(msg: String, keyword: String): Int {
    val regex = Regex("(\\d+)\\s+$keyword")
    return regex.find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
