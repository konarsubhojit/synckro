package com.synckro.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import com.synckro.R
import com.synckro.data.repository.SyncPairRepository
import com.synckro.domain.model.SyncPair
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Home-screen widget that lists every sync pair with its last-sync timestamp
 * and a per-row "Sync now" action.
 *
 * Rows are plain [RemoteViews] rebuilt from scratch on every refresh — there is
 * no Glance dependency in this project, and a static pair count (a handful of
 * pairs is the expected common case) does not need a `RemoteViewsService`
 * list adapter. [refresh] is called from [onUpdate] (platform-driven, at most
 * every ~30 minutes per [android.appwidget.AppWidgetProviderInfo]),
 * immediately after a widget "Sync now" tap (see [SyncWidgetActionReceiver]),
 * and from [com.synckro.SynckroApp], which observes the pairs table and pushes
 * a refresh whenever a sync (started from anywhere: widget, app, or the
 * periodic worker) changes `lastSyncAtMs`.
 *
 * The "Sync now" tap itself is handled by the separate, non-exported
 * [SyncWidgetActionReceiver] rather than by this class, because this provider
 * must be `exported="true"` for the system to deliver `APPWIDGET_UPDATE` —
 * an exported component can be sent an explicit-component broadcast by *any*
 * app regardless of its intent-filter, so a privileged action like enqueuing a
 * sync must live on a component the system cannot reach.
 */
@AndroidEntryPoint
class SyncWidgetProvider : AppWidgetProvider() {
    @Inject
    lateinit var syncPairRepository: SyncPairRepository

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        providerScope.launch {
            try {
                val pairs = syncPairRepository.getAll(context.contentResolver)
                appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id, pairs) }
            } catch (e: Exception) {
                Timber.w(e, "Failed to refresh sync widget")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val MAX_ROWS = 8

        // A tiny long-lived scope for a broadcast receiver's one-shot work; mirrors
        // the pattern used by WatcherBootReceiver so goAsync() callers never block
        // the main thread while still finishing the pending result deterministically.
        private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Rebuilds every installed widget instance from [pairs]. A no-op when no
         * widget is currently pinned, so callers can invoke this unconditionally
         * whenever the pairs table changes.
         */
        fun refresh(
            context: Context,
            pairs: List<SyncPair>,
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SyncWidgetProvider::class.java))
            if (ids.isEmpty()) return
            ids.forEach { id -> updateWidget(context, manager, id, pairs) }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            pairs: List<SyncPair>,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_sync)

            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launchIntent ->
                val openAppPendingIntent =
                    PendingIntent.getActivity(
                        context,
                        0,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                views.setOnClickPendingIntent(R.id.widget_title, openAppPendingIntent)
            }

            views.removeAllViews(R.id.widget_pair_list)
            if (pairs.isEmpty()) {
                views.setViewVisibility(R.id.widget_empty_text, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_empty_text, View.GONE)
                pairs.take(MAX_ROWS).forEach { pair ->
                    views.addView(R.id.widget_pair_list, buildRow(context, pair))
                }
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun buildRow(
            context: Context,
            pair: SyncPair,
        ): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_sync_row)
            row.setTextViewText(R.id.widget_row_name, pair.displayName)
            row.setTextViewText(R.id.widget_row_subtitle, lastSyncLabel(context, pair))

            val syncIntent =
                Intent(context, SyncWidgetActionReceiver::class.java).apply {
                    action = SyncWidgetActionReceiver.ACTION_SYNC_NOW
                    putExtra(SyncWidgetActionReceiver.EXTRA_PAIR_ID, pair.id)
                    // Distinct data URIs per pair so PendingIntent does not collapse the
                    // extras of otherwise identical (same action/component) intents.
                    data = Uri.parse("synckro://widget-sync/${pair.id}")
                }
            val syncPendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    // The distinct `data` URI above is what disambiguates PendingIntents
                    // across pairs, so a constant request code is safe here and avoids the
                    // request-code collisions a truncating `pair.id.toInt()` could hit for
                    // two ids that differ only in their upper 32 bits.
                    0,
                    syncIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            row.setOnClickPendingIntent(R.id.widget_row_sync_button, syncPendingIntent)
            return row
        }

        private fun lastSyncLabel(
            context: Context,
            pair: SyncPair,
        ): String {
            val lastSyncAtMs = pair.lastSyncAtMs ?: return context.getString(R.string.widget_never_synced)
            val relative =
                DateUtils.getRelativeTimeSpanString(
                    lastSyncAtMs,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                )
            return context.getString(R.string.widget_last_synced_format, relative)
        }
    }
}
