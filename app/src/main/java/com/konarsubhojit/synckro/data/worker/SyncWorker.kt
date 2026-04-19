package com.konarsubhojit.synckro.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.konarsubhojit.synckro.data.local.dao.SyncPairDao
import com.konarsubhojit.synckro.domain.model.SyncPair
import com.konarsubhojit.synckro.domain.sync.SyncEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Background worker that runs a single sync pass for one [SyncPair].
 * Scheduled by [SyncScheduler]; can also be enqueued manually ("Sync now").
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncPairDao: SyncPairDao,
    private val engine: SyncEngine,
) : CoroutineWorker(appContext, params) {

    /**
     * Runs a single synchronization pass for the SyncPair whose id is provided in the worker input and applies WorkManager retry/cancellation policies based on the outcome.
     *
     * If the input `pair_id` is missing or invalid, or if the corresponding DB entity no longer exists, the worker cancels the pair's unique periodic work and treats the run as successful. The function constructs a domain `SyncPair` (splitting newline-delimited include/exclude globs into non-blank lines) and delegates the sync to `SyncEngine.runOnce`.
     *
     * Mapping of engine results to WorkManager results:
     * - `SyncEngine.Result.Success` → `Result.success()`
     * - `SyncEngine.Result.PartialFailure` → logs warning and returns `Result.retry()`
     * - `SyncEngine.Result.Retriable` → logs info and returns `Result.retry()`
     * - `SyncEngine.Result.Terminal` → logs warning, cancels the pair's unique periodic work, and returns `Result.failure()`
     *
     * Any thrown exception is logged and results in `Result.retry()`.
     *
     * @return `Result.success()` for successful runs or when the pair is missing/invalid; `Result.retry()` for partial, retriable, or exceptional failures; `Result.failure()` for terminal failures after cancelling the periodic work.
     */
    override suspend fun doWork(): Result {
        val pairId = inputData.getLong(KEY_PAIR_ID, -1L)
        if (pairId < 0) {
            // Misconfigured enqueue — nothing we can do on retry, so cancel
            // this unique chain rather than marking it permanently failed.
            Timber.w("SyncWorker enqueued without %s; cancelling.", KEY_PAIR_ID)
            WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(pairId))
            return Result.success()
        }
        val entity = syncPairDao.getById(pairId)
        if (entity == null) {
            // Pair was deleted while work was pending; cancel future runs
            // and succeed so WorkManager doesn't back off this run forever.
            Timber.i("SyncPair %d no longer exists; cancelling periodic work.", pairId)
            WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(pairId))
            return Result.success()
        }
        val pair = SyncPair(
            id = entity.id,
            displayName = entity.displayName,
            localTreeUri = entity.localTreeUri,
            provider = entity.provider,
            remoteFolderId = entity.remoteFolderId,
            direction = entity.direction,
            conflictPolicy = entity.conflictPolicy,
            includeGlobs = entity.includeGlobs.split('\n').filter { it.isNotBlank() },
            excludeGlobs = entity.excludeGlobs.split('\n').filter { it.isNotBlank() },
            wifiOnly = entity.wifiOnly,
            requiresCharging = entity.requiresCharging,
        )

        return try {
            when (val r = engine.runOnce(pair)) {
                is SyncEngine.Result.Success -> Result.success()
                is SyncEngine.Result.PartialFailure -> {
                    Timber.w("Sync for pair %d completed with %d errors", pairId, r.errors.size)
                    // Partial failures are rescheduled so transient errors can recover.
                    Result.retry()
                }
                is SyncEngine.Result.Retriable -> {
                    Timber.i("Retrying sync for pair %d: %s", pairId, r.reason)
                    Result.retry()
                }
                is SyncEngine.Result.Terminal -> {
                    // Misconfigured pair (e.g. revoked auth, unsupported provider).
                    // Don't retry forever — cancel the periodic chain so the
                    // user can re-authenticate / reconfigure the pair.
                    Timber.w("Terminal sync failure for pair %d: %s", pairId, r.reason)
                    WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(pairId))
                    Result.failure()
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "Sync failed for pair %d", pairId)
            Result.retry()
        }
    }

    companion object {
        const val KEY_PAIR_ID = "pair_id"
        /**
 * Produces a deterministic unique WorkManager name for a SyncPair.
 *
 * @param pairId The SyncPair's id.
 * @return The unique work name for the given pair id (format: "synckro-sync-<pairId>").
 */
fun uniqueName(pairId: Long): String = "synckro-sync-$pairId"
    }
}

/** Schedules [SyncWorker] as periodic work per-pair with the pair's constraints. */
class SyncScheduler(private val workManager: WorkManager) {

    /**
     * Schedules or updates a periodic sync job for the given SyncPair with WorkManager.
     *
     * The requested interval is in minutes; values below 15 minutes are clamped to 15 and a warning is logged.
     * The scheduled work is enqueued as unique periodic work identified by the pair's unique name and will replace any existing schedule.
     *
     * @param pair The SyncPair to schedule periodic synchronization for; its properties (network, charging, storage, and include/exclude rules) are used to build the work's constraints and input.
     * @param intervalMinutes Desired interval between runs in minutes (defaults to 60). Values less than 15 will be clamped to the WorkManager minimum.
     */
    fun schedulePeriodic(pair: SyncPair, intervalMinutes: Long = 60) {
        // WorkManager rejects periodic intervals below 15 minutes. Clamp to
        // that floor rather than crashing in release builds.
        val interval = intervalMinutes.coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES)
        if (interval != intervalMinutes) {
            Timber.w(
                "Requested sync interval %d min is below WorkManager minimum; clamping to %d.",
                intervalMinutes, interval,
            )
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (pair.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(pair.requiresCharging)
            .setRequiresStorageNotLow(true)
            .build()

        val req = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_PAIR_ID to pair.id))
            .build()

        workManager.enqueueUniquePeriodicWork(
            SyncWorker.uniqueName(pair.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    /**
     * Cancels any scheduled periodic sync work for the SyncPair with the given id.
     *
     * @param pairId The id of the SyncPair whose periodic WorkManager job will be canceled.
     */
    fun cancel(pairId: Long) {
        workManager.cancelUniqueWork(SyncWorker.uniqueName(pairId))
    }

    companion object {
        const val MIN_PERIODIC_INTERVAL_MINUTES: Long = 15
    }
}
