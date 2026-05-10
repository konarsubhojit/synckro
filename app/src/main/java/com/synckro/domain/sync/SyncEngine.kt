package com.synckro.domain.sync

import android.net.Uri
import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.data.local.fs.LocalFsEnumerator
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.data.worker.SyncWorker
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.ConflictRecord
import com.synckro.domain.model.FileIndexEntry
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncPair
import com.synckro.domain.provider.CloudProvider
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.provider.RemoteFile
import com.synckro.providers.fake.FakeCloudProvider
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

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
 *   [CloudProviderType]. Injected by Hilt via [com.synckro.di.CloudProviderModule].
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
 * @param localFileAccess Factory that creates a [LocalFileAccess] scoped to a specific
 *   SAF tree URI. Called once per sync run with the pair's tree URI, so each run gets
 *   a file-access instance bound to the correct local folder.
 *   Required for [runReal]; swap with an in-memory fake for tests (e.g. `{ _ -> fake }`).
 */
class SyncEngine(
    private val conflictRepository: ConflictRepository,
    private val providers: Map<CloudProviderType, @JvmSuppressWildcards CloudProviderFactory>,
    private val localFsEnumerator: LocalFsEnumerator? = null,
    private val remoteEnumerators: Map<CloudProviderType, @JvmSuppressWildcards RemoteEnumerator> = emptyMap(),
    private val syncPairDao: SyncPairDao? = null,
    private val localIndexDao: LocalIndexDao? = null,
    private val eventRepository: SyncEventRepository? = null,
    private val localFileAccess: ((Uri) -> LocalFileAccess)? = null,
) {
    private val scopeFilterCache = ConcurrentHashMap<ScopeFilterCacheKey, ScopeFilters>()

    /**
     * Outcome of a single [runOnce]. [Success] and [PartialFailure] both mean
     * WorkManager should treat the run as complete; [Retriable] should be
     * rescheduled with backoff; [Terminal] means the pair is mis-configured
     * (revoked auth, unsupported provider, …) and periodic work should stop.
     */
    sealed interface Result {
        val applied: Int
        val conflicts: Int

        data class Success(
            override val applied: Int,
            override val conflicts: Int,
        ) : Result

        data class PartialFailure(
            override val applied: Int,
            override val conflicts: Int,
            val errors: List<String>,
        ) : Result

        data class Retriable(
            val reason: String,
        ) : Result {
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
         *   CTA, and [SyncWorker] uses it to
         *   persist a distinct `lastSyncResult` and emit ERROR events tagged `auth`.
         * @param needsReLink  True when the failure is caused by a lost SAF permission
         *   (e.g. [com.synckro.data.local.fs.LocalStorageException]). The sync pair list
         *   uses this to show a "Re-link local folder" CTA. Mutually exclusive with
         *   [needsReauth] — at most one should be true at a time.
         */
        data class Terminal(
            val reason: String,
            val needsReauth: Boolean = false,
            val needsReLink: Boolean = false,
        ) : Result {
            init {
                require(!(needsReauth && needsReLink)) {
                    "needsReauth and needsReLink are mutually exclusive"
                }
            }

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
     * @param pair       The SyncPair describing the local and remote endpoints.
     * @param onProgress Called after each file operation with a [TransferProgress] snapshot.
     *                   Only fires during [runReal]; no-op for [CloudProviderType.FAKE].
     *                   Defaults to a no-op so existing callers are unaffected.
     * @return A [Result] describing the sync outcome.
     */
    suspend fun runOnce(pair: SyncPair, onProgress: suspend (TransferProgress) -> Unit = {}): Result {
        val providerFactory =
            providers[pair.provider]
                ?: return Result.Terminal("Unsupported provider: ${pair.provider}")
        val provider =
            if (pair.provider == CloudProviderType.FAKE) {
                providerFactory.providerFor(pair.accountId ?: FAKE_ACCOUNT_KEY)
            } else {
                val accountId =
                    pair.accountId
                        ?: return Result.Terminal(
                            reason = "Sync pair ${pair.id} is not linked to an account.",
                            needsReLink = true,
                        )
                providerFactory.providerFor(accountId)
            }
        if (pair.provider == CloudProviderType.FAKE) {
            return runFake(pair, provider as FakeCloudProvider)
        }
        return runReal(pair, provider, onProgress)
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
    private suspend fun runReal(pair: SyncPair, provider: CloudProvider, onProgress: suspend (TransferProgress) -> Unit): Result {
        val fsEnumerator =
            localFsEnumerator
                ?: return Result.Terminal("SyncEngine: LocalFsEnumerator not configured for ${pair.provider}")
        val remoteEnumerator =
            remoteEnumerators[pair.provider]
                ?: return Result.Terminal("SyncEngine: no RemoteEnumerator registered for ${pair.provider}")
        val pairDao =
            syncPairDao
                ?: return Result.Terminal("SyncEngine: SyncPairDao not configured")
        val indexDao =
            localIndexDao
                ?: return Result.Terminal("SyncEngine: LocalIndexDao not configured")
        val evtRepo =
            eventRepository
                ?: return Result.Terminal("SyncEngine: SyncEventRepository not configured")
        val fileAccessFactory =
            localFileAccess
                ?: return Result.Terminal("SyncEngine: LocalFileAccess not configured")

        return try {
            runRealImpl(pair, provider, fsEnumerator, remoteEnumerator, pairDao, indexDao, evtRepo, fileAccessFactory, onProgress)
        } catch (c: CancellationException) {
            // Cooperative cancellation: do NOT write partial state; just rethrow.
            throw c
        } catch (t: Throwable) {
            CloudExceptionMapper.toResult(t)
        }
    }

    internal fun scopeFiltersFor(pair: SyncPair): ScopeFilters =
        scopeFilterCache.computeIfAbsent(
            ScopeFilterCacheKey(
                includeGlobs = pair.includeGlobs.toList(),
                excludeGlobs = pair.excludeGlobs.toList(),
            ),
        ) { key ->
            ScopeFilters(
                includeGlobs = key.includeGlobs.mapNotNull { runCatching { LocalFsEnumerator.globToRegex(it) }.getOrNull() },
                excludeGlobs = key.excludeGlobs.mapNotNull { runCatching { LocalFsEnumerator.globToRegex(it) }.getOrNull() },
                includeFilterActive = key.includeGlobs.isNotEmpty(),
            )
        }

    private suspend fun enumerateRemoteIncremental(
        remoteEnumerator: RemoteEnumerator,
        pair: SyncPair,
    ): RemoteSnapshot {
        val accountId = pair.accountId
        return if (accountId != null && remoteEnumerator is AccountAwareRemoteEnumerator) {
            remoteEnumerator.enumerateForAccount(accountId, pair.deltaToken, pair.remoteFolderId)
        } else {
            remoteEnumerator.enumerate(pair.deltaToken, pair.remoteFolderId)
        }
    }

    private suspend fun enumerateRemoteFull(
        remoteEnumerator: RemoteEnumerator,
        pair: SyncPair,
    ): RemoteSnapshot {
        val accountId = pair.accountId
        return if (accountId != null && remoteEnumerator is AccountAwareRemoteEnumerator) {
            remoteEnumerator.enumerateFullForAccount(accountId, pair.remoteFolderId)
        } else {
            remoteEnumerator.enumerateFull(pair.remoteFolderId)
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
        fileAccessFactory: (Uri) -> LocalFileAccess,
        onProgress: suspend (TransferProgress) -> Unit,
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
        // Reverse map: stable remote ID → index entry. Used to look up canonical
        // paths for DELETE events (where the item may no longer be in the remote
        // store) and to detect renames (MODIFY with a different path than indexed).
        // Remote IDs are provider-assigned and expected to be unique within a pair;
        // if duplicates somehow exist (data inconsistency), the last entry wins,
        // which is acceptable since any matching entry gives us the canonical path.
        val preScanIndexById =
            preScanIndex
                .filter { it.remoteId != null }
                .associateBy { it.remoteId!! }

        // -----------------------------------------------------------------
        // Step 1 – Enumerate local files (full scan, updates local_index).
        // -----------------------------------------------------------------
        val treeUri = Uri.parse(pair.localTreeUri)
        val fileAccess = fileAccessFactory(treeUri)
        // Create a single SyncOpApplier for the entire sync run so that both
        // conflict-resolution (Step 5) and regular op application (Step 6) share
        // the same instance, avoiding redundant instantiation.
        val applier =
            SyncOpApplier(
                provider = provider,
                localIndexDao = indexDao,
                conflictRepository = conflictRepository,
                eventRepository = evtRepo,
                localFileAccess = fileAccess,
            )
        val localEnum =
            fsEnumerator.enumerate(
                pairId = pair.id,
                treeUri = treeUri,
                includeGlobs = pair.includeGlobs,
                ignoreGlobs = pair.excludeGlobs,
                excludeSubfolders = pair.excludeSubfolders,
            )

        // -----------------------------------------------------------------
        // Step 2 – Enumerate remote changes (full listing for new pairs,
        //          incremental delta for established pairs).
        //
        // When pair.deltaToken is null this is a brand-new pair: call
        // enumerateFull() so that files already present in the remote folder
        // are returned as MODIFY changes and can be downloaded.  For existing
        // pairs the persisted delta token is used for incremental polling.
        // -----------------------------------------------------------------
        val rawRemoteSnapshot =
            if (pair.deltaToken == null) {
                enumerateRemoteFull(remoteEnumerator, pair)
            } else {
                enumerateRemoteIncremental(remoteEnumerator, pair)
            }

        // When excludeEmptyFolders is enabled, filter out folder entries from
        // the remote delta.  Folder entries (isFolder = true) represent empty
        // directories on the remote side; keeping them would let the engine
        // process them as file changes.  Only non-DELETE folder entries are
        // filtered — DELETE entries are harmless (removing an absent key from
        // syntheticRemote is a no-op) and must be kept so that previously-
        // tracked items with the same remoteId are not orphaned.
        val remoteSnapshot =
            if (pair.excludeEmptyFolders) {
                rawRemoteSnapshot.copy(
                    changes = rawRemoteSnapshot.changes.filterNot { it.isFolder && it.type != RemoteChangeType.DELETE },
                )
            } else {
                rawRemoteSnapshot
            }

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
        //
        // The same include/exclude glob filter that LocalFsEnumerator applies
        // to the local snapshot is mirrored here.  Without this, a previously-
        // synced path that falls outside the configured globs (e.g. because the
        // user added an includeGlobs pattern after the initial sync) would still
        // appear in syntheticRemote and fileIndexEntries.  SyncDiffer would then
        // interpret "in remote + in lastIndex, absent from local" as a local
        // deletion and emit a DeleteRemote — causing data loss from what is
        // effectively a configuration change.
        // -----------------------------------------------------------------
        val scopeFilters = scopeFiltersFor(pair)

        fun isInScope(path: String): Boolean {
            if (scopeFilters.excludeGlobs.any { it.matches(path) }) return false
            if (scopeFilters.includeFilterActive && scopeFilters.includeGlobs.none { it.matches(path) }) return false
            // When excludeSubfolders is enabled, only root-level paths (no '/' separator)
            // are in scope.  This mirrors the LocalFsEnumerator's BFS behaviour where
            // sub-directories are not traversed.
            if (pair.excludeSubfolders && path.contains('/')) return false
            return true
        }

        val localSnapshots =
            localEnum.snapshot.map { entry ->
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
            if (!isInScope(idx.relativePath)) continue
            val remoteSize = idx.remoteSizeBytes ?: continue
            val remoteMtime = idx.remoteMtimeMs ?: continue
            syntheticRemote[idx.relativePath] =
                FileSnapshot(
                    relativePath = idx.relativePath,
                    size = remoteSize,
                    lastModifiedMs = remoteMtime,
                    hash = idx.remoteEtag,
                )
        }
        // Apply remote delta: ADD/MODIFY override the baseline; DELETE removes.
        // For ADD/MODIFY, only update if we have concrete metadata; if both the
        // delta and the baseline lack size/mtime (provider returned a partial
        // change record), leave the existing baseline entry unchanged.
        //
        // Rename/move detection: if the stable remoteId is already in the
        // pre-scan index under a different path, remove the old path from the
        // synthetic remote and add the new one so SyncDiffer sees a delete of
        // the old path and an add of the new one.
        for (change in remoteSnapshot.changes) {
            // Use the canonical path from the pre-scan index (resolved via the
            // stable remote ID) when available, because change.relativePath is
            // "best-effort" and may fall back to a leaf name when the provider
            // cannot resolve hierarchy in a delta batch.  Without this, nested
            // items could be misclassified as root-level when excludeSubfolders
            // is enabled (the path.contains('/') check would not fire).
            val canonicalPath = preScanIndexById[change.remoteId]?.relativePath
                ?: change.relativePath
            if (!isInScope(canonicalPath)) continue
            when (change.type) {
                RemoteChangeType.ADD, RemoteChangeType.MODIFY -> {
                    // Detect rename/move: look up the existing path by stable remote ID.
                    val existingPath = preScanIndexById[change.remoteId]?.relativePath
                    val isRename = existingPath != null && existingPath != change.relativePath
                    // For a rename, seed the baseline from the old path so that a partial
                    // delta (missing size/mtime) does not lose the item entirely.
                    val baseline = if (isRename) syntheticRemote[existingPath!!] else syntheticRemote[change.relativePath]
                    val resolvedSize = change.sizeBytes ?: baseline?.size
                    val resolvedMtime = change.mtimeMs ?: baseline?.lastModifiedMs
                    if (resolvedSize != null && resolvedMtime != null) {
                        // Remove old path only after we know the new path can be inserted.
                        if (isRename) syntheticRemote.remove(existingPath!!)
                        syntheticRemote[change.relativePath] =
                            FileSnapshot(
                                relativePath = change.relativePath,
                                size = resolvedSize,
                                lastModifiedMs = resolvedMtime,
                                hash = change.etag,
                            )
                    } else if (isRename && baseline != null) {
                        // Partial metadata on rename with no baseline fallback: copy the old
                        // snapshot to the new path so the item isn't silently dropped.
                        syntheticRemote.remove(existingPath!!)
                        syntheticRemote[change.relativePath] = baseline.copy(relativePath = change.relativePath)
                    }
                    // If no size/mtime available (neither delta nor baseline), skip rather
                    // than inserting a FS(0, 0) stub that would trigger spurious ops.
                }
                RemoteChangeType.DELETE -> {
                    // Prefer the canonical path from the pre-scan index (identified by the
                    // stable remote ID) over change.relativePath, which providers typically
                    // populate with only the item name or the ID itself.
                    val knownPath = preScanIndexById[change.remoteId]?.relativePath
                        ?: change.relativePath
                    syntheticRemote.remove(knownPath)
                }
            }
        }

        // Use only pre-scan synced entries (remoteId != null, in scope) as the
        // SyncDiffer baseline — entries without remoteId are not yet synced to
        // remote; out-of-scope entries are excluded to match the local snapshot.
        val fileIndexEntries =
            preScanIndex
                .filter { it.remoteId != null && isInScope(it.relativePath) }
                .map { it.toFileIndexEntry() }

        // -----------------------------------------------------------------
        // Step 4 – Compute ops.
        // -----------------------------------------------------------------
        val ops =
            SyncDiffer.diff(
                local = localSnapshots,
                remote = syntheticRemote.values,
                lastIndex = fileIndexEntries,
                direction = pair.direction,
                conflictPolicy = pair.conflictPolicy,
                retentionDays = pair.retentionDays,
            )

        // -----------------------------------------------------------------
        // Step 5 – Apply previously-resolved ConflictRecords (same pattern
        //          as runFake).
        // -----------------------------------------------------------------
        var appliedResolutions = 0
        val resolutionErrors = mutableListOf<String>()
        val resolved =
            runCatching { conflictRepository.getResolvedForPair(pair.id) }
                .onFailure { Timber.w(it, "SyncEngine: could not read resolved conflicts for pair %d", pair.id) }
                .getOrDefault(emptyList())
        // Materialise the local index once so each conflict resolution does an O(1)
        // map lookup instead of an O(N) full-table scan.  See issue #90.
        val resolutionIndexByPath: Map<String, LocalIndexEntity> =
            if (resolved.isEmpty()) {
                emptyMap()
            } else {
                runCatching { indexDao.getForPair(pair.id) }
                    .onFailure { Timber.w(it, "SyncEngine: could not read local index for pair %d", pair.id) }
                    .getOrDefault(emptyList())
                    .associateBy { it.relativePath }
            }
        for (conflict in resolved) {
            try {
                applyRealResolution(conflict, pair, applier, provider, fileAccess, resolutionIndexByPath, evtRepo)
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
        val remoteFilesByPath: Map<String, RemoteFile> =
            remoteSnapshot.changes
                .filter { it.type != RemoteChangeType.DELETE }
                .mapNotNull { change ->
                    // Build a RemoteFile stub from the RemoteChange metadata.
                    // For providers returning a full file record (like FakeRemoteEnumerator),
                    // this is sufficient for SyncOpApplier.
                    change.sizeBytes?.let {
                        change.relativePath to
                            RemoteFile(
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

        val applyResult =
            applier.apply(
                ops = ops,
                pair = pair,
                remoteFilesByPath = remoteFilesByPath,
                localIndexByPath = preScanIndexByPath,
                onProgress = onProgress,
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
     * - [ConflictRecord.RESOLUTION_KEEP_BOTH]:
     *   - *Both-modified*: downloads the remote content to a conflict copy (named via
     *     [conflictCopyPath]), uploads it as a new remote file, and overwrites the remote
     *     original with the local content so both versions are accessible.
     *   - *Modify-delete* (remote was deleted): re-uploads the surviving local file.
     * - null / unknown: logged as a warning, treated as no-op.
     *
     * @param applier The [SyncOpApplier] instance shared for the entire sync run; reused here
     *   to avoid redundant instantiation for each conflict resolution.
     * @param indexByPath Pre-materialised snapshot of the local index for [pair], keyed by
     *   relative path. Built once by the caller so each conflict resolution does an O(1)
     *   map lookup instead of an O(N) full-table scan (issue #90).
     */
    private suspend fun applyRealResolution(
        conflict: ConflictRecord,
        pair: SyncPair,
        applier: SyncOpApplier,
        provider: CloudProvider,
        fileAccess: LocalFileAccess,
        indexByPath: Map<String, LocalIndexEntity>,
        evtRepo: SyncEventRepository,
    ) {
        Timber.i(
            "SyncEngine(real): applying resolution=%s for path=%s",
            conflict.resolution,
            conflict.relativePath,
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
                val indexEntry = indexByPath[conflict.relativePath]
                if (indexEntry?.remoteId != null) {
                    val remoteFile =
                        RemoteFile(
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
            ConflictRecord.RESOLUTION_KEEP_BOTH -> {
                val indexEntry = indexByPath[conflict.relativePath]
                if (indexEntry?.remoteId != null) {
                    // Both-modified conflict: preserve both versions.
                    // Attempt to download the remote content to create a conflict copy.
                    // If the remote file is gone (modify-delete conflict with a stale index
                    // entry), fall back to re-uploading the surviving local file.
                    val copyPath = conflictCopyPath(conflict.relativePath, conflict.detectedAtMs)
                    var downloadOk = false
                    try {
                        var retried = false
                        // Suppress unused-parameter warning: attempt index and cause are
                        // not needed here; only the fact that a retry occurred matters.
                        withRetry(onRetry = { _, _ -> retried = true }) {
                            val stream = provider.download(indexEntry.remoteId)
                            fileAccess.write(copyPath, stream, null)
                        }
                        if (retried) {
                            evtRepo.log(
                                pair.id,
                                SyncEventLevel.WARN,
                                TAG,
                                "Retried keep-both download for conflict copy: $copyPath",
                            )
                        }
                        downloadOk = true
                    } catch (e: Exception) {
                        Timber.w(
                            e,
                            "SyncEngine: keep-both download failed for %s; treating as modify-delete",
                            conflict.relativePath,
                        )
                        runCatching { fileAccess.delete(copyPath) } // best-effort cleanup of any partial write
                    }
                    if (downloadOk) {
                        // 2. Upload the conflict copy to remote as a new file.
                        //    UploadNew reads from local FS; remoteFilesByPath is not consulted
                        //    for upload ops, so an empty map is the correct value here.
                        applier.apply(
                            ops = listOf(SyncOp.UploadNew(copyPath)),
                            pair = pair,
                            remoteFilesByPath = emptyMap(),
                            localIndexByPath = emptyMap(),
                        )
                        // 3. Overwrite the remote original with the local content so both sides agree.
                        //    UpdateRemote resolves the remoteId from localIndexByPath, not from
                        //    remoteFilesByPath, so an empty map is the correct value here.
                        applier.apply(
                            ops = listOf(SyncOp.UpdateRemote(conflict.relativePath)),
                            pair = pair,
                            remoteFilesByPath = emptyMap(),
                            localIndexByPath = mapOf(conflict.relativePath to indexEntry),
                        )
                        evtRepo.log(
                            pair.id,
                            SyncEventLevel.INFO,
                            TAG,
                            "Conflict resolved (keep-both): ${conflict.relativePath} → copy at $copyPath",
                        )
                    } else {
                        // Remote was deleted (modify-delete): re-upload the surviving local file.
                        //    UploadNew reads from local FS; remoteFilesByPath is not consulted
                        //    for upload ops, so an empty map is the correct value here.
                        applier.apply(
                            ops = listOf(SyncOp.UploadNew(conflict.relativePath)),
                            pair = pair,
                            remoteFilesByPath = emptyMap(),
                            localIndexByPath = emptyMap(),
                        )
                        evtRepo.log(
                            pair.id,
                            SyncEventLevel.INFO,
                            TAG,
                            "Conflict resolved (keep-both, remote deleted): ${conflict.relativePath} re-uploaded",
                        )
                    }
                } else {
                    // No remote ID in index (file was never synced or index was cleared).
                    // Re-upload the surviving local file.
                    applier.apply(
                        ops = listOf(SyncOp.UploadNew(conflict.relativePath)),
                        pair = pair,
                        remoteFilesByPath = emptyMap(),
                        localIndexByPath = emptyMap(),
                    )
                    evtRepo.log(
                        pair.id,
                        SyncEventLevel.INFO,
                        TAG,
                        "Conflict resolved (keep-both, no remote): ${conflict.relativePath} uploaded",
                    )
                }
            }
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
    private suspend fun runFake(
        pair: SyncPair,
        fakeProvider: FakeCloudProvider,
    ): Result {
        var applied = 0
        val errors = mutableListOf<String>()

        // Step 1 – apply pending resolutions
        val resolved =
            runCatching { conflictRepository.getResolvedForPair(pair.id) }
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
        val newConflicts =
            runCatching { fakeProvider.drainForcedConflicts() }
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
                        ),
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
        private const val FAKE_ACCOUNT_KEY = "__fake__"

        internal data class ScopeFilterCacheKey(
            val includeGlobs: List<String>,
            val excludeGlobs: List<String>,
        )

        internal data class ScopeFilters(
            val includeGlobs: List<Regex>,
            val excludeGlobs: List<Regex>,
            val includeFilterActive: Boolean,
        )

        /**
         * Maps a [LocalIndexEntity] row to the [FileIndexEntry] domain model consumed by
         * [SyncDiffer.diff]. Remote metadata columns populate the remote-side fields;
         * these are null until the first successful sync populates them via [SyncOpApplier].
         */
        internal fun LocalIndexEntity.toFileIndexEntry() =
            FileIndexEntry(
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

        /**
         * Returns a deterministic conflict-copy path for a "keep-both" resolution.
         *
         * The conflict copy carries a date suffix so that multiple conflicts on the
         * same file on different days each produce a distinct name.
         *
         * Examples:
         * - `document.txt` → `document (conflict 2024-01-15).txt`
         * - `photos/vacation.jpg` → `photos/vacation (conflict 2024-01-15).jpg`
         * - `README` → `README (conflict 2024-01-15)`
         *
         * @param originalPath Relative path of the conflicting file.
         * @param detectedAtMs Epoch-milliseconds timestamp when the conflict was detected.
         */
        internal fun conflictCopyPath(
            originalPath: String,
            detectedAtMs: Long,
        ): String {
            val fmt =
                SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).also {
                    it.timeZone = TimeZone.getTimeZone("UTC")
                }
            val dateLabel = fmt.format(Date(detectedAtMs))
            val dir = originalPath.substringBeforeLast('/', "")
            val name = originalPath.substringAfterLast('/')
            // Treat a leading dot (hidden files like ".gitignore") as part of the stem,
            // not as an extension separator, so ".gitignore" → ".gitignore (conflict…)"
            // rather than " (conflict…).gitignore".
            val hasDot = '.' in name.drop(1)
            val stem = if (hasDot) name.substringBeforeLast('.') else name
            val ext = if (hasDot) ".${name.substringAfterLast('.')}" else ""
            val copyName = "$stem (conflict $dateLabel)$ext"
            return if (dir.isEmpty()) copyName else "$dir/$copyName"
        }

        private const val TAG = "SyncEngine"
    }
}
