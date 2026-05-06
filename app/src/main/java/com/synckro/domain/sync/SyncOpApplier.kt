package com.synckro.domain.sync

import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.ConflictRecord
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncPair
import com.synckro.domain.provider.CloudProvider
import com.synckro.domain.provider.CloudProviderException
import com.synckro.domain.provider.RemoteFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Abstraction over local file I/O for [SyncOpApplier].
 *
 * Extracted as an interface so the applier can be unit-tested on the JVM
 * without a live SAF ContentProvider.
 */
interface LocalFileAccess {
    /**
     * Opens a read stream for the local file at [relativePath].
     *
     * @return An [InputStream] to read the file, or `null` if the file does not exist.
     */
    fun openRead(relativePath: String): InputStream?

    /**
     * Writes the contents of [content] to the local file at [relativePath],
     * creating the file (and any parent directories) if needed.
     *
     * The [content] stream is consumed and closed by this call.
     *
     * @param relativePath Path relative to the sync-pair local root.
     * @param content      Stream of bytes to write; ownership is transferred and it will be closed.
     * @param mimeType     Optional MIME type hint.
     * @return [LocalFileStat] describing the written file.
     * @throws Exception if the write fails.
     */
    fun write(
        relativePath: String,
        content: InputStream,
        mimeType: String?,
    ): LocalFileStat

    /**
     * Deletes the local file at [relativePath].
     *
     * @return `true` if the file existed and was deleted, `false` if it did not exist.
     */
    fun delete(relativePath: String): Boolean

    /**
     * Returns basic metadata for the local file at [relativePath].
     *
     * @return [LocalFileStat] for the file, or `null` if the file does not exist.
     */
    fun stat(relativePath: String): LocalFileStat?
}

/**
 * Metadata of a local file, returned by [LocalFileAccess].
 *
 * @param sizeBytes File size in bytes.
 * @param mtimeMs   Last-modified time in epoch milliseconds.
 * @param mimeType  Optional MIME type (e.g. "text/plain").
 */
data class LocalFileStat(
    val sizeBytes: Long,
    val mtimeMs: Long,
    val mimeType: String? = null,
)

/**
 * Applies a list of [SyncOp]s (produced by [SyncDiffer]) against a [CloudProvider]
 * and the local file system, with retries, index updates, and structured event logging.
 *
 * ### Per-op behaviour
 * - Provider calls are wrapped with [withRetry]; if any retry occurs a WARN event is emitted.
 * - On success the [LocalIndexDao] is updated and an INFO event is emitted.
 * - Failures are collected in [ApplyResult.errors] and an ERROR event is emitted.
 * - Terminal auth exceptions ([CloudProviderException.AuthenticationRequired],
 *   [CloudProviderException.AuthenticationFailed], [CloudProviderException.NotConfigured])
 *   propagate immediately so the caller can signal the pair as broken.
 *
 * ### Conflict handling ([SyncOp.Conflict])
 * - [ConflictPolicy.KEEP_BOTH]: a [ConflictRecord] is written and the op is counted as a
 *   conflict (not applied).
 * - [ConflictPolicy.PREFER_LOCAL] / [ConflictPolicy.NEWEST_WINS] with local newer:
 *   upload the local copy to overwrite remote; emit INFO.
 * - [ConflictPolicy.PREFER_REMOTE] / [ConflictPolicy.NEWEST_WINS] with remote newer:
 *   download the remote copy to overwrite local; emit INFO.
 *
 * ### Threading
 * All blocking I/O runs on [ioDispatcher] (defaults to [Dispatchers.IO]).
 *
 * @param provider          The cloud-storage provider to use for remote operations.
 * @param localIndexDao     DAO for persisting the local file index.
 * @param conflictRepository Repository for writing [ConflictRecord] rows.
 * @param eventRepository   Repository for emitting structured sync-event log entries.
 * @param localFileAccess   Abstraction over local file I/O; swap with a fake for unit tests.
 * @param ioDispatcher      Dispatcher for blocking I/O (default: [Dispatchers.IO]).
 */
