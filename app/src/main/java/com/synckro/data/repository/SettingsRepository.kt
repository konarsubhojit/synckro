package com.synckro.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.synckro.domain.model.ConflictPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for global application settings, backed by Jetpack DataStore.
 *
 * Provides a single source of truth for user-configurable preferences that apply
 * across the whole app (as opposed to per-pair settings stored in Room).
 *
 * ## Default values
 * Defaults are chosen to match Synckro's "safe & conservative" stance: dynamic
 * color is **off** (the explicit brand scheme has tuned WCAG contrast), Wi-Fi
 * only is **on** (avoid metered cellular by default), failure notifications are
 * **on**, success notifications are **off** (avoid notification spam), and log
 * retention defaults to 30 days.
 */
@Singleton
class SettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        // -------------------------------------------------------------------------
        // Sync defaults
        // -------------------------------------------------------------------------

        /**
         * Emits `true` when global auto-sync is enabled (default), `false` when the
         * user has paused all background sync. Per-pair [com.synckro.domain.model.SyncPair.autoSyncEnabled]
         * is still respected: a pair will not sync automatically unless **both** this
         * global flag and its own per-pair flag are `true`.
         */
        val globalAutoSyncEnabled: Flow<Boolean> =
            dataStore.data.map { it[KEY_GLOBAL_AUTO_SYNC] ?: DEFAULT_GLOBAL_AUTO_SYNC }

        /**
         * Persists the new global auto-sync preference.
         *
         * @param enabled `true` to allow periodic sync for all eligible pairs;
         *   `false` to pause all background sync while keeping manual "Sync now" functional.
         */
        suspend fun setGlobalAutoSync(enabled: Boolean) {
            dataStore.edit { it[KEY_GLOBAL_AUTO_SYNC] = enabled }
        }

        /** Default value for [com.synckro.domain.model.SyncPair.wifiOnly] when creating a new pair. */
        val defaultWifiOnly: Flow<Boolean> =
            dataStore.data.map { it[KEY_DEFAULT_WIFI_ONLY] ?: DEFAULT_WIFI_ONLY }

        suspend fun setDefaultWifiOnly(enabled: Boolean) {
            dataStore.edit { it[KEY_DEFAULT_WIFI_ONLY] = enabled }
        }

        /** Default value for [com.synckro.domain.model.SyncPair.requiresCharging] when creating a new pair. */
        val defaultChargingOnly: Flow<Boolean> =
            dataStore.data.map { it[KEY_DEFAULT_CHARGING_ONLY] ?: DEFAULT_CHARGING_ONLY }

        suspend fun setDefaultChargingOnly(enabled: Boolean) {
            dataStore.edit { it[KEY_DEFAULT_CHARGING_ONLY] = enabled }
        }

        /** Default [ConflictPolicy] applied to newly created pairs in the pair editor. */
        val defaultConflictPolicy: Flow<ConflictPolicy> =
            dataStore.data.map { prefs ->
                prefs[KEY_DEFAULT_CONFLICT_POLICY]
                    ?.let { runCatching { ConflictPolicy.valueOf(it) }.getOrNull() }
                    ?: DEFAULT_CONFLICT_POLICY
            }

        suspend fun setDefaultConflictPolicy(policy: ConflictPolicy) {
            dataStore.edit { it[KEY_DEFAULT_CONFLICT_POLICY] = policy.name }
        }

        // -------------------------------------------------------------------------
        // Appearance
        // -------------------------------------------------------------------------

        /** Dark-mode preference (System / Light / Dark). */
        val darkMode: Flow<DarkModePreference> =
            dataStore.data.map { prefs ->
                prefs[KEY_DARK_MODE]
                    ?.let { runCatching { DarkModePreference.valueOf(it) }.getOrNull() }
                    ?: DEFAULT_DARK_MODE
            }

        suspend fun setDarkMode(mode: DarkModePreference) {
            dataStore.edit { it[KEY_DARK_MODE] = mode.name }
        }

        /**
         * Whether to opt in to Material 3 dynamic color (Android 12+).
         *
         * Defaults to `false` — see [com.synckro.ui.theme.SynckroTheme] for why
         * we ship with the explicit brand scheme instead of system wallpaper
         * colors.
         */
        val dynamicColor: Flow<Boolean> =
            dataStore.data.map { it[KEY_DYNAMIC_COLOR] ?: DEFAULT_DYNAMIC_COLOR }

        suspend fun setDynamicColor(enabled: Boolean) {
            dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
        }

        /**
         * When `true` (default) the UI respects the system font-scale setting;
         * when `false` the app forces a `fontScale = 1.0` density override so
         * very large system fonts do not break custom layouts.
         */
        val respectFontScale: Flow<Boolean> =
            dataStore.data.map { it[KEY_RESPECT_FONT_SCALE] ?: DEFAULT_RESPECT_FONT_SCALE }

        suspend fun setRespectFontScale(enabled: Boolean) {
            dataStore.edit { it[KEY_RESPECT_FONT_SCALE] = enabled }
        }

        // -------------------------------------------------------------------------
        // Notifications
        // -------------------------------------------------------------------------

        /** Whether to post a notification when a sync run completes successfully. */
        val notifyOnSuccess: Flow<Boolean> =
            dataStore.data.map { it[KEY_NOTIFY_ON_SUCCESS] ?: DEFAULT_NOTIFY_ON_SUCCESS }

        suspend fun setNotifyOnSuccess(enabled: Boolean) {
            dataStore.edit { it[KEY_NOTIFY_ON_SUCCESS] = enabled }
        }

        /** Whether to post a notification when a sync run fails terminally. */
        val notifyOnFailure: Flow<Boolean> =
            dataStore.data.map { it[KEY_NOTIFY_ON_FAILURE] ?: DEFAULT_NOTIFY_ON_FAILURE }

        suspend fun setNotifyOnFailure(enabled: Boolean) {
            dataStore.edit { it[KEY_NOTIFY_ON_FAILURE] = enabled }
        }

        /** Whether in-app haptic feedback should be used for supported interactions. */
        val enableHaptics: Flow<Boolean> =
            dataStore.data.map { it[KEY_ENABLE_HAPTICS] ?: DEFAULT_ENABLE_HAPTICS }

        suspend fun setEnableHaptics(enabled: Boolean) {
            dataStore.edit { it[KEY_ENABLE_HAPTICS] = enabled }
        }

        // -------------------------------------------------------------------------
        // Onboarding
        // -------------------------------------------------------------------------

        /**
         * Epoch milliseconds when the user explicitly completed or skipped
         * onboarding. `null` when the user has never finished or skipped
         * onboarding on this device (i.e. first-run state).
         *
         * @see setOnboardingCompletedAt
         */
        val onboardingCompletedAtMs: Flow<Long?> =
            dataStore.data.map { it[KEY_ONBOARDING_COMPLETED_AT_MS] }

        /**
         * Records [timestampMs] as the moment onboarding was completed or
         * explicitly skipped, suppressing the onboarding pager on all
         * subsequent launches.
         *
         * @param timestampMs Epoch-milliseconds timestamp; typically
         *   `System.currentTimeMillis()`.
         */
        suspend fun setOnboardingCompletedAt(timestampMs: Long) {
            dataStore.edit { it[KEY_ONBOARDING_COMPLETED_AT_MS] = timestampMs }
        }

        /**
         * Set of one-shot coach-tooltip ids that should no longer be shown.
         */
        val seenTooltips: Flow<Set<String>> =
            dataStore.data.map { it[KEY_SEEN_TOOLTIPS] ?: emptySet() }

        suspend fun markTooltipSeen(tooltipId: String) {
            dataStore.edit { prefs ->
                val current = prefs[KEY_SEEN_TOOLTIPS] ?: emptySet()
                prefs[KEY_SEEN_TOOLTIPS] = current + tooltipId
            }
        }

        suspend fun resetSeenTooltips() {
            dataStore.edit { it.remove(KEY_SEEN_TOOLTIPS) }
        }

        // -------------------------------------------------------------------------
        // Storage & logs
        // -------------------------------------------------------------------------

        /**
         * Number of days to retain structured sync events. Defaults to 30 and is
         * pinned to one of the supported [LogRetentionPreference] presets to
         * keep the slider UI bounded.
         */
        val logRetentionDays: Flow<Int> =
            dataStore.data.map { it[KEY_LOG_RETENTION_DAYS] ?: DEFAULT_LOG_RETENTION_DAYS }

        suspend fun setLogRetentionDays(days: Int) {
            dataStore.edit { it[KEY_LOG_RETENTION_DAYS] = days }
        }

        companion object {
            internal val KEY_GLOBAL_AUTO_SYNC = booleanPreferencesKey("global_auto_sync_enabled")
            internal val KEY_DEFAULT_WIFI_ONLY = booleanPreferencesKey("default_wifi_only")
            internal val KEY_DEFAULT_CHARGING_ONLY = booleanPreferencesKey("default_charging_only")
            internal val KEY_DEFAULT_CONFLICT_POLICY = stringPreferencesKey("default_conflict_policy")

            internal val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
            internal val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
            internal val KEY_RESPECT_FONT_SCALE = booleanPreferencesKey("respect_font_scale")

            internal val KEY_NOTIFY_ON_SUCCESS = booleanPreferencesKey("notify_on_success")
            internal val KEY_NOTIFY_ON_FAILURE = booleanPreferencesKey("notify_on_failure")
            internal val KEY_ENABLE_HAPTICS = booleanPreferencesKey("enable_haptics")

            internal val KEY_LOG_RETENTION_DAYS = intPreferencesKey("log_retention_days")

            internal val KEY_ONBOARDING_COMPLETED_AT_MS = longPreferencesKey("onboarding_completed_at_ms")
            internal val KEY_SEEN_TOOLTIPS = stringSetPreferencesKey("seen_tooltips")

            internal const val DEFAULT_GLOBAL_AUTO_SYNC = true
            internal const val DEFAULT_WIFI_ONLY = true
            internal const val DEFAULT_CHARGING_ONLY = false
            internal val DEFAULT_CONFLICT_POLICY = ConflictPolicy.NEWEST_WINS

            internal val DEFAULT_DARK_MODE = DarkModePreference.SYSTEM
            internal const val DEFAULT_DYNAMIC_COLOR = false
            internal const val DEFAULT_RESPECT_FONT_SCALE = true

            internal const val DEFAULT_NOTIFY_ON_SUCCESS = false
            internal const val DEFAULT_NOTIFY_ON_FAILURE = true
            internal const val DEFAULT_ENABLE_HAPTICS = true

            internal const val DEFAULT_LOG_RETENTION_DAYS = 30
        }
    }
