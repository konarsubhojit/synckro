package com.synckro.ui.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.data.worker.SyncWorker
import com.synckro.ui.screens.home.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Handles the widget's per-pair "Sync now" tap.
 *
 * Deliberately a separate, `exported="false"` receiver from [SyncWidgetProvider]:
 * that provider must stay `exported="true"` for the system to deliver
 * `APPWIDGET_UPDATE`, but an exported component can be sent an explicit-component
 * broadcast by *any* app (intent-filters only gate implicit-intent resolution, not
 * explicit ones), so enqueuing a sync must live on a component the system cannot
 * reach from outside this app. Only [SyncWidgetProvider] builds the
 * [android.app.PendingIntent] that targets this receiver.
 */
@AndroidEntryPoint
class SyncWidgetActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var syncPairRepository: SyncPairRepository

    @Inject
    lateinit var workManager: WorkManager

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_SYNC_NOW) return
        val pairId = intent.getLongExtra(EXTRA_PAIR_ID, -1L)
        if (pairId < 0) return
        val pendingResult = goAsync()
        receiverScope.launch {
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
        SyncWidgetProvider.refresh(context, pairs)
    }

    companion object {
        const val ACTION_SYNC_NOW = "com.synckro.widget.ACTION_SYNC_NOW"
        const val EXTRA_PAIR_ID = "pair_id"

        // Mirrors the pattern used by WatcherBootReceiver so goAsync() callers never
        // block the main thread while still finishing the pending result deterministically.
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
