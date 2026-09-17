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
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.synckro.R
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.data.worker.SyncWorker
import com.synckro.domain.model.SyncPair
import com.synckro.ui.screens.home.HomeViewModel
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
 * immediately after a widget "Sync now" tap, and from
 * [com.synckro.SynckroApp], which observes the pairs table and pushes a
 * refresh whenever a sync (started from anywhere: widget, app, or the
 * periodic worker) changes `lastSyncAtMs`.
 */
@AndroidEntryPoint
class SyncWidgetProvider : AppWidgetProvider() {
    @Inject
    lateinit var syncPairRepository: SyncPairRepository

    @Inject
    lateinit var workManager: WorkManager

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

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_SYNC_NOW) {
            super.onReceive(context, intent)
            return
        }
        val pairId = intent.getLongExtra(EXTRA_PAIR_ID, -1L)
        if (pairId < 0) return
        val pendingResult = goAsync()
        providerScope.launch {
            try {
                handleSyncNow(context, pairId)
            } catch (e: Exception) {
                Timber.w(e, "Widget \"Sync now\" failed for pair %d", pairId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleSyncNow(
        context: Context,
        pairId: Long,
    ) {
        val pairs = syncPairRepository.getAll(context.contentResolver)
        val pair = pairs.firstOrNull { it.id == pairId } ?: return
        val blockedReason = HomeViewModel.manualSyncBlockedReason(pair, syncingPairIds = emptySet())
        if (blockedReason != null) {
            Timber.i("Widget \"Sync now\"(id=$pairId) skipped: not eligible ($blockedReason)")
            return
        }
        workManager.enqueueUniqueWork(
            SyncWorker.syncNowUniqueName(pair.id),
            ExistingWorkPolicy.KEEP,
            SyncScheduler.oneTimeRequestFor(pair),
        )
        refresh(context, pairs)
    }

    companion object {
        const val ACTION_SYNC_NOW = "com.synckro.widget.ACTION_SYNC_NOW"
        const val EXTRA_PAIR_ID = "pair_id"
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
                Intent(context, SyncWidgetProvider::class.java).apply {
                    action = ACTION_SYNC_NOW
                    putExtra(EXTRA_PAIR_ID, pair.id)
                    // Distinct data URIs per pair so PendingIntent does not collapse the
                    // extras of otherwise identical (same action/component) intents.
                    data = Uri.parse("synckro://widget-sync/${pair.id}")
                }
            val syncPendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    pair.id.toInt(),
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
