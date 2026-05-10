package com.synckro.ui.screens.logs

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.util.logging.LogExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the [LogsScreen].
 *
 * If a non-zero [pairId] was passed via [SavedStateHandle], the log is filtered
 * to that pair; otherwise all events are shown (global view).
 *
 * [levelFilter] and [tagFilter] narrow the displayed list without hitting the
 * database — all filtering is done in memory on the already-observed list.
 */
@HiltViewModel
class LogsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val syncEventRepository: SyncEventRepository,
        private val logExporter: LogExporter,
    ) : ViewModel() {
        /** 0 means "show all pairs". */
        val pairId: Long = savedStateHandle[KEY_PAIR_ID] ?: 0L

        data class UiState(
            val events: List<SyncEvent> = emptyList(),
            val isLoading: Boolean = true,
            /** Active level filter; null means show all levels. */
            val levelFilter: SyncEventLevel? = null,
            /** Active tag filter; null means show all tags. */
            val tagFilter: String? = null,
        )

        private val _levelFilter = MutableStateFlow<SyncEventLevel?>(null)
        private val _tagFilter = MutableStateFlow<String?>(null)

        val state: StateFlow<UiState> =
            combine(
                if (pairId != 0L) syncEventRepository.observeForPair(pairId) else syncEventRepository.observeAll(),
                _levelFilter,
                _tagFilter,
            ) { events, levelFilter, tagFilter ->
                val filtered =
                    events.filter { e ->
                        (levelFilter == null || e.level == levelFilter) &&
                            (tagFilter == null || e.tag == tagFilter)
                    }
                UiState(events = filtered, isLoading = false, levelFilter = levelFilter, tagFilter = tagFilter)
            }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = UiState(),
                )

        /** One-shot export result: [Result.success] with the cache [Uri] on success,
         *  [Result.failure] on error. */
        private val _exportResult = MutableSharedFlow<Result<Uri>>()
        val exportResult: SharedFlow<Result<Uri>> = _exportResult

        /**
         * Kicks off a bundled log export (structured events + Timber files) and emits
         * the result via [exportResult].
         *
         * Writes a structured [SyncEventTag.Export] entry before and after the export
         * so the action itself is visible in the logs screen.
         */
        fun exportLogs() {
            viewModelScope.launch {
                syncEventRepository.log(
                    pairId = null,
                    level = SyncEventLevel.INFO,
                    tag = SyncEventTag.Export,
                    message = "Export started",
                )
                val result = runCatching { logExporter.export() }
                result.fold(
                    onSuccess = {
                        syncEventRepository.log(
                            pairId = null,
                            level = SyncEventLevel.INFO,
                            tag = SyncEventTag.Export,
                            message = "Export succeeded",
                        )
                    },
                    onFailure = { e ->
                        syncEventRepository.log(
                            pairId = null,
                            level = SyncEventLevel.ERROR,
                            tag = SyncEventTag.Export,
                            message = "Export failed: ${e.message}",
                        )
                    },
                )
                _exportResult.emit(result)
            }
        }

        /** Sets (or clears, when [level] is null) the active level filter. */
        fun setLevelFilter(level: SyncEventLevel?) {
            _levelFilter.value = level
        }

        /** Sets (or clears, when [tag] is null) the active tag filter. */
        fun setTagFilter(tag: String?) {
            _tagFilter.value = tag
        }

        companion object {
            const val KEY_PAIR_ID = "pairId"
        }
    }
