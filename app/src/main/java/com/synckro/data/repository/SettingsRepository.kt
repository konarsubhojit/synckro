package com.synckro.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for global application settings, backed by Jetpack DataStore.
 *
 * Provides a single source of truth for user-configurable preferences that apply
 * across the whole app (as opposed to per-pair settings stored in Room).
 */
@Singleton
class SettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        /**
         * Emits `true` when global auto-sync is enabled (default), `false` when the
         * user has paused all background sync. Per-pair [com.synckro.domain.model.SyncPair.autoSyncEnabled]
         * is still respected: a pair will not sync automatically unless **both** this
         * global flag and its own per-pair flag are `true`.
         */
        val globalAutoSyncEnabled: Flow<Boolean> =
            dataStore.data.map { prefs ->
                prefs[KEY_GLOBAL_AUTO_SYNC] ?: true
            }

        /**
         * Persists the new global auto-sync preference.
         *
         * @param enabled `true` to allow periodic sync for all eligible pairs;
         *   `false` to pause all background sync while keeping manual "Sync now" functional.
         */
        suspend fun setGlobalAutoSync(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[KEY_GLOBAL_AUTO_SYNC] = enabled
            }
        }

        companion object {
            internal val KEY_GLOBAL_AUTO_SYNC = booleanPreferencesKey("global_auto_sync_enabled")
        }
    }
