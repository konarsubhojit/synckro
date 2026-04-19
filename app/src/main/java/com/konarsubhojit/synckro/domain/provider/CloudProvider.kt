package com.konarsubhojit.synckro.domain.provider

import java.io.InputStream

/**
 * Provider-agnostic view of a remote file or folder. Fields that are not
 * available on a given provider should be left null rather than faked.
 */
data class RemoteFile(
    val id: String,
    val name: String,
    val parentId: String?,
    val isFolder: Boolean,
    val size: Long?,
    val lastModifiedMs: Long?,
    val eTag: String?,
    val mimeType: String?,
)

/**
 * A single change reported by a provider's delta/changes endpoint. Exactly one
 * of [file] and [removedId] must be non-null.
 */
data class RemoteChange(
    val file: RemoteFile?,
    val removedId: String?,
) {
    init {
        require((file == null) != (removedId == null)) {
            "Exactly one of file or removedId must be set on a RemoteChange"
        }
    }
    val isDeletion: Boolean get() = removedId != null
}

/** Result of a page of changes, with the token required to continue. */
data class ChangesPage(
    val changes: List<RemoteChange>,
    val nextToken: String,
    val hasMore: Boolean,
)

/**
 * Abstraction over a cloud storage provider (OneDrive, Google Drive, …).
 *
 * Implementations MUST be thread-safe: callers may issue multiple concurrent
 * download/upload requests. All methods are suspending and should perform
 * their own IO on an appropriate dispatcher.
 */
interface CloudProvider {

    /** Human-readable identifier, e.g. "OneDrive" or "Google Drive". */
    val displayName: String

    /** Ensures the provider has a valid access token, returning true on success. */
    suspend fun ensureAuthenticated(): Boolean

    /** List direct children of [folderId] (or the root if null). */
    suspend fun list(folderId: String?): List<RemoteFile>

    /** Fetch metadata for a single item by id. */
    suspend fun getMetadata(id: String): RemoteFile

    /**
     * Stream [id]'s contents. The caller is responsible for closing the
     * returned [InputStream].
     */
    suspend fun download(id: String): InputStream

    /**
     * Upload [content] as a new file under [parentId] with [name]. [size] may
     * be -1 if unknown — providers that require a known length MUST read into
     * a temporary buffer first.
     *
     * The provider takes ownership of [content] and MUST close it before
     * returning, even on failure.
     */
    suspend fun uploadNew(
        parentId: String,
        name: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile

    /**
     * Overwrite the contents of an existing file.
     *
     * The provider takes ownership of [content] and MUST close it before
     * returning, even on failure.
     */
    suspend fun updateContent(
        id: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile

    /** Create a folder [name] under [parentId] and return it. */
    suspend fun createFolder(parentId: String, name: String): RemoteFile

    /** Delete the item identified by [id]. */
    suspend fun delete(id: String)

    /**
     * Incremental changes since [token]. Pass `null` on the first call to get
     * an initial token without fetching historical data.
     */
    suspend fun changesSince(token: String?): ChangesPage
}
