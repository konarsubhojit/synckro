package com.synckro.data.watcher

import android.content.ContentResolver
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.domain.sync.WatcherPairSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The set of pairs whose local trees should currently be watched.
 *
 * Exposed as an interface so lifecycle components can be unit-tested without Room or DataStore.
 */
interface WatchablePairs {
    /** Emits the watchable pair ids whenever pairs or the global sync settings change. */
    fun observe(): Flow<Set<Long>>

    /** Returns the watchable pair ids once. */
    suspend fun current(): Set<Long>
}

/** [WatchablePairs] backed by the persisted pairs and the global sync settings. */
@Singleton
class DefaultWatchablePairs
    @Inject
    constructor(
        private val syncPairRepository: SyncPairRepository,
        private val settingsRepository: SettingsRepository,
        private val contentResolver: ContentResolver,
    ) : WatchablePairs {
        override fun observe(): Flow<Set<Long>> =
            combine(
                syncPairRepository.observeAll(contentResolver),
                settingsRepository.globalAutoSyncEnabled,
                settingsRepository.globalInstantSyncEnabled,
            ) { pairs, autoSync, instantSync ->
                WatcherPairSelection.selectWatchablePairIds(pairs, autoSync, instantSync)
            }.distinctUntilChanged()

        // Single-shot reads instead of collecting observe(), which would subscribe a Room query
        // and two DataStore flows on every lifecycle evaluation.
        override suspend fun current(): Set<Long> =
            WatcherPairSelection.selectWatchablePairIds(
                pairs = syncPairRepository.getAll(contentResolver),
                globalAutoSyncEnabled = settingsRepository.globalAutoSyncEnabled.first(),
                globalInstantSyncEnabled = settingsRepository.globalInstantSyncEnabled.first(),
            )
    }
