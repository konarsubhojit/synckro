package com.konarsubhojit.synckro.ui.screens.logs

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konarsubhojit.synckro.data.repository.SyncEventRepository
import com.konarsubhojit.synckro.domain.model.SyncEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the [LogsScreen].
 *
 * If a non-zero [pairId] was passed via [SavedStateHandle], the log is filtered
 * to that pair; otherwise all events are shown (global view).
 */
@HiltViewModel
class LogsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val syncEventRepository: SyncEventRepository,
) : ViewModel() {

    /** 0 means "show all pairs". */
    val pairId: Long = savedStateHandle[KEY_PAIR_ID] ?: 0L

    data class UiState(
        val events: List<SyncEvent> = emptyList(),
        val isLoading: Boolean = true,
    )

    val state: StateFlow<UiState> =
        (if (pairId != 0L) syncEventRepository.observeForPair(pairId) else syncEventRepository.observeAll())
            .map { UiState(events = it, isLoading = false) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = UiState(),
            )

    companion object {
        const val KEY_PAIR_ID = "pairId"
    }
}
