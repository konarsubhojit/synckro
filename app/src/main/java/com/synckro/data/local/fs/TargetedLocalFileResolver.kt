package com.synckro.data.local.fs

import android.content.ContentResolver
import android.net.Uri
import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.data.local.entity.PendingUploadEntity
import com.synckro.data.scanner.DefaultDocumentChildrenQuery
import com.synckro.data.scanner.DocumentChildrenQuery
import timber.log.Timber
import java.io.InputStream

internal sealed interface TargetedLocalFileResolution {
    class Resolved(
        val entry: LocalFileEntry,
        val documentId: String,
        private val openReadStream: () -> InputStream?,
    ) : TargetedLocalFileResolution {
        fun openRead(): InputStream? = openReadStream()
    }

    data object Missing : TargetedLocalFileResolution

    data object OutOfScope : TargetedLocalFileResolution

    data class Unavailable(
        val reason: Reason,
    ) : TargetedLocalFileResolution {
        enum class Reason {
            METADATA_UNAVAILABLE,
            PERMISSION_LOST,
            NO_READ_ACCESS,
        }
    }
}

/**
 * Resolves one claimed upload candidate into a local snapshot without running a
 * full SAF tree reconciliation.
 */
internal class TargetedLocalFileResolver(
    resolver: ContentResolver,
    private val treeUri: Uri,
    private val localIndexDao: LocalIndexDao,
    childrenQuery: DocumentChildrenQuery = DefaultDocumentChildrenQuery,
    metadataQuery: SafDocumentMetadataQuery = DefaultSafDocumentMetadataQuery,
    private val fsAccess: FsAccess = DefaultFsAccess(resolver),
) {
    private val sampler =
        TargetedSafMetadataSampler(
            resolver = resolver,
            treeUri = treeUri,
            childrenQuery = childrenQuery,
            metadataQuery = metadataQuery,
            readProbe = { _, candidateTreeUri, documentId ->
                fsAccess.openInputStream(candidateTreeUri, documentId)
            },
        )

    suspend fun resolve(
        upload: PendingUploadEntity,
        includeGlobs: List<String> = emptyList(),
        ignoreGlobs: List<String> = emptyList(),
        excludeSubfolders: Boolean = false,
    ): TargetedLocalFileResolution =
        resolve(
            upload = upload,
            pathScope =
                LocalFsEnumerator.compilePathScope(
                    includeGlobs = includeGlobs,
                    ignoreGlobs = ignoreGlobs,
                    excludeSubfolders = excludeSubfolders,
                ),
        )

    suspend fun resolve(
        upload: PendingUploadEntity,
        pathScope: LocalPathScope,
    ): TargetedLocalFileResolution {
        val relativePath = upload.relativePath
        if (!pathScope.contains(relativePath)) {
            localIndexDao.delete(upload.pairId, relativePath)
            return TargetedLocalFileResolution.OutOfScope
        }

        val sample = sample(upload)
        if (sample == TargetedSafMetadataSample.Missing) {
            localIndexDao.delete(upload.pairId, relativePath)
            return TargetedLocalFileResolution.Missing
        }
        if (sample is TargetedSafMetadataSample.Inconclusive) {
            return TargetedLocalFileResolution.Unavailable(sample.reason.toResolutionReason())
        }

        sample as TargetedSafMetadataSample.Available
        val cached = localIndexDao.get(upload.pairId, relativePath)
        val hashResolution = resolveHash(sample, cached, relativePath)
        if (hashResolution == HashResolution.PermissionLost) {
            return TargetedLocalFileResolution.Unavailable(
                TargetedLocalFileResolution.Unavailable.Reason.PERMISSION_LOST,
            )
        }
        val contentHash = (hashResolution as HashResolution.Available).contentHash

        upsertLocalIndex(upload, sample, cached, contentHash)

        if (!sample.openable || contentHash == null) {
            return TargetedLocalFileResolution.Unavailable(
                TargetedLocalFileResolution.Unavailable.Reason.NO_READ_ACCESS,
            )
        }

        return TargetedLocalFileResolution.Resolved(
            entry =
                LocalFileEntry(
                    relativePath = relativePath,
                    sizeBytes = sample.sizeBytes,
                    mtimeMs = sample.mtimeMs,
                    contentHash = contentHash,
                ),
            documentId = sample.documentId,
            openReadStream = { fsAccess.openInputStream(treeUri, sample.documentId) },
        )
    }

    private fun sample(upload: PendingUploadEntity): TargetedSafMetadataSample {
        val hinted = upload.documentIdHint
        if (!hinted.isNullOrBlank()) {
            val direct = sampler.sample(documentId = hinted)
            when {
                direct is TargetedSafMetadataSample.Available && direct.matches(upload.relativePath) -> return direct
                direct is TargetedSafMetadataSample.Inconclusive -> return direct
            }
        }
        return sampler.sample(relativePath = upload.relativePath)
    }

    private fun TargetedSafMetadataSample.Available.matches(relativePath: String): Boolean =
        displayName == null || displayName == relativePath.substringAfterLast('/')

    private fun resolveHash(
        sample: TargetedSafMetadataSample.Available,
        cached: LocalIndexEntity?,
        relativePath: String,
    ): HashResolution =
        if (cached != null &&
            cached.sizeBytes == sample.sizeBytes &&
            cached.mtimeMs == sample.mtimeMs &&
            cached.contentHash != null
        ) {
            HashResolution.Available(cached.contentHash)
        } else {
            computeHash(sample.documentId, relativePath)
        }

    private fun computeHash(
        documentId: String,
        relativePath: String,
    ): HashResolution =
        try {
            HashResolution.Available(
                fsAccess.openInputStream(treeUri, documentId)?.use(LocalFsEnumerator::sha256Hex),
            )
        } catch (_: SecurityException) {
            HashResolution.PermissionLost
        } catch (_: Exception) {
            HashResolution.Available(null)
        }.also { hash ->
            if (hash == HashResolution.Available(null)) {
                Timber.w("TargetedLocalFileResolver: no read access for '%s'", relativePath)
            }
        }

    private suspend fun upsertLocalIndex(
        upload: PendingUploadEntity,
        sample: TargetedSafMetadataSample.Available,
        cached: LocalIndexEntity?,
        contentHash: String?,
    ) {
        localIndexDao.upsert(
            LocalIndexEntity(
                pairId = upload.pairId,
                relativePath = upload.relativePath,
                sizeBytes = sample.sizeBytes,
                mtimeMs = sample.mtimeMs,
                contentHash = contentHash,
                remoteId = cached?.remoteId,
                remoteSizeBytes = cached?.remoteSizeBytes,
                remoteMtimeMs = cached?.remoteMtimeMs,
                remoteEtag = cached?.remoteEtag,
            ),
        )
    }

    private fun TargetedSafMetadataSample.Inconclusive.Reason.toResolutionReason() =
        when (this) {
            TargetedSafMetadataSample.Inconclusive.Reason.METADATA_UNAVAILABLE ->
                TargetedLocalFileResolution.Unavailable.Reason.METADATA_UNAVAILABLE
            TargetedSafMetadataSample.Inconclusive.Reason.PROVIDER_FAILURE ->
                TargetedLocalFileResolution.Unavailable.Reason.PERMISSION_LOST
        }

    private sealed interface HashResolution {
        data class Available(
            val contentHash: String?,
        ) : HashResolution

        data object PermissionLost : HashResolution
    }
}
