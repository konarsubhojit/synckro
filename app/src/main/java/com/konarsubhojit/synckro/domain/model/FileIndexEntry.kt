package com.konarsubhojit.synckro.domain.model

/**
 * Snapshot of a file's metadata, as known by the sync engine after the last
 * successful sync. Paths are relative (POSIX-style) to the sync pair root.
 */
data class FileIndexEntry(
    val pairId: Long,
    val relativePath: String,
    val localSize: Long,
    val localLastModifiedMs: Long,
    /** Optional content hash (e.g. SHA-256 or the provider's quickXorHash). */
    val localHash: String? = null,
    val remoteId: String? = null,
    /** Provider-specific opaque version tag (ETag / cTag). */
    val remoteETag: String? = null,
    val remoteSize: Long? = null,
    val remoteLastModifiedMs: Long? = null,
    /** MIME type of the local file (e.g. "text/plain", "image/jpeg"). Null for directories. */
    val mimeType: String? = null,
) {
    val isDirectory: Boolean get() = relativePath.endsWith('/')
}
