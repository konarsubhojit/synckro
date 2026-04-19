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
        fun uniqueName(pairId: Long): String = "synckro-sync-$pairId"
    }
}

/** Schedules [SyncWorker] as periodic work per-pair with the pair's constraints. */
class SyncScheduler(private val workManager: WorkManager) {

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

    fun cancel(pairId: Long) {
        workManager.cancelUniqueWork(SyncWorker.uniqueName(pairId))
    }

    companion object {
        const val MIN_PERIODIC_INTERVAL_MINUTES: Long = 15
    }
}
