package com.synckro.domain.sync

/** Kind of change reported by a [RemoteEnumerator]. */
enum class RemoteChangeType {
    /**
     * The provider reported the appearance of a previously-unseen item.
     *
     * Most cloud delta APIs (OneDrive `/delta`, Drive `changes.list`) do not
     * distinguish creation from modification — implementations may emit
     * [MODIFY] for both upserts and let the diff stage classify against the
     * local index.
     */
    ADD,

    /** The provider reported a content or metadata change to an existing item. */
    MODIFY,

    /** The provider reported the item has been deleted (or trashed). */
    DELETE,
}

/**
 * A single change emitted by a [RemoteEnumerator], mapped to a uniform shape
 * across providers.
 *
 * @property relativePath  Best-effort path relative to the synced root. Most
 *   provider delta APIs do not include a fully-resolved path; implementations
 *   typically populate this with the item's `name` and let the sync engine
 *   resolve a canonical path against [com.synckro.data.local.entity.LocalIndexEntity]
 *   via [remoteId].
 * @property type          The kind of change.
 * @property remoteId      The provider-specific identifier of the item. Stable
 *   across renames/moves on most providers.
 * @property sizeBytes     File size in bytes, when reported by the provider.
 * @property mtimeMs       Last-modified time as epoch milliseconds, when
 *   reported by the provider.
 * @property etag          Provider-supplied content fingerprint (eTag for
 *   OneDrive/Graph, md5Checksum for Google Drive).
 */
data class RemoteChange(
    val relativePath: String,
    val type: RemoteChangeType,
    val remoteId: String,
    val sizeBytes: Long? = null,
    val mtimeMs: Long? = null,
    val etag: String? = null,
    /**
     * `true` when the change represents a folder rather than a file.
     *
     * Populated by [RemoteEnumerator] implementations from the provider-specific
     * item metadata (e.g. [RemoteFile.isFolder], `GraphDriveItem.folder`,
     * `DriveFile.mimeType == FOLDER_MIME_TYPE`).  The sync engine uses this to
     * explicitly filter folder entries when [com.synckro.domain.model.SyncPair.excludeEmptyFolders]
     * is enabled.
     */
    val isFolder: Boolean = false,
    /**
     * Provider-supplied thumbnail URL for this remote item. Stored as
     * [com.synckro.data.local.entity.FileIndexEntity.remoteThumbnailUrl] so the
     * conflict inbox can display a thumbnail without a separate network call.
     *
     * Google Drive: `thumbnailLink` from the Files API response.
     * OneDrive: `@microsoft.graph.downloadUrl` pre-signed URL.
     * Null for folders, deletions, or when the provider did not include a thumbnail.
     */
    val thumbnailUrl: String? = null,
)

/**
 * The result of one call to [RemoteEnumerator.enumerate].
 *
 * @property changes        All changes since the previous delta token. Empty on
 *   the first ("baseline") call when the provider supports skipping history.
 * @property newDeltaToken  Opaque token to persist and pass back on the next
 *   call. Always populated.
 */
data class RemoteSnapshot(
    val changes: List<RemoteChange>,
    val newDeltaToken: String,
)

/**
 * Provider-agnostic abstraction over a cloud delta endpoint. Bound via Hilt
 * multibinding keyed by [com.synckro.domain.model.CloudProviderType]
 * so the sync engine can look up the correct enumerator at runtime.
 *
 * Implementations:
 * - Wrap their provider's delta/changes API and translate paged responses into
 *   a single uniform [RemoteSnapshot].
 * - Map provider-specific errors (HTTP 401, MSAL/Google auth failures, …) to
 *   [com.synckro.domain.provider.CloudProviderException] subtypes.
 *
 * On the first call (when no delta token has been persisted yet) callers pass
 * `null`; implementations should establish a baseline (empty change list +
 * fresh token) without replaying history.
 */
interface RemoteEnumerator {
    /**
     * Returns all changes since [deltaToken], plus a fresh token to use on the
     * next call.
     *
     * @param deltaToken Token returned by a previous call, or `null` to
     *   establish a baseline without replaying history.
     * @param rootFolderId The provider-specific ID of the sync root folder.
     *   Implementations use this to resolve canonical relative paths for items
     *   encountered in the delta response by walking the `parentReference` chain
     *   against items in the same batch. Pass an empty string when the root
     *   folder context is not available (e.g. in test doubles that supply a fixed
     *   snapshot); implementations must default to their best-effort leaf-name
     *   strategy in that case.
     */
    suspend fun enumerate(deltaToken: String?, rootFolderId: String = ""): RemoteSnapshot

    /**
     * Returns the current complete remote state as a [RemoteSnapshot], for use
     * when seeding a brand-new sync pair that has no prior delta token.
     *
     * The returned [RemoteSnapshot.changes] contains **all** currently-existing
     * remote files as [RemoteChangeType.MODIFY] entries, so that [SyncDiffer]
     * can produce [com.synckro.domain.sync.SyncOp.DownloadNew] operations for
     * each file that is absent locally.  [RemoteSnapshot.newDeltaToken] is a
     * fresh token suitable for passing to [enumerate] on the next incremental
     * sync run.
     *
     * The default implementation delegates to [enumerate] with a null delta
     * token, which establishes a baseline without replaying history (i.e. it
     * returns an empty change list).  Concrete implementations that can perform
     * a true full folder listing — for example OneDrive `/delta` without
     * `$deltaToken=latest`, or a recursive `files.list` for Google Drive —
     * **should override** this method to return all existing items.
     *
     * @param rootFolderId The provider-specific ID of the sync root folder.
     *   Pass an empty string when unavailable; the default implementation
     *   forwards it to [enumerate] unchanged.
     */
    suspend fun enumerateFull(rootFolderId: String = ""): RemoteSnapshot = enumerate(null, rootFolderId)
}
