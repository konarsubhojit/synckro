package com.synckro.data.watcher

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.synckro.data.local.fs.DefaultSafDocumentMetadataQuery
import com.synckro.data.local.fs.DefaultSafReadProbe
import com.synckro.data.local.fs.LocalFsEnumerator
import com.synckro.data.local.fs.LocalPathScope
import com.synckro.data.local.fs.MediaStorePendingStateQuery
import com.synckro.data.local.fs.TargetedSafMetadataSample
import com.synckro.data.local.fs.TargetedSafMetadataSampler
import com.synckro.data.repository.PendingUploadRepository
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.scanner.DefaultDocumentChildrenQuery
import com.synckro.data.worker.PendingDispatchResumer
import com.synckro.data.worker.SyncScheduler
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.domain.model.SyncEventTaxonomy
import com.synckro.domain.model.SyncPair
import com.synckro.domain.sync.FileCandidateDecision
import com.synckro.domain.sync.FileCandidatePolicy
import com.synckro.domain.sync.FileOpenabilityProbe
import com.synckro.domain.sync.FileStabilityDetector
import com.synckro.domain.sync.FileStabilityMetadata
import com.synckro.domain.sync.FileStabilityMetadataReader
import com.synckro.domain.sync.FileStabilityResult
import com.synckro.domain.sync.InstantSyncEligibilityPolicy
import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.MediaStorePendingState
import com.synckro.domain.sync.PairSignalCoordinator
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstantSyncCandidateIngestor
    @Inject
    constructor(
        private val contentResolver: ContentResolver,
        private val syncPairRepository: SyncPairRepository,
        private val settingsRepository: SettingsRepository,
        private val eligibilityPolicy: InstantSyncEligibilityPolicy,
        private val pendingUploadRepository: PendingUploadRepository,
        private val pairSignalCoordinator: PairSignalCoordinator,
        private val syncScheduler: SyncScheduler,
        private val pathResolver: InstantSyncChangedPathResolver,
        private val candidateSampler: SafInstantSyncCandidateSampler,
        private val stabilityDetector: FileStabilityDetector<InstantSyncCandidateTarget>,
        private val localFsEnumerator: LocalFsEnumerator? = null,
        private val eventRepository: SyncEventRepository? = null,
    ) {
        suspend fun onLocalChange(event: LocalChangeEvent.Changed) {
            Timber.i(
                "instant.ingest.received pairId=%d hintPresent=%s",
                event.pairId,
                !event.locationHint.isNullOrBlank(),
            )
            val pair =
                syncPairRepository.getById(event.pairId, contentResolver) ?: run {
                    Timber.i("instant.ingest.rejected pairId=%d reason=unknown_pair", event.pairId)
                    return
                }

            val globalAutoSyncEnabled = settingsRepository.globalAutoSyncEnabled.first()
            val globalInstantSyncEnabled = settingsRepository.globalInstantSyncEnabled.first()
            val pairDecision =
                eligibilityPolicy.evaluate(
                    pair = pair,
                    globalAutoSyncEnabled = globalAutoSyncEnabled,
                    globalInstantSyncEnabled = globalInstantSyncEnabled,
                    accountState = PendingDispatchResumer.accountStateFor(pair),
                )
            if (!pairDecision.isEligible) {
                Timber.i(
                    "instant.ingest.rejected pairId=%d reasons=%s",
                    pair.id,
                    pairDecision.reasons.joinToString(",") { it.name.lowercase() },
                )
                return
            }

            val pathScope =
                LocalFsEnumerator.compilePathScope(
                    includeGlobs = pair.includeGlobs,
                    ignoreGlobs = pair.excludeGlobs,
                    excludeSubfolders = pair.excludeSubfolders,
                )
            val candidates = pathResolver.resolve(pair, event)
            if (candidates.isEmpty()) {
                val enumerator = localFsEnumerator
                if (enumerator != null && pathResolver.isCoarse(pair, event)) {
                    pairSignalCoordinator.signal(pair.id) {
                        val changedPaths =
                            enumerator
                                .enumerate(
                                    pairId = pair.id,
                                    treeUri = Uri.parse(pair.localTreeUri),
                                    includeGlobs = pair.includeGlobs,
                                    ignoreGlobs = pair.excludeGlobs,
                                    excludeSubfolders = pair.excludeSubfolders,
                                ).let { it.added + it.modified }
                        var queuedAny = false
                        changedPaths.forEach { relativePath ->
                            val candidate = InstantSyncCandidateTarget(Uri.parse(pair.localTreeUri), relativePath)
                            if (queueCandidate(pair, candidate, pathScope)) {
                                queuedAny = true
                            }
                        }
                        eventRepository?.log(
                            pair.id,
                            SyncEventLevel.INFO,
                            SyncEventTag.INSTANT_WATCH,
                            SyncEventTaxonomy.watchRescan(changedPaths.size),
                        )
                        if (queuedAny) syncScheduler.enqueueInstant(pair)
                    }
                    return
                }
                // The INFO/DEBUG pair below is deliberate, not a duplicate: the INFO line stays
                // path-free (only hintPresent) so it is safe for release-level log captures, while
                // the DEBUG line carries the raw hint for local diagnosis only.
                Timber.i(
                    "instant.ingest.rejected pairId=%d reason=no_resolvable_candidate hintPresent=%s",
                    pair.id,
                    !event.locationHint.isNullOrBlank(),
                )
                Timber.d(
                    "Skipping Instant Sync change for pair %d: no resolvable candidate from hint '%s'",
                    pair.id,
                    event.locationHint,
                )
                return
            }

            var queuedAny = false
            candidates.forEach { candidate ->
                if (queueCandidate(pair, candidate, pathScope)) queuedAny = true
            }

            if (queuedAny) {
                pairSignalCoordinator.signal(pair.id) { syncScheduler.enqueueInstant(pair) }
            }
        }

        private suspend fun queueCandidate(
            pair: SyncPair,
            candidate: InstantSyncCandidateTarget,
            pathScope: LocalPathScope,
        ): Boolean {
            if (!pathScope.contains(candidate.relativePath)) {
                Timber.d(
                    "Skipping Instant Sync candidate for pair %d: out of scope '%s'",
                    pair.id,
                    candidate.relativePath,
                )
                return false
            }

            when (val preflight = candidateSampler.sample(candidate)) {
                InstantSyncCandidateSample.Missing -> {
                    Timber.d(
                        "Skipping Instant Sync candidate for pair %d: file missing '%s'",
                        pair.id,
                        candidate.relativePath,
                    )
                    return false
                }
                is InstantSyncCandidateSample.Inconclusive -> {
                    Timber.d(
                        "Deferring Instant Sync candidate for pair %d: %s (%s)",
                        pair.id,
                        candidate.relativePath,
                        preflight.reason,
                    )
                    return false
                }
                is InstantSyncCandidateSample.Available -> {
                    if (!preflight.isEligibleFile(candidate.relativePath)) return false
                }
            }

            when (val stability = stabilityDetector.awaitStable(candidate)) {
                is FileStabilityResult.Deferred -> {
                    Timber.d(
                        "Deferring Instant Sync candidate for pair %d: %s (%s)",
                        pair.id,
                        candidate.relativePath,
                        stability.reason,
                    )
                    return false
                }
                FileStabilityResult.Stable -> Unit
            }

            val stable =
                candidateSampler.sample(candidate) as? InstantSyncCandidateSample.Available ?: run {
                    Timber.d(
                        "Skipping Instant Sync candidate for pair %d after stability gate: %s",
                        pair.id,
                        candidate.relativePath,
                    )
                    return false
                }
            if (!stable.isEligibleFile(candidate.relativePath)) return false

            val observedAtMs = System.currentTimeMillis()
            pendingUploadRepository.upsertCandidate(
                pairId = pair.id,
                relativePath = candidate.relativePath,
                documentIdHint = stable.documentId,
                observedSizeBytes = stable.sizeBytes,
                observedMtimeMs = stable.mtimeMs,
                eligibleAtMs = observedAtMs,
                observedAtMs = observedAtMs,
            )
            return true
        }

        private fun InstantSyncCandidateSample.Available.isEligibleFile(relativePath: String): Boolean {
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                Timber.d("Skipping Instant Sync directory candidate '%s'", relativePath)
                return false
            }
            val decision =
                FileCandidatePolicy.evaluate(
                    pathOrName = displayName?.takeIf { it.isNotBlank() } ?: relativePath,
                    mediaStorePendingState = mediaStorePendingState,
                )
            return when (decision) {
                FileCandidateDecision.Eligible -> true
                is FileCandidateDecision.Excluded -> {
                    Timber.d("Skipping Instant Sync candidate '%s': %s", relativePath, decision.reason)
                    false
                }
                is FileCandidateDecision.Inconclusive -> {
                    Timber.d("Deferring Instant Sync candidate '%s': %s", relativePath, decision.reason)
                    false
                }
            }
        }
    }

