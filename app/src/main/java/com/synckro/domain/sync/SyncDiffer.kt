package com.synckro.domain.sync

import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.FileIndexEntry
import com.synckro.domain.model.SyncDirection

/**
 * Minimal metadata describing a file on one side, used as input to [SyncDiffer].
 * [hash] is optional; when null, equality falls back to (size, lastModifiedMs).
 */
data class FileSnapshot(
    val relativePath: String,
    val size: Long,
    val lastModifiedMs: Long,
    val hash: String? = null,
)

/**
 * A single operation the sync engine should apply to reconcile a pair of
 * folders. Paths are relative to the sync-pair root.
 */
sealed interface SyncOp {
    val relativePath: String

    data class UploadNew(
        override val relativePath: String,
    ) : SyncOp

    data class DownloadNew(
        override val relativePath: String,
    ) : SyncOp

    data class UpdateRemote(
        override val relativePath: String,
    ) : SyncOp

    data class UpdateLocal(
        override val relativePath: String,
    ) : SyncOp

    data class DeleteRemote(
        override val relativePath: String,
    ) : SyncOp

    data class DeleteLocal(
        override val relativePath: String,
    ) : SyncOp

    /**
     * Both sides changed since the last sync. The sync engine will further
     * reduce this to a concrete op using the pair's [ConflictPolicy].
     */
    data class Conflict(
        override val relativePath: String,
        val localNewerThanRemote: Boolean,
    ) : SyncOp

    /**
     * Delete the local copy after it has been confirmed on the remote and the
     * configured retention period has elapsed. Generated only in
     * [SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS] mode.
     */
    data class DeleteLocalRetention(
        override val relativePath: String,
    ) : SyncOp

    /**
     * Delete the remote copy after it has been confirmed on the local side and
     * the configured retention period has elapsed. Generated only in
     * [SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS] mode.
     */
    data class DeleteRemoteRetention(
        override val relativePath: String,
    ) : SyncOp
}

/**
 * Pure function from (local state, remote state, last-known index) to a list
 * of operations. Has no Android or IO dependencies, and is the unit of logic
 * covered by unit tests.
 */
