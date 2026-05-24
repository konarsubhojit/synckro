package com.synckro.ui.screens.status

import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.SyncPair
import com.synckro.ui.screens.home.PairSummary

/**
 * Pure-Kotlin view-model for the Phase 2 Status screen.
 *
 * Aggregates fields already exposed by
 * [com.synckro.ui.screens.home.HomeViewModel.UiState] into the structured rows
 * the redesigned screen renders inside each info card. Keeping the math out
 * of `@Composable` code lets us unit-test it on the JVM (no Android imports).
 */
data class StatusOverview(
    /** Aggregated counters for the "Sync status" card. */
    val syncStatus: SyncStatus,
    /** Aggregated counters for the "Recent changes" card. */
    val recentChanges: RecentChanges,
    /** One row per (provider, account) pair for the "Account info" card. */
    val accountRows: List<AccountRow>,
    /** Aggregated counters for the bottom dismissible warning banner. */
    val warnings: Warnings,
) {
    /**
     * @param totalPairs  Total number of configured sync pairs.
     * @param syncingPairs Pairs currently running a sync.
     * @param filesCompleted Sum of completed file ops across the active runs.
     * @param totalFiles  Sum of total file ops across the active runs.
     * @param bytesTransferred Sum of bytes transferred across the active runs.
     * @param totalBytes  Sum of total bytes expected across the active runs.
     */
    data class SyncStatus(
        val totalPairs: Int,
        val syncingPairs: Int,
        val filesCompleted: Int,
        val totalFiles: Int,
        val bytesTransferred: Long,
        val totalBytes: Long,
    ) {
        /** True when at least one pair is actively transferring. */
        val isSyncing: Boolean get() = syncingPairs > 0

        /**
         * Fraction of the in-flight batch completed, preferring byte-based
         * progress (matches the per-pair indicator on the Pairs screen).
         * Returns `null` for indeterminate progress.
         */
        val fraction: Float?
            get() =
                when {
                    totalBytes > 0L ->
                        (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    totalFiles > 0 ->
                        (filesCompleted.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
                    else -> null
                }
    }

    /**
     * @param applied   Total ops applied across the last terminal run of every pair.
     * @param conflicts Total conflicts detected across the last terminal runs.
     * @param errors    Total partial-failure errors across the last terminal runs.
     * @param pairsWithChanges Number of pairs that have at least one terminal run.
     * @param lastTimestampMs Most-recent terminal-event timestamp across all pairs,
     *   or `null` when no pair has ever completed a run.
     */
    data class RecentChanges(
        val applied: Int,
        val conflicts: Int,
        val errors: Int,
        val pairsWithChanges: Int,
        val lastTimestampMs: Long?,
    ) {
        val hasAnyHistory: Boolean get() = pairsWithChanges > 0
    }

    /**
     * One row in the "Account info" card. Synthesised from connected sync
     * pairs plus persisted accounts, so accounts appear even before the first
     * pair is configured. The email/display label is looked up from
     * [com.synckro.ui.screens.home.HomeViewModel.UiState.accountEmailById].
     *
     * @param provider The cloud provider this account belongs to.
     * @param accountId The provider-issued account id, or `null` for pairs
     *   that lost their account binding (rendered as "needs re-link").
     * @param email Display email (or display name) for the account.
     * @param pairCount Number of sync pairs bound to this account.
     */
    data class AccountRow(
        val provider: CloudProviderType,
        val accountId: String?,
        val email: String?,
        val pairCount: Int,
    )

    /**
     * Counters used by the dismissible bottom banner.
     *
     * @param pendingConflicts Number of unresolved conflicts across all pairs.
     * @param pairsNeedingReauth Pairs whose last run reported `NEEDS_REAUTH`.
     * @param pairsNeedingRelink Pairs whose persisted SAF permission was lost.
     */
    data class Warnings(
        val pendingConflicts: Int,
        val pairsNeedingReauth: Int,
        val pairsNeedingRelink: Int,
    ) {
        val hasAny: Boolean
            get() = pendingConflicts > 0 || pairsNeedingReauth > 0 || pairsNeedingRelink > 0
    }
}

/**
 * Aggregates the inputs exposed by `HomeViewModel.UiState` into a single
 * [StatusOverview] for the Status screen. All inputs map 1:1 to fields on
 * the existing UI state so this function stays pure and trivial to test.
 */
fun buildStatusOverview(
    pairs: List<SyncPair>,
    syncingPairIds: Set<Long>,
    progressByPairId: Map<Long, com.synckro.domain.sync.TransferProgress>,
    lastSummaryByPairId: Map<Long, PairSummary>,
    accountEmailById: Map<String, String>,
    accountProviderById: Map<String, CloudProviderType>,
    pendingConflictCount: Int,
): StatusOverview {
    // --- Sync status ---
    var filesCompleted = 0
    var totalFiles = 0
    var bytesTransferred = 0L
    var totalBytes = 0L
    for (id in syncingPairIds) {
        val p = progressByPairId[id] ?: continue
        filesCompleted += p.filesCompleted
        totalFiles += p.totalFiles
        bytesTransferred += p.bytesTransferred
        totalBytes += p.totalBytes
    }
    val syncStatus =
        StatusOverview.SyncStatus(
            totalPairs = pairs.size,
            syncingPairs = syncingPairIds.size,
            filesCompleted = filesCompleted,
            totalFiles = totalFiles,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes,
        )

    // --- Recent changes ---
    var applied = 0
    var conflicts = 0
    var errors = 0
    var lastTimestampMs: Long? = null
    for (s in lastSummaryByPairId.values) {
        applied += s.applied
        conflicts += s.conflicts
        errors += s.errors
        val ts = s.timestampMs
        if (lastTimestampMs == null || ts > lastTimestampMs) lastTimestampMs = ts
    }
    val recentChanges =
        StatusOverview.RecentChanges(
            applied = applied,
            conflicts = conflicts,
            errors = errors,
            pairsWithChanges = lastSummaryByPairId.size,
            lastTimestampMs = lastTimestampMs,
        )

    // --- Account info (grouped by provider + accountId) ---
    val countByKey = LinkedHashMap<Pair<CloudProviderType, String?>, Int>()
    for (p in pairs) {
        val key = p.provider to p.accountId
        countByKey[key] = (countByKey[key] ?: 0) + 1
    }
    for ((accountId, provider) in accountProviderById) {
        val key = provider to accountId
        if (key !in countByKey) countByKey[key] = 0
    }
    val accountRows =
        countByKey.map { (key, count) ->
            val (provider, accountId) = key
            StatusOverview.AccountRow(
                provider = provider,
                accountId = accountId,
                email = accountId?.let { accountEmailById[it] },
                pairCount = count,
            )
        }

    // --- Warnings ---
    var needsReauth = 0
    var needsRelink = 0
    for (p in pairs) {
        if (p.needsReLink) needsRelink++
        if (p.lastSyncResult == "NEEDS_REAUTH") needsReauth++
    }
    val warnings =
        StatusOverview.Warnings(
            pendingConflicts = pendingConflictCount,
            pairsNeedingReauth = needsReauth,
            pairsNeedingRelink = needsRelink,
        )

    return StatusOverview(
        syncStatus = syncStatus,
        recentChanges = recentChanges,
        accountRows = accountRows,
        warnings = warnings,
    )
}
