package com.synckro.ui.screens.paireditor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.R
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import com.synckro.util.StringProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Friendly schedule preset options shown in the pair editor.
 * [CUSTOM] falls back to the user's manually entered interval.
 */
enum class SyncSchedulePreset(
    /** Interval in minutes for this preset (0 for CUSTOM – interval is user-supplied). */
    val minutes: Long,
) {
    FIFTEEN_MINUTES(15L),
    THIRTY_MINUTES(30L),
    HOURLY(60L),
    DAILY(1440L),
    CUSTOM(0L),
    ;

    companion object {
        /** Maps a stored interval to the matching named preset, or [CUSTOM] if none matches exactly. */
        fun fromMinutes(minutes: Long): SyncSchedulePreset =
            entries.firstOrNull { it != CUSTOM && it.minutes == minutes } ?: CUSTOM
    }
}

/**
 * ViewModel for [PairEditorScreen]. Supports both create (pairId == 0) and edit
 * (pairId > 0) modes. The local folder URI result from [PickLocalFolderScreen] is
 * delivered by the navigation layer (which observes the back-stack entry's own
 * [SavedStateHandle]) via [onLocalFolderPicked]. The Hilt-injected [SavedStateHandle]
 * here is a distinct bucket from [androidx.navigation.NavBackStackEntry.savedStateHandle],
 * so we cannot rely on it to receive nav results — the navigation layer must forward
 * them explicitly.
 *
 * [strings] is injected as a [StringProvider] rather than [android.content.Context]
 * so the ViewModel stays decoupled from Android framework types and is straightforward
 * to test without Robolectric.
 */
