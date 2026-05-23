package com.synckro.ui.screens.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.BuildConfig
import com.synckro.data.repository.DarkModePreference
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.domain.model.ConflictPolicy
import com.synckro.util.logging.LogExporter
import com.synckro.util.logging.LogVisibilityConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * ViewModel for the Settings screen. Reads and writes all global preferences
 * surfaced by [SettingsRepository] and orchestrates app-wide side-effects
 * (rescheduling sync work, exporting logs, clearing the cache).
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val syncPairRepository: SyncPairRepository,
        private val syncScheduler: SyncScheduler,
        private val logExporter: LogExporter,
    ) : ViewModel() {
        data class UiState(
            // Sync defaults
            val globalAutoSyncEnabled: Boolean = true,
            val defaultWifiOnly: Boolean = true,
            val defaultChargingOnly: Boolean = false,
            val defaultConflictPolicy: ConflictPolicy = ConflictPolicy.NEWEST_WINS,
            val maxConcurrentTransfers: Int = 1,
            // Appearance
            val darkMode: DarkModePreference = DarkModePreference.SYSTEM,
            val dynamicColor: Boolean = false,
            val respectFontScale: Boolean = true,
            // Notifications
            val notifyOnSuccess: Boolean = false,
            val notifyOnFailure: Boolean = true,
            val enableHaptics: Boolean = true,
            // Logs
            val logRetentionDays: Int = 30,
        )

        /**
         * One-shot UI events the screen reacts to (snackbars, share sheets).
         * Replay = 0 + extraBufferCapacity = 1 ensures the most recent event is
         * delivered even if the collector momentarily lags.
         */
        sealed interface UiEvent {
            data class ShareLogs(val uri: android.net.Uri) : UiEvent

            data class ComposeFeedback(val uri: android.net.Uri) : UiEvent

            data class ExportFailed(val message: String) : UiEvent

            data class CacheCleared(val freedBytes: Long) : UiEvent
        }

        private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
        val events: SharedFlow<UiEvent> = _events.asSharedFlow()

        val state: StateFlow<UiState> =
            combine(
                combine(
                    settingsRepository.globalAutoSyncEnabled,
                    settingsRepository.defaultWifiOnly,
                    settingsRepository.defaultChargingOnly,
                    settingsRepository.defaultConflictPolicy,
                ) { auto, wifi, charging, conflict -> arrayOf(auto, wifi, charging, conflict) },
                combine(
                    settingsRepository.darkMode,
                    settingsRepository.dynamicColor,
                    settingsRepository.respectFontScale,
                ) { dark, dynamic, font -> arrayOf(dark, dynamic, font) },
                combine(
                    settingsRepository.notifyOnSuccess,
                    settingsRepository.notifyOnFailure,
                    settingsRepository.enableHaptics,
                    settingsRepository.logRetentionDays,
                ) { success, failure, haptics, retention -> arrayOf(success, failure, haptics, retention) },
            ) { syncBundle, appearanceBundle, miscBundle ->
                @Suppress("UNCHECKED_CAST")
                UiState(
                    globalAutoSyncEnabled = syncBundle[0] as Boolean,
                    defaultWifiOnly = syncBundle[1] as Boolean,
                    defaultChargingOnly = syncBundle[2] as Boolean,
                    defaultConflictPolicy = syncBundle[3] as ConflictPolicy,
                    darkMode = appearanceBundle[0] as DarkModePreference,
                    dynamicColor = appearanceBundle[1] as Boolean,
                    respectFontScale = appearanceBundle[2] as Boolean,
                    notifyOnSuccess = miscBundle[0] as Boolean,
                    notifyOnFailure = miscBundle[1] as Boolean,
                    enableHaptics = miscBundle[2] as Boolean,
                    logRetentionDays = miscBundle[3] as Int,
                )
            }.combine(settingsRepository.maxConcurrentTransfers) { uiState, maxConcurrent ->
                uiState.copy(maxConcurrentTransfers = maxConcurrent)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = UiState(),
            )

        // ---------------------------------------------------------------------
        // Sync defaults
        // ---------------------------------------------------------------------

        /**
         * Updates the global auto-sync preference and immediately reschedules (or
         * cancels) periodic sync work for every pair accordingly.
         */
        fun setGlobalAutoSync(enabled: Boolean) {
            Timber.i("SettingsViewModel.setGlobalAutoSync(enabled=$enabled)")
            viewModelScope.launch {
                settingsRepository.setGlobalAutoSync(enabled)
                val pairs = syncPairRepository.observeAll(context.contentResolver).first()
                syncScheduler.scheduleOrCancelAll(pairs, enabled)
            }
        }

        fun setDefaultWifiOnly(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setDefaultWifiOnly(enabled) }
        }

        fun setDefaultChargingOnly(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setDefaultChargingOnly(enabled) }
        }

        fun setDefaultConflictPolicy(policy: ConflictPolicy) {
            viewModelScope.launch { settingsRepository.setDefaultConflictPolicy(policy) }
        }

        fun setMaxConcurrentTransfers(n: Int) {
            viewModelScope.launch { settingsRepository.setMaxConcurrentTransfers(n) }
        }

        // ---------------------------------------------------------------------
        // Appearance
        // ---------------------------------------------------------------------

        fun setDarkMode(mode: DarkModePreference) {
            viewModelScope.launch { settingsRepository.setDarkMode(mode) }
        }

        fun setDynamicColor(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
        }

        fun setRespectFontScale(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setRespectFontScale(enabled) }
        }

        // ---------------------------------------------------------------------
        // Notifications
        // ---------------------------------------------------------------------

        fun setNotifyOnSuccess(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setNotifyOnSuccess(enabled) }
        }

        fun setNotifyOnFailure(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setNotifyOnFailure(enabled) }
        }

        fun setEnableHaptics(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setEnableHaptics(enabled) }
        }

        // ---------------------------------------------------------------------
        // Logs & storage
        // ---------------------------------------------------------------------

        fun setLogRetentionDays(days: Int) {
            viewModelScope.launch { settingsRepository.setLogRetentionDays(days) }
        }

        /**
         * Builds a zip containing all logs via [LogExporter] and emits a
         * [UiEvent.ShareLogs] event so the screen can launch the system share
         * sheet. Errors are surfaced via [UiEvent.ExportFailed].
         */
        fun exportLogs() {
            viewModelScope.launch {
                runCatching { logExporter.export() }
                    .onSuccess { uri -> _events.emit(UiEvent.ShareLogs(uri)) }
                    .onFailure { t ->
                        Timber.e(t, "SettingsViewModel.exportLogs failed")
                        _events.emit(UiEvent.ExportFailed(t.message ?: "Unknown error"))
                    }
            }
        }

        fun sendFeedback() {
            viewModelScope.launch {
                runCatching { logExporter.export(LogVisibilityConfig.currentExportConfig()) }
                    .onSuccess { uri -> _events.emit(UiEvent.ComposeFeedback(uri)) }
                    .onFailure { t ->
                        Timber.e(t, "SettingsViewModel.sendFeedback failed")
                        _events.emit(UiEvent.ExportFailed(t.message ?: "Unknown error"))
                    }
            }
        }

        /**
         * Clears the app's cache directory (excluding the directory itself).
         * Emits [UiEvent.CacheCleared] on completion with the number of bytes
         * freed. Safe to call repeatedly.
         */
        fun clearCache() {
            viewModelScope.launch {
                val freed =
                    withContext(Dispatchers.IO) {
                        var total = 0L
                        context.cacheDir.listFiles()?.forEach { child ->
                            total += child.sizeRecursive()
                            child.deleteRecursively()
                        }
                        total
                    }
                _events.emit(UiEvent.CacheCleared(freed))
            }
        }

        // ---------------------------------------------------------------------
        // About
        // ---------------------------------------------------------------------

        /** App version name (e.g. `"0.1.0-debug"`) sourced from BuildConfig. */
        val versionName: String get() = BuildConfig.VERSION_NAME

        /** App version code (e.g. `1`) sourced from BuildConfig. */
        val versionCode: Int get() = BuildConfig.VERSION_CODE

        val feedbackEmailConfig: FeedbackEmailConfig = resolveFeedbackEmail(BuildConfig.FEEDBACK_EMAIL)

        /**
         * Public URL of the Synckro privacy policy. Hard-coded for now; if this
         * ever needs to vary per build flavor it should move to BuildConfig.
         */
        val privacyPolicyUrl: String = "https://github.com/konarsubhojit/synckro/blob/main/PRIVACY.md"

        fun resetHints() {
            viewModelScope.launch { settingsRepository.resetSeenTooltips() }
        }

        /**
         * Reads the bundled OSS licenses file produced by the Gradle
         * `generateOssLicenses` task. Returns `null` when the asset is missing
         * (e.g. running unit tests where the asset isn't packaged) so the
         * caller can show an "unavailable" placeholder.
         */
        suspend fun loadOssLicensesText(): String? =
            withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open(OSS_LICENSES_ASSET).bufferedReader().use { it.readText() }
                }.getOrNull()
            }

        /**
         * Builds an [Intent] to open the per-app notification channel settings.
         * Returns `null` on pre-Oreo where channels don't exist (the app's
         * `minSdk` is 26 so in practice this is always non-null).
         */
        fun buildChannelSettingsIntent(): Intent =
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        private fun File.sizeRecursive(): Long =
            if (isDirectory) (listFiles()?.sumOf { it.sizeRecursive() } ?: 0L) else length()

        companion object {
            /** Filename produced by the `generateOssLicenses` Gradle task. */
            const val OSS_LICENSES_ASSET = "oss_licenses.txt"
            private const val FEEDBACK_EMAIL_PLACEHOLDER = "feedback@example.com"

            internal fun resolveFeedbackEmail(rawEmail: String): FeedbackEmailConfig {
                val normalized = rawEmail.trim()
                if (normalized.matches(FEEDBACK_EMAIL_REGEX)) {
                    return FeedbackEmailConfig(address = normalized, isConfigured = true)
                }
                return FeedbackEmailConfig(
                    address = FEEDBACK_EMAIL_PLACEHOLDER,
                    isConfigured = false,
                )
            }

            private val FEEDBACK_EMAIL_REGEX =
                Regex(
                    "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$",
                    RegexOption.IGNORE_CASE,
                )
        }

        data class FeedbackEmailConfig(
            val address: String,
            val isConfigured: Boolean,
        )
    }
