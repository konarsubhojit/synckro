package com.synckro.domain.sync

/**
 * Snapshot of how far [SyncOpApplier] has progressed through the current batch of ops.
 *
 * Consumers can choose which signal to show in a progress indicator:
 * - **Byte-based** (preferred): when [totalBytes] > 0, compute a fraction via
 *   `bytesTransferred / totalBytes`.
 * - **File-based** (fallback): when [totalBytes] == 0 but [totalFiles] > 0, use
 *   `filesCompleted / totalFiles`.
 * - **Indeterminate**: when both denominators are zero (unknown batch size).
 *
 * @param filesCompleted  Number of ops fully processed so far (success or failure).
 * @param totalFiles      Total number of ops in the batch.
 * @param bytesTransferred Cumulative bytes transferred so far (uploads + downloads).
 * @param totalBytes      Total bytes expected for the batch; 0 when sizes are unknown.
 */
data class TransferProgress(
    val filesCompleted: Int,
    val totalFiles: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
)