class SyncOpApplier(
    private val provider: CloudProvider,
    private val localIndexDao: LocalIndexDao,
    private val conflictRepository: ConflictRepository,
    private val eventRepository: SyncEventRepository,
    private val localFileAccess: LocalFileAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Outcome of a single [apply] call.
     *
     * @param applied   Number of ops successfully applied.
     * @param conflicts Number of [SyncOp.Conflict] ops processed (written to inbox or auto-resolved).
     * @param errors    Human-readable error messages for failed ops.
     */
    data class ApplyResult(
        val applied: Int,
        val conflicts: Int,
        val errors: List<String>,
    )

    /**
     * Applies [ops] for the given [pair].
     *
     * [remoteFilesByPath] must contain an entry for every path that appears in
     * [SyncOp.DownloadNew], [SyncOp.UpdateLocal], and any [SyncOp.Conflict] resolved
     * via REMOTE side.  [localIndexByPath] must contain an entry for every path that
     * appears in [SyncOp.UpdateRemote], [SyncOp.DeleteRemote], and any [SyncOp.Conflict]
     * resolved via LOCAL side when the remote ID is needed.
     *
     * Terminal auth exceptions propagate immediately; all other exceptions are collected
     * in [ApplyResult.errors] so a single bad file does not abort the whole batch.
     *
     * After each op (success or failure) [onProgress] is invoked with a [TransferProgress]
     * snapshot. [TransferProgress.totalBytes] is the sum of file sizes that could be resolved
     * from [remoteFilesByPath] and [localIndexByPath] before the batch started; it is 0 when
     * no sizes are available, in which case [TransferProgress.totalFiles] should be used as a
     * fallback denominator.
     *
     * @param ops              Ordered list of operations to apply.
     * @param pair             The sync pair owning the ops.
     * @param remoteFilesByPath Map from relative path to [RemoteFile] for all remote files.
     * @param localIndexByPath  Map from relative path to [LocalIndexEntity] for all indexed files.
     * @param onProgress       Called after every op with the current [TransferProgress].
     *                         Defaults to a no-op so existing callers are unaffected.
     * @return [ApplyResult] summarising what was applied, how many conflicts, and any errors.
     */
    suspend fun apply(
        ops: List<SyncOp>,
        pair: SyncPair,
        remoteFilesByPath: Map<String, RemoteFile>,
        localIndexByPath: Map<String, LocalIndexEntity>,
        onProgress: suspend (TransferProgress) -> Unit = {},
    ): ApplyResult =
        withContext(ioDispatcher) {
            var applied = 0
            var conflicts = 0
            val errors = mutableListOf<String>()

            val totalFiles = ops.size
            // Pre-compute per-op transfer bytes once so we don't repeat map lookups
            // inside the hot apply loop.
            val opBytes = LongArray(totalFiles) { i ->
                opTransferBytes(ops[i], pair, remoteFilesByPath, localIndexByPath)
            }
            val totalBytes = opBytes.sum()
            var filesProcessed = 0
            var bytesTransferred = 0L

            for ((index, op) in ops.withIndex()) {
                var opSucceeded = false
                try {
                    when (op) {
                        is SyncOp.UploadNew -> {
                            applyUploadNew(op, pair)
                            applied++
                            eventRepository.log(
                                pair.id,
                                SyncEventLevel.INFO,
                                TAG,
                                "Uploaded new file: ${op.relativePath}",
                            )
                        }

                        is SyncOp.DownloadNew -> {
                            val remote =
                                remoteFilesByPath[op.relativePath]
                                    ?: error("Remote file not in snapshot for DownloadNew: ${op.relativePath}")
                            applyDownloadNew(op, pair, remote)
                            applied++
                            eventRepository.log(
                                pair.id,
                                SyncEventLevel.INFO,
                                TAG,
                                "Downloaded new file: ${op.relativePath}",
                            )
                        }

                        is SyncOp.UpdateRemote -> {
                            val index =
                                localIndexByPath[op.relativePath]
                                    ?: error("No index entry for UpdateRemote: ${op.relativePath}")
                            applyUpdateRemote(op, pair, index)
                            applied++
                            eventRepository.log(
                                pair.id,
                                SyncEventLevel.INFO,
                                TAG,
                                "Updated remote file: ${op.relativePath}",
                            )
                        }

                        is SyncOp.UpdateLocal -> {
                            val remote =
                                remoteFilesByPath[op.relativePath]
                                    ?: error("Remote file not in snapshot for UpdateLocal: ${op.relativePath}")
                            applyUpdateLocal(op, pair, remote)
                            applied++
                            eventRepository.log(
                                pair.id,
                                SyncEventLevel.INFO,
                                TAG,
                                "Updated local file: ${op.relativePath}",
                            )
                        }

                        is SyncOp.DeleteRemote -> {
                            val index =
                                localIndexByPath[op.relativePath]
                                    ?: error("No index entry for DeleteRemote: ${op.relativePath}")
                            applyDeleteRemote(op, pair, index)
                            applied++
                            eventRepository.log(
                                pair.id,
                                SyncEventLevel.INFO,
                                TAG,
                                "Deleted remote file: ${op.relativePath}",
                            )
                        }

                        is SyncOp.DeleteLocal -> {
                            applyDeleteLocal(op, pair)
                            applied++
                            eventRepository.log(
                                pair.id,
                                SyncEventLevel.INFO,
                                TAG,
                                "Deleted local file: ${op.relativePath}",
                            )
                        }

                        is SyncOp.DeleteLocalRetention -> {
                            applyDeleteLocal(SyncOp.DeleteLocal(op.relativePath), pair)
                            applied++
                            eventRepository.log(
                                pair.id,
                                SyncEventLevel.INFO,
                                TAG,
                                "Deleted local file (retention cleanup): ${op.relativePath}",
                            )
                        }

                        is SyncOp.DeleteRemoteRetention -> {
                            val index =
                                localIndexByPath[op.relativePath]
                                    ?: error("No index entry for DeleteRemoteRetention: ${op.relativePath}")
                            applyDeleteRemote(SyncOp.DeleteRemote(op.relativePath), pair, index)
                            applied++
                            eventRepository.log(
                                pair.id,
                                SyncEventLevel.INFO,
                                TAG,
                                "Deleted remote file (retention cleanup): ${op.relativePath}",
                            )
                        }

                        is SyncOp.Conflict -> {
                            applyConflict(op, pair, remoteFilesByPath, localIndexByPath)
                            conflicts++
                        }
                    }
                    opSucceeded = true
                } catch (e: CloudProviderException.AuthenticationRequired) {
                    throw e
                } catch (e: CloudProviderException.AuthenticationFailed) {
                    throw e
                } catch (e: CloudProviderException.NotConfigured) {
                    throw e
                } catch (e: Throwable) {
                    val msg = "Failed ${opLabel(op)}: ${e.message}"
                    errors += msg
                    eventRepository.log(pair.id, SyncEventLevel.ERROR, TAG, msg)
                }

                filesProcessed++
                if (opSucceeded) {
                    bytesTransferred += opBytes[index]
                }
                onProgress(
                    TransferProgress(
                        filesCompleted = filesProcessed,
                        totalFiles = totalFiles,
                        bytesTransferred = bytesTransferred,
                        totalBytes = totalBytes,
                    ),
                )
            }

            ApplyResult(applied = applied, conflicts = conflicts, errors = errors)
        }

    // -------------------------------------------------------------------------
    // Individual op handlers
    // -------------------------------------------------------------------------

    private suspend fun applyUploadNew(
        op: SyncOp.UploadNew,
        pair: SyncPair,
    ) {
        val stat =
            localFileAccess.stat(op.relativePath)
                ?: error("Local file not found for UploadNew: ${op.relativePath}")

        // Resolve or create the remote parent folder hierarchy so nested local
        // files are uploaded to the correct location. For flat paths (no '/'),
        // ensureRemoteFolderPath returns pair.remoteFolderId immediately with no
        // provider calls.
        val pathSegments = op.relativePath.split('/')
        val parentSegments = pathSegments.dropLast(1)
        val fileName = pathSegments.last()
        val parentId = ensureRemoteFolderPath(pair.remoteFolderId, parentSegments)

        var retried = false
        val remote =
            withRetry(onRetry = { _, _ -> retried = true }) {
                val stream =
                    localFileAccess.openRead(op.relativePath)
                        ?: error("Cannot read local file for UploadNew: ${op.relativePath}")
                provider.uploadNew(
                    parentId = parentId,
                    name = fileName,
                    content = stream,
                    size = stat.sizeBytes,
                    mimeType = stat.mimeType,
                )
            }
        if (retried) {
            eventRepository.log(pair.id, SyncEventLevel.WARN, TAG, "Retried upload: ${op.relativePath}")
        }
        localIndexDao.upsert(
            LocalIndexEntity(
                pairId = pair.id,
                relativePath = op.relativePath,
                sizeBytes = stat.sizeBytes,
                mtimeMs = stat.mtimeMs,
                contentHash = null,
                remoteId = remote.id,
                remoteSizeBytes = remote.size,
                remoteMtimeMs = remote.lastModifiedMs,
                remoteEtag = remote.eTag,
            ),
        )
    }

    private suspend fun applyDownloadNew(
        op: SyncOp.DownloadNew,
        pair: SyncPair,
        remote: RemoteFile,
    ) {
        var retried = false
        val stat =
            withRetry(onRetry = { _, _ -> retried = true }) {
                val stream = provider.download(remote.id)
                localFileAccess.write(op.relativePath, stream, remote.mimeType)
            }
        if (retried) {
            eventRepository.log(pair.id, SyncEventLevel.WARN, TAG, "Retried download: ${op.relativePath}")
        }
        localIndexDao.upsert(
            LocalIndexEntity(
                pairId = pair.id,
                relativePath = op.relativePath,
                sizeBytes = stat.sizeBytes,
                mtimeMs = stat.mtimeMs,
                contentHash = null,
                remoteId = remote.id,
                remoteSizeBytes = remote.size,
                remoteMtimeMs = remote.lastModifiedMs,
                remoteEtag = remote.eTag,
            ),
        )
    }

    private suspend fun applyUpdateRemote(
        op: SyncOp.UpdateRemote,
        pair: SyncPair,
        index: LocalIndexEntity,
    ) {
        val stat =
            localFileAccess.stat(op.relativePath)
                ?: error("Local file not found for UpdateRemote: ${op.relativePath}")
        val remoteId =
            index.remoteId
                ?: error("No remote ID in index for UpdateRemote: ${op.relativePath}")
        var retried = false
        val remote =
            withRetry(onRetry = { _, _ -> retried = true }) {
                val stream =
                    localFileAccess.openRead(op.relativePath)
                        ?: error("Cannot read local file for UpdateRemote: ${op.relativePath}")
                provider.updateContent(
                    id = remoteId,
                    content = stream,
                    size = stat.sizeBytes,
                    mimeType = stat.mimeType,
                )
            }
        if (retried) {
            eventRepository.log(pair.id, SyncEventLevel.WARN, TAG, "Retried update-remote: ${op.relativePath}")
        }
        localIndexDao.upsert(
            index.copy(
                sizeBytes = stat.sizeBytes,
                mtimeMs = stat.mtimeMs,
                contentHash = null,
                remoteId = remote.id,
                remoteSizeBytes = remote.size,
                remoteMtimeMs = remote.lastModifiedMs,
                remoteEtag = remote.eTag,
            ),
        )
    }

    private suspend fun applyUpdateLocal(
        op: SyncOp.UpdateLocal,
        pair: SyncPair,
        remote: RemoteFile,
    ) {
        var retried = false
        val stat =
            withRetry(onRetry = { _, _ -> retried = true }) {
                val stream = provider.download(remote.id)
                localFileAccess.write(op.relativePath, stream, remote.mimeType)
            }
        if (retried) {
            eventRepository.log(pair.id, SyncEventLevel.WARN, TAG, "Retried update-local: ${op.relativePath}")
        }
        localIndexDao.upsert(
            LocalIndexEntity(
                pairId = pair.id,
                relativePath = op.relativePath,
                sizeBytes = stat.sizeBytes,
                mtimeMs = stat.mtimeMs,
                contentHash = null,
                remoteId = remote.id,
                remoteSizeBytes = remote.size,
                remoteMtimeMs = remote.lastModifiedMs,
                remoteEtag = remote.eTag,
            ),
        )
    }

    private suspend fun applyDeleteRemote(
        op: SyncOp.DeleteRemote,
        pair: SyncPair,
        index: LocalIndexEntity,
    ) {
        val remoteId =
            index.remoteId
                ?: error("No remote ID in index for DeleteRemote: ${op.relativePath}")
        var retried = false
        withRetry(onRetry = { _, _ -> retried = true }) {
            provider.delete(remoteId)
        }
        if (retried) {
            eventRepository.log(pair.id, SyncEventLevel.WARN, TAG, "Retried delete-remote: ${op.relativePath}")
        }
        localIndexDao.delete(pair.id, op.relativePath)
    }

    private suspend fun applyDeleteLocal(
        op: SyncOp.DeleteLocal,
        pair: SyncPair,
    ) {
        localFileAccess.delete(op.relativePath)
        localIndexDao.delete(pair.id, op.relativePath)
    }

    private suspend fun applyConflict(
        op: SyncOp.Conflict,
        pair: SyncPair,
        remoteFilesByPath: Map<String, RemoteFile>,
        localIndexByPath: Map<String, LocalIndexEntity>,
    ) {
        when (pair.conflictPolicy) {
            ConflictPolicy.KEEP_BOTH -> {
                val now = System.currentTimeMillis()
                val remote = remoteFilesByPath[op.relativePath]
                val localStat = localFileAccess.stat(op.relativePath)
                conflictRepository.insert(
                    ConflictRecord(
                        id = 0,
                        pairId = pair.id,
                        relativePath = op.relativePath,
                        localLastModifiedMs = localStat?.mtimeMs ?: now,
                        remoteLastModifiedMs = remote?.lastModifiedMs ?: now,
                        detectedAtMs = now,
                    ),
                )
            }

            ConflictPolicy.PREFER_LOCAL -> {
                val index = localIndexByPath[op.relativePath]
                val remote = remoteFilesByPath[op.relativePath]
                if (remote != null && index?.remoteId != null) {
                    val stat =
                        localFileAccess.stat(op.relativePath)
                            ?: error("Local file not found for conflict resolution: ${op.relativePath}")
                    var retried = false
                    val updatedRemote =
                        withRetry(onRetry = { _, _ -> retried = true }) {
                            val stream =
                                localFileAccess.openRead(op.relativePath)
                                    ?: error("Cannot read local file for conflict resolution: ${op.relativePath}")
                            provider.updateContent(
                                id = index.remoteId,
                                content = stream,
                                size = stat.sizeBytes,
                                mimeType = stat.mimeType,
                            )
                        }
                    if (retried) {
                        eventRepository.log(
                            pair.id,
                            SyncEventLevel.WARN,
                            TAG,
                            "Retried conflict-local-wins: ${op.relativePath}",
                        )
                    }
                    localIndexDao.upsert(
                        index.copy(
                            sizeBytes = stat.sizeBytes,
                            mtimeMs = stat.mtimeMs,
                            contentHash = null,
                            remoteSizeBytes = updatedRemote.size,
                            remoteMtimeMs = updatedRemote.lastModifiedMs,
                            remoteEtag = updatedRemote.eTag,
                        ),
                    )
                } else if (remote == null) {
                    // Remote was deleted; upload as new
                    applyUploadNew(SyncOp.UploadNew(op.relativePath), pair)
                }
                eventRepository.log(
                    pair.id,
                    SyncEventLevel.INFO,
                    TAG,
                    "Conflict resolved (local wins): ${op.relativePath}",
                )
            }

            ConflictPolicy.NEWEST_WINS -> {
                // Delegate to local-wins or remote-wins based on which side SyncDiffer says is newer.
                if (op.localNewerThanRemote) {
                    applyConflict(
                        op,
                        pair.copy(conflictPolicy = ConflictPolicy.PREFER_LOCAL),
                        remoteFilesByPath,
                        localIndexByPath,
                    )
                } else {
                    applyConflict(
                        op,
                        pair.copy(conflictPolicy = ConflictPolicy.PREFER_REMOTE),
                        remoteFilesByPath,
                        localIndexByPath,
                    )
                }
                // INFO event was already emitted by the delegated call above.
                return
            }

            ConflictPolicy.PREFER_REMOTE -> {
                val remote = remoteFilesByPath[op.relativePath]
                if (remote != null) {
                    var retried = false
                    val stat =
                        withRetry(onRetry = { _, _ -> retried = true }) {
                            val stream = provider.download(remote.id)
                            localFileAccess.write(op.relativePath, stream, remote.mimeType)
                        }
                    if (retried) {
                        eventRepository.log(
                            pair.id,
                            SyncEventLevel.WARN,
                            TAG,
                            "Retried conflict-remote-wins: ${op.relativePath}",
                        )
                    }
                    localIndexDao.upsert(
                        LocalIndexEntity(
                            pairId = pair.id,
                            relativePath = op.relativePath,
                            sizeBytes = stat.sizeBytes,
                            mtimeMs = stat.mtimeMs,
                            contentHash = null,
                            remoteId = remote.id,
                            remoteSizeBytes = remote.size,
                            remoteMtimeMs = remote.lastModifiedMs,
                            remoteEtag = remote.eTag,
                        ),
                    )
                } else {
                    // Remote was deleted; delete local too
                    applyDeleteLocal(SyncOp.DeleteLocal(op.relativePath), pair)
                }
                eventRepository.log(
                    pair.id,
                    SyncEventLevel.INFO,
                    TAG,
                    "Conflict resolved (remote wins): ${op.relativePath}",
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Walks [segments] under [rootId], creating any missing intermediate folders
     * via [provider.createFolder]. If a folder with the given name already exists
     * as a direct child of the current parent it is reused without creating a
     * duplicate.
     *
     * Returns the provider ID of the innermost folder (i.e. the direct parent
     * that the file should be uploaded into). If [segments] is empty [rootId] is
     * returned immediately with no provider calls.
     *
     * **Performance note**: each path segment requires one `provider.list()` call
     * to detect whether the folder already exists. For typical sync payloads with
     * shallow nesting (1–2 levels) this is acceptable. Deeper hierarchies or
     * bulk operations could benefit from a per-run folder-ID cache, which can be
     * added if profiling shows it to be a bottleneck.
     *
     * @param rootId   The provider ID of the starting folder (e.g. [SyncPair.remoteFolderId]).
     * @param segments Path components to traverse/create, in order (e.g. `["docs", "subdir"]`).
     * @return Provider ID of the deepest resolved folder.
     */
    private suspend fun ensureRemoteFolderPath(
        rootId: String,
        segments: List<String>,
    ): String {
        var currentId = rootId
        for (segment in segments) {
            val children = provider.list(currentId)
            val existing = children.find { it.isFolder && it.name == segment }
            currentId = existing?.id ?: provider.createFolder(currentId, segment).id
        }
        return currentId
    }

    private fun opLabel(op: SyncOp): String =
        when (op) {
            is SyncOp.UploadNew -> "UploadNew(${op.relativePath})"
            is SyncOp.DownloadNew -> "DownloadNew(${op.relativePath})"
            is SyncOp.UpdateRemote -> "UpdateRemote(${op.relativePath})"
            is SyncOp.UpdateLocal -> "UpdateLocal(${op.relativePath})"
            is SyncOp.DeleteRemote -> "DeleteRemote(${op.relativePath})"
            is SyncOp.DeleteLocal -> "DeleteLocal(${op.relativePath})"
            is SyncOp.DeleteLocalRetention -> "DeleteLocalRetention(${op.relativePath})"
            is SyncOp.DeleteRemoteRetention -> "DeleteRemoteRetention(${op.relativePath})"
            is SyncOp.Conflict -> "Conflict(${op.relativePath})"
        }

    /**
     * Returns the number of bytes expected to be transferred for a single [op].
     *
     * For download ops the size comes from [remoteFilesByPath]; for upload ops it
     * comes from [localIndexByPath]. Returns 0 when the size is not known (new
     * local files not yet indexed, delete ops, unresolvable conflicts).
     */
    private fun opTransferBytes(
        op: SyncOp,
        pair: SyncPair,
        remoteFilesByPath: Map<String, RemoteFile>,
        localIndexByPath: Map<String, LocalIndexEntity>,
    ): Long =
        when (op) {
            is SyncOp.DownloadNew -> remoteFilesByPath[op.relativePath]?.size ?: 0L
            is SyncOp.UpdateLocal -> remoteFilesByPath[op.relativePath]?.size ?: 0L
            is SyncOp.UploadNew -> localIndexByPath[op.relativePath]?.sizeBytes ?: 0L
            is SyncOp.UpdateRemote -> localIndexByPath[op.relativePath]?.sizeBytes ?: 0L
            is SyncOp.Conflict -> conflictTransferBytes(op, pair, remoteFilesByPath, localIndexByPath)
            is SyncOp.DeleteRemote, is SyncOp.DeleteLocal -> 0L
            is SyncOp.DeleteLocalRetention, is SyncOp.DeleteRemoteRetention -> 0L
        }

    private fun conflictTransferBytes(
        op: SyncOp.Conflict,
        pair: SyncPair,
        remoteFilesByPath: Map<String, RemoteFile>,
        localIndexByPath: Map<String, LocalIndexEntity>,
    ): Long =
        when (pair.conflictPolicy) {
            ConflictPolicy.KEEP_BOTH -> 0L
            ConflictPolicy.PREFER_LOCAL ->
                localIndexByPath[op.relativePath]?.sizeBytes ?: 0L
            ConflictPolicy.PREFER_REMOTE ->
                remoteFilesByPath[op.relativePath]?.size ?: 0L
            ConflictPolicy.NEWEST_WINS ->
                if (op.localNewerThanRemote) {
                    localIndexByPath[op.relativePath]?.sizeBytes ?: 0L
                } else {
                    remoteFilesByPath[op.relativePath]?.size ?: 0L
                }
        }

    private companion object {
        const val TAG = "SyncOpApplier"
    }
}
