package com.synckro.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import com.synckro.data.repository.AccountRepository
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.data.worker.SyncWorker
import com.synckro.domain.model.SyncPair
import com.synckro.domain.sync.TransferProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the home / pair-list screen. Observes the [SyncPairRepository] and
 * exposes actions for deleting pairs and triggering one-shot syncs.
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val syncPairRepository: SyncPairRepository,
        private val conflictRepository: ConflictRepository,
        private val workManager: WorkManager,
        private val syncScheduler: SyncScheduler,
        private val accountRepository: AccountRepository,
        private val settingsRepository: SettingsRepository,
        private val syncEventRepository: SyncEventRepository,
    ) : ViewModel() {
        /**
         * Soft-deleted pair waiting for the undo grace window to expire. Surfaced via
         * [UiState.pendingDelete] so the home screen can render an "undo" snackbar
         * (Material 3 snackbar pattern).
         */
        data class PendingDelete(val pair: SyncPair)

        data class UiState(
            val pairs: List<SyncPair> = emptyList(),
            val isLoading: Boolean = true,
            /** Number of pending (unresolved) conflicts across all pairs. */
            val pendingConflictCount: Int = 0,
            /** IDs of pairs that currently have an in-flight one-shot "sync now" job. */
            val syncingPairIds: Set<Long> = emptySet(),
            /** Non-null while a soft-deleted pair is still within the undo window. */
            val pendingDelete: PendingDelete? = null,
            /** Maps account ID → display email/name for showing account context on each pair card. */
            val accountEmailById: Map<String, String> = emptyMap(),
            /** Whether global auto-sync is currently enabled. */
            val globalAutoSyncEnabled: Boolean = true,
            /**
             * Map of `pairId` → estimated epoch-ms of the next periodic run, used to
             * render the "Next sync in ~N min" line on each pair card. Pairs whose
             * auto-sync is paused (globally or per-pair) are absent from the map.
             */
            val nextRunByPairId: Map<Long, Long> = emptyMap(),
            /**
             * Map of `pairId` → most-recent terminal [PairSummary] (parsed from the
             * `sync_event` table). Pairs that have never completed a run are absent.
             */
            val lastSummaryByPairId: Map<Long, PairSummary> = emptyMap(),
            /** `true` when at least one terminal sync run exists in history. */
            val hasCompletedSyncRun: Boolean = false,
            /** Non-null when onboarding has been completed/skipped on this install. */
            val onboardingCompletedAtMs: Long? = null,
            /** One-shot coach-tooltip ids already shown to the user. */
            val seenTooltips: Set<String> = emptySet(),
            /** Live transfer progress for each syncing pair; absent when unavailable or finished. */
            val progressByPairId: Map<Long, TransferProgress> = emptyMap(),
        )

        /** Pair IDs that have an active "sync now" run; updated optimistically. */
        private val syncingIds = MutableStateFlow<Set<Long>>(emptySet())

        /** Live transfer progress emitted by WorkManager for currently syncing pairs. */
        private val pairProgress = MutableStateFlow<Map<Long, TransferProgress>>(emptyMap())

        /** Pair IDs hidden from the visible list because they are pending soft-deletion. */
        private val hiddenIds = MutableStateFlow<Set<Long>>(emptySet())

        /** Currently pending soft-delete (if any). */
        private val pendingDeleteState = MutableStateFlow<PendingDelete?>(null)

        /** Coroutine that commits the pending soft-delete after [UNDO_WINDOW_MS]. */
        private var pendingDeleteJob: Job? = null

        /** Maps accountId → display email/name, kept in sync with the accounts table. */
        private val accountEmailById = MutableStateFlow<Map<String, String>>(emptyMap())

        /**
         * Stream of recent terminal sync events used to compute
         * [UiState.lastSummaryByPairId]. We cap the query at [SUMMARY_EVENT_LIMIT]
         * rows because we only need the newest terminal entry per pair; pulling the
         * full 5 000-row log into memory on every pair-card recomposition would be
         * wasteful.
         */
        private val recentEvents = syncEventRepository.observeAll(SUMMARY_EVENT_LIMIT)

        val state: StateFlow<UiState> =
            combine(
                syncPairRepository.observeAll(context.contentResolver),
                conflictRepository.observeUnresolvedCount(),
                syncingIds,
                hiddenIds,
                pendingDeleteState,
            ) { pairs, conflictCount, syncing, hidden, pending ->
                UiState(
                    pairs = pairs.filter { it.id !in hidden },
                    isLoading = false,
                    pendingConflictCount = conflictCount,
                    syncingPairIds = syncing,
                    pendingDelete = pending,
                )
            }.combine(accountEmailById) { uiState, emailMap ->
                uiState.copy(accountEmailById = emailMap)
            }.combine(settingsRepository.globalAutoSyncEnabled) { uiState, globalEnabled ->
                uiState.copy(
                    globalAutoSyncEnabled = globalEnabled,
                    nextRunByPairId = computeNextRunMap(uiState.pairs, globalEnabled),
                )
            }.combine(recentEvents) { uiState, events ->
                val summaries = aggregatePairSummaries(events)
                uiState.copy(
                    lastSummaryByPairId = summaries,
                    hasCompletedSyncRun = summaries.isNotEmpty(),
                )
            }.combine(settingsRepository.onboardingCompletedAtMs) { uiState, completedAt ->
                uiState.copy(onboardingCompletedAtMs = completedAt)
            }.combine(settingsRepository.seenTooltips) { uiState, seenTooltips ->
                uiState.copy(seenTooltips = seenTooltips)
            }.combine(pairProgress) { uiState, prog ->
                uiState.copy(progressByPairId = prog)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = UiState(),
            )

        init {
            // Keep accountEmailById in sync with the accounts table so pair cards can
            // show which account each pair is bound to without a separate query.
            accountRepository.observeAll()
                .onEach { accounts ->
                    accountEmailById.value =
                        accounts.associateBy(
                            keySelector = { it.id },
                            valueTransform = { it.email ?: it.displayName },
                        )
                }
                .launchIn(viewModelScope)
        }

        /**
         * Permanently removes the sync pair with [id] from the database and cancels any
         * associated background sync jobs (both periodic and one-shot).
         *
         * Prefer [requestDelete] for user-initiated deletions so the user has a chance
         * to undo via a snackbar. This method is kept for callers that need an
         * immediate, non-undoable removal.
         */
        fun delete(id: Long) {
            Timber.i("HomeViewModel.delete(id=$id)")
            syncScheduler.cancel(id)
            viewModelScope.launch { syncPairRepository.delete(id) }
        }

        /**
         * Soft-deletes [pair] with an undo grace period of [UNDO_WINDOW_MS] ms. The
         * pair is hidden from the UI immediately so the list reflects the change, and
         * the [UiState.pendingDelete] field is set so the screen can surface an undo
         * snackbar. If the user taps undo within the window, [undoDelete] reverses
         * the action; otherwise the deletion commits exactly as in [delete].
         *
         * If a previous undo is still pending when this is called, that previous
         * pending delete is committed immediately to avoid losing the prior intent.
         */
        fun requestDelete(pair: SyncPair) {
            Timber.i("HomeViewModel.requestDelete(id=${pair.id})")
            // Always cancel any in-flight commit timer first so we never have two
            // racing jobs — including when the same pair is deleted again during
            // its own undo window (the old job's deadline would otherwise commit
            // earlier than the user's new undo window expects).
            pendingDeleteJob?.cancel()
            pendingDeleteJob = null
            // If a different pair was waiting to commit, flush it now so we don't
            // drop the user's previous intent.
            val prior = pendingDeleteState.value
            if (prior != null && prior.pair.id != pair.id) {
                commitDeleteInternal(prior.pair.id)
            }
            hiddenIds.update { it + pair.id }
            pendingDeleteState.value = PendingDelete(pair)
            pendingDeleteJob =
                viewModelScope.launch {
                    delay(UNDO_WINDOW_MS)
                    val current = pendingDeleteState.value
                    if (current?.pair?.id == pair.id) {
                        pendingDeleteState.value = null
                        commitDeleteInternal(pair.id)
                    }
                }
        }

        /**
         * Cancels the pending soft-delete (if any) and restores the pair to the visible
         * list. Safe to call when no delete is pending — it becomes a no-op.
         */
        fun undoDelete() {
            val pending = pendingDeleteState.value ?: return
            Timber.i("HomeViewModel.undoDelete(id=${pending.pair.id})")
            pendingDeleteJob?.cancel()
            pendingDeleteJob = null
            pendingDeleteState.value = null
            hiddenIds.update { it - pending.pair.id }
        }

        /**
         * Forces the currently-pending soft-delete to commit immediately instead of
         * waiting for [UNDO_WINDOW_MS]. The home screen calls this when the snackbar
         * is dismissed by user action so no stale "pending" state lingers in the VM.
         */
        fun finalizePendingDelete() {
            val pending = pendingDeleteState.value ?: return
            pendingDeleteJob?.cancel()
            pendingDeleteJob = null
            pendingDeleteState.value = null
            commitDeleteInternal(pending.pair.id)
        }

        fun markTooltipSeen(tooltipId: String) {
            viewModelScope.launch { settingsRepository.markTooltipSeen(tooltipId) }
        }

        private fun commitDeleteInternal(id: Long) {
            syncScheduler.cancel(id)
            viewModelScope.launch { syncPairRepository.delete(id) }
        }

        /**
         * Result of a [syncAllNow] invocation, emitted via [syncAllResults] so the
         * UI can surface a snackbar with synced/skipped counts after the user taps
         * "Sync all now" or pulls-to-refresh.
         *
         * @param synced  Number of pairs that were enqueued for sync.
         * @param skipped Number of pairs skipped because they require user action
         *   (needs re-link or re-auth) or are already syncing.
         */
        data class SyncAllResult(val synced: Int, val skipped: Int)

        private val _syncAllResults = MutableSharedFlow<SyncAllResult>(extraBufferCapacity = 1)

        /**
         * One-shot stream of [SyncAllResult]s, emitted whenever [syncAllNow]
         * finishes enqueuing. The Pairs screen collects this and shows a snackbar.
         */
        val syncAllResults: SharedFlow<SyncAllResult> = _syncAllResults.asSharedFlow()

        /**
         * Enqueues a one-shot [SyncWorker] for every healthy pair currently in
         * the UiState (Phase 5b — "Sync all now" + pull-to-refresh).
         *
         * Pairs are skipped when they require user intervention:
         * - [SyncPair.needsReLink] is `true` (SAF folder access lost), or
         * - [SyncPair.lastSyncResult] is `"NEEDS_REAUTH"` (token revoked).
         * Pairs already in [UiState.syncingPairIds] are also skipped so a rapid
         * pull-to-refresh does not stack duplicate one-shot jobs on top of a
         * worker that WorkManager is about to coalesce away anyway.
         *
         * Emits a [SyncAllResult] on [syncAllResults] so the caller can render a
         * "Started sync for N pair(s)" snackbar after the function returns. The
         * same exponential-backoff policy as [syncNow] is applied so a transient
         * failure for one pair backs off in line with the periodic schedule.
         */
        fun syncAllNow() {
            val current = state.value
            val (eligible, skipped) = partitionForSyncAll(current.pairs, current.syncingPairIds)
            Timber.i(
                "HomeViewModel.syncAllNow: enqueuing %d pair(s), skipping %d unhealthy/in-flight",
                eligible.size, skipped,
            )
            eligible.forEach { syncNow(it) }
            _syncAllResults.tryEmit(SyncAllResult(synced = eligible.size, skipped = skipped))
        }

        /**
         * Enqueues a one-shot [SyncWorker] for [pair]. Any in-flight one-shot run for
         * the same pair is kept so the user never interrupts an ongoing sync.
         *
         * The pair's id is added to [UiState.syncingPairIds] immediately so the UI
         * can render a spinner without waiting up to ~30s for the foreground
         * notification. A background coroutine watches the worker and clears the id
         * when the job reaches a finished state.
         *
         * Constraints from the pair's settings are applied so the user's preferences
         * (Wi-Fi only, requires charging) are respected even for manual syncs.
         */
        fun syncNow(pair: SyncPair) {
            Timber.i("HomeViewModel.syncNow(id=${pair.id})")
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(if (pair.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(pair.requiresCharging)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            val req =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .setInputData(
                        workDataOf(
                            SyncWorker.KEY_PAIR_ID to pair.id,
                            SyncWorker.KEY_IS_PERIODIC to false,
                        ),
                    )
                    // Same exponential-backoff policy as the periodic schedule so a
                    // manual "Sync now" that hits a transient network/auth blip retries
                    // on a sane curve instead of WorkManager's default 10s linear.
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        SyncWorker.BACKOFF_INITIAL_DELAY_SECONDS,
                        TimeUnit.SECONDS,
                    )
                    .build()
            // Optimistically mark the pair as syncing so the home row shows a spinner
            // immediately; the watcher coroutine below clears it once the worker is
            // in a finished state.
            syncingIds.update { it + pair.id }
            workManager.enqueueUniqueWork(
                SyncWorker.syncNowUniqueName(pair.id),
                ExistingWorkPolicy.KEEP,
                req,
            )
            viewModelScope.launch {
                val result =
                    runCatching {
                        workManager
                            .getWorkInfosForUniqueWorkFlow(SyncWorker.syncNowUniqueName(pair.id))
                            .onEach { infos ->
                                val progress = infos.firstOrNull()?.progress?.let { SyncWorker.parseProgress(it) }
                                pairProgress.update { map ->
                                    if (progress != null) map + (pair.id to progress) else map - pair.id
                                }
                            }
                            .first { infos ->
                                // Wait until WorkManager has a record for this work and all
                                // entries report a finished state (SUCCEEDED/FAILED/CANCELLED).
                                infos.isNotEmpty() && infos.all { it.state.isFinished }
                            }
                    }
                if (result.isFailure) {
                    Timber.w(result.exceptionOrNull(), "syncNow watcher failed for pair=${pair.id}")
                }
                // Always clear the syncing flag — leaving it set on watcher failure
                // would disable the Sync now button indefinitely with no way to retry.
                // If the worker is still running, the foreground notification keeps
                // the user informed; tapping Sync now again is a safe no-op because
                // ExistingWorkPolicy.KEEP preserves the in-flight job.
                pairProgress.update { it - pair.id }
                syncingIds.update { it - pair.id }
            }
        }

        /**
         * Pure helper that computes the [UiState.nextRunByPairId] map from the
         * currently observed pairs and the global auto-sync flag. Extracted so it
         * can be reused (and unit-tested) without spinning up the full StateFlow
         * pipeline.
         */
        private fun computeNextRunMap(pairs: List<SyncPair>, globalAutoSyncEnabled: Boolean): Map<Long, Long> {
            if (pairs.isEmpty()) return emptyMap()
            val now = System.currentTimeMillis()
            val out = LinkedHashMap<Long, Long>(pairs.size)
            for (p in pairs) {
                val next = SyncScheduler.estimateNextRunAtMs(
                    pair = p,
                    nowMs = now,
                    globalAutoSyncEnabled = globalAutoSyncEnabled,
                )
                if (next != null) out[p.id] = next
            }
            return out
        }

        companion object {
            /**
             * Length of the undo grace window for soft-deleted sync pairs, in
             * milliseconds. Slightly longer than the Material 3 short snackbar
             * duration (~4s) so the snackbar disappears before the commit fires
             * but the user still has time to react.
             */
            const val UNDO_WINDOW_MS: Long = 5_000L

            /**
             * Upper bound on the number of recent events streamed for computing
             * [UiState.lastSummaryByPairId]. The newest terminal event per pair is
             * picked from the head of this list, so a few hundred rows is plenty
             * even for users with many pairs and noisy retries.
             */
            const val SUMMARY_EVENT_LIMIT: Int = 200

            /**
             * Pure-Kotlin filter used by [syncAllNow] (Phase 5b). Returns the
             * pairs that should be enqueued as the first component, and the number
             * that were skipped because they require user intervention or are
             * already syncing as the second.
             *
             * Exposed in the companion object so unit tests can exercise the
             * filtering rules without touching WorkManager.
             */
            fun partitionForSyncAll(
                pairs: List<SyncPair>,
                syncingPairIds: Set<Long>,
            ): Pair<List<SyncPair>, Int> {
                if (pairs.isEmpty()) return emptyList<SyncPair>() to 0
                val eligible = ArrayList<SyncPair>(pairs.size)
                var skipped = 0
                for (p in pairs) {
                    val unhealthy = p.needsReLink || p.lastSyncResult == "NEEDS_REAUTH"
                    val inFlight = p.id in syncingPairIds
                    if (unhealthy || inFlight) {
                        skipped++
                    } else {
                        eligible.add(p)
                    }
                }
                return eligible to skipped
            }
        }
    }
