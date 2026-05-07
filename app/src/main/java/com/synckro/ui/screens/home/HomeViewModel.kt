package com.synckro.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.data.worker.SyncWorker
import com.synckro.domain.model.SyncPair
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the home / pair-list screen. Observes the [SyncPairRepository] and
 * exposes actions for deleting pairs and triggering one-shot syncs.
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val syncPairRepository: SyncPairRepository,
        private val conflictRepository: ConflictRepository,
        private val workManager: WorkManager,
        private val syncScheduler: SyncScheduler,
    ) : ViewModel() {
        data class UiState(
            val pairs: List<SyncPair> = emptyList(),
            val isLoading: Boolean = true,
            /** Number of pending (unresolved) conflicts across all pairs. */
            val pendingConflictCount: Int = 0,
        )

        val state: StateFlow<UiState> =
            combine(
                syncPairRepository.observeAll(context.contentResolver),
                conflictRepository.observeUnresolved(),
            ) { pairs, conflicts ->
                UiState(pairs = pairs, isLoading = false, pendingConflictCount = conflicts.size)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = UiState(),
            )

        /** Permanently removes the sync pair with [id] from the database and cancels any
         *  associated background sync jobs (both periodic and one-shot). */
        fun delete(id: Long) {
            Timber.i("HomeViewModel.delete(id=$id)")
            syncScheduler.cancel(id)
            viewModelScope.launch { syncPairRepository.delete(id) }
        }

        /**
         * Enqueues a one-shot [SyncWorker] for [pair]. Any in-flight one-shot run for
         * the same pair is kept so the user never interrupts an ongoing sync.
         */
        fun syncNow(pair: SyncPair) {
            Timber.i("HomeViewModel.syncNow(id=${pair.id})")
            val req =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(workDataOf(SyncWorker.KEY_PAIR_ID to pair.id))
                    .build()
            workManager.enqueueUniqueWork(
                SyncWorker.syncNowUniqueName(pair.id),
                ExistingWorkPolicy.KEEP,
                req,
            )
        }
    }
