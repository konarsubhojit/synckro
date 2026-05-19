package com.synckro.util.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.synckro.MainActivity
import com.synckro.R
import com.synckro.data.worker.SyncWorker
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.SyncPair
import kotlin.math.absoluteValue

/**
 * Helper that posts and cancels re-authentication Android notifications.
 *
 * ## Notification strategy
 *
 * Re-auth notifications use [SyncWorker.REAUTH_CHANNEL_ID] (`"synckro_reauth"`), which is
 * configured with [NotificationManager.IMPORTANCE_HIGH] so the alert appears as a heads-up
 * banner even when the app is in the background or killed.  Unlike the low-importance sync-
 * progress channel, this channel shows a badge and makes a sound so the user is unlikely to
 * miss it.
 *
 * Each affected cloud account receives its own persistent notification so the user can see
 * at a glance how many accounts need attention.  On Android 7+ (API 24) the system groups
 * them under a single header in the notification drawer; a dedicated group-summary
 * notification is posted alongside each individual alert to drive this grouping.
 *
 * ## Notification IDs
 *
 * | Range                    | Purpose                                         |
 * |:-------------------------|:------------------------------------------------|
 * | 1 000 – 66 535           | Sync-progress foreground service notifications  |
 * | [REAUTH_NOTIFICATION_ID_BASE] .. + [MAX_ACCOUNTS] − 1 | Per-account reauth alerts |
 * | [REAUTH_GROUP_SUMMARY_ID]| Group-summary notification (one at a time)      |
 *
 * ## Dismissal
 *
 * Call [cancelReauthNotification] with the account's ID after a successful interactive
 * sign-in (e.g. from [com.synckro.ui.screens.accounts.AccountsViewModel]) to remove the
 * alert.  The group summary is automatically cancelled when no individual alerts remain.
 *
 * ## Deep link
 *
 * The notification's tap action and "Reconnect" button both launch [MainActivity] with
 * [ACTION_OPEN_ACCOUNTS].  [MainActivity] forwards this to [com.synckro.util.navigation.AppNavigationDispatcher]
 * which navigates the Compose NavHost directly to the Accounts screen.
 */
object ReauthNotificationHelper {

    /**
     * Intent action placed on the [MainActivity] launch intent by this helper.
     * [MainActivity] forwards this to [com.synckro.util.navigation.AppNavigationDispatcher]
     * so the Compose NavHost can navigate straight to the Accounts screen.
     */
    const val ACTION_OPEN_ACCOUNTS = "com.synckro.ACTION_OPEN_ACCOUNTS"

    /**
     * Optional intent extra carrying the account id whose row should be highlighted on
     * arrival at the Accounts screen (Phase 5d reauth deep-link). Absent when the
     * notification doesn't have a specific account associated (e.g. the group summary).
     */
    const val EXTRA_ACCOUNT_ID = "com.synckro.EXTRA_ACCOUNT_ID"

    /** Notification channel ID for re-auth alerts. Registered in [com.synckro.SynckroApp]. */
    const val REAUTH_CHANNEL_ID = "synckro_reauth"

    /** Notification group key used to stack multiple per-account alerts in the drawer. */
    const val REAUTH_GROUP_KEY = "com.synckro.REAUTH_GROUP"

    /**
     * Base notification ID for individual reauth alerts.
     * IDs range from [REAUTH_NOTIFICATION_ID_BASE] to [REAUTH_NOTIFICATION_ID_BASE] +
     * [MAX_ACCOUNTS] − 1 (inclusive).
     */
    private const val REAUTH_NOTIFICATION_ID_BASE = 100_000

    /** Maximum number of distinct per-account IDs in the reauth range. */
    private const val MAX_ACCOUNTS = 4_096

    /**
     * Notification ID reserved for the group-summary notification.
     * Placed just below the per-account range to avoid collisions.
     */
    private const val REAUTH_GROUP_SUMMARY_ID = REAUTH_NOTIFICATION_ID_BASE - 1

    /**
     * Human-readable display names for each [CloudProviderType].
     * These are brand names and therefore not localised.
     */
    private val PROVIDER_DISPLAY_NAMES: Map<CloudProviderType, String> =
        mapOf(
            CloudProviderType.GOOGLE_DRIVE to "Google Drive",
            CloudProviderType.ONEDRIVE to "OneDrive",
            CloudProviderType.FAKE to "Test Provider",
        )