object SyncDiffer {
    /**
     * Compute the ordered list of reconciliation operations needed to bring local and remote file sets
     * into agreement with each other and the provided baseline index, respecting the sync direction and
     * conflict resolution policy.
     *
     * @param local Collection of FileSnapshot for the local side.
     * @param remote Collection of FileSnapshot for the remote side.
     * @param lastIndex Collection of FileIndexEntry representing the last-known baseline index.
     * @param direction Controls which directions of change are permitted (e.g., disallowing remote->local).
     * @param conflictPolicy Strategy used to resolve concurrent modifications when both sides changed.
     * @param retentionDays Number of days after which source files are eligible for retention-based
     *   deletion in [SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS] and
     *   [SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS] modes. `null` disables retention
     *   deletion even in those modes.
     * @param nowMs Current epoch-milliseconds timestamp used for retention age calculations.
     *   Defaults to [System.currentTimeMillis]; override in tests for deterministic behaviour.
     * @return A list of SyncOp describing the operations to perform, in the order they were determined. */
    fun diff(
        local: Collection<FileSnapshot>,
        remote: Collection<FileSnapshot>,
        lastIndex: Collection<FileIndexEntry>,
        direction: SyncDirection,
        conflictPolicy: ConflictPolicy,
        retentionDays: Int? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): List<SyncOp> {
        val localByPath = local.associateBy { it.relativePath }
        val remoteByPath = remote.associateBy { it.relativePath }
        val indexByPath = lastIndex.associateBy { it.relativePath }

        val allPaths =
            buildSet {
                addAll(localByPath.keys)
                addAll(remoteByPath.keys)
                addAll(indexByPath.keys)
            }

        val ops = mutableListOf<SyncOp>()

        for (path in allPaths) {
            val l = localByPath[path]
            val r = remoteByPath[path]
            val idx = indexByPath[path]

            // Initial sync (no index) with a path that already exists on both
            // sides: if the snapshots are equivalent, treat as a no-op so the
            // engine can just persist the baseline index; otherwise fall
            // through to conflict resolution.
            if (idx == null && l != null && r != null) {
                if (snapshotsEquivalent(l, r)) continue
                val op = resolveConflict(path, l, r, conflictPolicy, direction)
                if (op != null) ops += op
                continue
            }

            val localChanged = l != null && (idx == null || changed(l, idx))
            val remoteChanged = r != null && (idx == null || changedRemote(r, idx))
            val localDeleted = l == null && idx != null
            val remoteDeleted = r == null && idx != null

            // New on one side only
            if (l != null && r == null && idx == null) {
                if (direction.allowsUpload()) ops += SyncOp.UploadNew(path)
                continue
            }
            if (r != null && l == null && idx == null) {
                if (direction.allowsDownload()) ops += SyncOp.DownloadNew(path)
                continue
            }

            // Deletions
            if (localDeleted && remoteDeleted) continue // converged
            if (localDeleted && !remoteChanged) {
                when (direction) {
                    SyncDirection.REMOTE_TO_LOCAL,
                    SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
                    -> ops += SyncOp.DownloadNew(path)
                    // In upload-backup mode the local file may have been deleted by the
                    // retention process or by the user; keep the remote as backup.
                    SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS -> Unit
                    else -> ops += SyncOp.DeleteRemote(path)
                }
                continue
            }
            if (remoteDeleted && !localChanged) {
                when (direction) {
                    SyncDirection.LOCAL_TO_REMOTE,
                    SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
                    -> ops += SyncOp.UploadNew(path)
                    // In download-offload mode the remote file may have been deleted by the
                    // retention process; keep the local copy.
                    SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS -> Unit
                    else -> ops += SyncOp.DeleteLocal(path)
                }
                continue
            }

            // Modification on one side
            if (localChanged && !remoteChanged && r != null) {
                if (direction.allowsUpload()) ops += SyncOp.UpdateRemote(path)
                continue
            }
            if (remoteChanged && !localChanged && l != null) {
                if (direction.allowsDownload()) ops += SyncOp.UpdateLocal(path)
                continue
            }

            // Both changed → conflict
            if (localChanged && remoteChanged) {
                // When both sides changed, both snapshots are present by definition.
                val op = resolveConflict(path, l, r, conflictPolicy, direction)
                if (op != null) ops += op
                continue
            }

            // Local changed, remote deleted (or vice versa) → conflict too
            if (localChanged && remoteDeleted) {
                val op =
                    resolveModifyDeleteConflict(
                        path = path,
                        changedSide = ChangedSide.LOCAL,
                        policy = conflictPolicy,
                        direction = direction,
                    )
                if (op != null) ops += op
                continue
            }
            if (remoteChanged && localDeleted) {
                val op =
                    resolveModifyDeleteConflict(
                        path = path,
                        changedSide = ChangedSide.REMOTE,
                        policy = conflictPolicy,
                        direction = direction,
                    )
                if (op != null) ops += op
                continue
            }
            // Otherwise: no-op (both sides equal to index).
        }

        // -------------------------------------------------------------------------
        // Retention-cleanup pass
        //
        // After the main diff loop, check for files that have been confirmed synced
        // (present in lastIndex with a non-null remoteId) and whose source-side
        // last-modified time is older than the configured retention threshold.
        //
        // Rules:
        // • Only runs when retentionDays is non-null.
        // • Only for files that are STILL present on the source side (to avoid
        //   double-counting files already covered by the main delete logic).
        // • Skips files that already have a pending op in this diff to avoid
        //   generating conflicting operations for the same path.
        // -------------------------------------------------------------------------
        if (retentionDays != null) {
            val thresholdMs = retentionDays.toLong() * 24L * 60L * 60L * 1000L
            val pathsWithOps = ops.mapTo(mutableSetOf()) { it.relativePath }

            when (direction) {
                SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS -> {
                    for ((path, idx) in indexByPath) {
                        if (idx.remoteId == null) continue
                        val localSnap = localByPath[path] ?: continue
                        if (path in pathsWithOps) continue
                        if ((nowMs - localSnap.lastModifiedMs) >= thresholdMs) {
                            ops += SyncOp.DeleteLocalRetention(path)
                        }
                    }
                }
                SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS -> {
                    for ((path, idx) in indexByPath) {
                        if (idx.remoteId == null) continue
                        val remoteSnap = remoteByPath[path] ?: continue
                        if (path in pathsWithOps) continue
                        if ((nowMs - remoteSnap.lastModifiedMs) >= thresholdMs) {
                            ops += SyncOp.DeleteRemoteRetention(path)
                        }
                    }
                }
                else -> Unit // retention only applies to the two dedicated modes
            }
        }

        return ops
    }

    /**
     * Returns `true` when this direction permits upload operations (local → remote).
     * The download-only modes suppress uploads; all other modes allow them.
     */
    private fun SyncDirection.allowsUpload(): Boolean =
        this != SyncDirection.REMOTE_TO_LOCAL &&
            this != SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS

    /**
     * Returns `true` when this direction permits download operations (remote → local).
     * The upload-only modes suppress downloads; all other modes allow them.
     */
    private fun SyncDirection.allowsDownload(): Boolean =
        this != SyncDirection.LOCAL_TO_REMOTE &&
            this != SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS

