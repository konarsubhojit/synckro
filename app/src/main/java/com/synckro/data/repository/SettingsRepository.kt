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

        /** Number of file operations that may run concurrently during a single sync
         *  pass. Clamped to [1, MAX_CONCURRENT_TRANSFERS] on read and write. */
        val maxConcurrentTransfers: Flow<Int> =
            dataStore.data.map {
                (it[KEY_MAX_CONCURRENT_TRANSFERS] ?: DEFAULT_MAX_CONCURRENT_TRANSFERS)
                    .coerceIn(1, MAX_CONCURRENT_TRANSFERS)
            }

        suspend fun setMaxConcurrentTransfers(n: Int) {
            dataStore.edit {
                it[KEY_MAX_CONCURRENT_TRANSFERS] = n.coerceIn(1, MAX_CONCURRENT_TRANSFERS)
            }
        }

        val mobileUploadLimitMb: Flow<Int> =
            dataStore.data.map {
                (it[KEY_MOBILE_UPLOAD_LIMIT_MB] ?: DEFAULT_MOBILE_UPLOAD_LIMIT_MB)
                    .coerceIn(0, MAX_MOBILE_TRANSFER_LIMIT_MB)
            }

        suspend fun setMobileUploadLimitMb(limitMb: Int) {
            dataStore.edit {
                it[KEY_MOBILE_UPLOAD_LIMIT_MB] = limitMb.coerceIn(0, MAX_MOBILE_TRANSFER_LIMIT_MB)
            }
        }

        val mobileDownloadLimitMb: Flow<Int> =
            dataStore.data.map {
                (it[KEY_MOBILE_DOWNLOAD_LIMIT_MB] ?: DEFAULT_MOBILE_DOWNLOAD_LIMIT_MB)
                    .coerceIn(0, MAX_MOBILE_TRANSFER_LIMIT_MB)
            }

        suspend fun setMobileDownloadLimitMb(limitMb: Int) {
            dataStore.edit {
                it[KEY_MOBILE_DOWNLOAD_LIMIT_MB] = limitMb.coerceIn(0, MAX_MOBILE_TRANSFER_LIMIT_MB)
            }
        }

        val warnOnMobileNetworkSync: Flow<Boolean> =
            dataStore.data.map { it[KEY_WARN_ON_MOBILE_NETWORK_SYNC] ?: DEFAULT_WARN_ON_MOBILE_NETWORK_SYNC }

        suspend fun setWarnOnMobileNetworkSync(enabled: Boolean) {
            dataStore.edit { it[KEY_WARN_ON_MOBILE_NETWORK_SYNC] = enabled }
        }

        val retryAutomaticallyAfterError: Flow<Boolean> =
            dataStore.data.map { it[KEY_RETRY_AUTOMATICALLY_AFTER_ERROR] ?: DEFAULT_RETRY_AUTOMATICALLY_AFTER_ERROR }

        suspend fun setRetryAutomaticallyAfterError(enabled: Boolean) {
            dataStore.edit { it[KEY_RETRY_AUTOMATICALLY_AFTER_ERROR] = enabled }
        }

        val retryWaitMinutes: Flow<Int> =
            dataStore.data.map {
                (it[KEY_RETRY_WAIT_MINUTES] ?: DEFAULT_RETRY_WAIT_MINUTES)
                    .coerceIn(MIN_RETRY_WAIT_MINUTES, MAX_RETRY_WAIT_MINUTES)
            }

        suspend fun setRetryWaitMinutes(minutes: Int) {
            dataStore.edit {
                it[KEY_RETRY_WAIT_MINUTES] =
                    minutes.coerceIn(MIN_RETRY_WAIT_MINUTES, MAX_RETRY_WAIT_MINUTES)
            }
        }

        val retryMaxAttempts: Flow<Int> =
            dataStore.data.map {
                (it[KEY_RETRY_MAX_ATTEMPTS] ?: DEFAULT_RETRY_MAX_ATTEMPTS)
                    .coerceIn(MIN_RETRY_ATTEMPTS, MAX_RETRY_ATTEMPTS)
            }

        suspend fun setRetryMaxAttempts(attempts: Int) {
            dataStore.edit {
                it[KEY_RETRY_MAX_ATTEMPTS] = attempts.coerceIn(MIN_RETRY_ATTEMPTS, MAX_RETRY_ATTEMPTS)
            }
        }

        val parallelUploads: Flow<Int> =
            dataStore.data.map {
                (it[KEY_PARALLEL_UPLOADS] ?: DEFAULT_PARALLEL_UPLOADS)
                    .coerceIn(1, MAX_PARALLEL_TRANSFERS_PER_DIRECTION)
            }

        suspend fun setParallelUploads(value: Int) {
            dataStore.edit { it[KEY_PARALLEL_UPLOADS] = value.coerceIn(1, MAX_PARALLEL_TRANSFERS_PER_DIRECTION) }
        }

        val parallelDownloads: Flow<Int> =
            dataStore.data.map {
                (it[KEY_PARALLEL_DOWNLOADS] ?: DEFAULT_PARALLEL_DOWNLOADS)
                    .coerceIn(1, MAX_PARALLEL_TRANSFERS_PER_DIRECTION)
            }

        suspend fun setParallelDownloads(value: Int) {
            dataStore.edit { it[KEY_PARALLEL_DOWNLOADS] = value.coerceIn(1, MAX_PARALLEL_TRANSFERS_PER_DIRECTION) }
        }

        val autoSyncSchedule: Flow<AutoSyncSchedule> =
            dataStore.data.map { prefs ->
                prefs[KEY_AUTO_SYNC_SCHEDULE]
                    ?.let { runCatching { AutoSyncSchedule.valueOf(it) }.getOrNull() }
                    ?: DEFAULT_AUTO_SYNC_SCHEDULE
            }

        suspend fun setAutoSyncSchedule(schedule: AutoSyncSchedule) {
            dataStore.edit { it[KEY_AUTO_SYNC_SCHEDULE] = schedule.name }
        }

        val autoSyncChargingOnly: Flow<Boolean> =
            dataStore.data.map { it[KEY_AUTO_SYNC_CHARGING_ONLY] ?: DEFAULT_AUTO_SYNC_CHARGING_ONLY }

        suspend fun setAutoSyncChargingOnly(enabled: Boolean) {
            dataStore.edit { it[KEY_AUTO_SYNC_CHARGING_ONLY] = enabled }
        }

        val autoSyncBatteryThresholdPercent: Flow<Int> =
            dataStore.data.map {
                (it[KEY_AUTO_SYNC_BATTERY_THRESHOLD_PERCENT] ?: DEFAULT_AUTO_SYNC_BATTERY_THRESHOLD_PERCENT)
                    .coerceIn(0, 100)
            }

        suspend fun setAutoSyncBatteryThresholdPercent(value: Int) {
            dataStore.edit { it[KEY_AUTO_SYNC_BATTERY_THRESHOLD_PERCENT] = value.coerceIn(0, 100) }
        }

        val internetConnectionScope: Flow<InternetConnectionScope> =
            dataStore.data.map { prefs ->
                prefs[KEY_INTERNET_CONNECTION_SCOPE]
                    ?.let { runCatching { InternetConnectionScope.valueOf(it) }.getOrNull() }
                    ?: DEFAULT_INTERNET_CONNECTION_SCOPE
            }

        suspend fun setInternetConnectionScope(scope: InternetConnectionScope) {
            dataStore.edit { it[KEY_INTERNET_CONNECTION_SCOPE] = scope.name }
        }

        val syncOnMeteredWifi: Flow<Boolean> =
            dataStore.data.map { it[KEY_SYNC_ON_METERED_WIFI] ?: DEFAULT_SYNC_ON_METERED_WIFI }

        suspend fun setSyncOnMeteredWifi(enabled: Boolean) {
            dataStore.edit { it[KEY_SYNC_ON_METERED_WIFI] = enabled }
        }

        val allowedWifiNetworks: Flow<Set<String>> =
            dataStore.data.map { it[KEY_ALLOWED_WIFI_NETWORKS] ?: emptySet() }

        suspend fun setAllowedWifiNetworks(networks: Set<String>) {
            dataStore.edit { it[KEY_ALLOWED_WIFI_NETWORKS] = networks }
        }

        val disallowedWifiNetworks: Flow<Set<String>> =
            dataStore.data.map { it[KEY_DISALLOWED_WIFI_NETWORKS] ?: emptySet() }

        suspend fun setDisallowedWifiNetworks(networks: Set<String>) {
            dataStore.edit { it[KEY_DISALLOWED_WIFI_NETWORKS] = networks }
        }

        val syncOnMobileRoaming: Flow<Boolean> =
            dataStore.data.map { it[KEY_SYNC_ON_MOBILE_ROAMING] ?: DEFAULT_SYNC_ON_MOBILE_ROAMING }

        suspend fun setSyncOnMobileRoaming(enabled: Boolean) {
            dataStore.edit { it[KEY_SYNC_ON_MOBILE_ROAMING] = enabled }
        }

        val syncOnSlow2g: Flow<Boolean> =
            dataStore.data.map { it[KEY_SYNC_ON_SLOW_2G] ?: DEFAULT_SYNC_ON_SLOW_2G }

        suspend fun setSyncOnSlow2g(enabled: Boolean) {
            dataStore.edit { it[KEY_SYNC_ON_SLOW_2G] = enabled }
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

        /** App language preference (System / English). */
        val appLanguage: Flow<AppLanguagePreference> =
            dataStore.data.map { prefs ->
                prefs[KEY_APP_LANGUAGE]
                    ?.let { runCatching { AppLanguagePreference.valueOf(it) }.getOrNull() }
                    ?: DEFAULT_APP_LANGUAGE
            }

        suspend fun setAppLanguage(language: AppLanguagePreference) {
            dataStore.edit { it[KEY_APP_LANGUAGE] = language.name }
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
        // Security
        // -------------------------------------------------------------------------

        val pinProtectionEnabled: Flow<Boolean> =
            dataStore.data.map { it[KEY_PIN_PROTECTION_ENABLED] ?: DEFAULT_PIN_PROTECTION_ENABLED }

        suspend fun setPinProtectionEnabled(enabled: Boolean) {
            dataStore.edit { it[KEY_PIN_PROTECTION_ENABLED] = enabled }
        }

        val pinTimeoutMinutes: Flow<Int> =
            dataStore.data.map {
                (it[KEY_PIN_TIMEOUT_MINUTES] ?: DEFAULT_PIN_TIMEOUT_MINUTES)
                    .coerceIn(MIN_PIN_TIMEOUT_MINUTES, MAX_PIN_TIMEOUT_MINUTES)
            }

        suspend fun setPinTimeoutMinutes(minutes: Int) {
            dataStore.edit {
                it[KEY_PIN_TIMEOUT_MINUTES] =
                    minutes.coerceIn(MIN_PIN_TIMEOUT_MINUTES, MAX_PIN_TIMEOUT_MINUTES)
            }
        }

        val unlockWithBiometrics: Flow<Boolean> =
            dataStore.data.map { it[KEY_UNLOCK_WITH_BIOMETRICS] ?: DEFAULT_UNLOCK_WITH_BIOMETRICS }

        suspend fun setUnlockWithBiometrics(enabled: Boolean) {
            dataStore.edit { it[KEY_UNLOCK_WITH_BIOMETRICS] = enabled }
        }

        val protectSettingsOnly: Flow<Boolean> =
            dataStore.data.map { it[KEY_PROTECT_SETTINGS_ONLY] ?: DEFAULT_PROTECT_SETTINGS_ONLY }

        suspend fun setProtectSettingsOnly(enabled: Boolean) {
            dataStore.edit { it[KEY_PROTECT_SETTINGS_ONLY] = enabled }
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
            internal val KEY_MAX_CONCURRENT_TRANSFERS =
                intPreferencesKey("max_concurrent_transfers")
            internal val KEY_MOBILE_UPLOAD_LIMIT_MB = intPreferencesKey("mobile_upload_limit_mb")
            internal val KEY_MOBILE_DOWNLOAD_LIMIT_MB = intPreferencesKey("mobile_download_limit_mb")
            internal val KEY_WARN_ON_MOBILE_NETWORK_SYNC =
                booleanPreferencesKey("warn_on_mobile_network_sync")
            internal val KEY_RETRY_AUTOMATICALLY_AFTER_ERROR =
                booleanPreferencesKey("retry_automatically_after_error")
            internal val KEY_RETRY_WAIT_MINUTES = intPreferencesKey("retry_wait_minutes")
            internal val KEY_RETRY_MAX_ATTEMPTS = intPreferencesKey("retry_max_attempts")
            internal val KEY_PARALLEL_UPLOADS = intPreferencesKey("parallel_uploads")
            internal val KEY_PARALLEL_DOWNLOADS = intPreferencesKey("parallel_downloads")
            internal val KEY_AUTO_SYNC_SCHEDULE = stringPreferencesKey("auto_sync_schedule")
            internal val KEY_AUTO_SYNC_CHARGING_ONLY = booleanPreferencesKey("auto_sync_charging_only")
            internal val KEY_AUTO_SYNC_BATTERY_THRESHOLD_PERCENT =
                intPreferencesKey("auto_sync_battery_threshold_percent")
            internal val KEY_INTERNET_CONNECTION_SCOPE =
                stringPreferencesKey("internet_connection_scope")
            internal val KEY_SYNC_ON_METERED_WIFI = booleanPreferencesKey("sync_on_metered_wifi")
            internal val KEY_ALLOWED_WIFI_NETWORKS = stringSetPreferencesKey("allowed_wifi_networks")
            internal val KEY_DISALLOWED_WIFI_NETWORKS =
                stringSetPreferencesKey("disallowed_wifi_networks")
            internal val KEY_SYNC_ON_MOBILE_ROAMING = booleanPreferencesKey("sync_on_mobile_roaming")
            internal val KEY_SYNC_ON_SLOW_2G = booleanPreferencesKey("sync_on_slow_2g")

            internal val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
            internal val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
            internal val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
            internal val KEY_RESPECT_FONT_SCALE = booleanPreferencesKey("respect_font_scale")

            internal val KEY_NOTIFY_ON_SUCCESS = booleanPreferencesKey("notify_on_success")
            internal val KEY_NOTIFY_ON_FAILURE = booleanPreferencesKey("notify_on_failure")
            internal val KEY_ENABLE_HAPTICS = booleanPreferencesKey("enable_haptics")
            internal val KEY_PIN_PROTECTION_ENABLED = booleanPreferencesKey("pin_protection_enabled")
            internal val KEY_PIN_TIMEOUT_MINUTES = intPreferencesKey("pin_timeout_minutes")
            internal val KEY_UNLOCK_WITH_BIOMETRICS = booleanPreferencesKey("unlock_with_biometrics")
            internal val KEY_PROTECT_SETTINGS_ONLY = booleanPreferencesKey("protect_settings_only")

            internal val KEY_LOG_RETENTION_DAYS = intPreferencesKey("log_retention_days")

            internal val KEY_ONBOARDING_COMPLETED_AT_MS = longPreferencesKey("onboarding_completed_at_ms")
            internal val KEY_SEEN_TOOLTIPS = stringSetPreferencesKey("seen_tooltips")

            internal const val DEFAULT_GLOBAL_AUTO_SYNC = true
            internal const val DEFAULT_WIFI_ONLY = true
            internal const val DEFAULT_CHARGING_ONLY = false
            internal val DEFAULT_CONFLICT_POLICY = ConflictPolicy.NEWEST_WINS
            internal const val DEFAULT_MAX_CONCURRENT_TRANSFERS = 3
            internal const val DEFAULT_MOBILE_UPLOAD_LIMIT_MB = 100
            internal const val DEFAULT_MOBILE_DOWNLOAD_LIMIT_MB = 100
            internal const val DEFAULT_WARN_ON_MOBILE_NETWORK_SYNC = true
            internal const val DEFAULT_RETRY_AUTOMATICALLY_AFTER_ERROR = true
            internal const val DEFAULT_RETRY_WAIT_MINUTES = 15
            internal const val DEFAULT_RETRY_MAX_ATTEMPTS = 3
            internal const val DEFAULT_PARALLEL_UPLOADS = 3
            internal const val DEFAULT_PARALLEL_DOWNLOADS = 3
            internal val DEFAULT_AUTO_SYNC_SCHEDULE = AutoSyncSchedule.EVERY_30_MINUTES
            internal const val DEFAULT_AUTO_SYNC_CHARGING_ONLY = false
            internal const val DEFAULT_AUTO_SYNC_BATTERY_THRESHOLD_PERCENT = 20
            internal val DEFAULT_INTERNET_CONNECTION_SCOPE = InternetConnectionScope.WIFI_AND_MOBILE
            internal const val DEFAULT_SYNC_ON_METERED_WIFI = false
            internal const val DEFAULT_SYNC_ON_MOBILE_ROAMING = false
            internal const val DEFAULT_SYNC_ON_SLOW_2G = false

            /** Public max cap used by both clamping logic and the settings slider upper bound. */
            const val MAX_CONCURRENT_TRANSFERS = 3
            const val MAX_MOBILE_TRANSFER_LIMIT_MB = 2_048
            const val MIN_RETRY_WAIT_MINUTES = 1
            const val MAX_RETRY_WAIT_MINUTES = 120
            const val MIN_RETRY_ATTEMPTS = 1
            const val MAX_RETRY_ATTEMPTS = 10
            const val MAX_PARALLEL_TRANSFERS_PER_DIRECTION = 5

            internal val DEFAULT_DARK_MODE = DarkModePreference.SYSTEM
            internal val DEFAULT_APP_LANGUAGE = AppLanguagePreference.SYSTEM
            internal const val DEFAULT_DYNAMIC_COLOR = false
            internal const val DEFAULT_RESPECT_FONT_SCALE = true

            internal const val DEFAULT_NOTIFY_ON_SUCCESS = false
            internal const val DEFAULT_NOTIFY_ON_FAILURE = true
            internal const val DEFAULT_ENABLE_HAPTICS = true
            internal const val DEFAULT_PIN_PROTECTION_ENABLED = false
            internal const val DEFAULT_PIN_TIMEOUT_MINUTES = 2
            internal const val DEFAULT_UNLOCK_WITH_BIOMETRICS = false
            internal const val DEFAULT_PROTECT_SETTINGS_ONLY = false

            internal const val DEFAULT_LOG_RETENTION_DAYS = 30
            internal const val MIN_PIN_TIMEOUT_MINUTES = 1
            internal const val MAX_PIN_TIMEOUT_MINUTES = 15
        }
    }

enum class AutoSyncSchedule {
    EVERY_15_MINUTES,
    EVERY_30_MINUTES,
    HOURLY,
    DAILY,
}

enum class InternetConnectionScope {
    WIFI_AND_MOBILE,
    WIFI_ONLY,
    MOBILE_ONLY,
}
