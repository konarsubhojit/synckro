package com.synckro.ui.screens.pairdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.data.worker.SyncWorker
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncPair
import com.synckro.domain.sync.TransferProgress
import com.synckro.ui.screens.home.PairSummary
import com.synckro.ui.screens.home.parsePairSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the per-pair detail screen (Phase 5c — issue #163).
 *
 * Aggregates everything the user needs to see for a single sync pair without
 * having to drop back to the Pairs list, the Logs screen and the Conflicts
 * inbox separately:
 *
 *  - the [SyncPair] itself (live from the repository)
 *  - the most recent terminal [PairSummary] parsed from `sync_event`
 *  - the last [RECENT_EVENT_LIMIT] events for the pair
 *  - the count of unresolved conflicts for the pair
 *  - the next-run ETA computed via [SyncScheduler.estimateNextRunAtMs]
 *  - live WorkManager-backed sync progress for periodic and "sync now" runs
 *  - aggregated per-pair transfer [PairStats] over the last [STATS_WINDOW] terminal
 *    runs, computed by [aggregatePairStats] purely in-memory from `sync_event` rows
 *    (issue #375)
 *
 * `pairId` is read from [SavedStateHandle] using the [KEY_PAIR_ID] key so the
 * NavHost can pass it via a path arg without an explicit lambda.
 *
 * No new DB queries are introduced — [SyncEventRepository.observeForPair] and
 * [ConflictRepository.observeForPair] both already exist.  This satisfies the
 * acceptance criterion that aggregation runs on existing IO-bound flows.
 */
@HiltViewModel
class PairDetailViewModel
    @Inject
    constructor(
        @ApplicationContext context: Context,
        savedStateHandle: SavedStateHandle,
        syncPairRepository: SyncPairRepository,
        syncEventRepository: SyncEventRepository,
        conflictRepository: ConflictRepository,
        settingsRepository: SettingsRepository,
        workManager: WorkManager,
    ) : ViewModel() {
        val pairId: Long = savedStateHandle[KEY_PAIR_ID] ?: 0L

        data class UiState(
            val pair: SyncPair? = null,
            val isLoading: Boolean = true,
            /** Non-null when the state flow terminates with an error. */
            val error: String? = null,
            /** Most recent terminal sync result for the pair, or `null` if never synced. */
            val lastSummary: PairSummary? = null,
            /** Last [RECENT_EVENT_LIMIT] events for the pair, newest first. */
            val recentEvents: List<SyncEvent> = emptyList(),
            /** Number of unresolved conflicts for the pair. */
            val unresolvedConflictCount: Int = 0,
            /** Estimated epoch-ms of the next periodic run, or `null` when paused. */
            val nextRunAtMs: Long? = null,
            /** Current global auto-sync flag — surfaced so the screen can explain a null ETA. */
            val globalAutoSyncEnabled: Boolean = true,
            /** True while either the periodic or one-shot sync worker is queued/running. */
            val isSyncing: Boolean = false,
            /** Live transfer progress from WorkManager while a sync is actively running. */
            val progress: TransferProgress? = null,
            /** Aggregated per-pair stats over the last [STATS_WINDOW] runs (issue #375). */
            val stats: PairStats = PairStats(),
        )

        private data class WorkProgressState(
            val isSyncing: Boolean = false,
            val progress: TransferProgress? = null,
        )

        // Use observeAll() so the screen stays live across sync-now / auto-sync runs
        // (the worker updates lastSyncAtMs / lastSyncResult on the row, which re-emits
        // here). The repository version also evaluates `needsReLink` for free.
        private val pairFlow =
            syncPairRepository.observeAll(context.contentResolver)
                .map { all -> all.firstOrNull { it.id == pairId } }

        private val workInfoFlow =
            combine(
                workManager
                    .getWorkInfosForUniqueWorkFlow(SyncWorker.uniqueName(pairId))
                    .catch { emit(emptyList()) },
                workManager
                    .getWorkInfosForUniqueWorkFlow(SyncWorker.syncNowUniqueName(pairId))
                    .catch { emit(emptyList()) },
            ) { periodicInfos, syncNowInfos ->
                val infos = periodicInfos + syncNowInfos
                val runningInfo = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                WorkProgressState(
                    isSyncing = infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED },
                    progress = runningInfo?.let { SyncWorker.parseProgress(it.progress) },
                )
            }

        // Wider window than RECENT_EVENT_LIMIT so STATS_WINDOW terminal runs are
        // reliably captured even with non-terminal rows (progress/debug/instant-sync
        // taxonomy events) interspersed between them. STATS_EVENT_LIMIT scales with
        // STATS_WINDOW (see STATS_EVENT_LIMIT_MULTIPLIER) rather than being a fixed
        // constant, but it is still a heuristic: a pair whose non-terminal row count
        // exceeds the multiplier between terminal runs (e.g. very chatty Instant Sync
        // taxonomy logging) can under-fill the window. aggregatePairStats() degrades
        // gracefully in that case — runsConsidered is simply lower than STATS_WINDOW
        // rather than throwing or padding with stale data.
        private val statsFlow =
            syncEventRepository.observeForPair(pairId, STATS_EVENT_LIMIT)
                .map { events ->
                    val stats = aggregatePairStats(events, STATS_WINDOW)
                    // Only log when the fetch itself was truncated (fetched rows ==
                    // the limit) — that's the only case where under-filling could be
                    // a truncation artifact rather than the pair simply not having
                    // STATS_WINDOW terminal runs in its whole history yet.
                    if (stats.runsConsidered < STATS_WINDOW && events.size >= STATS_EVENT_LIMIT) {
                        Timber.d(
                            "PairDetailViewModel: pair %d stats window under-filled (%d/%d terminal runs " +
                                "found in %d fetched rows) — consider raising STATS_EVENT_LIMIT_MULTIPLIER",
                            pairId,
                            stats.runsConsidered,
                            STATS_WINDOW,
                            STATS_EVENT_LIMIT,
                        )
                    }
                    stats
                }

        val state: StateFlow<UiState> =
            combine(
                pairFlow,
                syncEventRepository.observeForPair(pairId, RECENT_EVENT_LIMIT),
                conflictRepository.observeForPair(pairId)
                    .map { records -> records.count { it.resolution == null } },
                settingsRepository.globalAutoSyncEnabled,
                workInfoFlow,
            ) { pair, events, conflictCount, globalEnabled, workProgress ->
                val summary = events.firstNotNullOfOrNull { parsePairSummary(it) }
                val now = System.currentTimeMillis()
                val nextRun =
                    pair?.let {
                        SyncScheduler.estimateNextRunAtMs(
                            pair = it,
                            nowMs = now,
                            globalAutoSyncEnabled = globalEnabled,
                        )
                    }
                UiState(
                    pair = pair,
                    isLoading = false,
                    lastSummary = summary,
                    recentEvents = events,
                    unresolvedConflictCount = conflictCount,
                    nextRunAtMs = nextRun,
                    globalAutoSyncEnabled = globalEnabled,
                    isSyncing = workProgress.isSyncing,
                    progress = workProgress.progress,
                )
            }.combine(statsFlow) { state, stats -> state.copy(stats = stats) }
                .catch { e ->
                    Timber.w(e, "PairDetailViewModel: state flow error")
                    emit(UiState(isLoading = false, error = e.message ?: e.javaClass.simpleName))
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = UiState(),
                )

        companion object {
            const val KEY_PAIR_ID = "pairId"

            /** Number of recent events shown in the timeline section. */
            const val RECENT_EVENT_LIMIT = 5

            /** Number of most-recent terminal runs folded into [UiState.stats]. */
            const val STATS_WINDOW = DEFAULT_STATS_WINDOW

            /**
             * How many non-terminal rows we expect, on average, per terminal run —
             * used to size [STATS_EVENT_LIMIT] so the fetch scales with [STATS_WINDOW]
             * instead of being a fixed constant that silently under-fills for larger
             * windows. Chosen generously relative to observed `SyncWorker` logging:
             * a normal periodic run logs at most one or two non-terminal rows before
             * its terminal one (e.g. a single "Sync started …"), and even bursty
             * Instant Sync retry/deferral chains (`SyncEventTag.INSTANT_STABILITY`)
             * are individually rate-limited by
             * [com.synckro.data.repository.SyncEventRepository.logRateLimited]. If a
             * pair's non-terminal volume ever regresses past this ratio,
             * [statsFlow]'s under-fill log line below surfaces it for retuning rather
             * than silently reporting a smaller-than-requested window.
             */
            private const val STATS_EVENT_LIMIT_MULTIPLIER = 10

            /** Row limit passed to [SyncEventRepository.observeForPair] for [statsFlow]. */
            const val STATS_EVENT_LIMIT = STATS_WINDOW * STATS_EVENT_LIMIT_MULTIPLIER
        }
    }
