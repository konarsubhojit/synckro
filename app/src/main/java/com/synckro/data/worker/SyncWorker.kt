package com.synckro.data.worker

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
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
import com.synckro.R
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.entity.toDomain
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncPair
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.provider.CloudProviderException
import com.synckro.domain.sync.CloudExceptionMapper
import com.synckro.domain.sync.SyncEngine
import com.synckro.domain.sync.TransferProgress
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.JvmSuppressWildcards

/**
 * Background worker that runs a single sync pass for one [SyncPair].
 * Scheduled by [SyncScheduler]; can also be enqueued manually ("Sync now").
 *
 * If the sync takes longer than [FOREGROUND_PROMOTE_DELAY_MS] (30 s) **or** if
 * the total transfer size reported by the engine reaches [LARGE_TRANSFER_THRESHOLD_BYTES]
 * (10 MiB), the worker promotes itself to a foreground service with a progress
 * notification so that the transfer survives app death and Doze mode.
 *
 * When the engine reports a known total byte count the notification shows a
 * determinate progress bar; otherwise it falls back to a file-count-based bar,
 * and finally to an indeterminate spinner when no sizes are available at all.
 */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val syncPairDao: SyncPairDao,
        private val providerFactories: Map<CloudProviderType, @JvmSuppressWildcards CloudProviderFactory>,
        private val engine: SyncEngine,
        private val syncEventRepository: SyncEventRepository,
    ) : CoroutineWorker(appContext, params) {
        /**
         * Returns the [ForegroundInfo] used when WorkManager promotes this worker to a foreground
         * service. Called by the system before [doWork] if the worker was marked long-running.
         */
        override suspend fun getForegroundInfo(): ForegroundInfo {
            val pairId = inputData.getLong(KEY_PAIR_ID, -1L)
            val displayName =
                if (pairId >= 0) {
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
            val pair = entity.toDomain()
            if (pair.provider != CloudProviderType.FAKE && pair.accountId == null) {
                val reason = "Sync pair is no longer linked to an account. Please re-link this pair."
                Timber.w("SyncWorker.doWork: terminal relink required for pairId=%d (missing accountId)", pairId)
                syncPairDao.updateLastSyncResult(pairId, System.currentTimeMillis(), RESULT_NEEDS_RELINK)
                syncEventRepository.log(pairId, SyncEventLevel.ERROR, LOG_TAG, reason)
                WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(pairId))
                return Result.failure()
            }
            val accountId = pair.accountId
            if (pair.provider != CloudProviderType.FAKE && accountId != null) {
                val factory =
                    providerFactories[pair.provider] ?: run {
                        val reason = "No provider factory registered for ${pair.provider}."
                        syncPairDao.updateLastSyncResult(pairId, System.currentTimeMillis(), RESULT_FAILURE)
                        syncEventRepository.log(pairId, SyncEventLevel.ERROR, LOG_TAG, reason)
                        WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(pairId))
                        return Result.failure()
                    }
                factory.providerFor(accountId)
            }

            syncEventRepository.log(
                pairId,
                SyncEventLevel.INFO,
                LOG_TAG,
                "Sync started for \"${pair.displayName}\" (attempt ${runAttemptCount + 1})",
            )
            Timber.i("SyncWorker.doWork: start pairId=%d attempt=%d", pairId, runAttemptCount + 1)

            val notificationId = NOTIFICATION_ID_BASE + (pairId and 0xFFFFL).toInt()
            val notificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            return coroutineScope {
                val promotedToForeground = AtomicBoolean(false)
                val foregroundPromotionInFlight = AtomicBoolean(false)

                suspend fun tryPromoteToForeground(
                    progress: TransferProgress? = null,
                    logMessage: () -> Unit,
                    failureMessage: String,
                ): Boolean {
                    if (promotedToForeground.get()) return true
                    while (!foregroundPromotionInFlight.compareAndSet(false, true)) {
                        if (promotedToForeground.get()) return true
                        delay(50L)
                    }
                    return try {
                        logMessage()
                        setForeground(buildForegroundInfo(pairId, pair.displayName, progress))
                        promotedToForeground.set(true)
                        true
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        Timber.w(e, failureMessage, pairId)
                        false
                    } finally {
                        foregroundPromotionInFlight.set(false)
                    }
                }

                // Promote to a foreground service if the sync takes longer than the threshold.
                // This keeps the transfer alive when the app is killed and prevents Doze
                // from deferring the worker on API 31+.
                val foregroundJob =
                    launch {
                        delay(FOREGROUND_PROMOTE_DELAY_MS)
                        tryPromoteToForeground(
                            logMessage = {
                                Timber.i(
                                    "SyncWorker: promoting pair %d to foreground service after %d ms.",
                                    pairId,
                                    FOREGROUND_PROMOTE_DELAY_MS,
                                )
                            },
                            failureMessage = "SyncWorker: could not promote pair %d to foreground.",
                        )
                    }

                // Progress callback forwarded from SyncEngine → SyncOpApplier.
                // Handles two concerns:
                //  1. Early foreground promotion for large transfers (≥ LARGE_TRANSFER_THRESHOLD_BYTES).
                //  2. Updating the progress notification once the worker is in foreground.
                val onSyncProgress: suspend (TransferProgress) -> Unit = { progress ->
                    if (!promotedToForeground.get() && progress.totalBytes >= LARGE_TRANSFER_THRESHOLD_BYTES) {
                        if (tryPromoteToForeground(
                                progress = progress,
                                logMessage = {
                                    Timber.i(
                                        "SyncWorker: early foreground promotion for pair %d (%d bytes total).",
                                        pairId,
                                        progress.totalBytes,
                                    )
                                },
                                failureMessage = "SyncWorker: could not early-promote pair %d to foreground.",
                            )
                        ) {
                            foregroundJob.cancel()
                        }
                    }
                    if (promotedToForeground.get()) {
                        if (canPostNotifications(applicationContext)) {
                            notificationManager.notify(
                                notificationId,
                                buildProgressNotification(pair.displayName, progress),
                            )
                        } else {
                            Timber.d(
                                "SyncWorker: POST_NOTIFICATIONS not granted; progress update skipped for pair %d.",
                                pairId,
                            )
                        }
                    }
                }

                val workerResult =
                    try {
                        when (val r = engine.runOnce(pair, onSyncProgress)) {
                            is SyncEngine.Result.Success -> {
                                syncPairDao.updateLastSyncResult(pairId, System.currentTimeMillis(), RESULT_SUCCESS)
                                syncEventRepository.log(
                                    pairId,
                                    SyncEventLevel.INFO,
                                    LOG_TAG,
                                    "Sync succeeded: ${r.applied} applied, ${r.conflicts} conflicts",
                                )
                                Timber.i("SyncWorker.doWork: success pairId=%d applied=%d conflicts=%d", pairId, r.applied, r.conflicts)
                                Result.success()
                            }
                            is SyncEngine.Result.PartialFailure -> {
                                Timber.w("Sync for pair %d completed with %d errors", pairId, r.errors.size)
                                syncPairDao.updateLastSyncResult(pairId, System.currentTimeMillis(), RESULT_PARTIAL_FAILURE)
                                val errorSummary =
                                    r.errors.take(MAX_LOGGED_ERRORS).joinToString("; ") +
                                        if (r.errors.size > MAX_LOGGED_ERRORS) " … (${r.errors.size - MAX_LOGGED_ERRORS} more)" else ""
                                syncEventRepository.log(
                                    pairId,
                                    SyncEventLevel.WARN,
                                    LOG_TAG,
                                    "Sync partial failure: ${r.applied} applied, ${r.errors.size} errors — $errorSummary",
                                )
                                Result.success()
                            }
                            is SyncEngine.Result.Retriable -> {
                                if (runAttemptCount + 1 >= MAX_RETRY_ATTEMPTS) {
                                    Timber.w(
                                        "Sync for pair %d exhausted %d attempt(s): %s",
                                        pairId, MAX_RETRY_ATTEMPTS, r.reason,
                                    )
                                    syncPairDao.updateLastSyncResult(pairId, System.currentTimeMillis(), RESULT_FAILURE)
                                    syncEventRepository.log(
                                        pairId, SyncEventLevel.ERROR, LOG_TAG,
                                        "Sync failed after $MAX_RETRY_ATTEMPTS attempt(s), giving up: ${r.reason}",
                                    )
                                    WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(pairId))
                                    Result.failure()
                                } else {
                                    Timber.i(
                                        "Retrying sync for pair %d (attempt %d/%d): %s",
                                        pairId, runAttemptCount + 1, MAX_RETRY_ATTEMPTS, r.reason,
                                    )
                                    syncEventRepository.log(
                                        pairId, SyncEventLevel.WARN, LOG_TAG,
                                        "Sync retriable, will retry (attempt ${runAttemptCount + 1}/$MAX_RETRY_ATTEMPTS): ${r.reason}",
                                    )
                                    Result.retry()
                                }
                            }
                            is SyncEngine.Result.Terminal -> {
                                // Misconfigured pair (e.g. revoked auth, unsupported provider,
                                // or revoked SAF permission). Don't retry forever — cancel the
                                // periodic chain so the user can re-authenticate / re-link /
                                // reconfigure the pair.
                                Timber.w("Terminal sync failure for pair %d: %s", pairId, r.reason)
                                val outcome =
                                    when {
                                        r.needsReauth -> RESULT_NEEDS_REAUTH
                                        r.needsReLink -> RESULT_NEEDS_RELINK
                                        else -> RESULT_FAILURE
                                    }
                                syncPairDao.updateLastSyncResult(pairId, System.currentTimeMillis(), outcome)
                                when {
                                    r.needsReauth ->
                                        // Tag `auth` so the user can filter/copy these from LogsScreen.
                                        syncEventRepository.log(
                                            pairId,
                                            SyncEventLevel.ERROR,
                                            LOG_TAG_AUTH,
                                            "Re-authentication required: ${r.reason}",
                                        )
                                    r.needsReLink ->
                                        syncEventRepository.log(
                                            pairId,
                                            SyncEventLevel.ERROR,
                                            LOG_TAG,
                                            "Local folder access lost, re-link required: ${r.reason}",
                                        )
                                    else ->
                                        syncEventRepository.log(pairId, SyncEventLevel.ERROR, LOG_TAG, "Sync failed (terminal): ${r.reason}")
                                }
                                WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(pairId))
                                Result.failure()
                            }
                        }
                    } catch (c: CancellationException) {
                        foregroundJob.cancel()
                        throw c
                    } catch (e: CloudProviderException) {
                        // A provider escaped the engine without being mapped to a Result —
                        // run it through CloudExceptionMapper so we don't slip into an
                        // infinite Result.retry() storm on AuthenticationRequired / NotConfigured.
                        when (val mapped = CloudExceptionMapper.toResult(e)) {
                            is SyncEngine.Result.Terminal -> {
                                Timber.w(e, "Terminal CloudProviderException for pair %d: %s", pairId, mapped.reason)
                                val outcome = if (mapped.needsReauth) RESULT_NEEDS_REAUTH else RESULT_FAILURE
                                syncPairDao.updateLastSyncResult(pairId, System.currentTimeMillis(), outcome)
                                val tag = if (mapped.needsReauth) LOG_TAG_AUTH else LOG_TAG
                                syncEventRepository.log(pairId, SyncEventLevel.ERROR, tag, "Sync failed (terminal): ${mapped.reason}")
                                WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(pairId))
                                Result.failure()
                            }
                            is SyncEngine.Result.Retriable -> {
                                // Auth-related retriable failures (e.g. AuthenticationFailed during refresh)
                                // are still tagged `auth` so the user can find them in LogsScreen.
                                val tag = if (e is CloudProviderException.AuthenticationFailed) LOG_TAG_AUTH else LOG_TAG
                                if (runAttemptCount + 1 >= MAX_RETRY_ATTEMPTS) {
                                    Timber.w(
                                        e,
                                        "Sync for pair %d exhausted %d attempt(s): %s",
                                        pairId, MAX_RETRY_ATTEMPTS, mapped.reason,
                                    )
                                    syncPairDao.updateLastSyncResult(pairId, System.currentTimeMillis(), RESULT_FAILURE)
                                    syncEventRepository.log(
                                        pairId, SyncEventLevel.ERROR, tag,
                                        "Sync failed after $MAX_RETRY_ATTEMPTS attempt(s), giving up: ${mapped.reason}",
                                    )
                                    WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(pairId))
                                    Result.failure()
                                } else {
                                    Timber.i(
                                        e,
                                        "Retriable CloudProviderException for pair %d (attempt %d/%d): %s",
                                        pairId, runAttemptCount + 1, MAX_RETRY_ATTEMPTS, mapped.reason,
                                    )
                                    syncEventRepository.log(
                                        pairId, SyncEventLevel.WARN, tag,
                                        "Sync retriable, will retry (attempt ${runAttemptCount + 1}/$MAX_RETRY_ATTEMPTS): ${mapped.reason}",
                                    )
                                    Result.retry()
                                }
                            }
                            // Mapper never returns Success / PartialFailure for an exception input.
                            else -> if (runAttemptCount + 1 >= MAX_RETRY_ATTEMPTS) {
                                syncPairDao.updateLastSyncResult(pairId, System.currentTimeMillis(), RESULT_FAILURE)
                                syncEventRepository.log(
                                    pairId, SyncEventLevel.ERROR, LOG_TAG,
                                    "Sync failed after $MAX_RETRY_ATTEMPTS attempt(s), giving up: ${e.message}",
                                )
                                WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(pairId))
                                Result.failure()
                            } else {
                                Result.retry()
                            }
                        }
                    } catch (t: Throwable) {
                        if (runAttemptCount + 1 >= MAX_RETRY_ATTEMPTS) {
                            Timber.w(t, "Sync for pair %d exhausted %d attempt(s)", pairId, MAX_RETRY_ATTEMPTS)
                            syncPairDao.updateLastSyncResult(pairId, System.currentTimeMillis(), RESULT_FAILURE)
                            syncEventRepository.log(
                                pairId, SyncEventLevel.ERROR, LOG_TAG,
                                "Sync failed after $MAX_RETRY_ATTEMPTS attempt(s), giving up: ${t.message}",
                            )
                            WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueName(pairId))
                            Result.failure()
                        } else {
                            Timber.w(t, "Sync failed for pair %d", pairId)
                            syncEventRepository.log(
                                pairId, SyncEventLevel.ERROR, LOG_TAG,
                                "Sync threw an exception, will retry (attempt ${runAttemptCount + 1}/$MAX_RETRY_ATTEMPTS): ${t.message}",
                            )
                            Result.retry()
                        }
                    } finally {
                        foregroundJob.cancel()
                    }

                workerResult
            }
        }

        private fun buildForegroundInfo(
            pairId: Long,
            displayName: String,
            progress: TransferProgress? = null,
        ): ForegroundInfo {
            val notification = buildProgressNotification(displayName, progress)
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

        private fun buildProgressNotification(
            displayName: String,
            progress: TransferProgress? = null,
        ): Notification {
            val (progressMax, progressCurrent, indeterminate) =
                when {
                    progress != null && progress.totalBytes > 0L -> {
                        // Scale to 0..1000 so that byte counts don't overflow Int and
                        // the bar still advances smoothly for large files.
                        val max = 1_000
                        val current = scaleByteProgress(progress.bytesTransferred, progress.totalBytes, max)
                        Triple(max, current, false)
                    }
                    progress != null && progress.totalFiles > 0 -> {
                        Triple(
                            progress.totalFiles,
                            progress.filesCompleted.coerceIn(0, progress.totalFiles),
                            false,
                        )
                    }
                    else -> Triple(0, 0, true)
                }
            return NotificationCompat
                .Builder(applicationContext, SYNC_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(
                    applicationContext.getString(R.string.sync_notification_title, displayName),
                ).setContentText(applicationContext.getString(R.string.sync_notification_content))
                .setProgress(progressMax, progressCurrent, indeterminate)
                .setOngoing(true)
                .setSilent(true)
                .build()
        }

        private fun scaleByteProgress(
            bytesTransferred: Long,
            totalBytes: Long,
            max: Int,
        ): Int {
            if (totalBytes <= 0L || max <= 0) return 0
            val clampedBytes = bytesTransferred.coerceIn(0L, totalBytes)
            return ((clampedBytes.toDouble() / totalBytes.toDouble()) * max)
                .toInt()
                .coerceIn(0, max)
        }

        companion object {
            const val KEY_PAIR_ID = "pair_id"

            /** Notification channel ID for sync progress. Created by SynckroApp.createNotificationChannels(). */
            const val SYNC_CHANNEL_ID = "synckro_sync"

            /** Delay before promoting to a foreground service: 30 seconds. */
            const val FOREGROUND_PROMOTE_DELAY_MS = 30_000L

            /**
             * Byte threshold above which a sync is considered a "large transfer".
             * When the engine reports a total transfer size at or above this value the worker
             * promotes to a foreground service immediately (before the 30-second delay).
             */
            const val LARGE_TRANSFER_THRESHOLD_BYTES = 10L * 1024 * 1024 // 10 MiB

            /** Base notification ID to avoid collisions with other app notifications. */
            private const val NOTIFICATION_ID_BASE = 1_000

            /** Outcome strings persisted to [SyncPairEntity.lastSyncResult]. */
            const val RESULT_SUCCESS = "SUCCESS"
            const val RESULT_PARTIAL_FAILURE = "PARTIAL_FAILURE"
            const val RESULT_FAILURE = "FAILURE"

            /**
             * Outcome string written when the engine returns a [SyncEngine.Result.Terminal]
             * with `needsReauth = true` (token expired, account removed, scope revoked,
             * `MsalUiRequiredException`, missing client ID, …). The Accounts screen reads
             * this to decide whether to show a "Re-authenticate" CTA on the provider card.
             */
            const val RESULT_NEEDS_REAUTH = "NEEDS_REAUTH"

            /**
             * Outcome string written when the engine returns a [SyncEngine.Result.Terminal]
             * with `needsReLink = true` (SAF permission for the local folder was revoked or
             * the storage became unavailable). The sync pair list reads this to show a
             * "Re-link local folder" CTA on the affected pair.
             */
            const val RESULT_NEEDS_RELINK = "NEEDS_RELINK"

            /** Tag used for [SyncEventRepository] log entries written by this worker. */
            private const val LOG_TAG = "SyncWorker"

            /**
             * Tag used for auth-related log entries (re-auth required, MSAL refresh failure, …).
             * Kept short and stable so users can filter / share these entries from `LogsScreen`.
             */
            const val LOG_TAG_AUTH = "auth"

            /** Maximum number of error strings included in the partial-failure log message. */
            private const val MAX_LOGGED_ERRORS = 5

            /**
             * Maximum number of total attempts (first run + retries) for a [SyncEngine.Result.Retriable]
             * result or a retriable exception before the worker escalates to a terminal failure and
             * cancels the periodic sync chain.  This prevents indefinite retry storms when an
             * underlying transient error persists for a long time.
             *
             * Example: with the default WorkManager exponential backoff (starting at 30 s) and
             * MAX_RETRY_ATTEMPTS = 5, the worker gives up after roughly 7.5 minutes of retrying.
             */
            const val MAX_RETRY_ATTEMPTS = 5

            /**
             * Produces a deterministic unique WorkManager name for a SyncPair.
             *
             * @param pairId The SyncPair's id.
             * @return The unique work name for the given pair id (format: "synckro-sync-<pairId>").
             */
            fun uniqueName(pairId: Long): String = "synckro-sync-$pairId"

            /**
             * Produces the unique WorkManager name for a one-shot "sync now" job for a SyncPair.
             *
             * @param pairId The SyncPair's id.
             * @return The unique work name for the one-shot job (format: "syncnow-<pairId>").
             */
            fun syncNowUniqueName(pairId: Long): String = "syncnow-$pairId"

            /**
             * Returns `true` if the app is allowed to post notifications.
             *
             * On API < 33 (Android 13 / TIRAMISU) the `POST_NOTIFICATIONS` permission is not
             * a runtime permission, so notifications are always permitted.  On API 33+ the
             * user must explicitly grant the permission at runtime; this method returns `false`
             * when it has not been granted so callers can skip notification updates and avoid
             * a potential [SecurityException].
             */
            internal fun canPostNotifications(context: Context): Boolean =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
        }
    }

/** Schedules [SyncWorker] as periodic work per-pair with the pair's constraints. */
class SyncScheduler(
    private val workManager: WorkManager,
) {
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
    fun schedulePeriodic(
        pair: SyncPair,
        intervalMinutes: Long = 60,
    ) {
        // WorkManager rejects periodic intervals below 15 minutes. Clamp to
        // that floor rather than crashing in release builds.
        val interval = intervalMinutes.coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES)
        if (interval != intervalMinutes) {
            Timber.w(
                "Requested sync interval %d min is below WorkManager minimum; clamping to %d.",
                intervalMinutes,
                interval,
            )
        }

        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(if (pair.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(pair.requiresCharging)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

        val req =
            PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
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
     * Cancels any scheduled sync work for the SyncPair with the given id.
     *
     * This cancels both the periodic sync job and any pending one-shot "sync now" job,
     * ensuring no background work for the pair can start after this call returns.
     *
     * @param pairId The id of the SyncPair whose WorkManager jobs will be canceled.
     */
    fun cancel(pairId: Long) {
        workManager.cancelUniqueWork(SyncWorker.uniqueName(pairId))
        workManager.cancelUniqueWork(SyncWorker.syncNowUniqueName(pairId))
    }

    /**
     * Schedules or cancels periodic sync work for [pair] depending on [SyncPair.autoSyncEnabled].
     *
     * When [SyncPair.autoSyncEnabled] is `true` this delegates to [schedulePeriodic] with the
     * pair's stored [SyncPair.scheduleIntervalMinutes].  When `false` any existing periodic job
     * is cancelled so the pair no longer runs automatically; manual "Sync now" is unaffected.
     *
     * @param pair The SyncPair whose periodic work should be scheduled or cancelled.
     */
    fun scheduleOrCancel(pair: SyncPair) {
        if (pair.autoSyncEnabled) {
            schedulePeriodic(pair, pair.scheduleIntervalMinutes)
        } else {
            cancel(pair.id)
        }
    }

    companion object {
        const val MIN_PERIODIC_INTERVAL_MINUTES: Long = 15
    }
}
