package com.konarsubhojit.synckro.ui.screens.paireditor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konarsubhojit.synckro.R
import com.konarsubhojit.synckro.data.repository.SyncPairRepository
import com.konarsubhojit.synckro.data.worker.SyncScheduler
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.SyncDirection
import com.konarsubhojit.synckro.domain.model.SyncPair
import com.konarsubhojit.synckro.util.StringProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ViewModel for [PairEditorScreen]. Supports both create (pairId == 0) and edit
 * (pairId > 0) modes. The local folder URI result from [PickLocalFolderScreen] is
 * received through the navigation back-stack's [SavedStateHandle] by the key
 * [KEY_LOCAL_TREE_URI].
 *
 * [strings] is injected as a [StringProvider] rather than [android.content.Context]
 * so the ViewModel stays decoupled from Android framework types and is straightforward
 * to test without Robolectric.
 */
@HiltViewModel
class PairEditorViewModel @Inject constructor(
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
        val provider: CloudProviderType = CloudProviderType.FAKE,
        val remoteFolderId: String = "",
        val conflictPolicy: ConflictPolicy = ConflictPolicy.NEWEST_WINS,
        val direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        val wifiOnly: Boolean = true,
        val requiresCharging: Boolean = false,
        val scheduleIntervalMinutes: Long = 60L,
        /** Newline-separated glob patterns. */
        val includeGlobsText: String = "",
        /** Newline-separated glob patterns. */
        val excludeGlobsText: String = "",
        val isSaving: Boolean = false,
        val saveError: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Receive the folder URI result set by the nav host when PickLocalFolderScreen returns.
        savedStateHandle.getStateFlow<String?>(KEY_LOCAL_TREE_URI, null)
            .filterNotNull()
            .onEach { uri -> _state.update { it.copy(localTreeUri = uri) } }
            .launchIn(viewModelScope)

        if (pairId > 0L) loadExisting(pairId)
    }

    private fun loadExisting(id: Long) {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val entity = syncPairRepository.getById(id)
            if (entity != null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        displayName = entity.displayName,
                        localTreeUri = entity.localTreeUri,
                        provider = entity.provider,
                        remoteFolderId = entity.remoteFolderId,
                        conflictPolicy = entity.conflictPolicy,
                        direction = entity.direction,
                        wifiOnly = entity.wifiOnly,
                        requiresCharging = entity.requiresCharging,
                        scheduleIntervalMinutes = entity.scheduleIntervalMinutes,
                        includeGlobsText = entity.includeGlobs.joinToString("\n"),
                        excludeGlobsText = entity.excludeGlobs.joinToString("\n"),
                    )
                }
            } else {
                Timber.w("PairEditorViewModel: no entity found for id=%d", id)
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value) }
    fun onRemoteFolderIdChange(value: String) = _state.update { it.copy(remoteFolderId = value) }
    fun onProviderChange(value: CloudProviderType) = _state.update { it.copy(provider = value) }
    fun onConflictPolicyChange(value: ConflictPolicy) = _state.update { it.copy(conflictPolicy = value) }
    fun onDirectionChange(value: SyncDirection) = _state.update { it.copy(direction = value) }
    fun onWifiOnlyChange(value: Boolean) = _state.update { it.copy(wifiOnly = value) }
    fun onRequiresChargingChange(value: Boolean) = _state.update { it.copy(requiresCharging = value) }
    fun onScheduleIntervalChange(value: Long) = _state.update { it.copy(scheduleIntervalMinutes = value) }
    fun onIncludeGlobsChange(value: String) = _state.update { it.copy(includeGlobsText = value) }
    fun onExcludeGlobsChange(value: String) = _state.update { it.copy(excludeGlobsText = value) }

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
        _state.update { it.copy(isSaving = true, saveError = null) }
        viewModelScope.launch {
            runCatching {
                val pair = SyncPair(
                    id = pairId,
                    displayName = s.displayName.trim(),
                    localTreeUri = s.localTreeUri,
                    provider = s.provider,
                    remoteFolderId = s.remoteFolderId.trim(),
                    conflictPolicy = s.conflictPolicy,
                    direction = s.direction,
                    wifiOnly = s.wifiOnly,
                    requiresCharging = s.requiresCharging,
                    scheduleIntervalMinutes = s.scheduleIntervalMinutes,
                    includeGlobs = s.includeGlobsText.split('\n').map { it.trim() }.filter { it.isNotBlank() },
                    excludeGlobs = s.excludeGlobsText.split('\n').map { it.trim() }.filter { it.isNotBlank() },
                )
                val savedId = syncPairRepository.upsert(pair)
                syncScheduler.schedulePeriodic(pair.copy(id = savedId), pair.scheduleIntervalMinutes)
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
        /** Key used to pass the chosen folder URI from [PickLocalFolderScreen] back to the editor. */
        const val KEY_LOCAL_TREE_URI = "localTreeUri"
    }
}
