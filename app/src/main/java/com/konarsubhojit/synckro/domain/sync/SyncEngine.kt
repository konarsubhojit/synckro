package com.konarsubhojit.synckro.domain.sync

import android.net.Uri
import com.konarsubhojit.synckro.data.local.dao.LocalIndexDao
import com.konarsubhojit.synckro.data.local.entity.LocalIndexEntity
import com.konarsubhojit.synckro.data.local.fs.LocalFsEnumerator
import com.konarsubhojit.synckro.data.local.dao.SyncPairDao
import com.konarsubhojit.synckro.data.repository.ConflictRepository
import com.konarsubhojit.synckro.data.repository.SyncEventRepository
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.ConflictRecord
import com.konarsubhojit.synckro.domain.model.FileIndexEntry
import com.konarsubhojit.synckro.domain.model.SyncPair
import com.konarsubhojit.synckro.domain.provider.CloudProvider
import com.konarsubhojit.synckro.providers.fake.FakeCloudProvider
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Orchestrates a single sync run for a [SyncPair].
 *
 * For the [CloudProviderType.FAKE] provider, [runFake] is used (in-memory only:
 * drains forced conflicts and applies user resolutions). For all other providers,
 * [runReal] is used: it walks the full pipeline — local enumeration, remote delta
 * enumeration, [SyncDiffer] diffing, [SyncOpApplier] application, and persistence
 * of the new delta token and local-index entries.
 *
 * @param providers       Map of all registered [CloudProvider] implementations, keyed by
 *   [CloudProviderType]. Injected by Hilt via [com.konarsubhojit.synckro.di.CloudProviderModule].
 * @param localFsEnumerator  Walks the SAF document tree and updates the `local_index`.
 *   Required for [runReal]; if null the engine returns [Result.Terminal].
 * @param remoteEnumerators  Map of provider → [RemoteEnumerator]. Required for [runReal];
 *   if the entry for the pair's provider is absent the engine returns [Result.Terminal].
 * @param syncPairDao     DAO used to persist the new delta token and last-full-scan
 *   timestamp after a successful [runReal] pass. Required for [runReal].
 * @param localIndexDao   DAO used to read the current local index for [SyncDiffer].
 *   Required for [runReal].
 * @param eventRepository Repository for emitting structured log events during [runReal].
 *   Required for [runReal].
 * @param localFileAccess Local I/O abstraction passed to [SyncOpApplier].
 *   Required for [runReal]; swap with an in-memory fake for tests.
 */
