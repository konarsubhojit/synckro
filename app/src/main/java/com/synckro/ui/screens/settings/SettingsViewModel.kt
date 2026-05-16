package com.synckro.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the Settings screen. Reads and writes the global auto-sync toggle
 * and triggers a batch reschedule of all sync pairs when the toggle changes.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val syncPairRepository: SyncPairRepository,
        private val syncScheduler: SyncScheduler,
    ) : ViewModel() {
        data class UiState(
            val globalAutoSyncEnabled: Boolean = true,
        )

        val state: StateFlow<UiState> =
            settingsRepository.globalAutoSyncEnabled
                .map { UiState(globalAutoSyncEnabled = it) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = UiState(),
                )

        /**
         * Updates the global auto-sync preference and immediately reschedules (or
         * cancels) periodic sync work for every pair accordingly.
         *
         * @param enabled `true` to resume periodic sync for all eligible pairs;
         *   `false` to pause all background sync (manual "Sync now" is unaffected).
         */
        fun setGlobalAutoSync(enabled: Boolean) {
            Timber.i("SettingsViewModel.setGlobalAutoSync(enabled=$enabled)")
            viewModelScope.launch {
                settingsRepository.setGlobalAutoSync(enabled)
                val pairs = syncPairRepository.observeAll(context.contentResolver).first()
                syncScheduler.scheduleOrCancelAll(pairs, enabled)
            }
        }
    }
