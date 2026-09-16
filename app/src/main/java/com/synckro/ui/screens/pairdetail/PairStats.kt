package com.synckro.ui.screens.pairdetail

import com.synckro.domain.model.SyncEvent
import com.synckro.ui.screens.home.PairSummary
import com.synckro.ui.screens.home.parsePairSummary

/**
 * Aggregate per-pair transfer stats (issue #375 / E6.S2), derived entirely from
 * existing `sync_event` rows — no new Room entity or migration is introduced.
 *
 * [SyncEventEntity][com.synckro.data.local.entity.SyncEventEntity] does not carry a
 * byte-count column: `SyncWorker` only logs the number of *applied* operations
 * (uploads + downloads + deletes) in its terminal "Sync succeeded …" / "Sync
 * partial failure …" messages, the same text [parsePairSummary] already parses
 * for the Home/Pair-detail "last result" cards. Rather than add a new column
 * purely to rename that count as "bytes", this aggregator reports
 * [totalFilesTransferred] (sum of [PairSummary.applied] across the window) as the
 * transfer-volume signal. If true byte totals become necessary, `SyncWorker`
 * would need to persist [com.synckro.domain.sync.TransferProgress.bytesTransferred]
 * into a new `sync_event` column (or a dedicated stats table) in a follow-up
 * migration — out of scope for this story per its "no new entity unless
 * genuinely required" acceptance criterion.
 *
 * @param runsConsidered        Number of terminal runs found within the window.
 * @param successCount          Runs whose outcome was [PairSummary.Outcome.SUCCESS].
 * @param failureCount          Runs whose outcome was anything else (partial
 *   failure, failure, needs-reauth, needs-relink).
 * @param totalFilesTransferred Sum of applied file operations across the window.
 * @param lastSuccessAtMs       Timestamp (epoch ms) of the most recent successful
 *   run within the window, or `null` when no success is present.
 */
data class PairStats(
    val runsConsidered: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val totalFilesTransferred: Int = 0,
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
    var totalFilesTransferred = 0
    var lastSuccessAtMs: Long? = null

    for (event in events) {
        if (runsConsidered >= windowSize) break
        val summary = parsePairSummary(event) ?: continue
        runsConsidered++
        totalFilesTransferred += summary.applied
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
        totalFilesTransferred = totalFilesTransferred,
        lastSuccessAtMs = lastSuccessAtMs,
    )
}

/** Default number of most-recent terminal runs folded into a [PairStats] snapshot. */
const val DEFAULT_STATS_WINDOW = 20
