package com.konarsubhojit.synckro.domain.sync

import com.konarsubhojit.synckro.domain.model.SyncPair

/**
 * Orchestrates a single sync run for a [SyncPair]. The current implementation
 * is a skeleton: it demonstrates the intended call shape but leaves provider
 * and local-fs integration as TODOs. The pure diff logic lives in
 * [SyncDiffer] and is unit-tested independently.
 */
class SyncEngine {

    /**
     * Outcome of a single [runOnce]. [Success] and [PartialFailure] both mean
     * WorkManager should treat the run as complete; [Retriable] should be
     * rescheduled with backoff; [Terminal] means the pair is mis-configured
     * (revoked auth, unsupported provider, …) and periodic work should stop.
     */
    sealed interface Result {
        val applied: Int
        val conflicts: Int

        data class Success(override val applied: Int, override val conflicts: Int) : Result
        data class PartialFailure(
            override val applied: Int,
            override val conflicts: Int,
            val errors: List<String>,
        ) : Result
        data class Retriable(val reason: String) : Result {
            override val applied: Int = 0
            override val conflicts: Int = 0
        }
        data class Terminal(val reason: String) : Result {
            override val applied: Int = 0
            override val conflicts: Int = 0
        }
    }

    /**
     * Run a single synchronization pass for the given SyncPair.
     *
     * Performs a full sync workflow: enumerate local files, fetch remote changes, compute
     * required operations, apply uploads/downloads/deletes with retries, and persist updated
     * index entries and delta token.
     *
     * Currently not implemented; every invocation returns `Result.Terminal("SyncEngine not yet implemented")`.
     *
     * @param pair The SyncPair describing the local and remote endpoints and synchronization metadata.
     * @return A [Result] describing the sync outcome (`Success`, `PartialFailure`, `Retriable`, or `Terminal`).
     */
    suspend fun runOnce(pair: SyncPair): Result {
        // TODO:
        //  1. Enumerate local files via SAF DocumentFile tree into List<FileSnapshot>.
        //  2. Fetch remote changes via CloudProvider.changesSince(token).
        //  3. Compute ops via SyncDiffer.diff(...).
        //  4. Apply ops (upload / download / delete / mkdir) with retries.
        //  5. Persist updated FileIndexEntry rows and the new delta token.
        // Until the pipeline is wired, treat every invocation as a terminal
        // no-op so callers don't silently report stale data as "synced".
        return Result.Terminal("SyncEngine not yet implemented")
    }
}
