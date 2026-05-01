package com.konarsubhojit.synckro.domain.sync

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
 *   resolve a canonical path against [com.konarsubhojit.synckro.data.local.entity.LocalIndexEntity]
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
 * multibinding keyed by [com.konarsubhojit.synckro.domain.model.CloudProviderType]
 * so the sync engine can look up the correct enumerator at runtime.
 *
 * Implementations:
 * - Wrap their provider's delta/changes API and translate paged responses into
 *   a single uniform [RemoteSnapshot].
 * - Map provider-specific errors (HTTP 401, MSAL/Google auth failures, …) to
 *   [com.konarsubhojit.synckro.domain.provider.CloudProviderException] subtypes.
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
     */
    suspend fun enumerate(deltaToken: String?): RemoteSnapshot
}
