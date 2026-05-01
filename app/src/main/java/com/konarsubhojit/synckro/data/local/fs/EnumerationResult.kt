package com.konarsubhojit.synckro.data.local.fs

/**
 * The output of a single [LocalFsEnumerator.enumerate] call.
 *
 * The snapshot and diff sets are computed atomically with respect to the
 * `local_index` table: a caller that collects this result and stores it into
 * the index in the same transaction will see a consistent view.
 *
 * @param snapshot  Full list of files discovered in this enumeration, after
 *                  hidden-file and ignore-glob filtering.  Directories are not
 *                  included.
 * @param added     Relative paths that are **new** — present in the filesystem
 *                  but absent from the previous `local_index`.
 * @param modified  Relative paths whose **size or mtime changed** since the
 *                  previous `local_index` snapshot.
 * @param deleted   Relative paths that were in `local_index` but are **no
 *                  longer present** on the filesystem (or are now filtered out).
 */
data class EnumerationResult(
    val snapshot: List<LocalFileEntry>,
    val added: Set<String>,
    val modified: Set<String>,
    val deleted: Set<String>,
)
