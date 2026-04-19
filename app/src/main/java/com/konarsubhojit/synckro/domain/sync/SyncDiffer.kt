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
                if (direction != SyncDirection.REMOTE_TO_LOCAL) ops += SyncOp.DeleteRemote(path)
                continue
            }
            if (remoteDeleted && !localChanged) {
                if (direction != SyncDirection.LOCAL_TO_REMOTE) ops += SyncOp.DeleteLocal(path)
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
                // Treat as a conflict with "remote side missing".
                ops += SyncOp.Conflict(path, localNewerThanRemote = true)
                continue
            }
            if (remoteChanged && localDeleted) {
                ops += SyncOp.Conflict(path, localNewerThanRemote = false)
                continue
            }
            // Otherwise: no-op (both sides equal to index).
        }

        return ops
    }

    private fun changed(snap: FileSnapshot, idx: FileIndexEntry): Boolean {
        if (snap.hash != null && idx.localHash != null) return snap.hash != idx.localHash
        return snap.size != idx.localSize || snap.lastModifiedMs != idx.localLastModifiedMs
    }

    private fun changedRemote(snap: FileSnapshot, idx: FileIndexEntry): Boolean {
        // For the remote side we compare against the remote columns of the index.
        val idxSize = idx.remoteSize ?: return true
        val idxMtime = idx.remoteLastModifiedMs ?: return true
        return snap.size != idxSize || snap.lastModifiedMs != idxMtime
    }

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
}
