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
        if (pairId < 0) return Result.failure()
        val entity = syncPairDao.getById(pairId) ?: return Result.failure()
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

        return runCatching { engine.runOnce(pair) }
            .onFailure { Timber.w(it, "Sync failed for pair %d", pairId) }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { Result.retry() },
            )
    }

    companion object {
        const val KEY_PAIR_ID = "pair_id"
        fun uniqueName(pairId: Long): String = "synckro-sync-$pairId"
    }
}

/** Schedules [SyncWorker] as periodic work per-pair with the pair's constraints. */
class SyncScheduler(private val workManager: WorkManager) {

    fun schedulePeriodic(pair: SyncPair, intervalMinutes: Long = 60) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (pair.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(pair.requiresCharging)
            .setRequiresStorageNotLow(true)
            .build()

        val req = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
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
}