    /**
     * Posts a persistent, high-priority re-auth notification for [pair].
     *
     * If [SyncWorker.canPostNotifications] returns `false` (POST_NOTIFICATIONS not granted on
     * Android 13+) the call is a no-op.
     *
     * A group-summary notification is always updated alongside the individual alert so
     * that Android 7+ groups them correctly in the notification drawer.
     *
     * @param context Android context — may be an application context.
     * @param pair    The SyncPair whose last sync ended with a terminal auth failure.
     */
    fun postReauthNotification(
        context: Context,
        pair: SyncPair,
    ) {
        if (!SyncWorker.canPostNotifications(context)) return

        val nm = context.getSystemService(NotificationManager::class.java)
        val providerName = PROVIDER_DISPLAY_NAMES[pair.provider] ?: pair.provider.name
        val notificationId = notificationIdForAccount(pair.accountId ?: pair.provider.name)

        val pendingIntent = buildOpenAccountsPendingIntent(context, notificationId, pair.accountId)

        val notification =
            NotificationCompat
                .Builder(context, REAUTH_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.reauth_notification_title, providerName))
                .setContentText(context.getString(R.string.reauth_notification_content, pair.displayName))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.reauth_notification_content, pair.displayName)),
                ).setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup(REAUTH_GROUP_KEY)
                .addAction(
                    android.R.drawable.ic_lock_idle_lock,
                    context.getString(R.string.reauth_notification_action),
                    pendingIntent,
                ).build()

        nm.notify(notificationId, notification)
        postGroupSummary(context, nm)
    }

    /**
     * Cancels the reauth notification for the given [accountId] and removes the group
     * summary if no other per-account reauth notifications remain active.
     *
     * @param context   Android context — may be an application context.
     * @param accountId The account ID whose notification should be dismissed.
     */
    fun cancelReauthNotification(
        context: Context,
        accountId: String,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(notificationIdForAccount(accountId))
        cancelGroupSummaryIfEmpty(nm)
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Builds a [PendingIntent] that launches [MainActivity] with [ACTION_OPEN_ACCOUNTS].
     * [requestCode] is set to [notificationId] so each account gets a distinct intent
     * that won't be collapsed by the system's intent deduplication.
     */
    private fun buildOpenAccountsPendingIntent(
        context: Context,
        requestCode: Int,
        accountId: String? = null,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_ACCOUNTS
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (accountId != null) {
                    putExtra(EXTRA_ACCOUNT_ID, accountId)
                }
            }
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    /**
     * Posts or updates the group-summary notification.  Android 7+ uses this notification
     * to collapse individual alerts under a single header in the notification drawer.
     *
     * The summary shows the count of currently active reauth notifications (active
     * notifications whose group key matches [REAUTH_GROUP_KEY], excluding the summary
     * itself).
     */
    private fun postGroupSummary(
        context: Context,
        nm: NotificationManager,
    ) {
        val activeReauthCount = countActiveReauthNotifications(nm)
        val summaryText =
            if (activeReauthCount > 1) {
                context.getString(R.string.reauth_notification_group_summary, activeReauthCount)
            } else {
                context.getString(R.string.reauth_channel_name)
            }

        val pendingIntent = buildOpenAccountsPendingIntent(context, REAUTH_GROUP_SUMMARY_ID)
        val summary =
            NotificationCompat
                .Builder(context, REAUTH_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(summaryText)
                .setContentIntent(pendingIntent)
                .setGroup(REAUTH_GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(false)
                .setOngoing(false)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

        nm.notify(REAUTH_GROUP_SUMMARY_ID, summary)
    }

    /**
     * Cancels the group-summary notification if there are no remaining individual
     * reauth notifications.
     */
    private fun cancelGroupSummaryIfEmpty(nm: NotificationManager) {
        if (countActiveReauthNotifications(nm) == 0) {
            nm.cancel(REAUTH_GROUP_SUMMARY_ID)
        }
    }

    /**
     * Returns the number of currently active per-account reauth notifications
     * (i.e. those whose ID falls in the [REAUTH_NOTIFICATION_ID_BASE] range,
     * excluding the summary).
     *
     * [NotificationManager.getActiveNotifications] requires API 23, which is below
     * the app's minSdk, so the call is always safe.
     */
    private fun countActiveReauthNotifications(nm: NotificationManager): Int =
        nm.activeNotifications.count { sbn ->
            sbn.id >= REAUTH_NOTIFICATION_ID_BASE &&
                sbn.id < REAUTH_NOTIFICATION_ID_BASE + MAX_ACCOUNTS
        }

    /**
     * Maps an account identifier to a stable notification ID within the reauth range.
     *
     * The mapping uses the absolute value of [accountId]'s hash code modulo
     * [MAX_ACCOUNTS] so the resulting ID is always in
     * [[REAUTH_NOTIFICATION_ID_BASE], [REAUTH_NOTIFICATION_ID_BASE] + [MAX_ACCOUNTS] − 1].
     */
    internal fun notificationIdForAccount(accountId: String): Int =
        REAUTH_NOTIFICATION_ID_BASE + (accountId.hashCode().absoluteValue % MAX_ACCOUNTS)
}
