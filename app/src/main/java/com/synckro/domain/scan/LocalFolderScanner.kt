package com.synckro.domain.scan

import android.net.Uri
import kotlinx.coroutines.flow.Flow

/**
 * Scans a SAF document tree and reconciles the result with the Room file index.
 *
 * Implementations must:
 * - Walk the tree using DocumentsContract for performance.
 * - Emit [ScanProgress.Scanning] updates at regular intervals so callers can
 *   display progress.
 * - Perform an incremental update: only insert/update entries whose metadata
 *   (size or last-modified) has changed; delete entries no longer present on disk.
 * - Preserve any existing `localHash` (SHA-256) on entries whose size and
 *   last-modified timestamp are unchanged (lazy hash strategy).
 * - Run IO work off the main thread.
 */
interface LocalFolderScanner {

    companion object {
        /** Number of files scanned between consecutive [ScanProgress.Scanning] emissions. */
        const val PROGRESS_INTERVAL = 50
    }

    /**
     * Walks the SAF tree identified by [treeUri] and upserts discovered files
     * into the Room index for [pairId].  The returned [Flow] is cold; collect it
     * on a background scope or use [kotlinx.coroutines.flow.flowOn].
     *
     * @param pairId  The [com.synckro.domain.model.SyncPair.id] this
     *                scan belongs to.  A matching row must exist in the `sync_pair`
     *                table before collecting.
     * @param treeUri A persisted SAF tree URI obtained from `ACTION_OPEN_DOCUMENT_TREE`.
     */
    fun scan(pairId: Long, treeUri: Uri): Flow<ScanProgress>
}