    /**
     * Determines whether the local snapshot differs from the last-known local columns in the index.
     *
     * Compares `hash` values when both `snap.hash` and `idx.localHash` are non-null; otherwise compares `size` and `lastModifiedMs`.
     *
     * @param snap The current local file snapshot.
     * @param idx The last-known index entry for the same path.
     * @return `true` if the local snapshot differs from the index's local metadata, `false` otherwise.
     */
    private fun changed(
        snap: FileSnapshot,
        idx: FileIndexEntry,
    ): Boolean {
        if (snap.hash != null && idx.localHash != null) return snap.hash != idx.localHash
        return snap.size != idx.localSize || snap.lastModifiedMs != idx.localLastModifiedMs
    }

    /**
     * Determines whether two snapshots for the same path represent identical content.
     *
     * When both snapshots include a hash, equality is based on the hash; otherwise it is
     * based on matching size and lastModifiedMs.
     *
     * @return `true` if the snapshots represent the same content, `false` otherwise.
     */
    private fun snapshotsEquivalent(
        a: FileSnapshot,
        b: FileSnapshot,
    ): Boolean {
        if (a.hash != null && b.hash != null) return a.hash == b.hash
        return a.size == b.size && a.lastModifiedMs == b.lastModifiedMs
    }

    /**
     * Determines whether the remote snapshot differs from the remote columns in the index.
     *
     * @param snap The remote-side FileSnapshot to compare.
     * @param idx The FileIndexEntry whose remote metadata columns are used for comparison.
     * @return `true` if the index lacks remote size or mtime, or if `snap.size` or `snap.lastModifiedMs` differ from the index's remote values; `false` otherwise.
     */
    private fun changedRemote(
        snap: FileSnapshot,
        idx: FileIndexEntry,
    ): Boolean {
        // For the remote side we compare against the remote columns of the index.
        val idxSize = idx.remoteSize ?: return true
        val idxMtime = idx.remoteLastModifiedMs ?: return true
        return snap.size != idxSize || snap.lastModifiedMs != idxMtime
    }

    /**
     * Resolve a two-sided content conflict into a concrete reconciliation operation using the given policy and sync direction.
     *
     * @param path The file's relative path within the sync roots.
     * @param local The local side's file snapshot.
     * @param remote The remote side's file snapshot.
     * @param policy The conflict resolution policy to apply.
     * @param direction The configured sync direction which may forbid certain operations.
     * @return A `SyncOp` describing the chosen action, or `null` if the resolved action would be disallowed by `direction`.
     */
    private fun resolveConflict(
        path: String,
        local: FileSnapshot?,
        remote: FileSnapshot?,
        policy: ConflictPolicy,
        direction: SyncDirection,
    ): SyncOp? =
        when (policy) {
            ConflictPolicy.PREFER_LOCAL ->
                if (direction.allowsUpload()) SyncOp.UpdateRemote(path) else null
            ConflictPolicy.PREFER_REMOTE ->
                if (direction.allowsDownload()) SyncOp.UpdateLocal(path) else null
            ConflictPolicy.NEWEST_WINS ->
                // On exact-tie timestamps we deterministically prefer local, since a
                // local edit is generally what the user most recently interacted with.
                if ((local?.lastModifiedMs ?: 0L) >= (remote?.lastModifiedMs ?: 0L)) {
                    if (direction.allowsUpload()) SyncOp.UpdateRemote(path) else null
                } else {
                    if (direction.allowsDownload()) SyncOp.UpdateLocal(path) else null
                }
            ConflictPolicy.KEEP_BOTH ->
                SyncOp.Conflict(path, localNewerThanRemote = (local?.lastModifiedMs ?: 0L) >= (remote?.lastModifiedMs ?: 0L))
        }

    private enum class ChangedSide { LOCAL, REMOTE }

    private fun resolveModifyDeleteConflict(
        path: String,
        changedSide: ChangedSide,
        policy: ConflictPolicy,
        direction: SyncDirection,
    ): SyncOp? =
        when (policy) {
            ConflictPolicy.KEEP_BOTH ->
                SyncOp.Conflict(path, localNewerThanRemote = changedSide == ChangedSide.LOCAL)
            ConflictPolicy.PREFER_LOCAL ->
                if (changedSide == ChangedSide.LOCAL) {
                    if (direction.allowsUpload()) SyncOp.UploadNew(path) else null
                } else {
                    if (direction.allowsUpload()) SyncOp.DeleteRemote(path) else null
                }
            ConflictPolicy.PREFER_REMOTE ->
                if (changedSide == ChangedSide.LOCAL) {
                    if (direction.allowsDownload()) SyncOp.DeleteLocal(path) else null
                } else {
                    if (direction.allowsDownload()) SyncOp.DownloadNew(path) else null
                }
            ConflictPolicy.NEWEST_WINS ->
                if (changedSide == ChangedSide.LOCAL) {
                    if (direction.allowsUpload()) SyncOp.UploadNew(path) else null
                } else {
                    if (direction.allowsDownload()) SyncOp.DownloadNew(path) else null
                }
        }
}
