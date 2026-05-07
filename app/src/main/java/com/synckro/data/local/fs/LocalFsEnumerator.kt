package com.synckro.data.local.fs

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.data.scanner.DefaultDocumentChildrenQuery
import com.synckro.data.scanner.DocumentChildrenQuery
import com.synckro.data.scanner.RawDocChild
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Callable that opens a read [InputStream] for a document inside a SAF tree.
 *
 * Extracted as a functional interface so [LocalFsEnumerator] can be unit-tested
 * on the JVM without a live ContentProvider.
 *
 * @see DefaultFsAccess
 */
internal fun interface FsAccess {
    /**
     * Opens a read stream for the document identified by [docId] inside [treeUri].
     *
     * @param treeUri Root SAF tree URI (from `ACTION_OPEN_DOCUMENT_TREE`).
     * @param docId   DocumentsContract document ID of the target file.
     * @return An [InputStream] for the file content, or `null` if the file
     *         cannot be opened (e.g. permission revoked or I/O error).
     */
    fun openInputStream(
        treeUri: Uri,
        docId: String,
    ): InputStream?
}

/** Production [FsAccess] backed by [ContentResolver]. */
internal class DefaultFsAccess(
    private val resolver: ContentResolver,
) : FsAccess {
    override fun openInputStream(
        treeUri: Uri,
        docId: String,
    ): InputStream? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return try {
            resolver.openInputStream(docUri)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Walks a SAF document tree and produces a diffed snapshot suitable for
 * persisting to the `local_index` table and for diffing against remote state.
 *
 * **Features**
 * - BFS tree walk via [DocumentChildrenQuery] (avoids stack overflow on deep trees).
 * - Skips hidden files (display name starts with `.`).
 * - Supports **include-glob filtering**: when [LocalFsEnumerator.enumerate]'s
 *   `includeGlobs` list is non-empty, only files matching **at least one** include
 *   pattern are retained.  When the list is empty, all files pass the include filter
 *   (preserves previous behaviour).
 * - Skips files matching any pattern in [LocalFsEnumerator.enumerate]'s
 *   `ignoreGlobs` list (patterns matched against the full relative path;
 *   `*` matches within a single path component, `**` matches across separators,
 *   `?` matches a single non-separator character, `{a,b}` matches alternatives).
 *   Exclude patterns always take precedence over include matches.
 * - **Lazy SHA-256**: the hash is recomputed (via [FsAccess]) only when a file's
 *   `(sizeBytes, mtimeMs)` differ from the cached `local_index` row.  Unchanged
 *   files reuse the cached hash with zero I/O cost.
 * - Files that cannot be read (stream open fails) are skipped and a WARN log is
 *   emitted; the file still appears in the snapshot with `contentHash = null`.
 * - If a directory cannot be queried (e.g. SAF permission revoked), a
 *   [LocalStorageException] is thrown so the sync can abort safely rather than
 *   treating the unreadable subtree as empty.
 * - Atomically reconciles the `local_index` table: upserts changed entries and
 *   deletes stale ones in a single Room transaction.
 *
 * **Threading**: [enumerate] is a `suspend` function and must be called on a
 * coroutine.  All Room and I/O work is performed inline; callers should dispatch
 * to [kotlinx.coroutines.Dispatchers.IO].
 */
@Singleton
class LocalFsEnumerator internal constructor(
    private val resolver: ContentResolver,
    private val localIndexDao: LocalIndexDao,
    private val childrenQuery: DocumentChildrenQuery,
    private val fsAccess: FsAccess,
) {
    /** Hilt-injected production constructor. */
    @Inject
    constructor(
        @ApplicationContext context: Context,
        localIndexDao: LocalIndexDao,
    ) : this(
        resolver = context.contentResolver,
        localIndexDao = localIndexDao,
        childrenQuery = DefaultDocumentChildrenQuery,
        fsAccess = DefaultFsAccess(context.contentResolver),
    )

    /**
     * Enumerates the SAF tree at [treeUri] for sync pair [pairId], computing the
     * diff against the current `local_index` and atomically persisting the result.
     *
     * @param pairId       The [com.synckro.domain.model.SyncPair.id]
     *                     this enumeration belongs to.
     * @param treeUri      Persisted SAF tree URI from `ACTION_OPEN_DOCUMENT_TREE`.
     * @param includeGlobs Optional list of glob patterns.  When non-empty, only
     *                     files matching **at least one** pattern are included in
     *                     the snapshot.  An empty list means "include everything"
     *                     (the default).
     * @param ignoreGlobs  Optional list of glob patterns (matched against the full
     *                     relative path).  Files matching **any** pattern are
     *                     excluded from the snapshot and treated as deleted from
     *                     `local_index` if they were previously indexed.  Exclude
     *                     patterns take precedence over include matches.
     * @param excludeSubfolders When `true`, only files at the immediate root of the
     *                     SAF tree are enumerated — sub-directories are not traversed.
     *                     Combine with [ignoreGlobs] for fine-grained filtering.
     * @return [EnumerationResult] containing the full snapshot and diff sets.
     */
    suspend fun enumerate(
        pairId: Long,
        treeUri: Uri,
        includeGlobs: List<String> = emptyList(),
        ignoreGlobs: List<String> = emptyList(),
        excludeSubfolders: Boolean = false,
    ): EnumerationResult {
        // 1. Load existing local_index as a map for O(1) lookup.
        val cached: Map<String, LocalIndexEntity> =
            localIndexDao.getForPair(pairId).associateBy { it.relativePath }

        // Pre-compile ignore-glob patterns for efficiency.
        val compiledIgnoreGlobs =
            ignoreGlobs.mapNotNull { pattern ->
                try {
                    globToRegex(pattern)
                } catch (_: Exception) {
                    Timber.w("LocalFsEnumerator: invalid ignore glob '%s', skipping", pattern)
                    null
                }
            }

        // Pre-compile include-glob patterns for efficiency.
        val compiledIncludeGlobs =
            includeGlobs.mapNotNull { pattern ->
                try {
                    globToRegex(pattern)
                } catch (_: Exception) {
                    Timber.w("LocalFsEnumerator: invalid include glob '%s', skipping", pattern)
                    null
                }
            }

        val snapshot = mutableListOf<LocalFileEntry>()

        // 2. BFS walk of the SAF document tree.
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        // Queue: (documentId, relativePathPrefix)
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootDocId to "")

        while (queue.isNotEmpty()) {
            val (parentDocId, prefix) = queue.removeFirst()
            val children: List<RawDocChild> =
                try {
                    childrenQuery(resolver, treeUri, parentDocId)
                } catch (e: Exception) {
                    Timber.w(
                        e,
                        "LocalFsEnumerator: failed to list children of docId='%s'",
                        parentDocId,
                    )
                    throw LocalStorageException(
                        "SAF permission denied or storage unavailable for docId='$parentDocId'",
                        e,
                    )
                }

            for (child in children) {
                val relativePath =
                    if (prefix.isEmpty()) child.name else "$prefix/${child.name}"

                if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    // When excludeSubfolders is enabled, skip traversing into any
                    // subdirectories so only root-level files are enumerated.
                    if (!excludeSubfolders) {
                        queue.add(child.docId to relativePath)
                    }
                    continue
                }

                // Skip hidden files.
                if (child.name.startsWith('.')) {
                    Timber.d("LocalFsEnumerator: skipping hidden file '%s'", relativePath)
                    continue
                }

                // Skip files not matching any include glob (when include globs are configured).
                // Use `includeGlobs.isNotEmpty()` (the original list) rather than
                // `compiledIncludeGlobs.isNotEmpty()` so that a misconfiguration where
                // all patterns fail to compile still activates the filter (fail-closed:
                // no file passes a broken include glob rather than all files passing).
                if (includeGlobs.isNotEmpty() && compiledIncludeGlobs.none { it.matches(relativePath) }) {
                    continue
                }

                // Skip files matching any ignore glob (excludes take precedence over includes).
                if (compiledIgnoreGlobs.any { it.matches(relativePath) }) {
                    Timber.d("LocalFsEnumerator: skipping ignored file '%s'", relativePath)
                    continue
                }

                // 3. Determine content hash (lazy: reuse cache when size+mtime unchanged).
                val cachedEntry = cached[relativePath]
                val contentHash =
                    resolveHash(
                        treeUri = treeUri,
                        docId = child.docId,
                        relativePath = relativePath,
                        newSize = child.size,
                        newMtime = child.lastModifiedMs,
                        cached = cachedEntry,
                    )

                snapshot +=
                    LocalFileEntry(
                        relativePath = relativePath,
                        sizeBytes = child.size,
                        mtimeMs = child.lastModifiedMs,
                        contentHash = contentHash,
                    )
            }
        }

        // 4. Compute diff vs. local_index.
        val snapshotPaths = snapshot.mapTo(HashSet(snapshot.size)) { it.relativePath }

        val added = mutableSetOf<String>()
        val modified = mutableSetOf<String>()
        for (entry in snapshot) {
            val old = cached[entry.relativePath]
            when {
                old == null -> added += entry.relativePath
                old.sizeBytes != entry.sizeBytes || old.mtimeMs != entry.mtimeMs ->
                    modified += entry.relativePath
            }
        }
        val deleted = cached.keys.filter { it !in snapshotPaths }.toSet()

        // 5. Atomically persist: upsert changed entries, delete stale ones.
        val toUpsert =
            snapshot
                .filter { it.relativePath in added || it.relativePath in modified }
                .map { entry ->
                    val existing = cached[entry.relativePath]
                    LocalIndexEntity(
                        pairId = pairId,
                        relativePath = entry.relativePath,
                        sizeBytes = entry.sizeBytes,
                        mtimeMs = entry.mtimeMs,
                        contentHash = entry.contentHash,
                        remoteId = existing?.remoteId,
                        // Preserve remote metadata so that a local-mtime-only change does not
                        // make the file disappear from syntheticRemote and trigger a spurious
                        // re-upload or remote-deletion on the next sync run.  Remote metadata
                        // is only refreshed by SyncOpApplier after a real remote operation.
                        remoteSizeBytes = existing?.remoteSizeBytes,
                        remoteMtimeMs = existing?.remoteMtimeMs,
                        remoteEtag = existing?.remoteEtag,
                    )
                }
        localIndexDao.reconcileForPair(pairId, toUpsert, snapshotPaths.toList())

        return EnumerationResult(
            snapshot = snapshot,
            added = added,
            modified = modified,
            deleted = deleted,
        )
    }

    /**
     * Returns the SHA-256 hash to use for a file:
     * - If size **and** mtime are unchanged vs. [cached], reuse [LocalIndexEntity.contentHash].
     * - Otherwise (new file or metadata changed), open the file and compute SHA-256.
     *   On failure (unreadable), returns `null` and emits a WARN log.
     */
    private fun resolveHash(
        treeUri: Uri,
        docId: String,
        relativePath: String,
        newSize: Long,
        newMtime: Long,
        cached: LocalIndexEntity?,
    ): String? {
        if (cached != null &&
            cached.sizeBytes == newSize &&
            cached.mtimeMs == newMtime &&
            cached.contentHash != null
        ) {
            return cached.contentHash
        }
        // Compute fresh hash.
        return try {
            fsAccess.openInputStream(treeUri, docId)?.use { stream -> sha256Hex(stream) }
        } catch (e: Exception) {
            Timber.w(e, "LocalFsEnumerator: cannot hash '%s', skipping hash", relativePath)
            null
        }.also { hash ->
            if (hash == null) {
                Timber.w("LocalFsEnumerator: no read access for '%s'", relativePath)
            }
        }
    }

    companion object {
        /**
         * Computes the SHA-256 digest of [stream] and returns it as a lowercase hex string.
         * The caller is responsible for closing the stream; this function does not close it.
         */
        internal fun sha256Hex(stream: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            var read = stream.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = stream.read(buffer)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Converts a glob pattern to a [Regex] that can be matched against a full
         * relative file path.
         *
         * Supported wildcards:
         * - `**`  matches any sequence of characters including `/`
         * - `*`   matches any sequence of characters **not** including `/`
         * - `?`   matches a single character **not** including `/`
         * - `{a,b}` matches either alternative `a` or `b`
         * - `[abc]` character classes are passed through as-is
         *
         * The match is case-sensitive on all platforms.
         */
        internal fun globToRegex(glob: String): Regex {
            val sb = StringBuilder("^")
            var i = 0
            while (i < glob.length) {
                when (val c = glob[i]) {
                    '*' -> {
                        if (i + 1 < glob.length && glob[i + 1] == '*') {
                            sb.append(".*")
                            i++ // consume second '*'
                        } else {
                            sb.append("[^/]*")
                        }
                    }
                    '?' -> sb.append("[^/]")
                    '.' -> sb.append("\\.")
                    '{' -> {
                        val end = glob.indexOf('}', i + 1)
                        if (end == -1) {
                            sb.append(Regex.escape(c.toString()))
                        } else {
                            val alternatives = glob.substring(i + 1, end).split(',')
                            sb.append("(?:")
                            alternatives.joinTo(sb, "|") { Regex.escape(it) }
                            sb.append(')')
                            i = end // will be incremented below
                        }
                    }
                    '[' -> {
                        val end = glob.indexOf(']', i + 1)
                        if (end == -1) {
                            sb.append(Regex.escape(c.toString()))
                        } else {
                            sb.append('[')
                            sb.append(glob.substring(i + 1, end))
                            sb.append(']')
                            i = end // will be incremented below
                        }
                    }
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            sb.append('$')
            return Regex(sb.toString())
        }
    }
}