data class InstantSyncCandidateTarget(
    val treeUri: Uri,
    val relativePath: String,
    val documentIdHint: String? = null,
)

sealed interface InstantSyncCandidateSample {
    data class Available(
        val documentId: String,
        val sizeBytes: Long,
        val mtimeMs: Long,
        val mimeType: String,
        val openable: Boolean,
        val mediaStorePendingState: MediaStorePendingState,
        val displayName: String? = null,
    ) : InstantSyncCandidateSample

    data object Missing : InstantSyncCandidateSample

    data class Inconclusive(
        val reason: Reason,
    ) : InstantSyncCandidateSample {
        enum class Reason {
            METADATA_UNAVAILABLE,
            PENDING_STATE_UNAVAILABLE,
            PROVIDER_FAILURE,
        }
    }
}

class InstantSyncChangedPathResolver
    @Inject
    constructor(
        private val sourceProvider: LocalTreeWatchSourceProvider,
    ) {
        @Suppress("DEPRECATION")
        private val primaryStorageRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')

        fun resolve(
            pair: SyncPair,
            event: LocalChangeEvent.Changed,
        ): List<InstantSyncCandidateTarget> {
            val hint = event.locationHint?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
            val treeUri = Uri.parse(pair.localTreeUri)
            return listOfNotNull(
                resolveContentUriHint(treeUri, hint),
                resolveAbsolutePathHint(treeUri, hint),
                resolveVolumeRelativeHint(treeUri, hint),
                resolvePairRelativeHint(treeUri, hint),
            ).distinctBy { it.relativePath }
        }

        /** True for an absent hint or for the pair tree root, neither of which identifies a file. */
        fun isCoarse(
            pair: SyncPair,
            event: LocalChangeEvent.Changed,
        ): Boolean {
            val hint = event.locationHint?.trim().orEmpty()
            if (event.isCoarse || hint.isEmpty() || trimTrailingSlash(hint) == trimTrailingSlash(pair.localTreeUri)) {
                return true
            }
            if (sameRootDocument(pair.localTreeUri, hint)) return true
            val source = sourceProvider.sourceFor(pair.id) as? LocalTreeWatchSource.DirectPath
            return source != null && trimTrailingSlash(hint) == trimTrailingSlash(source.path)
        }

        private fun resolveContentUriHint(
            treeUri: Uri,
            hint: String,
        ): InstantSyncCandidateTarget? {
            if (!hint.startsWith("content://")) return null
            val candidateUri = Uri.parse(hint)
            val documentId = candidateUri.documentIdOrNull() ?: return null
            val relativePath = relativePathFromDocumentId(treeUri, documentId) ?: return null
            return InstantSyncCandidateTarget(treeUri, relativePath, documentId)
        }

        private fun resolveAbsolutePathHint(
            treeUri: Uri,
            hint: String,
        ): InstantSyncCandidateTarget? {
            if (!hint.startsWith('/')) return null
            val rootRelativePath = treeRelativePath(treeUri) ?: return null
            val rootPath =
                if (rootRelativePath.isEmpty()) {
                    primaryStorageRoot
                } else {
                    "$primaryStorageRoot/$rootRelativePath"
                }.trimEnd('/')
            if (hint == rootPath || !hint.startsWith("$rootPath/")) return null
            val relativePath = hint.removePrefix("$rootPath/").trim('/')
            return relativePath.takeIf { it.isNotEmpty() }?.let { InstantSyncCandidateTarget(treeUri, it) }
        }

        private fun resolveVolumeRelativeHint(
            treeUri: Uri,
            hint: String,
        ): InstantSyncCandidateTarget? {
            if (hint.startsWith("content://") || hint.startsWith('/')) return null
            val normalizedHint = hint.trim('/')
            if (normalizedHint.isEmpty()) return null
            val rootRelativePath = treeRelativePath(treeUri) ?: return null
            if (rootRelativePath.isEmpty()) {
                return InstantSyncCandidateTarget(treeUri, normalizedHint)
            }
            if (normalizedHint == rootRelativePath || !normalizedHint.startsWith("$rootRelativePath/")) {
                return null
            }
            val relativePath = normalizedHint.removePrefix("$rootRelativePath/")
            return relativePath.takeIf { it.isNotEmpty() }?.let { InstantSyncCandidateTarget(treeUri, it) }
        }

        private fun resolvePairRelativeHint(
            treeUri: Uri,
            hint: String,
        ): InstantSyncCandidateTarget? {
            if (hint.startsWith("content://") || hint.startsWith('/')) return null
            val relativePath = hint.trim('/')
            return relativePath.takeIf { it.isNotEmpty() }?.let { InstantSyncCandidateTarget(treeUri, it) }
        }

        private fun relativePathFromDocumentId(
            treeUri: Uri,
            documentId: String,
        ): String? {
            val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
            if (documentId == rootDocumentId) return null
            val relativePath =
                when {
                    documentId.startsWith("$rootDocumentId/") -> documentId.removePrefix("$rootDocumentId/")
                    rootDocumentId.endsWith(':') && documentId.startsWith(rootDocumentId) ->
                        documentId.removePrefix(rootDocumentId)
                    else -> return null
                }.trim('/')
            return relativePath.takeIf { it.isNotEmpty() }
        }

        private fun treeRelativePath(treeUri: Uri): String? {
            if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
            val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
            if (!treeDocumentId.startsWith(PRIMARY_VOLUME_PREFIX)) return null
            return treeDocumentId.removePrefix(PRIMARY_VOLUME_PREFIX).trim('/')
        }

        private fun Uri.documentIdOrNull(): String? =
            runCatching { DocumentsContract.getDocumentId(this) }
                .getOrElse { runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull() }

        private fun sameRootDocument(
            treeUriString: String,
            hint: String,
        ): Boolean {
            if (!hint.startsWith("content://")) return false
            val treeUri = Uri.parse(treeUriString)
            val hintUri = Uri.parse(hint)
            val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            val hintDocumentId = hintUri.documentIdOrNull()
            return rootDocumentId != null && hintDocumentId == rootDocumentId
        }

        private fun trimTrailingSlash(value: String): String = value.trim().trimEnd('/')

        private companion object {
            const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
            const val PRIMARY_VOLUME_PREFIX = "primary:"
        }
    }

