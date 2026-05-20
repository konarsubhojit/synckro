package com.synckro.ui.screens.conflictinbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.data.local.dao.FileIndexDao
import com.synckro.data.repository.AccountRepository
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.domain.model.ConflictRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for [ConflictInboxScreen]. Observes unresolved conflicts from
 * [ConflictRepository] and exposes actions for the user to resolve them.
 */
@HiltViewModel
class ConflictInboxViewModel
    @Inject
    constructor(
        private val conflictRepository: ConflictRepository,
        private val fileIndexDao: FileIndexDao,
        private val syncPairRepository: SyncPairRepository,
        private val accountRepository: AccountRepository,
    ) : ViewModel() {
        data class ConflictRow(
            val id: Long,
            val pairId: Long,
            val relativePath: String,
            val localLastModifiedMs: Long,
            val remoteLastModifiedMs: Long,
            val localSizeBytes: Long?,
            val remoteSizeBytes: Long?,
            val detectedAtMs: Long,
            val resolution: String?,
            val remoteAccountEmail: String?,
            val fileType: FileTypeIcon,
            /**
             * SAF document ID of the local copy, non-null only for
             * [FileTypeIcon.IMAGE] conflicts when the file-index entry has been
             * populated by the local scanner.  The Screen combines this with
             * [localTreeUri] to build the full `content://` thumbnail URI.
             */
            val localDocumentId: String? = null,
            /**
             * SAF tree URI of the sync-pair root, used together with
             * [localDocumentId] to build the thumbnail URI in the Screen.
             * Non-null when [localDocumentId] is non-null.
             */
            val localTreeUri: String? = null,
            /**
             * Provider-supplied thumbnail URL for the remote copy (e.g. Google Drive
             * `thumbnailLink` or OneDrive pre-signed download URL). Non-null only when
             * [com.synckro.data.local.entity.FileIndexEntity.remoteThumbnailUrl] is set and
             * [fileType] == [FileTypeIcon.IMAGE].
             */
            val remoteThumbnailUrl: String? = null,
        )

        enum class FileTypeIcon {
            FOLDER,
            IMAGE,
            DOCUMENT,
            GENERIC,
        }

        data class UiState(
            val conflicts: List<ConflictRow> = emptyList(),
            val isLoading: Boolean = true,
        )

        val state: StateFlow<UiState> =
            conflictRepository
                .observeUnresolved()
                .map { UiState(conflicts = projectRows(it), isLoading = false) }
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

        private fun resolve(
            id: Long,
            resolution: String,
        ) {
            Timber.i("ConflictInboxViewModel.resolve(id=$id, resolution=$resolution)")
            viewModelScope.launch {
                runCatching { conflictRepository.resolve(id, resolution) }
                    .onFailure { Timber.w(it, "ConflictInboxViewModel: failed to resolve conflict %d", id) }
            }
        }

        private suspend fun projectRows(conflicts: List<ConflictRecord>): List<ConflictRow> {
            val pairIds = conflicts.map { it.pairId }.distinct()
            val indexByPair =
                pairIds.associateWith { pairId ->
                    fileIndexDao
                        .getForPair(pairId)
                        .associateBy { it.relativePath }
                }
            val pairById = pairIds.associateWith { pairId -> syncPairRepository.getById(pairId) }
            val accountsById = accountRepository.getAll().associateBy { it.id }

            return conflicts.map { conflict ->
                val index = indexByPair[conflict.pairId]?.get(conflict.relativePath)
                val accountId = pairById[conflict.pairId]?.accountId
                val remoteEmail = accountId?.let { id -> accountsById[id]?.email }
                val fileType = fileTypeIconForPath(conflict.relativePath)
                val (localDocumentId, localTreeUri) =
                    if (fileType == FileTypeIcon.IMAGE) {
                        val docId = index?.localDocumentId
                        val treeUri = if (docId != null) pairById[conflict.pairId]?.localTreeUri else null
                        docId to treeUri
                    } else {
                        null to null
                    }
                val remoteThumbnailUrl =
                    if (fileType == FileTypeIcon.IMAGE) index?.remoteThumbnailUrl else null
                ConflictRow(
                    id = conflict.id,
                    pairId = conflict.pairId,
                    relativePath = conflict.relativePath,
                    localLastModifiedMs = index?.localLastModifiedMs ?: conflict.localLastModifiedMs,
                    remoteLastModifiedMs = index?.remoteLastModifiedMs ?: conflict.remoteLastModifiedMs,
                    localSizeBytes = index?.localSize,
                    remoteSizeBytes = index?.remoteSize,
                    detectedAtMs = conflict.detectedAtMs,
                    resolution = conflict.resolution,
                    remoteAccountEmail = remoteEmail,
                    fileType = fileType,
                    localDocumentId = localDocumentId,
                    localTreeUri = localTreeUri,
                    remoteThumbnailUrl = remoteThumbnailUrl,
                )
            }
        }
    }

internal fun fileTypeIconForPath(relativePath: String): ConflictInboxViewModel.FileTypeIcon {
    val trimmedPath = relativePath.trim()
    if (trimmedPath.endsWith("/")) {
        return ConflictInboxViewModel.FileTypeIcon.FOLDER
    }
    val fileNameSegment = trimmedPath.substringAfterLast('/').trim()
    val extension = fileNameSegment.substringAfterLast('.', "").lowercase()
    if (extension in IMAGE_EXTENSIONS) {
        return ConflictInboxViewModel.FileTypeIcon.IMAGE
    }
    if (extension in DOCUMENT_EXTENSIONS) {
        return ConflictInboxViewModel.FileTypeIcon.DOCUMENT
    }
    if (extension.isEmpty() && fileNameSegment.lowercase() in NO_EXTENSION_DOCUMENT_NAMES) {
        return ConflictInboxViewModel.FileTypeIcon.DOCUMENT
    }
    return ConflictInboxViewModel.FileTypeIcon.GENERIC
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val DOCUMENT_EXTENSIONS =
    setOf(
        "txt",
        "md",
        "pdf",
        "doc",
        "docx",
        "xls",
        "xlsx",
        "ppt",
        "pptx",
        "odt",
        "ods",
        "rtf",
        "csv",
    )
private val NO_EXTENSION_DOCUMENT_NAMES = setOf("readme", "license", "changelog", "authors")
