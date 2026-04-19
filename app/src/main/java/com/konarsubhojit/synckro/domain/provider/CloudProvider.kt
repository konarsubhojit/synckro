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

    /**
 * Ensure the provider has a valid access token.
 *
 * @return `true` if authentication (or token refresh) succeeded, `false` otherwise.
 */
    suspend fun ensureAuthenticated(): Boolean

    /**
 * Lists direct children of the given folder or the root when `folderId` is `null`.
 *
 * @param folderId The parent folder's id, or `null` to list root entries.
 * @return A list of `RemoteFile` objects representing the direct children of the specified folder.
 */
    suspend fun list(folderId: String?): List<RemoteFile>

    /**
 * Fetches metadata for the remote item identified by the given id.
 *
 * @param id The provider-specific identifier of the remote item.
 * @return The item's metadata as a `RemoteFile`.
 */
    suspend fun getMetadata(id: String): RemoteFile

    /**
 * Open a read stream for the contents of the remote item identified by `id`.
 *
 * The caller is responsible for closing the returned stream.
 *
 * @param id The remote item's identifier.
 * @return An `InputStream` providing the item's contents; the caller must close it. 
 */
    suspend fun download(id: String): InputStream

    /**
     * Uploads a new file named [name] under [parentId] with the provided content.
     *
     * The provider takes ownership of [content] and must close it before returning, even on failure.
     *
     * @param content The input stream supplying file bytes; ownership is transferred to the provider and it will be closed by the provider.
     * @param size The length of [content] in bytes, or -1 if unknown. Providers that require a known length must buffer the stream first.
     * @param mimeType The MIME type to associate with the uploaded file, or `null` if unspecified.
     * @return The created `RemoteFile` representing the uploaded file.
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
     * The provider takes ownership of [content] and MUST close it before returning, even on failure.
     *
     * @param id The identifier of the file to update.
     * @param content Stream providing the new file contents; the provider assumes ownership and will close it.
     * @param size The length of [content] in bytes, or `-1` if unknown; providers that require a known length may buffer.
     * @param mimeType Optional MIME type hint for the new content.
     * @return The updated RemoteFile reflecting metadata after the content replacement.
     */
    suspend fun updateContent(
        id: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile

    /**
 * Creates a folder named [name] under the specified [parentId].
 *
 * @param parentId ID of the parent folder under which to create the new folder.
 * @param name The name of the folder to create.
 * @return The created folder as a [RemoteFile].
 */
    suspend fun createFolder(parentId: String, name: String): RemoteFile

    /**
 * Delete the remote item with the given identifier.
 *
 * @param id The provider-specific identifier of the remote item to delete.
 */
    suspend fun delete(id: String)

    /**
 * Requests incremental change events from the provider since the given token.
 *
 * Pass `null` on the first call to obtain an initial token without retrieving past changes.
 *
 * @param token Paging token returned by a previous call, or `null` to obtain an initial token.
 * @return A ChangesPage containing the list of changes, a token for the next page, and a flag indicating whether more pages exist.
 */
    suspend fun changesSince(token: String?): ChangesPage
}
