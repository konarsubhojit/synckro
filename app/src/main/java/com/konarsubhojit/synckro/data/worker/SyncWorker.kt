package com.konarsubhojit.synckro.data.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.konarsubhojit.synckro.R
import com.konarsubhojit.synckro.data.local.dao.SyncPairDao
import com.konarsubhojit.synckro.data.repository.SyncEventRepository
import com.konarsubhojit.synckro.domain.model.SyncEventLevel
import com.konarsubhojit.synckro.domain.model.SyncPair
import com.konarsubhojit.synckro.domain.sync.SyncEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Background worker that runs a single sync pass for one [SyncPair].
 * Scheduled by [SyncScheduler]; can also be enqueued manually ("Sync now").
 *
 * If the sync takes longer than [FOREGROUND_PROMOTE_DELAY_MS] (30 s), the
 * worker promotes itself to a foreground service with a progress notification
 * so that the transfer survives app death and Doze mode. Once the engine is
 * wired to report progress, the 10-MiB threshold can be added here by calling
 * [setForeground] from a progress callback.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncPairDao: SyncPairDao,
    private val engine: SyncEngine,
    private val syncEventRepository: SyncEventRepository,
) : CoroutineWorker(appContext, params) {

    /**
     * Returns the [ForegroundInfo] used when WorkManager promotes this worker to a foreground
     * service. Called by the system before [doWork] if the worker was marked long-running.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val pairId = inputData.getLong(KEY_PAIR_ID, -1L)
        val displayName = if (pairId >= 0) {
            syncPairDao.getById(pairId)?.displayName
                ?: applicationContext.getString(R.string.sync_notification_content)
        } else {
            applicationContext.getString(R.string.sync_notification_content)
        }
        return buildForegroundInfo(pairId, displayName)
    }

    /**
     * Runs a single synchronization pass for the SyncPair whose id is provided in the worker
     * input and applies WorkManager retry/cancellation policies based on the outcome.
     *
     * If the input `pair_id` is missing or invalid, the worker treats the run as a permanent
     * failure. If the corresponding DB entity no longer exists, the worker cancels the pair's
     * unique periodic work and treats the run as successful.
     *
     * When the sync has been running for [FOREGROUND_PROMOTE_DELAY_MS] (30 s) the worker
     * promotes itself to a foreground service so the transfer survives process death.
     *
     * After each completed run (Success, PartialFailure, or Terminal failure) the worker persists
     * the timestamp and outcome to the database.
     *
     * Mapping of engine results to WorkManager results:
     * - `SyncEngine.Result.Success` → `Result.success()`
     * - `SyncEngine.Result.PartialFailure` → logs warning and returns `Result.success()`
     * - `SyncEngine.Result.Retriable` → logs info and returns `Result.retry()`
     * - `SyncEngine.Result.Terminal` → logs warning, cancels the pair's unique periodic work,
     *   and returns `Result.failure()`
     *
     * Any thrown exception is logged and results in `Result.retry()`.
     */
    override suspend fun doWork(): Result {
        val pairId = inputData.getLong(KEY_PAIR_ID, -1L)
        if (pairId < 0) {
            Timber.w("SyncWorker enqueued without a valid %s; failing permanently.", KEY_PAIR_ID)
            return Result.failure()
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
            scheduleIntervalMinutes = entity.scheduleIntervalMinutes,
        )

        syncEventRepository.log(pairId, SyncEventLevel.INFO, LOG_TAG, "Sync started for \"${pair.displayName}\" (attempt ${runAttemptCount + 1})")

        return coroutineScope {
            // Promote to a foreground service if the sync takes longer than the threshold.
            // This keeps the transfer alive when the app is killed and prevents Doze
            // from deferring the worker on API 31+.
            val foregroundJob = launch {
                delay(FOREGROUND_PROMOTE_DELAY_MS)
                Timber.i("SyncWorker: promoting pair %d to foreground service after %d ms.", pairId, FOREGROUND_PROMOTE_DELAY_MS)
                try {
                    setForeground(buildForegroundInfo(pairId, pair.displayName))
                } catch (e: Exception) {
                    Timber.w(e, "SyncWorker: could not promote pair %d to foreground.", pairId)
                }
            }

            val workerResult = try {
                when (val r = engine.runOnce(pair)) {
                    is SyncEngine.Result.Success -> {
                        syncPairDao.updateLastSyncResult(pairId, System.currentTimeMillis(), RESULT_SUCCESS)
                        syncEventRepository.log(pairId, SyncEventLevel.INFO, LOG_TAG, "Sync succeeded: ${r.applied} applied, ${r.conflicts} conflicts")
                        Result.success()
                    }
                    is SyncEngine.Result.PartialFailure -> {
                        Timber.w("Sync for pair %d completed with %d errors", pairId, r.errors.size)
                        syncPairDao.updateLastSyncResult(pairId, System.currentTimeMillis(), RESULT_PARTIAL_FAILURE)
                        val errorSummary = r.errors.take(MAX_LOGGED_ERRORS).joinToString("; ") +
                            if (r.errors.size > MAX_LOGGED_ERRORS) " … (${r.errors.size - MAX_LOGGED_ERRORS} more)" else ""
                        syncEventRepository.log(pairId, SyncEventLevel.WARN, LOG_TAG, "Sync partial failure: ${r.applied} applied, ${r.errors.size} errors — $errorSummary")
                        Result.success()
                    }
                    is SyncEngine.Result.Retriable -> {
                        Timber.i("Retrying sync for pair %d: %s", pairId, r.reason)
                        syncEventRepository.log(pairId, SyncEventLevel.WARN, LOG_TAG, "Sync retriable, will retry: ${r.reason}")
                        Result.retry()
                    }
                    is SyncEngine.Result.Terminal -> {
                        // Misconfigured pair (e.g. revoked auth, unsupported provider).
                        // Don't retry forever — cancel the periodic chain so the
                        // user can re-authenticate / reconfigure the pair.
                        Timber.w("Terminal sync failure for pair %d: %s", pairId, r.reason)
                        syncPairDao.updateLastSyncResult(pairId, System.currentTimeMillis(), RESULT_FAILURE)
                        syncEventRepository.log(pairId, SyncEventLevel.ERROR, LOG_TAG, "Sync failed (terminal): ${r.reason}")
                        WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(pairId))
                        Result.failure()
                    }
                }
            } catch (c: CancellationException) {
                foregroundJob.cancel()
                throw c
            } catch (t: Throwable) {
                Timber.w(t, "Sync failed for pair %d", pairId)
                syncEventRepository.log(pairId, SyncEventLevel.ERROR, LOG_TAG, "Sync threw an exception, will retry: ${t.message}")
                Result.retry()
            } finally {
                foregroundJob.cancel()
            }

            workerResult
        }
    }

    private fun buildForegroundInfo(pairId: Long, displayName: String): ForegroundInfo {
        val notification = buildProgressNotification(displayName)
        // Clamp pairId into Int range for the notification ID. We keep the lower
        // 16 bits (0–65535) so that the resulting Int is always positive and small
        // enough to avoid wrapping. The NOTIFICATION_ID_BASE offset prevents
        // accidental collisions with notification IDs used elsewhere in the app.
        val notificationId = NOTIFICATION_ID_BASE + (pairId and 0xFFFFL).toInt()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun buildProgressNotification(displayName: String): Notification =
        NotificationCompat.Builder(applicationContext, SYNC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(
                applicationContext.getString(R.string.sync_notification_title, displayName)
            )
            .setContentText(applicationContext.getString(R.string.sync_notification_content))
            .setProgress(0, 0, /* indeterminate = */ true)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        const val KEY_PAIR_ID = "pair_id"

        /** Notification channel ID for sync progress. Created by SynckroApp.createNotificationChannels(). */
        const val SYNC_CHANNEL_ID = "synckro_sync"

        /** Delay before promoting to a foreground service: 30 seconds. */
        const val FOREGROUND_PROMOTE_DELAY_MS = 30_000L

        /** Base notification ID to avoid collisions with other app notifications. */
        private const val NOTIFICATION_ID_BASE = 1_000

        /** Outcome strings persisted to [SyncPairEntity.lastSyncResult]. */
        const val RESULT_SUCCESS = "SUCCESS"
        const val RESULT_PARTIAL_FAILURE = "PARTIAL_FAILURE"
        const val RESULT_FAILURE = "FAILURE"

        /** Tag used for [SyncEventRepository] log entries written by this worker. */
        private const val LOG_TAG = "SyncWorker"

        /** Maximum number of error strings included in the partial-failure log message. */
        private const val MAX_LOGGED_ERRORS = 5

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
     * The requested interval is in minutes; values below 15 minutes are clamped to 15 and a
     * warning is logged. The scheduled work is enqueued as unique periodic work identified by the
     * pair's unique name and will replace any existing schedule.
     *
     * Constraints applied:
     * - Network: UNMETERED (Wi-Fi) when [SyncPair.wifiOnly] is true, otherwise CONNECTED.
     * - Charging: required when [SyncPair.requiresCharging] is true.
     * - Battery-not-low: always required so Doze mode does not starve the worker on API 31+.
     * - Storage-not-low: always required to avoid filling the device.
     *
     * @param pair The SyncPair to schedule periodic synchronization for.
     * @param intervalMinutes Desired interval between runs in minutes (defaults to 60). Values
     *   less than 15 will be clamped to the WorkManager minimum.
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
            .setRequiresBatteryNotLow(true)
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
