package com.konarsubhojit.synckro.providers.fake

import com.konarsubhojit.synckro.domain.provider.ChangesPage
import com.konarsubhojit.synckro.domain.provider.CloudProvider
import com.konarsubhojit.synckro.domain.provider.RemoteChange
import com.konarsubhojit.synckro.domain.provider.RemoteFile
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [CloudProvider] for use in unit tests and developer preview builds.
 * Keeps a simple map of id → file record and a monotonically-increasing change
 * log for [changesSince].
 */
class FakeCloudProvider : CloudProvider {

    override val displayName: String = "Fake"

    private data class Record(val meta: RemoteFile, val bytes: ByteArray)

    private val store = ConcurrentHashMap<String, Record>()
    private val changeLog = mutableListOf<RemoteChange>()
    private val mutex = Mutex()

    /**
     * Indicates whether the provider is authenticated.
     *
     * @return `true` if the provider is authenticated; always `true` for this fake provider.
     */
    override suspend fun ensureAuthenticated(): Boolean = true

    /**
     * Lists stored remote files whose `parentId` matches the provided folder ID.
     *
     * @param folderId The parent folder ID to filter by; pass `null` to list items with no parent.
     * @return A list of `RemoteFile` metadata entries whose `parentId` equals `folderId`.
     */
    override suspend fun list(folderId: String?): List<RemoteFile> =
        store.values.map { it.meta }.filter { it.parentId == folderId }

    /**
     * Retrieve the metadata for a remote file or folder by its id.
     *
     * @param id The file or folder id to look up.
     * @return The corresponding RemoteFile metadata.
     * @throws IllegalStateException if no record exists for the given id.
     */
    override suspend fun getMetadata(id: String): RemoteFile =
        store[id]?.meta ?: error("Not found: $id")

    /**
     * Provides an InputStream for the bytes of the file identified by the given id.
     *
     * @param id The id of the file to download.
     * @return An InputStream that yields the file's bytes.
     * @throws IllegalStateException if no record exists for the given id.
     * @throws IllegalArgumentException if the id refers to a folder (folders cannot be downloaded).
     */
    override suspend fun download(id: String): InputStream {
        val rec = store[id] ?: error("Not found: $id")
        require(!rec.meta.isFolder) { "Cannot download a folder: $id" }
        return ByteArrayInputStream(rec.bytes)
    }

