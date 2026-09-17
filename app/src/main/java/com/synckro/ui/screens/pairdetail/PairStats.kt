package com.synckro.ui.screens.pairdetail

import com.synckro.domain.model.SyncEvent
import com.synckro.ui.screens.home.PairSummary
import com.synckro.ui.screens.home.parsePairSummary

/**
 * Aggregate per-pair transfer stats (issue #375 / E6.S2), derived entirely from
 * existing `sync_event` rows.
 *
 * @param runsConsidered        Number of terminal runs found within the window.
 * @param successCount          Runs whose outcome was [PairSummary.Outcome.SUCCESS].
 * @param failureCount          Runs whose outcome was anything else (partial
 *   failure, failure, needs-reauth, needs-relink).
 * @param totalBytesTransferred Sum of upload and download bytes across the window.
 *   Events written before byte tracking, and outcomes without transfer data,
 *   contribute zero. Hard-failure events do not have a completed transfer total.
 * @param lastSuccessAtMs       Timestamp (epoch ms) of the most recent successful
 *   run within the window, or `null` when no success is present.
 */
data class PairStats(
    val runsConsidered: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val totalBytesTransferred: Long = 0,
    val lastSuccessAtMs: Long? = null,
) {
    /** Fraction of considered runs that succeeded, in `[0f, 1f]`; `0f` when no runs. */
    val successRate: Float
        get() = if (runsConsidered == 0) 0f else successCount.toFloat() / runsConsidered
}

/**
 * Walks [events] (newest first, as returned by
 * [com.synckro.data.repository.SyncEventRepository.observeForPair]) and folds the
 * first [windowSize] *terminal* sync runs — i.e. the ones [parsePairSummary]
 * recognises — into a [PairStats] snapshot.
 *
 * Non-terminal rows (progress/debug/instant-sync taxonomy events) are skipped
 * without counting against the window, mirroring [PairSummary]'s existing
 * "most-recent terminal event" convention.
 *
 * @param events     Events for a single pair, newest first.
 * @param windowSize Maximum number of terminal runs to fold into the result.
 */
fun aggregatePairStats(
    events: List<SyncEvent>,
    windowSize: Int = DEFAULT_STATS_WINDOW,
): PairStats {
    var runsConsidered = 0
    var successCount = 0
    var failureCount = 0
    var totalBytesTransferred = 0L
    var lastSuccessAtMs: Long? = null

    for (event in events) {
        if (runsConsidered >= windowSize) break
        val summary = parsePairSummary(event) ?: continue
        runsConsidered++
        totalBytesTransferred += event.bytesTransferred ?: 0L
        if (summary.outcome == PairSummary.Outcome.SUCCESS) {
            successCount++
            if (lastSuccessAtMs == null) {
                lastSuccessAtMs = summary.timestampMs
            }
        } else {
            failureCount++
        }
    }

    return PairStats(
        runsConsidered = runsConsidered,
        successCount = successCount,
        failureCount = failureCount,
        totalBytesTransferred = totalBytesTransferred,
        lastSuccessAtMs = lastSuccessAtMs,
    )
}

/** Default number of most-recent terminal runs folded into a [PairStats] snapshot. */
const val DEFAULT_STATS_WINDOW = 20
