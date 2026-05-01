package com.konarsubhojit.synckro.data.scanner

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.konarsubhojit.synckro.data.local.dao.FileIndexDao
import com.konarsubhojit.synckro.data.local.entity.FileIndexEntity
import com.konarsubhojit.synckro.domain.scan.LocalFolderScanner
import com.konarsubhojit.synckro.domain.scan.LocalFolderScanner.Companion.PROGRESS_INTERVAL
import com.konarsubhojit.synckro.domain.scan.ScanProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single direct child returned by a document-tree directory query.
 *
 * @param docId        DocumentsContract document ID for this child.
 * @param name         Display name (file/folder name without path).
 * @param size         File size in bytes (0 for directories or unknown).
 * @param lastModifiedMs Last-modified epoch milliseconds (0 if not available).
 * @param mimeType     MIME type; [DocumentsContract.Document.MIME_TYPE_DIR] for directories.
 */
internal data class RawDocChild(
    val docId: String,
    val name: String,
    val size: Long,
    val lastModifiedMs: Long,
    val mimeType: String?,
)

/**
 * Callable that returns the direct children of a document-tree directory.
 *
 * Extracted as a functional interface so that [LocalFolderScannerImpl] can be
 * unit-tested without a live ContentProvider.
 */
internal fun interface DocumentChildrenQuery {
    /**
     * Lists the direct children of [parentDocId] within [treeUri].
     *
     * @param resolver  ContentResolver to use for the query.
     * @param treeUri   Root tree URI (from `ACTION_OPEN_DOCUMENT_TREE`).
     * @param parentDocId DocumentsContract document ID of the directory to list.
     * @return Direct children; empty list if the directory is empty or the
     *         query returns null (e.g. the URI is no longer accessible).
     */
    operator fun invoke(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
    ): List<RawDocChild>
}

/** Production implementation that queries the SAF content provider. */
internal object DefaultDocumentChildrenQuery : DocumentChildrenQuery {
    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    override fun invoke(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
    ): List<RawDocChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val result = mutableListOf<RawDocChild>()
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val idxName = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idxSize = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val idxMtime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val idxMime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                result += RawDocChild(
                    docId = cursor.getString(idxId) ?: continue,
                    name = cursor.getString(idxName) ?: "",
                    size = if (cursor.isNull(idxSize)) 0L else cursor.getLong(idxSize),
                    lastModifiedMs = if (cursor.isNull(idxMtime)) 0L else cursor.getLong(idxMtime),
                    mimeType = cursor.getString(idxMime),
                )
            }
        }
        return result
    }
}

/**
 * Production [LocalFolderScanner] backed by [DocumentsContract] for efficient
 * batch-cursor queries and Room for index persistence.
 *
 * **Incremental update strategy**
 * 1. Load the current Room index for [pairId] into memory.
 * 2. Walk the SAF document tree using a breadth-first queue (avoids stack overflow
 *    on deeply nested trees; also makes progress emission straightforward).
 * 3. For each file discovered:
 *    - If the file is new → will be upserted with `localHash = null`.
 *    - If size **or** last-modified changed → will be upserted with `localHash = null`
 *      (the old hash is stale so it must be recomputed by the engine on demand).
 *    - If neither changed → the existing `localHash` is preserved (lazy SHA-256).
 * 4. Entries present in the index but not found during the scan are deleted.
 * 5. Steps 3 and 4 are executed as a **single Room transaction** via
 *    [FileIndexDao.reconcileForPair], so the index is never observed in a
 *    half-updated state; a failure rolls back all writes.
 * 6. [ScanProgress.Scanning] is emitted every [PROGRESS_INTERVAL] files so the
 *    UI can display a progress indicator; [ScanProgress.Done] is the terminal event.
 *
 * All IO runs on [Dispatchers.IO] via `flowOn`.
 */
@Singleton
class LocalFolderScannerImpl internal constructor(
    private val context: Context,
    private val fileIndexDao: FileIndexDao,
    internal val childrenQuery: DocumentChildrenQuery,
) : LocalFolderScanner {

    /** Hilt-injected primary constructor; uses the production DocumentsContract query. */
    @Inject
    constructor(
        @ApplicationContext context: Context,
        fileIndexDao: FileIndexDao,
    ) : this(context, fileIndexDao, DefaultDocumentChildrenQuery)

    override fun scan(pairId: Long, treeUri: Uri): Flow<ScanProgress> = flow {
        // 1. Snapshot the existing index for this pair so we can compute the diff.
        val existing: Map<String, FileIndexEntity> =
            fileIndexDao.getForPair(pairId).associateBy { it.relativePath }

        val scannedEntries = mutableListOf<FileIndexEntity>()

        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)

            // 2. BFS tree walk — iterative to avoid stack overflow on deep trees.
            //    Queue entries: (documentId, relativePathPrefix)
            val queue = ArrayDeque<Pair<String, String>>()
            queue.add(rootDocId to "")

            var scannedCount = 0

            while (queue.isNotEmpty()) {
                val (parentDocId, prefix) = queue.removeFirst()
                val children = childrenQuery(context.contentResolver, treeUri, parentDocId)

                for (child in children) {
                    val relativePath =
                        if (prefix.isEmpty()) child.name else "$prefix/${child.name}"

                    if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // Recurse into sub-directory.
                        queue.add(child.docId to relativePath)
                    } else {
                        // Preserve existing hash when size+mtime are unchanged (lazy SHA-256).
                        val existingEntry = existing[relativePath]
                        val preservedHash = if (
                            existingEntry != null &&
                            existingEntry.localSize == child.size &&
                            existingEntry.localLastModifiedMs == child.lastModifiedMs
                        ) existingEntry.localHash else null

                        scannedEntries += FileIndexEntity(
                            pairId = pairId,
                            relativePath = relativePath,
                            localSize = child.size,
                            localLastModifiedMs = child.lastModifiedMs,
                            localHash = preservedHash,
                            // Preserve remote columns so we don't lose sync state.
                            remoteId = existingEntry?.remoteId,
                            remoteETag = existingEntry?.remoteETag,
                            remoteSize = existingEntry?.remoteSize,
                            remoteLastModifiedMs = existingEntry?.remoteLastModifiedMs,
                            mimeType = child.mimeType,
                        )

                        scannedCount++
                        if (scannedCount % PROGRESS_INTERVAL == 0) {
                            emit(ScanProgress.Scanning(scannedCount))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "LocalFolderScanner: scan failed for pairId=%d uri=%s", pairId, treeUri)
            emit(ScanProgress.Failed(e.message ?: "Scan failed"))
            return@flow
        }

        // 3. Compute diff and apply to Room atomically.
        val seenPaths = scannedEntries.mapTo(ArrayList(scannedEntries.size)) { it.relativePath }

        // Only upsert entries that are new or whose local metadata changed.
        val toUpsert = scannedEntries.filter { scanned ->
            val old = existing[scanned.relativePath]
            old == null ||
                old.localSize != scanned.localSize ||
                old.localLastModifiedMs != scanned.localLastModifiedMs
        }

        // Single transactional reconcile: upsert changed entries and batch-delete stale ones.
        fileIndexDao.reconcileForPair(pairId, toUpsert, seenPaths)

        val added = toUpsert.count { it.relativePath !in existing }
        val updated = toUpsert.size - added
        val deleted = existing.keys.count { it !in seenPaths }
        emit(ScanProgress.Done(added = added, updated = updated, deleted = deleted))
    }.flowOn(Dispatchers.IO)
}
