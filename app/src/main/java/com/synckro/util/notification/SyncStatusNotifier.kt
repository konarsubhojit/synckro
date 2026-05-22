package com.synckro.util.notification

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import com.synckro.MainActivity
import com.synckro.R
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.worker.SyncWorker
import com.synckro.domain.model.SyncPair
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncStatusNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val workerCompletionAggregator: WorkerCompletionAggregator,
    ) {
        suspend fun notifyFailure(
            pair: SyncPair,
            reason: String,
        ) {
            if (!settingsRepository.notifyOnFailure.first()) return
            if (!SyncWorker.canPostNotifications(context)) return
            val hapticsEnabled = settingsRepository.enableHaptics.first()

            val summary = summarizeFailure(reason)
            val notification =
                NotificationCompat
                    .Builder(context, SYNC_STATUS_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(pair.displayName)
                    .setContentText(summary)
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            if (summary == reason.trim()) summary else "$summary\n$reason",
                        ),
                    ).setContentIntent(buildOpenLogsPendingIntent(pair.id))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()

            context.getSystemService(NotificationManager::class.java)
                .notify(pair.id.toInt(), notification)

            if (hapticsEnabled && isAppInForeground()) {
                performInAppErrorHaptic()
            }
        }

        suspend fun notifySuccessSummary(
            pair: SyncPair,
            applied: Int,
            _conflicts: Int,
        ) {
            if (!settingsRepository.notifyOnSuccess.first()) return
            if (!SyncWorker.canPostNotifications(context)) return
            if (applied <= 0) return

            workerCompletionAggregator.record(pair, applied) { completions ->
                postSuccessSummary(completions)
            }
        }

        companion object {
            /** Notification channel ID reserved for sync success/failure result notifications. */
            const val SYNC_STATUS_CHANNEL_ID = "sync_status"
            const val ACTION_OPEN_LOGS = "com.synckro.ACTION_OPEN_LOGS"
            const val EXTRA_PAIR_ID = "com.synckro.EXTRA_PAIR_ID"
            private const val MAX_SUMMARY_LENGTH = 120
            private const val SUCCESS_GROUP_KEY = "com.synckro.SYNC_SUCCESS_GROUP"
            private const val SUCCESS_GROUP_SUMMARY_ID = 0
        }

        private fun summarizeFailure(reason: String): String {
            val normalized = reason.trim()
            if (normalized.isBlank()) {
                return context.getString(R.string.sync_failure_unknown_summary)
            }
            return when {
                normalized.startsWith("Authentication failed", ignoreCase = true) ->
                    context.getString(R.string.sync_failure_auth_summary)
                normalized.startsWith("Rate limited", ignoreCase = true) ->
                    context.getString(R.string.sync_failure_rate_limited_summary)
                normalized.startsWith("Provider not configured", ignoreCase = true) ->
                    context.getString(R.string.sync_failure_not_configured_summary)
                normalized.startsWith("Re-authentication required", ignoreCase = true) ->
                    context.getString(R.string.sync_failure_reauth_summary)
                else ->
                    normalized.lineSequence().firstOrNull()
                        ?.take(MAX_SUMMARY_LENGTH)
                        ?.ifBlank { context.getString(R.string.sync_failure_unknown_summary) }
                        ?: context.getString(R.string.sync_failure_unknown_summary)
            }
        }

        private fun isAppInForeground(): Boolean {
            val processInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(processInfo)
            return processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

        private fun performInAppErrorHaptic() {
            val vibrator =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Vibrator::class.java)
                }
            if (vibrator?.hasVibrator() != true) return
            val effect =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                } else {
                    VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE)
                }
            runCatching { vibrator.vibrate(effect) }
                .onFailure { Timber.w(it, "SyncStatusNotifier: unable to vibrate error haptic") }
        }

        private fun buildOpenLogsPendingIntent(pairId: Long): PendingIntent {
            val intent =
                Intent(context, MainActivity::class.java).apply {
                    action = ACTION_OPEN_LOGS
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_PAIR_ID, pairId)
                }
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            return PendingIntent.getActivity(context, pairId.toInt(), intent, flags)
        }

        private suspend fun postSuccessSummary(completions: List<WorkerCompletionAggregator.Completion>) {
            if (completions.isEmpty()) return
            if (!settingsRepository.notifyOnSuccess.first()) return
            if (!SyncWorker.canPostNotifications(context)) return

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val totalPairs = completions.size
            val totalFiles = completions.sumOf { it.transferredFiles }
            val summaryText =
                context.getString(
                    R.string.sync_success_notification_summary,
                    totalPairs,
                    totalFiles,
                )

            completions.forEach { completion ->
                val childText =
                    context.getString(
                        R.string.sync_success_notification_child_text,
                        completion.transferredFiles,
                    )
                val childNotification =
                    NotificationCompat
                        .Builder(context, SYNC_STATUS_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                        .setContentTitle(completion.pair.displayName)
                        .setContentText(childText)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(childText))
                        .setContentIntent(buildOpenLogsPendingIntent(completion.pair.id))
                        .setAutoCancel(true)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setOnlyAlertOnce(true)
                        .setGroup(SUCCESS_GROUP_KEY)
                        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                        .build()

                notificationManager.notify(completion.pair.id.toInt(), childNotification)
            }

            val inboxStyle =
                NotificationCompat
                    .InboxStyle()
                    .setSummaryText(summaryText)
                    .also { style ->
                        completions.forEach { completion ->
                            style.addLine(
                                context.getString(
                                    R.string.sync_success_notification_inbox_line,
                                    completion.pair.displayName,
                                    completion.transferredFiles,
                                ),
                            )
                        }
                    }

            val summaryNotification =
                NotificationCompat
                    .Builder(context, SYNC_STATUS_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle(summaryText)
                    .setContentText(summaryText)
                    .setStyle(inboxStyle)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setGroup(SUCCESS_GROUP_KEY)
                    .setGroupSummary(true)
                    .build()

            notificationManager.notify(SUCCESS_GROUP_SUMMARY_ID, summaryNotification)
        }
    }
