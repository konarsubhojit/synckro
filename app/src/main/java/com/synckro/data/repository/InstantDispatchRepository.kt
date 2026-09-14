package com.synckro.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.synckro.domain.sync.PairDispatchHistory
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable [PairDispatchHistory] backed by the settings DataStore.
 *
 * Persisting the last instant dispatch per pair keeps
 * [com.synckro.domain.sync.PairSignalCoordinator]'s rate-limit window intact across process death,
 * so a restart cannot amplify dispatch by starting from an empty window.
 */
@Singleton
class InstantDispatchRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : PairDispatchHistory {
        override suspend fun lastDispatchAtMs(pairId: Long): Long? = dataStore.data.first()[keyFor(pairId)]

        override suspend fun recordDispatch(
            pairId: Long,
            atMs: Long,
        ) {
            dataStore.edit { it[keyFor(pairId)] = atMs }
        }

        /** Removes [pairId]'s stored dispatch time, for example when the pair is deleted. */
        suspend fun clearPair(pairId: Long) {
            dataStore.edit { it.remove(keyFor(pairId)) }
        }

        companion object {
            internal const val KEY_PREFIX = "instant_last_dispatch_at_ms_"

            internal fun keyFor(pairId: Long) = longPreferencesKey("$KEY_PREFIX$pairId")
        }
    }
