package com.synckro.data.local.fs

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.synckro.data.scanner.DefaultDocumentChildrenQuery
import com.synckro.data.scanner.DocumentChildrenQuery
import com.synckro.data.scanner.RawDocChild
import com.synckro.domain.sync.LocalFileAccess
import com.synckro.domain.sync.LocalFileStat
import timber.log.Timber
import java.io.InputStream

/**
 * SAF-backed [LocalFileAccess] scoped to a single document-tree URI.
 *
 * Each public method navigates the document tree from its root, resolving
 * path segments against the SAF children query. Intermediate directories are
 * created on demand during [write].
 *
 * Instances are cheap to create; the recommended usage is to construct one
 * per sync-pair run via [AppModule] so the [treeUri] is always
 * correct for the active pair.
 *
 * @param resolver      ContentResolver used for all SAF stream operations.
 * @param treeUri       Persisted SAF tree URI (from `ACTION_OPEN_DOCUMENT_TREE`).
 * @param childrenQuery Strategy for listing directory children. Defaults to the
 *                      production SAF implementation; swap with a fake in unit tests.
 */
internal class SafLocalFileAccess(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val childrenQuery: DocumentChildrenQuery = DefaultDocumentChildrenQuery,
) : LocalFileAccess {
    // -------------------------------------------------------------------------
    // LocalFileAccess
    // -------------------------------------------------------------------------

    override fun openRead(relativePath: String): InputStream? {
        val docId = findDocId(relativePath) ?: return null
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return try {
            resolver.openInputStream(docUri)
        } catch (e: Exception) {
            Timber.w(e, "SafLocalFileAccess: cannot open '%s' for read", relativePath)
            null
        }
    }

    override fun write(
        relativePath: String,
        content: InputStream,
        mimeType: String?,
    ): LocalFileStat {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "SafLocalFileAccess: relativePath must not be empty" }

        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)

        // Navigate to (and create, if absent) every parent directory.
        var parentDocId = rootDocId
        for (dirName in segments.dropLast(1)) {
            val existing =
                listChildren(parentDocId).find {
                    it.name == dirName && it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                }
            parentDocId =
                if (existing != null) {
                    existing.docId
                } else {
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
                    val newDirUri =
                        DocumentsContract.createDocument(
                            resolver,
                            parentUri,
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            dirName,
                        ) ?: error("SafLocalFileAccess: failed to create directory '$dirName'")
                    DocumentsContract.getDocumentId(newDirUri)
                }
        }

        val fileName = segments.last()
        val fileMimeType = mimeType ?: "application/octet-stream"

        // Reuse existing document (overwrite) or create a new one.
        val existingDoc = listChildren(parentDocId).find { it.name == fileName }
        val docUri: Uri =
            if (existingDoc != null) {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, existingDoc.docId)
            } else {
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
                DocumentsContract.createDocument(resolver, parentUri, fileMimeType, fileName)
                    ?: error("SafLocalFileAccess: failed to create file '$fileName'")
            }

        // Write content, truncating any previous content.
        var bytesWritten = 0L
        resolver.openOutputStream(docUri, "wt")?.use { out ->
            content.use { inp -> bytesWritten = inp.copyTo(out) }
        } ?: error("SafLocalFileAccess: cannot open output stream for '$relativePath'")

        // Re-query to return accurate file metadata.
        val updatedDoc = listChildren(parentDocId).find { it.name == fileName }
        return LocalFileStat(
            sizeBytes = updatedDoc?.size ?: bytesWritten,
            mtimeMs = updatedDoc?.lastModifiedMs ?: System.currentTimeMillis(),
            mimeType = updatedDoc?.mimeType ?: mimeType,
        )
    }

    override fun delete(relativePath: String): Boolean {
        val docId = findDocId(relativePath) ?: return false
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return try {
            DocumentsContract.deleteDocument(resolver, docUri)
        } catch (e: Exception) {
            Timber.w(e, "SafLocalFileAccess: cannot delete '%s'", relativePath)
            false
        }
    }

    override fun stat(relativePath: String): LocalFileStat? {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        var currentDocId = rootDocId
        var lastChild: RawDocChild? = null
        for (segment in segments) {
            val child = listChildren(currentDocId).find { it.name == segment } ?: return null
            lastChild = child
            currentDocId = child.docId
        }
        val child = lastChild ?: return null
        return LocalFileStat(
            sizeBytes = child.size,
            mtimeMs = child.lastModifiedMs,
            mimeType = child.mimeType,
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves [relativePath] to a SAF document ID by walking the tree from
     * the root, matching each path segment against the children list.
     *
     * @return The document ID, or `null` if any segment is not found.
     */
    private fun findDocId(relativePath: String): String? {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return rootDocId

        var currentDocId = rootDocId
        for (segment in segments) {
            val child = listChildren(currentDocId).find { it.name == segment } ?: return null
            currentDocId = child.docId
        }
        return currentDocId
    }

    /**
     * Returns the direct children of [parentDocId] within [treeUri].
     *
     * @throws LocalStorageException if the SAF query fails (e.g. permission revoked or
     *   storage temporarily unavailable). Callers must not treat this as an empty
     *   directory — the failure must propagate so the sync can abort safely.
     */
    private fun listChildren(parentDocId: String): List<RawDocChild> =
        try {
            childrenQuery(resolver, treeUri, parentDocId)
        } catch (e: Exception) {
            Timber.w(e, "SafLocalFileAccess: failed to list children of '%s'", parentDocId)
            throw LocalStorageException(
                "SAF permission denied or storage unavailable for document '$parentDocId'",
                e,
            )
        }
}