class SafInstantSyncCandidateSampler
    @Inject
    constructor(
        private val contentResolver: ContentResolver,
    ) : FileStabilityMetadataReader<InstantSyncCandidateTarget>,
        FileOpenabilityProbe<InstantSyncCandidateTarget> {
        fun sample(target: InstantSyncCandidateTarget): InstantSyncCandidateSample =
            when (val sampled = sampleTarget(target, includePendingState = true)) {
                is TargetedSafMetadataSample.Available ->
                    InstantSyncCandidateSample.Available(
                        documentId = sampled.documentId,
                        sizeBytes = sampled.sizeBytes,
                        mtimeMs = sampled.mtimeMs,
                        mimeType = sampled.mimeType,
                        openable = sampled.openable,
                        mediaStorePendingState =
                            when (sampled.mediaStorePending) {
                                true -> MediaStorePendingState.PENDING
                                false -> MediaStorePendingState.NOT_PENDING
                                null -> MediaStorePendingState.NOT_APPLICABLE
                            },
                        displayName = sampled.displayName,
                    )
                TargetedSafMetadataSample.Missing -> InstantSyncCandidateSample.Missing
                is TargetedSafMetadataSample.Inconclusive ->
                    InstantSyncCandidateSample.Inconclusive(
                        when (sampled.reason) {
                            TargetedSafMetadataSample.Inconclusive.Reason.METADATA_UNAVAILABLE ->
                                InstantSyncCandidateSample.Inconclusive.Reason.METADATA_UNAVAILABLE
                            TargetedSafMetadataSample.Inconclusive.Reason.PENDING_STATE_UNAVAILABLE ->
                                InstantSyncCandidateSample.Inconclusive.Reason.PENDING_STATE_UNAVAILABLE
                            TargetedSafMetadataSample.Inconclusive.Reason.PROVIDER_FAILURE ->
                                InstantSyncCandidateSample.Inconclusive.Reason.PROVIDER_FAILURE
                        },
                    )
            }

        override suspend fun readMetadata(target: InstantSyncCandidateTarget): FileStabilityMetadata? =
            when (val sampled = sampleTarget(target, includePendingState = false)) {
                is TargetedSafMetadataSample.Available ->
                    FileStabilityMetadata(
                        sizeBytes = sampled.sizeBytes,
                        mtimeMs = sampled.mtimeMs,
                    )
                TargetedSafMetadataSample.Missing,
                is TargetedSafMetadataSample.Inconclusive,
                -> null
            }

        override suspend fun canOpen(target: InstantSyncCandidateTarget): Boolean =
            when (val sampled = sampleTarget(target, includePendingState = false)) {
                is TargetedSafMetadataSample.Available -> sampled.openable
                TargetedSafMetadataSample.Missing,
                is TargetedSafMetadataSample.Inconclusive,
                -> false
            }

        private fun sampleTarget(
            target: InstantSyncCandidateTarget,
            includePendingState: Boolean,
        ): TargetedSafMetadataSample {
            val sampler =
                TargetedSafMetadataSampler(
                    resolver = contentResolver,
                    treeUri = target.treeUri,
                    childrenQuery = DefaultDocumentChildrenQuery,
                    metadataQuery = DefaultSafDocumentMetadataQuery,
                    readProbe = DefaultSafReadProbe,
                    mediaStorePendingStateQuery =
                        if (includePendingState) {
                            ExternalStorageMediaStorePendingStateQuery
                        } else {
                            null
                        },
                )
            val hinted = target.documentIdHint?.takeIf { it.isNotBlank() }
            if (hinted != null) {
                when (val direct = sampler.sample(documentId = hinted)) {
                    is TargetedSafMetadataSample.Available ->
                        if (direct.matches(target.relativePath)) return direct
                    is TargetedSafMetadataSample.Inconclusive -> return direct
                    TargetedSafMetadataSample.Missing -> Unit
                }
            }
            return sampler.sample(relativePath = target.relativePath)
        }

        private fun TargetedSafMetadataSample.Available.matches(relativePath: String): Boolean =
            displayName == null || displayName == relativePath.substringAfterLast('/')
    }

