package com.synckro.domain.model

/**
 * A conflict that was detected during a sync run and could not be auto-resolved.
 * Stored in Room so the user can inspect and resolve it via [ConflictInboxScreen].
 *
 * @param resolution One of [RESOLUTION_KEEP_LOCAL], [RESOLUTION_KEEP_REMOTE],
 *   [RESOLUTION_KEEP_BOTH], or `null` while the conflict is still pending.
 */
data class ConflictRecord(
    val id: Long,
    val pairId: Long,
    val relativePath: String,
    val localLastModifiedMs: Long,
    val remoteLastModifiedMs: Long,
    val detectedAtMs: Long,
    val resolution: String? = null,
) {
    companion object {
        const val RESOLUTION_KEEP_LOCAL = "KEEP_LOCAL"
        const val RESOLUTION_KEEP_REMOTE = "KEEP_REMOTE"
        const val RESOLUTION_KEEP_BOTH = "KEEP_BOTH"
    }
}
