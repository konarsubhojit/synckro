package com.synckro.data.worker

import android.content.Context
import com.synckro.data.local.dao.PendingUploadDao
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.domain.model.SyncPair
import com.synckro.domain.sync.InstantSyncAccountState
import com.synckro.domain.sync.InstantSyncEligibilityPolicy
import com.synckro.domain.sync.PairSignalCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-arms instant dispatch for pending upload rows that survived a process restart.
 *
 * The pending-upload queue is durable while the debounce timers held by
 * [PairSignalCoordinator] are not, so a process death between a watcher observation and
 * its debounced dispatch would otherwise strand rows until the next periodic run.
 * On startup this resumer:
 *
 * 1. releases claims left behind by a process that died mid-batch,
 * 2. queries the pairs that own eligible pending rows,
 * 3. signals the coordinator for the pairs that are currently eligible for Instant Sync.
 *
 * Ineligible pairs (Instant Sync disabled globally or per-pair, download-only direction,
 * unlinked account, re-auth or re-link required) keep their rows queued and undispatched;
 * they are picked up once the blocking condition is resolved and the pair is signalled again.
 *
 * [resume] performs its work at most once per process, so repeated startup callbacks are safe.
 */
@Singleton
class PendingDispatchResumer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val pendingUploadDao: PendingUploadDao,
        private val syncPairRepository: SyncPairRepository,
        private val settingsRepository: SettingsRepository,
        private val eligibilityPolicy: InstantSyncEligibilityPolicy,
        private val pairSignalCoordinator: PairSignalCoordinator,
        private val syncScheduler: SyncScheduler,
    ) {
        private val resumed = AtomicBoolean(false)

        /**
         * Re-arms dispatch for every pair that still has eligible pending rows.
         *
         * @param nowMs Clock reading used for claim recovery and eligibility, injectable for tests.
         */
        suspend fun resume(nowMs: Long = System.currentTimeMillis()) {
            if (!resumed.compareAndSet(false, true)) {
                Timber.d("PendingDispatchResumer.resume() already ran for this process; skipping")
                return
            }
            pendingUploadDao.recoverStaleClaims(
                staleBeforeMs = nowMs - SyncWorker.INSTANT_CLAIM_TIMEOUT_MS,
                recoveredAtMs = nowMs,
            )
            val pairIds = pendingUploadDao.pairIdsWithEligibleRows(nowMs)
            if (pairIds.isEmpty()) return

            val globalAutoSyncEnabled = settingsRepository.globalAutoSyncEnabled.first()
            val globalInstantSyncEnabled = settingsRepository.globalInstantSyncEnabled.first()
            val pairsById =
                syncPairRepository.getAll(context.contentResolver).associateBy(SyncPair::id)

            pairIds.forEach { pairId ->
                val pair = pairsById[pairId]
                if (pair == null) {
                    Timber.d("Skipping resume for unknown pair %d", pairId)
                    return@forEach
                }
                val decision =
                    eligibilityPolicy.evaluate(
                        pair = pair,
                        globalAutoSyncEnabled = globalAutoSyncEnabled,
                        globalInstantSyncEnabled = globalInstantSyncEnabled,
                        accountState = accountStateFor(pair),
                    )
                if (!decision.isEligible) {
                    Timber.d(
                        "Leaving pending rows queued for pair %d: %s",
                        pairId,
                        decision.reasons.joinToString(),
                    )
                    return@forEach
                }
                pairSignalCoordinator.signal(pairId) { syncScheduler.enqueueInstant(pair) }
            }
        }

        companion object {
            /**
             * Maps a pair's persisted linkage state onto [InstantSyncAccountState].
             *
             * Mirrors the checks the sync worker applies before claiming instant work so a
             * restart never dispatches a pair that the user must repair first.
             */
            internal fun accountStateFor(pair: SyncPair): InstantSyncAccountState =
                when {
                    pair.accountId == null -> InstantSyncAccountState.ACCOUNT_NOT_LINKED
                    pair.needsReLink -> InstantSyncAccountState.NEEDS_RELINK
                    pair.lastSyncResult == SyncWorker.RESULT_NEEDS_RELINK -> InstantSyncAccountState.NEEDS_RELINK
                    pair.lastSyncResult == SyncWorker.RESULT_NEEDS_REAUTH -> InstantSyncAccountState.NEEDS_REAUTH
                    else -> InstantSyncAccountState.READY
                }
        }
    }