@HiltViewModel
class PairEditorViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val strings: StringProvider,
        private val syncPairRepository: SyncPairRepository,
        private val syncScheduler: SyncScheduler,
    ) : ViewModel() {
        private val pairId: Long = savedStateHandle.get<Long>("pairId") ?: 0L

        data class UiState(
            val isLoading: Boolean = false,
            val displayName: String = "",
            val localTreeUri: String = "",
            val provider: CloudProviderType = CloudProviderType.GOOGLE_DRIVE,
            val remoteFolderId: String = "",
            /** Human-readable display name for [remoteFolderId], set when the user browses
             *  and picks a folder via [PickRemoteFolderScreen]. Empty when the ID was loaded
             *  from the database (name is not persisted) or typed manually. */
            val remoteFolderName: String = "",
            val conflictPolicy: ConflictPolicy = ConflictPolicy.NEWEST_WINS,
            val direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
            val wifiOnly: Boolean = true,
            val requiresCharging: Boolean = false,
            /** Whether periodic auto-sync is enabled for this pair. */
            val autoSyncEnabled: Boolean = true,
            /** Currently selected schedule preset. */
            val schedulePreset: SyncSchedulePreset = SyncSchedulePreset.HOURLY,
            /** Raw text for the custom interval field; only used when [schedulePreset] is [SyncSchedulePreset.CUSTOM]. */
            val customIntervalText: String = "60",
            /** Newline-separated glob patterns. */
            val includeGlobsText: String = "",
            /** Newline-separated glob patterns. */
            val excludeGlobsText: String = "",
            /**
             * Text representation of the retention period in days. Only meaningful
             * when [direction] is [SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS]
             * or [SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS].
             * Empty string means no automatic deletion (null retention).
             */
            val retentionDaysText: String = "",
            val isSaving: Boolean = false,
            val saveError: String? = null,
        ) {
            /** Parses [customIntervalText] as a non-negative Long, or 0 if the text is blank/invalid. */
            private val parsedCustomInterval: Long
                get() = customIntervalText.trim().toLongOrNull() ?: 0L

            /** Effective sync interval in minutes derived from the active preset or custom value.
             *  Custom text that is blank or non-numeric is treated as 0 and clamped here to the
             *  15-minute WorkManager floor. */
            val scheduleIntervalMinutes: Long
                get() {
                    if (schedulePreset != SyncSchedulePreset.CUSTOM) return schedulePreset.minutes
                    return parsedCustomInterval.coerceAtLeast(15L)
                }

            /** True when the custom interval text is non-empty but does not parse as a valid long ≥ 15. */
            val customIntervalError: Boolean
                get() =
                    schedulePreset == SyncSchedulePreset.CUSTOM &&
                        customIntervalText.isNotEmpty() &&
                        parsedCustomInterval < 15L
        }

        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state.asStateFlow()

        /**
         * Tracks whether the user has freshly picked a folder during this VM's lifetime.
         * Used to prevent a slow [loadExisting] coroutine from clobbering a newly chosen
         * URI that arrived via [onLocalFolderPicked] before the DB load completed.
         */
        private var userPickedFolder: Boolean = false

        init {
            // Re-apply any URI restored from process death (the Hilt-injected SavedStateHandle
            // can survive process recreation even though it does NOT receive nav-result writes).
            savedStateHandle
                .get<String?>(KEY_LOCAL_TREE_URI)
                ?.takeIf { it.isNotBlank() }
                ?.let { restored ->
                    userPickedFolder = true
                    _state.update { it.copy(localTreeUri = restored) }
                }

            // Restore the remote folder pick result across process death in the same way.
            val restoredRemoteId = savedStateHandle.get<String?>(KEY_REMOTE_FOLDER_ID)?.takeIf { it.isNotEmpty() }
            val restoredRemoteName = savedStateHandle.get<String?>(KEY_REMOTE_FOLDER_NAME).orEmpty()
            if (restoredRemoteId != null) {
                _state.update { it.copy(remoteFolderId = restoredRemoteId, remoteFolderName = restoredRemoteName) }
            }

            if (pairId > 0L) loadExisting(pairId)
        }

        private fun loadExisting(id: Long) {
            _state.update { it.copy(isLoading = true) }
            viewModelScope.launch {
                val entity = syncPairRepository.getById(id)
                if (entity != null) {
                    val preset = SyncSchedulePreset.fromMinutes(entity.scheduleIntervalMinutes)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            displayName = entity.displayName,
                            // If the user already picked a folder before this coroutine
                            // finished, keep their choice instead of clobbering it with
                            // the persisted value.
                            localTreeUri = if (userPickedFolder) it.localTreeUri else entity.localTreeUri,
                            provider = entity.provider,
                            remoteFolderId = entity.remoteFolderId,
                            conflictPolicy = entity.conflictPolicy,
                            direction = entity.direction,
                            wifiOnly = entity.wifiOnly,
                            requiresCharging = entity.requiresCharging,
                            autoSyncEnabled = entity.autoSyncEnabled,
                            schedulePreset = preset,
                            customIntervalText = entity.scheduleIntervalMinutes.toString(),
                            includeGlobsText = entity.includeGlobs.joinToString("\n"),
                            excludeGlobsText = entity.excludeGlobs.joinToString("\n"),
                            retentionDaysText = entity.retentionDays?.toString() ?: "",
                        )
                    }
                } else {
                    Timber.w("PairEditorViewModel: no entity found for id=%d", id)
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }

        /**
         * Called by the navigation layer when [PickLocalFolderScreen] has returned a
         * confirmed folder URI. The navigation host observes the back-stack entry's
         * own [SavedStateHandle] (which is a different instance from the Hilt-injected
         * [savedStateHandle] here) and forwards the result through this method.
         */
        fun onLocalFolderPicked(uri: String) {
            if (uri.isBlank()) return
            userPickedFolder = true
            // Persist into the Hilt-injected SavedStateHandle too so the URI survives
            // process death / configuration changes for this ViewModel.
            savedStateHandle[KEY_LOCAL_TREE_URI] = uri
            _state.update { it.copy(localTreeUri = uri) }
        }

        fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value) }

        /**
         * Called by the navigation layer when [PickRemoteFolderScreen] has returned a confirmed
         * cloud folder. Both the folder ID and human-readable name are stored so the editor can
         * display the name while still persisting the opaque provider ID.
         */
        fun onRemoteFolderPicked(id: String, name: String) {
            savedStateHandle[KEY_REMOTE_FOLDER_ID] = id
            savedStateHandle[KEY_REMOTE_FOLDER_NAME] = name
            _state.update { it.copy(remoteFolderId = id, remoteFolderName = name) }
        }

        fun onProviderChange(value: CloudProviderType) =
            _state.update {
                // Clear the remote folder selection when the provider changes because folder IDs
                // are provider-specific and the previously chosen folder no longer applies.
                it.copy(provider = value, remoteFolderId = "", remoteFolderName = "")
            }

        fun onConflictPolicyChange(value: ConflictPolicy) = _state.update { it.copy(conflictPolicy = value) }

        fun onDirectionChange(value: SyncDirection) = _state.update { it.copy(direction = value) }

        fun onWifiOnlyChange(value: Boolean) = _state.update { it.copy(wifiOnly = value) }

        fun onRequiresChargingChange(value: Boolean) = _state.update { it.copy(requiresCharging = value) }

        fun onAutoSyncEnabledChange(value: Boolean) = _state.update { it.copy(autoSyncEnabled = value) }

        fun onSchedulePresetChange(value: SyncSchedulePreset) = _state.update { it.copy(schedulePreset = value) }

        fun onCustomIntervalChange(value: String) = _state.update { it.copy(customIntervalText = value) }

        fun onIncludeGlobsChange(value: String) = _state.update { it.copy(includeGlobsText = value) }

        fun onExcludeGlobsChange(value: String) = _state.update { it.copy(excludeGlobsText = value) }

        fun onRetentionDaysChange(value: String) = _state.update { it.copy(retentionDaysText = value.filter { ch -> ch.isDigit() }) }

        /**
         * Validates and persists the current form state. Calls [onSaved] with the
         * persisted row ID on success, or sets [UiState.saveError] on failure.
         */
        fun save(onSaved: (Long) -> Unit) {
            val s = _state.value
            if (s.displayName.isBlank()) {
                _state.update { it.copy(saveError = strings.getString(R.string.pair_editor_error_name_required)) }
                return
            }
            if (s.localTreeUri.isBlank()) {
                _state.update { it.copy(saveError = strings.getString(R.string.pair_editor_error_folder_required)) }
                return
            }
            val retentionDaysText = s.retentionDaysText.trim()
            val retentionDays = retentionDaysText.takeIf { it.isNotBlank() }?.toIntOrNull()
            if (retentionDaysText.isNotBlank() && (retentionDays == null || retentionDays !in 0..MAX_RETENTION_DAYS)) {
                _state.update { it.copy(saveError = strings.getString(R.string.pair_editor_retention_days_error)) }
                return
            }
            _state.update { it.copy(isSaving = true, saveError = null) }
            viewModelScope.launch {
                runCatching {
                    val pair =
                        SyncPair(
                            id = pairId,
                            displayName = s.displayName.trim(),
                            localTreeUri = s.localTreeUri,
                            provider = s.provider,
                            remoteFolderId = s.remoteFolderId.trim(),
                            conflictPolicy = s.conflictPolicy,
                            direction = s.direction,
                            wifiOnly = s.wifiOnly,
                            requiresCharging = s.requiresCharging,
                            autoSyncEnabled = s.autoSyncEnabled,
                            // Enforce WorkManager's 15-minute floor here so the persisted value
                            // always matches what the scheduler will actually use.
                            scheduleIntervalMinutes = s.scheduleIntervalMinutes,
                            includeGlobs =
                                s.includeGlobsText
                                    .split('\n')
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() },
                            excludeGlobs =
                                s.excludeGlobsText
                                    .split('\n')
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() },
                            retentionDays = retentionDays,
                        )
                    val savedId = syncPairRepository.upsert(pair)
                    // Schedule or cancel depending on autoSyncEnabled.
                    syncScheduler.scheduleOrCancel(pair.copy(id = savedId))
                    savedId
                }.onSuccess { savedId ->
                    _state.update { it.copy(isSaving = false) }
                    Timber.i("PairEditorViewModel.save: saved pair id=$savedId")
                    onSaved(savedId)
                }.onFailure { t ->
                    Timber.e(t, "PairEditorViewModel.save: failed")
                    _state.update {
                        it.copy(
                            isSaving = false,
                            saveError = t.message ?: strings.getString(R.string.pair_editor_error_save_failed),
                        )
                    }
                }
            }
        }

        fun clearSaveError() = _state.update { it.copy(saveError = null) }

        companion object {
            /**
             * Key used by the navigation layer to deliver the chosen folder URI from
             * [PickLocalFolderScreen] back to the editor via the back-stack entry's
             * own [SavedStateHandle]. The navigation host observes this key and forwards
             * the value to the ViewModel through [onLocalFolderPicked]; it is also used
             * by this ViewModel to persist the picked URI across process death.
             */
            const val KEY_LOCAL_TREE_URI = "localTreeUri"

            /**
             * Key used to pass the current folder URI from [PairEditorScreen] to
             * [PickLocalFolderScreen] so the picker can pre-populate the current selection.
             */
            const val KEY_PICK_FOLDER_INITIAL_URI = "pickFolderInitialUri"

            /**
             * Key used by the navigation layer to deliver the chosen cloud folder ID from
             * [PickRemoteFolderScreen] back to the editor via the back-stack entry's
             * [SavedStateHandle]. Also used to persist the value across process death.
             */
            const val KEY_REMOTE_FOLDER_ID = "remotePickedFolderId"

            /**
             * Key used to deliver the human-readable name of the chosen cloud folder alongside
             * [KEY_REMOTE_FOLDER_ID].
             */
            const val KEY_REMOTE_FOLDER_NAME = "remotePickedFolderName"

            /** Maximum retention period accepted by the pair editor. */
            const val MAX_RETENTION_DAYS = 36500
        }
    }