private object ExternalStorageMediaStorePendingStateQuery : MediaStorePendingStateQuery {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_VOLUME_PREFIX = "primary:"

    override fun isPending(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
    ): Boolean? {
        val volumeRelativePath = volumeRelativePath(treeUri, documentId) ?: return false
        val parentRelativePath =
            volumeRelativePath.substringBeforeLast('/', missingDelimiterValue = "").let {
                if (it.isEmpty()) "" else "$it/"
            }
        val displayName = volumeRelativePath.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return false
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING)
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val args = arrayOf(parentRelativePath, displayName)
        return try {
            resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return false
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                index >= 0 && cursor.getInt(index) != 0
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun volumeRelativePath(
        treeUri: Uri,
        documentId: String,
    ): String? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        if (!treeDocumentId.startsWith(PRIMARY_VOLUME_PREFIX)) return null
        val pairRoot = treeDocumentId.removePrefix(PRIMARY_VOLUME_PREFIX).trim('/')
        val relativePath =
            when {
                documentId.startsWith("$treeDocumentId/") -> documentId.removePrefix("$treeDocumentId/")
                treeDocumentId.endsWith(':') && documentId.startsWith(treeDocumentId) ->
                    documentId.removePrefix(treeDocumentId)
                else -> return null
            }.trim('/')
        if (relativePath.isEmpty()) return null
        return listOf(pairRoot, relativePath).filter { it.isNotEmpty() }.joinToString("/")
    }
}
