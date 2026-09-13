package com.synckro.data.local.fs

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.synckro.data.scanner.DefaultDocumentChildrenQuery
import com.synckro.data.scanner.DocumentChildrenQuery
import java.io.InputStream

internal data class SafDocumentMetadata(
    val sizeBytes: Long?,
    val mtimeMs: Long?,
    val mimeType: String?,
)

internal fun interface SafDocumentMetadataQuery {
    fun query(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
    ): SafDocumentMetadata?
}

internal class SafMetadataUnavailableException : Exception()

internal object DefaultSafDocumentMetadataQuery : SafDocumentMetadataQuery {
    private val projection =
        arrayOf(
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

    override fun query(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
    ): SafDocumentMetadata? {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return resolver.query(documentUri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val mtimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            SafDocumentMetadata(
                sizeBytes = cursor.getLongOrNull(sizeIndex),
                mtimeMs = cursor.getLongOrNull(mtimeIndex),
                mimeType = cursor.getString(mimeIndex),
            )
        } ?: throw SafMetadataUnavailableException()
    }

    private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)
}

internal fun interface SafReadProbe {
    fun open(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
    ): InputStream?
}

internal object DefaultSafReadProbe : SafReadProbe {
    override fun open(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
    ): InputStream? =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId))
}

internal fun interface MediaStorePendingStateQuery {
    fun isPending(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
    ): Boolean?
}

internal sealed interface TargetedSafMetadataSample {
    data class Available(
        val documentId: String,
        val sizeBytes: Long,
        val mtimeMs: Long,
        val mimeType: String,
        val openable: Boolean,
        val mediaStorePending: Boolean?,
    ) : TargetedSafMetadataSample

    data object Missing : TargetedSafMetadataSample

    data class Inconclusive(
        val reason: Reason,
    ) : TargetedSafMetadataSample {
        enum class Reason {
            METADATA_UNAVAILABLE,
            PROVIDER_FAILURE,
        }
    }
}

/**
 * Samples one SAF document without enumerating the entire document tree.
 *
 * A document ID is queried directly. A relative path is resolved by querying only
 * each of its parent directories. This class only reads provider state and never
 * mutates the database or document tree.
 */
internal class TargetedSafMetadataSampler(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val childrenQuery: DocumentChildrenQuery = DefaultDocumentChildrenQuery,
    private val metadataQuery: SafDocumentMetadataQuery = DefaultSafDocumentMetadataQuery,
    private val readProbe: SafReadProbe = DefaultSafReadProbe,
    private val mediaStorePendingStateQuery: MediaStorePendingStateQuery? = null,
) {
    fun sample(
        relativePath: String? = null,
        documentId: String? = null,
    ): TargetedSafMetadataSample {
        val resolvedDocumentId =
            documentId ?: try {
                relativePath?.let(::findDocumentId)
            } catch (_: Exception) {
                return TargetedSafMetadataSample.Inconclusive(
                    TargetedSafMetadataSample.Inconclusive.Reason.PROVIDER_FAILURE,
                )
            } ?: return TargetedSafMetadataSample.Missing
        val metadata =
            try {
                metadataQuery.query(resolver, treeUri, resolvedDocumentId)
            } catch (_: SafMetadataUnavailableException) {
                return TargetedSafMetadataSample.Inconclusive(
                    TargetedSafMetadataSample.Inconclusive.Reason.METADATA_UNAVAILABLE,
                )
            } catch (_: Exception) {
                return TargetedSafMetadataSample.Inconclusive(
                    TargetedSafMetadataSample.Inconclusive.Reason.PROVIDER_FAILURE,
                )
            } ?: return TargetedSafMetadataSample.Missing
        val sizeBytes = metadata.sizeBytes
        val mtimeMs = metadata.mtimeMs
        val mimeType = metadata.mimeType
        if (sizeBytes == null || mtimeMs == null || mimeType == null) {
            return TargetedSafMetadataSample.Inconclusive(
                TargetedSafMetadataSample.Inconclusive.Reason.METADATA_UNAVAILABLE,
            )
        }
        val openable =
            try {
                readProbe.open(resolver, treeUri, resolvedDocumentId)?.use { } != null
            } catch (_: Exception) {
                return TargetedSafMetadataSample.Inconclusive(
                    TargetedSafMetadataSample.Inconclusive.Reason.PROVIDER_FAILURE,
                )
            }
        val mediaStorePending =
            try {
                mediaStorePendingStateQuery?.isPending(resolver, treeUri, resolvedDocumentId)
            } catch (_: Exception) {
                return TargetedSafMetadataSample.Inconclusive(
                    TargetedSafMetadataSample.Inconclusive.Reason.PROVIDER_FAILURE,
                )
            }
        return TargetedSafMetadataSample.Available(
            documentId = resolvedDocumentId,
            sizeBytes = sizeBytes,
            mtimeMs = mtimeMs,
            mimeType = mimeType,
            openable = openable,
            mediaStorePending = mediaStorePending,
        )
    }

    private fun findDocumentId(relativePath: String): String? {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var documentId = DocumentsContract.getTreeDocumentId(treeUri)
        for (segment in segments) {
            val child = childrenQuery(resolver, treeUri, documentId).find { it.name == segment }
            documentId = child?.docId ?: return null
        }
        return documentId
    }
}
