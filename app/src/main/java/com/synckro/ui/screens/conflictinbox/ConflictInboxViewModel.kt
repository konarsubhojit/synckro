package com.synckro.ui.screens.conflictinbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.data.repository.ConflictRepository
import com.synckro.domain.model.ConflictRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ViewModel for [ConflictInboxScreen]. Observes unresolved conflicts from
 * [ConflictRepository] and exposes actions for the user to resolve them.
 */
@HiltViewModel
class ConflictInboxViewModel @Inject constructor(
    private val conflictRepository: ConflictRepository,
) : ViewModel() {

    data class UiState(
        val conflicts: List<ConflictRecord> = emptyList(),
        val isLoading: Boolean = true,
    )

    val state: StateFlow<UiState> = conflictRepository.observeUnresolved()
        .map { UiState(conflicts = it, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UiState(),
        )

    /** Records the user's choice to keep the local version for the conflict with [id]. */
    fun keepLocal(id: Long) = resolve(id, ConflictRecord.RESOLUTION_KEEP_LOCAL)

    /** Records the user's choice to keep the remote version for the conflict with [id]. */
    fun keepRemote(id: Long) = resolve(id, ConflictRecord.RESOLUTION_KEEP_REMOTE)

    /** Records the user's choice to keep both versions for the conflict with [id]. */
    fun keepBoth(id: Long) = resolve(id, ConflictRecord.RESOLUTION_KEEP_BOTH)

    /** Dismisses a conflict without applying a resolution (e.g. the user chooses to skip it). */
    fun dismiss(id: Long) {
        Timber.i("ConflictInboxViewModel.dismiss(id=$id)")
        viewModelScope.launch {
            runCatching { conflictRepository.delete(id) }
                .onFailure { Timber.w(it, "ConflictInboxViewModel: failed to dismiss conflict %d", id) }
        }
    }

    private fun resolve(id: Long, resolution: String) {
        Timber.i("ConflictInboxViewModel.resolve(id=$id, resolution=$resolution)")
        viewModelScope.launch {
            runCatching { conflictRepository.resolve(id, resolution) }
                .onFailure { Timber.w(it, "ConflictInboxViewModel: failed to resolve conflict %d", id) }
        }
    }
}
