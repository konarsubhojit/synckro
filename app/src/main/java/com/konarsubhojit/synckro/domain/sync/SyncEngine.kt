package com.konarsubhojit.synckro.domain.sync

import com.konarsubhojit.synckro.data.repository.ConflictRepository
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.ConflictRecord
import com.konarsubhojit.synckro.domain.model.SyncPair
import com.konarsubhojit.synckro.domain.provider.CloudProvider
import com.konarsubhojit.synckro.providers.fake.FakeCloudProvider
import timber.log.Timber

/**
 * Orchestrates a single sync run for a [SyncPair]. The current implementation
 * is a skeleton: it demonstrates the intended call shape but leaves provider
 * and local-fs integration as TODOs. The pure diff logic lives in
 * [SyncDiffer] and is unit-tested independently.
 *
 * For the [CloudProviderType.FAKE] provider, the engine performs a real
 * (in-memory) sync pass: it drains any forced conflicts from [FakeCloudProvider],
 * applies resolutions that were set by the user, and writes new [ConflictRecord]
 * rows for unresolved [ConflictPolicy.KEEP_BOTH] conflicts.
 *
 * @param providers Map of all registered [CloudProvider] implementations, keyed by
 *   [CloudProviderType]. Injected by Hilt via [com.konarsubhojit.synckro.di.CloudProviderModule].
 */
class SyncEngine(
    private val conflictRepository: ConflictRepository,
    private val providers: Map<CloudProviderType, @JvmSuppressWildcards CloudProvider>,
) {

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
        val provider = providers[pair.provider]
            ?: return Result.Terminal("Unsupported provider: ${pair.provider}")
        if (pair.provider == CloudProviderType.FAKE) {
            return runFake(pair, provider as FakeCloudProvider)
        }
        // TODO:
        //  1. Enumerate local files via SAF DocumentFile tree into List<FileSnapshot>.
        //  2. Fetch remote changes via CloudProvider.changesSince(token).
        //  3. Compute ops via SyncDiffer.diff(...).
        //  4. Apply ops (upload / download / delete / mkdir) with retries.
        //  5. Persist updated FileIndexEntry rows and the new delta token.
        // Until the pipeline is wired, treat every invocation as a terminal
        // no-op so callers don't silently report stale data as "synced".
        return Result.Terminal("SyncEngine not yet implemented for ${pair.provider}")
    }

    /**
     * Fake-provider sync pass:
     * 1. Apply any resolutions the user set since the last run.
     * 2. Drain forced conflicts from the given [fakeProvider] parameter.
     * 3. For each new conflict, write a [ConflictRecord] if the pair policy is [ConflictPolicy.KEEP_BOTH].
     */
    private suspend fun runFake(pair: SyncPair, fakeProvider: FakeCloudProvider): Result {
        var applied = 0
        val errors = mutableListOf<String>()

        // Step 1 – apply pending resolutions
        val resolved = runCatching { conflictRepository.getResolvedForPair(pair.id) }
            .onFailure { Timber.w(it, "SyncEngine: could not read resolved conflicts for pair %d", pair.id) }
            .getOrDefault(emptyList())

        for (conflict in resolved) {
            try {
                applyFakeResolution(conflict)
                conflictRepository.delete(conflict.id)
                applied++
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: failed to apply resolution for conflict %d", conflict.id)
                errors += "Failed to apply resolution for ${conflict.relativePath}: ${t.message}"
            }
        }

        // Step 2 – detect new conflicts
        val newConflicts = runCatching { fakeProvider.drainForcedConflicts() }
            .onFailure { Timber.w(it, "SyncEngine: could not drain forced conflicts") }
            .getOrDefault(emptyList())

        val now = System.currentTimeMillis()
        var conflictCount = 0
        for (op in newConflicts) {
            if (pair.conflictPolicy == ConflictPolicy.KEEP_BOTH) {
                runCatching {
                    conflictRepository.insert(
                        ConflictRecord(
                            id = 0,
                            pairId = pair.id,
                            relativePath = op.relativePath,
                            localLastModifiedMs = op.localLastModifiedMs,
                            remoteLastModifiedMs = op.remoteLastModifiedMs,
                            detectedAtMs = now,
                        )
                    )
                }.onFailure { Timber.w(it, "SyncEngine: could not persist conflict for %s", op.relativePath) }
                conflictCount++
            }
            // For other policies the conflict was already auto-resolved by SyncDiffer rules;
            // nothing extra to do here for the fake provider.
        }

        return if (errors.isEmpty()) {
            Result.Success(applied = applied, conflicts = conflictCount)
        } else {
            Result.PartialFailure(applied = applied, conflicts = conflictCount, errors = errors)
        }
    }

    /**
     * Applies a user-chosen conflict resolution in the fake provider.
     * For the real engine this would perform the appropriate file operation
     * (overwrite / rename / download). Here we log the action as a placeholder.
     */
    private fun applyFakeResolution(conflict: ConflictRecord) {
        Timber.i(
            "SyncEngine(FAKE): applying resolution=%s for path=%s",
            conflict.resolution,
            conflict.relativePath,
        )
        // The actual bytes manipulation for FAKE is a no-op because the test
        // scenario only needs to verify that the resolution round-trips through
        // the database and that the conflict disappears from the inbox.
    }
}
