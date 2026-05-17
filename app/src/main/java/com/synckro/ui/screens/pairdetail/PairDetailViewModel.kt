package com.synckro.ui.screens.pairdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncPair
import com.synckro.ui.screens.home.PairSummary
import com.synckro.ui.screens.home.parsePairSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    ) : ViewModel() {
        val pairId: Long = savedStateHandle[KEY_PAIR_ID] ?: 0L

        data class UiState(
            val pair: SyncPair? = null,
            val isLoading: Boolean = true,
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
        )

        // Use observeAll() so the screen stays live across sync-now / auto-sync runs
        // (the worker updates lastSyncAtMs / lastSyncResult on the row, which re-emits
        // here). The repository version also evaluates `needsReLink` for free.
        private val pairFlow =
            syncPairRepository.observeAll(context.contentResolver)
                .map { all -> all.firstOrNull { it.id == pairId } }

        val state: StateFlow<UiState> =
            combine(
                pairFlow,
                syncEventRepository.observeForPair(pairId, RECENT_EVENT_LIMIT),
                conflictRepository.observeForPair(pairId)
                    .map { records -> records.count { it.resolution == null } },
                settingsRepository.globalAutoSyncEnabled,
            ) { pair, events, conflictCount, globalEnabled ->
                val summary = events.firstNotNullOfOrNull { parsePairSummary(it) }
                val now = System.currentTimeMillis()
                val nextRun = pair?.let {
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
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = UiState(),
            )

        companion object {
            const val KEY_PAIR_ID = "pairId"

            /** Number of recent events shown in the timeline section. */
            const val RECENT_EVENT_LIMIT = 5
        }
    }

