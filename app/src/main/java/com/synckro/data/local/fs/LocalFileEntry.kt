package com.synckro.data.local.fs

/**
 * A lightweight snapshot of a single local file discovered during a
 * [LocalFsEnumerator] tree walk.
 *
 * Paths are relative (POSIX-style, forward-slash separated) to the sync-pair
 * root, e.g. `"photos/vacation.jpg"` or `"Ñoño/émoji 🎉.txt"`.
 *
 * @param relativePath POSIX-style path relative to the sync-pair root.
 * @param sizeBytes    File size in bytes as reported by the SAF content provider.
 * @param mtimeMs      Last-modified timestamp in epoch milliseconds.
 * @param contentHash  Lowercase hex-encoded SHA-256 digest, or `null` when the
 *                     file has not been hashed (e.g. not yet opened or access was
 *                     denied).
 */
data class LocalFileEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val mtimeMs: Long,
    val contentHash: String? = null,
)
