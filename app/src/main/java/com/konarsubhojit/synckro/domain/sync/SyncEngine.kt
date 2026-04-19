package com.konarsubhojit.synckro.domain.sync

import com.konarsubhojit.synckro.domain.model.SyncPair

/**
 * Orchestrates a single sync run for a [SyncPair]. The current implementation
 * is a skeleton: it demonstrates the intended call shape but leaves provider
 * and local-fs integration as TODOs. The pure diff logic lives in
 * [SyncDiffer] and is unit-tested independently.
 */
class SyncEngine {

    data class Result(
        val applied: Int,
        val conflicts: Int,
        val errors: List<String>,
    )

    suspend fun runOnce(pair: SyncPair): Result {
        // TODO:
        //  1. Enumerate local files via SAF DocumentFile tree into List<FileSnapshot>.
        //  2. Fetch remote changes via CloudProvider.changesSince(token).
        //  3. Compute ops via SyncDiffer.diff(...).
        //  4. Apply ops (upload / download / delete / mkdir) with retries.
        //  5. Persist updated FileIndexEntry rows and the new delta token.
        return Result(applied = 0, conflicts = 0, errors = emptyList())
    }
}
