package com.synckro.util.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.synckro.MainActivity
import com.synckro.R
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.worker.SyncWorker
import com.synckro.domain.model.SyncPair
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncStatusNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
    ) {
        suspend fun notifyFailure(
            pair: SyncPair,
            reason: String,
        ) {
            if (!settingsRepository.notifyOnFailure.first()) return
            if (!SyncWorker.canPostNotifications(context)) return

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
        }

        suspend fun notifySuccessSummary(
            _pair: SyncPair,
            _applied: Int,
            _conflicts: Int,
        ) {
            if (!settingsRepository.notifyOnSuccess.first()) return
        }

        companion object {
            /** Notification channel ID reserved for sync success/failure result notifications. */
            const val SYNC_STATUS_CHANNEL_ID = "sync_status"
            const val ACTION_OPEN_LOGS = "com.synckro.ACTION_OPEN_LOGS"
            const val EXTRA_PAIR_ID = "com.synckro.EXTRA_PAIR_ID"
            private const val MAX_SUMMARY_LENGTH = 120
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
    }