    /**
     * Creates a new file under the given parent and stores its contents in-memory.
     *
     * The provided `content` stream is fully read and closed; the resulting file `size` is set
     * from the actual number of bytes read (the `size` parameter is ignored). A corresponding
     * creation entry is appended to the provider's change log.
     *
     * @param parentId ID of the folder to create the file in.
     * @param name The name for the new file.
     * @param content Stream providing the file contents; this stream is consumed and closed.
     * @param size Ignored — the stored file size is determined from the actual content bytes.
     * @param mimeType Optional MIME type to record for the file.
     * @return The metadata of the newly created `RemoteFile`.
     */
    override suspend fun uploadNew(
        parentId: String,
        name: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile = mutex.withLock {
        val bytes = content.use { it.readBytes() }
        val meta = RemoteFile(
            id = UUID.randomUUID().toString(),
            name = name,
            parentId = parentId,
            isFolder = false,
            size = bytes.size.toLong(),
            lastModifiedMs = System.currentTimeMillis(),
            eTag = UUID.randomUUID().toString(),
            mimeType = mimeType,
        )
        store[meta.id] = Record(meta, bytes)
        changeLog += RemoteChange(file = meta, removedId = null)
        meta
    }

    /**
     * Replaces the content of an existing remote file and returns its updated metadata.
     *
     * Reads and stores all bytes from [content], updates the file's size, last-modified time,
     * `eTag`, and `mimeType` (if provided), appends a change entry, and returns the new metadata.
     *
     * Note: the [size] parameter is ignored; the stored size is derived from the actual bytes read.
     *
     * @param id The identifier of the file to update; must already exist.
     * @param content The input stream whose bytes will replace the file's content; the stream is consumed and closed.
     * @param size Ignored — included for API compatibility.
     * @param mimeType If non-null, sets the file's MIME type; otherwise preserves the existing MIME type.
     * @return The updated `RemoteFile` metadata for the file with id [id].
     * @throws IllegalStateException if no file with the given [id] exists.
     */
    override suspend fun updateContent(
        id: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile = mutex.withLock {
        val existing = store[id] ?: error("Not found: $id")
        require(!existing.meta.isFolder) { "Cannot update content of a folder: $id" }
        val bytes = content.use { it.readBytes() }
        val meta = existing.meta.copy(
            size = bytes.size.toLong(),
            lastModifiedMs = System.currentTimeMillis(),
            eTag = UUID.randomUUID().toString(),
            mimeType = mimeType ?: existing.meta.mimeType,
        )
        store[id] = Record(meta, bytes)
        changeLog += RemoteChange(file = meta, removedId = null)
        meta
    }

    /**
     * Creates a new folder with the given name under the specified parent and records it in the fake provider.
     *
     * The created folder has empty content, no `size`, `eTag`, or `mimeType`, and an autogenerated `id` and `lastModifiedMs`.
     * A corresponding creation entry is appended to the provider's change log.
     *
     * @param parentId The id of the parent folder.
     * @param name The new folder's name.
     * @return The metadata for the created folder.
     */
    override suspend fun createFolder(parentId: String, name: String): RemoteFile = mutex.withLock {
        val meta = RemoteFile(
            id = UUID.randomUUID().toString(),
            name = name,
            parentId = parentId,
            isFolder = true,
            size = null,
            lastModifiedMs = System.currentTimeMillis(),
            eTag = null,
            mimeType = null,
        )
        store[meta.id] = Record(meta, ByteArray(0))
        changeLog += RemoteChange(file = meta, removedId = null)
        meta
    }

    /**
     * Delete the stored record with the given id and record the removal in the change log.
     *
     * If a record with `id` exists it is removed and a `RemoteChange` with `removedId = id` is appended;
     * if no record exists the call has no effect.
     *
     * @param id The identifier of the file or folder to delete.
     */
    override suspend fun delete(id: String) = mutex.withLock {
        if (store.remove(id) != null) {
            changeLog += RemoteChange(file = null, removedId = id)
        }
    }

    /**
     * Produces a page of recorded changes starting from the provided change token.
     *
     * If `token` is null, returns an initial (empty) page with `nextToken` set to the current change log size
     * so callers can establish a baseline without receiving historical changes. If `token` is present,
     * it is parsed as an integer index and the slice of changes from that index to the end of the change log
     * is returned. The returned `ChangesPage.nextToken` is always the current change log size and `hasMore`
     * is always `false`.
     *
     * @param token A string token previously returned by this method, or `null` to request the initial baseline.
     * @return A `ChangesPage` containing the requested changes, the next token equal to the current change log size,
     *         and `hasMore = false`.
     * @throws IllegalArgumentException If `token` is non-null but not a valid integer, or if the parsed index is
     *         outside the range `0..changeLog.size`.
     */
    override suspend fun changesSince(token: String?): ChangesPage = mutex.withLock {
        // Contract: a null token establishes the initial delta state without
        // replaying history — matches OneDrive `/delta` and Drive
        // `changes.getStartPageToken` first-call behaviour.
        if (token == null) {
            return@withLock ChangesPage(
                changes = emptyList(),
                nextToken = changeLog.size.toString(),
                hasMore = false,
            )
        }
        val start = requireNotNull(token.toIntOrNull()) { "Malformed change token: $token" }
        require(start in 0..changeLog.size) { "Change token out of range: $start" }
        val slice = changeLog.subList(start, changeLog.size).toList()
        ChangesPage(
            changes = slice,
            nextToken = changeLog.size.toString(),
            hasMore = false,
        )
    }
}