class SyncEngine(
    private val conflictRepository: ConflictRepository,
    private val providers: Map<CloudProviderType, @JvmSuppressWildcards CloudProvider>,
    private val localFsEnumerator: LocalFsEnumerator? = null,
    private val remoteEnumerators: Map<CloudProviderType, @JvmSuppressWildcards RemoteEnumerator> = emptyMap(),
    private val syncPairDao: SyncPairDao? = null,
    private val localIndexDao: LocalIndexDao? = null,
    private val eventRepository: SyncEventRepository? = null,
    private val localFileAccess: LocalFileAccess? = null,
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
        /**
         * Permanent failure for this pair; periodic work should stop until the
         * user takes action.
         *
         * @param reason       Short human-readable description (surfaced in logs).
         * @param needsReauth  True when the failure is auth-related (token expired /
         *   account removed / scope revoked / `MsalUiRequiredException` /
         *   `NotConfigured`). The Accounts screen uses this to show a "Re-authenticate"
         *   CTA, and [com.konarsubhojit.synckro.data.worker.SyncWorker] uses it to
         *   persist a distinct `lastSyncResult` and emit ERROR events tagged `auth`.
         */
        data class Terminal(
            val reason: String,
            val needsReauth: Boolean = false,
        ) : Result {
            override val applied: Int = 0
            override val conflicts: Int = 0
        }
    }

    /**
     * Run a single synchronization pass for the given [pair].
     *
     * Dispatches to [runFake] for [CloudProviderType.FAKE], and to [runReal] for
     * all other providers. [runReal] requires the optional constructor dependencies
     * to be provided; if any are absent it returns [Result.Terminal].
     *
     * @param pair The SyncPair describing the local and remote endpoints.
     * @return A [Result] describing the sync outcome.
     */
    suspend fun runOnce(pair: SyncPair): Result {
        val provider = providers[pair.provider]
            ?: return Result.Terminal("Unsupported provider: ${pair.provider}")
        if (pair.provider == CloudProviderType.FAKE) {
            return runFake(pair, provider as FakeCloudProvider)
        }
        return runReal(pair, provider)
    }

    // -------------------------------------------------------------------------
    // runReal — full pipeline for non-FAKE providers
    // -------------------------------------------------------------------------

    /**
     * Full sync pipeline for a non-FAKE [pair]:
     *
     * 1. Enumerate local files via [LocalFsEnumerator].
     * 2. Enumerate remote delta via [RemoteEnumerator].
     * 3. Build a synthetic full remote snapshot (index baseline + delta changes).
     * 4. Compute ops via [SyncDiffer.diff].
     * 5. Apply previously-resolved [ConflictRecord]s (same pattern as [runFake]).
     * 6. Apply ops via [SyncOpApplier].
     * 7. Persist the new delta token and last-full-scan timestamp (only if no
     *    [CancellationException] occurred — cancellation leaves state unchanged).
     * 8. Map to [Result].
     *
     * Auth exceptions are converted to [Result.Terminal]; network/rate-limit
     * errors to [Result.Retriable]; [CancellationException] always propagates.
     */
    private suspend fun runReal(pair: SyncPair, provider: CloudProvider): Result {
        val fsEnumerator = localFsEnumerator
            ?: return Result.Terminal("SyncEngine: LocalFsEnumerator not configured for ${pair.provider}")
        val remoteEnumerator = remoteEnumerators[pair.provider]
            ?: return Result.Terminal("SyncEngine: no RemoteEnumerator registered for ${pair.provider}")
        val pairDao = syncPairDao
            ?: return Result.Terminal("SyncEngine: SyncPairDao not configured")
        val indexDao = localIndexDao
            ?: return Result.Terminal("SyncEngine: LocalIndexDao not configured")
        val evtRepo = eventRepository
            ?: return Result.Terminal("SyncEngine: SyncEventRepository not configured")
        val fileAccess = localFileAccess
            ?: return Result.Terminal("SyncEngine: LocalFileAccess not configured")

        return try {
            runRealImpl(pair, provider, fsEnumerator, remoteEnumerator, pairDao, indexDao, evtRepo, fileAccess)
        } catch (c: CancellationException) {
            // Cooperative cancellation: do NOT write partial state; just rethrow.
            throw c
        } catch (t: Throwable) {
            CloudExceptionMapper.toResult(t)
        }
    }

    private suspend fun runRealImpl(
        pair: SyncPair,
        provider: CloudProvider,
        fsEnumerator: LocalFsEnumerator,
        remoteEnumerator: RemoteEnumerator,
        pairDao: SyncPairDao,
        indexDao: LocalIndexDao,
        evtRepo: SyncEventRepository,
        fileAccess: LocalFileAccess,
    ): Result {
        // -----------------------------------------------------------------
        // Step 0 – Snapshot the index BEFORE local enumeration.
        //
        // LocalFsEnumerator.enumerate() atomically reconciles local_index
        // (upserts changed entries, removes stale ones) during the scan.
        // If we read the index after the scan, newly-discovered local files
        // appear in the index with remoteId = null, and SyncDiffer would
        // misinterpret them as "file was in index (synced) but not in remote
        // delta" → DeleteLocal.
        //
        // Using the pre-scan index (filtered to synced entries, i.e. those
        // with remoteId != null) solves both:
        //   • New local files: not in pre-scan index → UploadNew ✓
        //   • Locally deleted synced files: in pre-scan index but absent from
        //     the local snapshot → DeleteRemote ✓
        //   • Unchanged files: in pre-scan index with remote metadata →
        //     SyncDiffer sees them in syntheticRemote and no-ops ✓
        // -----------------------------------------------------------------
        val preScanIndex = indexDao.getForPair(pair.id)
        val preScanIndexByPath = preScanIndex.associateBy { it.relativePath }

        // -----------------------------------------------------------------
        // Step 1 – Enumerate local files (full scan, updates local_index).
        // -----------------------------------------------------------------
        val treeUri = Uri.parse(pair.localTreeUri)
        val localEnum = fsEnumerator.enumerate(
            pairId = pair.id,
            treeUri = treeUri,
            ignoreGlobs = pair.excludeGlobs,
        )

        // -----------------------------------------------------------------
        // Step 2 – Enumerate remote changes (delta since last token).
        // -----------------------------------------------------------------
        val remoteSnapshot = remoteEnumerator.enumerate(pair.deltaToken)

        // -----------------------------------------------------------------
        // Step 3 – Build inputs for SyncDiffer.
        //
        // • local  = full snapshot from LocalFsEnumerator → FileSnapshot
        // • remote = synthetic full remote state:
        //     baseline (pre-scan synced entries with remote metadata) +
        //     overrides (ADD / MODIFY from delta) -
        //     deletions (DELETE from delta)
        //   This approach ensures that unchanged remote files (absent from
        //   the delta) are still represented in the diff, preventing them
        //   from being misidentified as "remote-deleted".
        // • index  = pre-scan synced entries only (remoteId != null), mapped
        //   to FileIndexEntry. Using only synced entries ensures that new
        //   local files (in index post-scan but not yet uploaded) do not
        //   appear as "in index but not in remote" → DeleteLocal.
        // -----------------------------------------------------------------
        val localSnapshots = localEnum.snapshot.map { entry ->
            FileSnapshot(
                relativePath = entry.relativePath,
                size = entry.sizeBytes,
                lastModifiedMs = entry.mtimeMs,
                hash = entry.contentHash,
            )
        }

        // Build synthetic remote snapshot starting from last-known remote state
        // (pre-scan index rows that have remote metadata), then apply the delta.
        val syntheticRemote = mutableMapOf<String, FileSnapshot>()
        for (idx in preScanIndex) {
            val remoteSize = idx.remoteSizeBytes ?: continue
            val remoteMtime = idx.remoteMtimeMs ?: continue
            syntheticRemote[idx.relativePath] = FileSnapshot(
                relativePath = idx.relativePath,
                size = remoteSize,
                lastModifiedMs = remoteMtime,
                hash = idx.remoteEtag,
            )
        }
        // Apply remote delta: ADD/MODIFY override the baseline; DELETE removes.
        for (change in remoteSnapshot.changes) {
            when (change.type) {
                RemoteChangeType.ADD, RemoteChangeType.MODIFY ->
                    syntheticRemote[change.relativePath] = FileSnapshot(
                        relativePath = change.relativePath,
                        size = change.sizeBytes ?: syntheticRemote[change.relativePath]?.size ?: 0L,
                        lastModifiedMs = change.mtimeMs ?: syntheticRemote[change.relativePath]?.lastModifiedMs ?: 0L,
                        hash = change.etag,
                    )
                RemoteChangeType.DELETE ->
                    syntheticRemote.remove(change.relativePath)
            }
        }

        // Use only pre-scan synced entries (remoteId != null) as the SyncDiffer
        // baseline — entries without remoteId are not yet synced to remote.
        val fileIndexEntries = preScanIndex
            .filter { it.remoteId != null }
            .map { it.toFileIndexEntry() }

        // -----------------------------------------------------------------
        // Step 4 – Compute ops.
        // -----------------------------------------------------------------
        val ops = SyncDiffer.diff(
            local = localSnapshots,
            remote = syntheticRemote.values,
            lastIndex = fileIndexEntries,
            direction = pair.direction,
            conflictPolicy = pair.conflictPolicy,
        )

        // -----------------------------------------------------------------
        // Step 5 – Apply previously-resolved ConflictRecords (same pattern
        //          as runFake).
        // -----------------------------------------------------------------
        var appliedResolutions = 0
        val resolutionErrors = mutableListOf<String>()
        val resolved = runCatching { conflictRepository.getResolvedForPair(pair.id) }
            .onFailure { Timber.w(it, "SyncEngine: could not read resolved conflicts for pair %d", pair.id) }
            .getOrDefault(emptyList())
        for (conflict in resolved) {
            try {
                applyRealResolution(conflict, pair, provider, fileAccess, indexDao, evtRepo)
                conflictRepository.delete(conflict.id)
                appliedResolutions++
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: failed to apply resolution for conflict %d", conflict.id)
                resolutionErrors += "Failed to apply resolution for ${conflict.relativePath}: ${t.message}"
            }
        }

        // -----------------------------------------------------------------
        // Step 6 – Apply ops (upload / download / delete …).
        // -----------------------------------------------------------------
        val remoteFilesByPath: Map<String, com.konarsubhojit.synckro.domain.provider.RemoteFile> =
            remoteSnapshot.changes
                .filter { it.type != RemoteChangeType.DELETE }
                .mapNotNull { change ->
                    // Build a RemoteFile stub from the RemoteChange metadata.
                    // For providers returning a full file record (like FakeRemoteEnumerator),
                    // this is sufficient for SyncOpApplier.
                    change.sizeBytes?.let {
                        change.relativePath to com.konarsubhojit.synckro.domain.provider.RemoteFile(
                            id = change.remoteId,
                            name = change.relativePath.substringAfterLast('/'),
                            parentId = null,
                            isFolder = false,
                            size = change.sizeBytes,
                            lastModifiedMs = change.mtimeMs,
                            eTag = change.etag,
                            mimeType = null,
                        )
                    }
                }
                .toMap()

        val applier = SyncOpApplier(
            provider = provider,
            localIndexDao = indexDao,
            conflictRepository = conflictRepository,
            eventRepository = evtRepo,
            localFileAccess = fileAccess,
        )
        val applyResult = applier.apply(
            ops = ops,
            pair = pair,
            remoteFilesByPath = remoteFilesByPath,
            localIndexByPath = preScanIndexByPath,
        )

        // -----------------------------------------------------------------
        // Step 7 – Persist delta token and last-full-scan timestamp.
        //          This only runs if we reach here without cancellation,
        //          so partial state is never written on CancellationException.
        // -----------------------------------------------------------------
        pairDao.updateDeltaToken(pair.id, remoteSnapshot.newDeltaToken)
        pairDao.updateLastFullScanAtMs(pair.id, System.currentTimeMillis())

        // -----------------------------------------------------------------
        // Step 8 – Map to Result.
        // -----------------------------------------------------------------
        val totalApplied = appliedResolutions + applyResult.applied
        val allErrors = resolutionErrors + applyResult.errors
        return if (allErrors.isEmpty()) {
            Result.Success(applied = totalApplied, conflicts = applyResult.conflicts)
        } else {
            Result.PartialFailure(
                applied = totalApplied,
                conflicts = applyResult.conflicts,
                errors = allErrors,
            )
        }
    }

    /**
     * Applies a user-chosen conflict resolution using the real provider and local I/O.
     *
     * - [ConflictRecord.RESOLUTION_KEEP_LOCAL]: upload the local copy to overwrite remote.
     * - [ConflictRecord.RESOLUTION_KEEP_REMOTE]: download the remote copy to overwrite local.
     * - [ConflictRecord.RESOLUTION_KEEP_BOTH]: no-op (the user has acknowledged; both copies
     *   exist separately — future UX will rename one).
     * - null / unknown: logged as a warning, treated as no-op.
     */
    private suspend fun applyRealResolution(
        conflict: ConflictRecord,
        pair: SyncPair,
        provider: CloudProvider,
        fileAccess: LocalFileAccess,
        indexDao: LocalIndexDao,
        evtRepo: SyncEventRepository,
    ) {
        Timber.i(
            "SyncEngine(real): applying resolution=%s for path=%s",
            conflict.resolution,
            conflict.relativePath,
        )
        // Delegate to SyncOpApplier to reuse retry / index-update logic.
        val applier = SyncOpApplier(
            provider = provider,
            localIndexDao = indexDao,
            conflictRepository = conflictRepository,
            eventRepository = evtRepo,
            localFileAccess = fileAccess,
        )
        when (conflict.resolution) {
            ConflictRecord.RESOLUTION_KEEP_LOCAL ->
                applier.apply(
                    ops = listOf(SyncOp.UploadNew(conflict.relativePath)),
                    pair = pair,
                    remoteFilesByPath = emptyMap(),
                    localIndexByPath = emptyMap(),
                )
            ConflictRecord.RESOLUTION_KEEP_REMOTE -> {
                val indexEntry = indexDao.getForPair(pair.id)
                    .firstOrNull { it.relativePath == conflict.relativePath }
                if (indexEntry?.remoteId != null) {
                    val remoteFile = com.konarsubhojit.synckro.domain.provider.RemoteFile(
                        id = indexEntry.remoteId,
                        name = conflict.relativePath.substringAfterLast('/'),
                        parentId = null,
                        isFolder = false,
                        size = indexEntry.remoteSizeBytes,
                        lastModifiedMs = indexEntry.remoteMtimeMs,
                        eTag = indexEntry.remoteEtag,
                        mimeType = null,
                    )
                    applier.apply(
                        ops = listOf(SyncOp.DownloadNew(conflict.relativePath)),
                        pair = pair,
                        remoteFilesByPath = mapOf(conflict.relativePath to remoteFile),
                        localIndexByPath = emptyMap(),
                    )
                } else {
                    Timber.w(
                        "SyncEngine: KEEP_REMOTE resolution for %s but no remote ID in index; skipping",
                        conflict.relativePath,
                    )
                }
            }
            ConflictRecord.RESOLUTION_KEEP_BOTH ->
                Timber.i("SyncEngine: KEEP_BOTH conflict acknowledged for %s; no file operation needed", conflict.relativePath)
            else ->
                Timber.w("SyncEngine: unknown resolution '%s' for %s; skipping", conflict.resolution, conflict.relativePath)
        }
    }

    // -------------------------------------------------------------------------
    // runFake — in-memory pass for FAKE provider
    // -------------------------------------------------------------------------

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

    companion object {
        /**
         * Maps a [LocalIndexEntity] row to the [FileIndexEntry] domain model consumed by
         * [SyncDiffer.diff]. Remote metadata columns populate the remote-side fields;
         * these are null until the first successful sync populates them via [SyncOpApplier].
         */
        internal fun LocalIndexEntity.toFileIndexEntry() = FileIndexEntry(
            pairId = pairId,
            relativePath = relativePath,
            localSize = sizeBytes,
            localLastModifiedMs = mtimeMs,
            localHash = contentHash,
            remoteId = remoteId,
            remoteETag = remoteEtag,
            remoteSize = remoteSizeBytes,
            remoteLastModifiedMs = remoteMtimeMs,
        )
    }
}
