package com.konarsubhojit.synckro.domain.sync

import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.FileIndexEntry
import com.konarsubhojit.synckro.domain.model.SyncDirection

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

    data class UploadNew(override val relativePath: String) : SyncOp
    data class DownloadNew(override val relativePath: String) : SyncOp
    data class UpdateRemote(override val relativePath: String) : SyncOp
    data class UpdateLocal(override val relativePath: String) : SyncOp
    data class DeleteRemote(override val relativePath: String) : SyncOp
    data class DeleteLocal(override val relativePath: String) : SyncOp

    /**
     * Both sides changed since the last sync. The sync engine will further
     * reduce this to a concrete op using the pair's [ConflictPolicy].
     */
    data class Conflict(
        override val relativePath: String,
        val localNewerThanRemote: Boolean,
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
     * @return A list of SyncOp describing the operations to perform, in the order they were determined. */
    fun diff(
        local: Collection<FileSnapshot>,
        remote: Collection<FileSnapshot>,
        lastIndex: Collection<FileIndexEntry>,
        direction: SyncDirection,
        conflictPolicy: ConflictPolicy,
    ): List<SyncOp> {
        val localByPath = local.associateBy { it.relativePath }
        val remoteByPath = remote.associateBy { it.relativePath }
        val indexByPath = lastIndex.associateBy { it.relativePath }

        val allPaths = buildSet {
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
                if (direction != SyncDirection.REMOTE_TO_LOCAL) ops += SyncOp.UploadNew(path)
                continue
            }
            if (r != null && l == null && idx == null) {
                if (direction != SyncDirection.LOCAL_TO_REMOTE) ops += SyncOp.DownloadNew(path)
                continue
            }

            // Deletions
            if (localDeleted && remoteDeleted) continue // converged
            if (localDeleted && !remoteChanged) {
                ops += when (direction) {
                    SyncDirection.REMOTE_TO_LOCAL -> SyncOp.DownloadNew(path)
                    else -> SyncOp.DeleteRemote(path)
                }
                continue
            }
            if (remoteDeleted && !localChanged) {
                ops += when (direction) {
                    SyncDirection.LOCAL_TO_REMOTE -> SyncOp.UploadNew(path)
                    else -> SyncOp.DeleteLocal(path)
                }
                continue
            }

            // Modification on one side
            if (localChanged && !remoteChanged && r != null) {
                if (direction != SyncDirection.REMOTE_TO_LOCAL) ops += SyncOp.UpdateRemote(path)
                continue
            }
            if (remoteChanged && !localChanged && l != null) {
                if (direction != SyncDirection.LOCAL_TO_REMOTE) ops += SyncOp.UpdateLocal(path)
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
                val op = resolveModifyDeleteConflict(
                    path = path,
                    changedSide = ChangedSide.LOCAL,
                    policy = conflictPolicy,
                    direction = direction,
                )
                if (op != null) ops += op
                continue
            }
            if (remoteChanged && localDeleted) {
                val op = resolveModifyDeleteConflict(
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

        return ops
    }

    /**
     * Determines whether the local snapshot differs from the last-known local columns in the index.
     *
     * Compares `hash` values when both `snap.hash` and `idx.localHash` are non-null; otherwise compares `size` and `lastModifiedMs`.
     *
     * @param snap The current local file snapshot.
     * @param idx The last-known index entry for the same path.
     * @return `true` if the local snapshot differs from the index's local metadata, `false` otherwise.
     */
    private fun changed(snap: FileSnapshot, idx: FileIndexEntry): Boolean {
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
    private fun snapshotsEquivalent(a: FileSnapshot, b: FileSnapshot): Boolean {
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
    private fun changedRemote(snap: FileSnapshot, idx: FileIndexEntry): Boolean {
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
        local: FileSnapshot,
        remote: FileSnapshot,
        policy: ConflictPolicy,
        direction: SyncDirection,
    ): SyncOp? {
        return when (policy) {
            ConflictPolicy.PREFER_LOCAL ->
                if (direction != SyncDirection.REMOTE_TO_LOCAL) SyncOp.UpdateRemote(path) else null
            ConflictPolicy.PREFER_REMOTE ->
                if (direction != SyncDirection.LOCAL_TO_REMOTE) SyncOp.UpdateLocal(path) else null
            ConflictPolicy.NEWEST_WINS ->
                // On exact-tie timestamps we deterministically prefer local, since a
                // local edit is generally what the user most recently interacted with.
                if (local.lastModifiedMs >= remote.lastModifiedMs) {
                    if (direction != SyncDirection.REMOTE_TO_LOCAL) SyncOp.UpdateRemote(path) else null
                } else {
                    if (direction != SyncDirection.LOCAL_TO_REMOTE) SyncOp.UpdateLocal(path) else null
                }
            ConflictPolicy.KEEP_BOTH ->
                SyncOp.Conflict(path, localNewerThanRemote = local.lastModifiedMs >= remote.lastModifiedMs)
        }
    }

    private enum class ChangedSide { LOCAL, REMOTE }

    private fun resolveModifyDeleteConflict(
        path: String,
        changedSide: ChangedSide,
        policy: ConflictPolicy,
        direction: SyncDirection,
    ): SyncOp? {
        return when (policy) {
            ConflictPolicy.KEEP_BOTH ->
                SyncOp.Conflict(path, localNewerThanRemote = changedSide == ChangedSide.LOCAL)
            ConflictPolicy.PREFER_LOCAL ->
                if (changedSide == ChangedSide.LOCAL) {
                    if (direction != SyncDirection.REMOTE_TO_LOCAL) SyncOp.UploadNew(path) else null
                } else {
                    if (direction != SyncDirection.REMOTE_TO_LOCAL) SyncOp.DeleteRemote(path) else null
                }
            ConflictPolicy.PREFER_REMOTE ->
                if (changedSide == ChangedSide.LOCAL) {
                    if (direction != SyncDirection.LOCAL_TO_REMOTE) SyncOp.DeleteLocal(path) else null
                } else {
                    if (direction != SyncDirection.LOCAL_TO_REMOTE) SyncOp.DownloadNew(path) else null
                }
            ConflictPolicy.NEWEST_WINS ->
                if (changedSide == ChangedSide.LOCAL) {
                    if (direction != SyncDirection.REMOTE_TO_LOCAL) SyncOp.UploadNew(path) else null
                } else {
                    if (direction != SyncDirection.LOCAL_TO_REMOTE) SyncOp.DownloadNew(path) else null
                }
        }
    }
}
