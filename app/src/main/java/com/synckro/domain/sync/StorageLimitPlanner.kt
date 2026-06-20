package com.synckro.domain.sync

import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.domain.provider.RemoteFile

/**
 * Plans which remote-to-local download operations are allowed given a per-pair
 * local storage limit.
 *
 * This is a **pure function** object with no I/O dependencies, intentionally
 * kept platform-free so it can be unit-tested on the JVM without any Android
 * dependencies.
 *
 * ### Accounting rules
 * - Only [SyncOp.DownloadNew] and [SyncOp.UpdateLocal] are considered; all
 *   other ops pass through unchanged.
 * - When no limit is configured ([limitBytes] is `null`) the input list is
 *   returned as-is.
 * - Download ops are evaluated in **relative-path ascending** order so that
 *   decisions are stable and deterministic across runs.
 * - For a [SyncOp.DownloadNew]: `projected = currentUsage + remote.size`.
 * - For a [SyncOp.UpdateLocal]: `projected = currentUsage - existingLocalSize + remote.size`
 *   to avoid double-counting the file being replaced.  If the existing local
 *   size is unavailable the remote size alone is added (safe over-estimate).
 * - If `RemoteFile.size` is `null` and a limit is active the file is skipped
 *   with [SkippedDownload.Reason.UNKNOWN_SIZE].
 * - Non-download ops retain their original relative order and are interleaved
 *   between the sorted download ops in a stable way (non-download ops come
 *   after the sorted download batch, preserving their own original order).
 */
object StorageLimitPlanner {
    /** Outcome of a planning pass for a [SyncOp.DownloadNew] or [SyncOp.UpdateLocal] that was
     *  skipped because of the configured storage cap. */
    data class SkippedDownload(
        val relativePath: String,
        val reason: Reason,
        /** Remote file size, or `null` when the size was unknown. */
        val sizeBytes: Long?,
    ) {
        enum class Reason {
            /** The download would cause local synced content to exceed [limitBytes]. */
            WOULD_EXCEED_LIMIT,

            /** [RemoteFile.size] was `null` so the impact could not be assessed. */
            UNKNOWN_SIZE,
        }
    }

    /** The result of a single [plan] call. */
    data class Plan(
        /** Ops that are safe to apply (non-download ops are always included). */
        val allowedOps: List<SyncOp>,
        /** Download ops that were skipped by the planner. */
        val skipped: List<SkippedDownload>,
    )

    /**
     * Partitions [ops] into allowed and skipped based on the local storage limit.
     *
     * @param ops                  Full op list produced by [SyncDiffer].
     * @param remoteFilesByPath    Remote-file metadata keyed by relative path.
     * @param localIndexByPath     Current local index keyed by relative path
     *                             (used to subtract existing file sizes for [SyncOp.UpdateLocal]).
     * @param currentLocalUsageBytes Sum of all local file sizes already in the sync folder.
     * @param limitBytes           The configured limit, or `null` to disable enforcement.
     */
    fun plan(
        ops: List<SyncOp>,
        remoteFilesByPath: Map<String, RemoteFile>,
        localIndexByPath: Map<String, LocalIndexEntity>,
        currentLocalUsageBytes: Long,
        limitBytes: Long?,
    ): Plan {
        // Fast-path: no limit configured — return everything as-is.
        if (limitBytes == null) return Plan(allowedOps = ops, skipped = emptyList())

        val downloadOps = mutableListOf<SyncOp>()
        val nonDownloadOps = mutableListOf<SyncOp>()
        for (op in ops) {
            if (op is SyncOp.DownloadNew || op is SyncOp.UpdateLocal) {
                downloadOps += op
            } else {
                nonDownloadOps += op
            }
        }

        // Evaluate downloads in stable relative-path order.
        downloadOps.sortWith(compareBy { it.relativePath })

        val allowed = mutableListOf<SyncOp>()
        val skipped = mutableListOf<SkippedDownload>()
        var projectedUsage = currentLocalUsageBytes

        for (op in downloadOps) {
            val remote = remoteFilesByPath[op.relativePath]
            val remoteSize = remote?.size

            if (remoteSize == null) {
                skipped +=
                    SkippedDownload(
                        relativePath = op.relativePath,
                        reason = SkippedDownload.Reason.UNKNOWN_SIZE,
                        sizeBytes = null,
                    )
                continue
            }

            // For UpdateLocal, subtract the existing local file size to avoid
            // double-counting the file being replaced.
            val existingLocalSize: Long =
                if (op is SyncOp.UpdateLocal) {
                    localIndexByPath[op.relativePath]?.sizeBytes ?: 0L
                } else {
                    0L
                }

            val afterDownload = projectedUsage - existingLocalSize + remoteSize

            if (afterDownload > limitBytes) {
                skipped +=
                    SkippedDownload(
                        relativePath = op.relativePath,
                        reason = SkippedDownload.Reason.WOULD_EXCEED_LIMIT,
                        sizeBytes = remoteSize,
                    )
            } else {
                allowed += op
                projectedUsage = afterDownload
            }
        }

        // Non-download ops are always allowed; append them after the download
        // batch to preserve their original relative order.
        return Plan(allowedOps = allowed + nonDownloadOps, skipped = skipped)
    }
}
