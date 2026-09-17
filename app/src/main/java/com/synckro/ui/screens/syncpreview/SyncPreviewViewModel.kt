package com.synckro.ui.screens.syncpreview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.data.repository.SyncPairRepository
import com.synckro.domain.sync.SyncEngine
import com.synckro.domain.sync.SyncOp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/** The user-facing categories a previewed [SyncOp] can fall into. */
enum class PlanGroupKind {
    UPLOAD,
    DOWNLOAD,
    UPDATE_REMOTE,
    UPDATE_LOCAL,
    DELETE_REMOTE,
    DELETE_LOCAL,
    MOVE,
    CONFLICT,
}

/**
 * ViewModel for the sync dry-run preview screen (issue #359).
 *
 * Calls [SyncEngine.previewOnce] on demand and groups the resulting [SyncOp]s into
 * user-facing buckets. The preview is strictly read-only: it never applies the plan
 * and never mutates `local_index`, the delta token, or the event log, so opening this
 * screen has no effect on the next real sync run.
 *
 * `pairId` is read from [SavedStateHandle] using the [KEY_PAIR_ID] key so the
 * NavHost can pass it via a path arg without an explicit lambda.
 */
@HiltViewModel
class SyncPreviewViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val syncPairRepository: SyncPairRepository,
        private val syncEngine: SyncEngine,
    ) : ViewModel() {
        val pairId: Long = savedStateHandle[KEY_PAIR_ID] ?: 0L

        /** One user-facing group of planned operations, e.g. all uploads. */
        data class PlanGroup(
            val kind: PlanGroupKind,
            val paths: List<String>,
        )

        data class UiState(
            /** True while a preview is being computed. */
            val isLoading: Boolean = false,
            /** True once a preview has completed successfully at least once. */
            val hasPlan: Boolean = false,
            /** Planned operations grouped by kind; empty groups are omitted. */
            val groups: List<PlanGroup> = emptyList(),
            /** Total number of planned operations across all groups. */
            val totalOps: Int = 0,
            /** Non-null when the preview could not be computed. */
            val error: String? = null,
            /** True when [error] is caused by a revoked/expired account token. */
            val needsReauth: Boolean = false,
            /** True when [error] is caused by a lost local-folder permission. */
            val needsReLink: Boolean = false,
            /** True when the pair could not be loaded at all. */
            val pairMissing: Boolean = false,
            /** Display name of the previewed pair, or `null` while unknown. */
            val pairName: String? = null,
        )

        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                val name = runCatching { syncPairRepository.getById(pairId)?.displayName }.getOrNull()
                if (name != null) _state.update { it.copy(pairName = name) }
            }
        }

        /** Computes the plan for the next run of this pair on demand, without applying it. */
        fun refresh() {
            if (_state.value.isLoading) return
            _state.update {
                it.copy(isLoading = true, error = null, needsReauth = false, needsReLink = false, pairMissing = false)
            }
            viewModelScope.launch {
                val pair = runCatching { syncPairRepository.getById(pairId) }.getOrNull()
                if (pair == null) {
                    _state.update { it.copy(isLoading = false, pairMissing = true) }
                    return@launch
                }
                val preview =
                    runCatching { withContext(Dispatchers.IO) { syncEngine.previewOnce(pair) } }
                        .onFailure { Timber.w(it, "SyncPreview: preview failed for pair %d", pairId) }
                        .getOrElse { SyncEngine.PreviewResult.Failure(it.message.orEmpty()) }
                _state.update { current ->
                    when (preview) {
                        is SyncEngine.PreviewResult.Success ->
                            current.copy(
                                isLoading = false,
                                hasPlan = true,
                                groups = groupOps(preview.ops),
                                totalOps = preview.ops.size,
                                error = null,
                                pairName = pair.displayName,
                            )
                        is SyncEngine.PreviewResult.Failure ->
                            current.copy(
                                isLoading = false,
                                error = preview.reason,
                                needsReauth = preview.needsReauth,
                                needsReLink = preview.needsReLink,
                                pairName = pair.displayName,
                            )
                    }
                }
            }
        }

        companion object {
            const val KEY_PAIR_ID = "pairId"

            /**
             * Buckets [ops] by [PlanGroupKind], preserving the differ's ordering inside each
             * bucket and dropping empty buckets so the UI only renders meaningful sections.
             */
            internal fun groupOps(ops: List<SyncOp>): List<PlanGroup> =
                PlanGroupKind.entries
                    .map { kind -> PlanGroup(kind, ops.filter { kindOf(it) == kind }.map { describe(it) }) }
                    .filter { it.paths.isNotEmpty() }

            private fun kindOf(op: SyncOp): PlanGroupKind =
                when (op) {
                    is SyncOp.UploadNew -> PlanGroupKind.UPLOAD
                    is SyncOp.DownloadNew -> PlanGroupKind.DOWNLOAD
                    is SyncOp.UpdateRemote -> PlanGroupKind.UPDATE_REMOTE
                    is SyncOp.UpdateLocal -> PlanGroupKind.UPDATE_LOCAL
                    is SyncOp.DeleteRemote, is SyncOp.DeleteRemoteRetention -> PlanGroupKind.DELETE_REMOTE
                    is SyncOp.DeleteLocal, is SyncOp.DeleteLocalRetention -> PlanGroupKind.DELETE_LOCAL
                    is SyncOp.MoveLocal, is SyncOp.MoveRemote -> PlanGroupKind.MOVE
                    is SyncOp.Conflict -> PlanGroupKind.CONFLICT
                }

            private fun describe(op: SyncOp): String =
                when (op) {
                    is SyncOp.MoveLocal -> "${op.fromRelativePath} → ${op.relativePath}"
                    is SyncOp.MoveRemote -> "${op.fromRelativePath} → ${op.relativePath}"
                    else -> op.relativePath
                }
        }
    }
