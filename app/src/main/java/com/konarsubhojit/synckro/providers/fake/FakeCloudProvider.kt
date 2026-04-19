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

    override suspend fun ensureAuthenticated(): Boolean = true

    override suspend fun list(folderId: String?): List<RemoteFile> =
        store.values.map { it.meta }.filter { it.parentId == folderId }

    override suspend fun getMetadata(id: String): RemoteFile =
        store[id]?.meta ?: error("Not found: $id")

    override suspend fun download(id: String): InputStream {
        val rec = store[id] ?: error("Not found: $id")
        require(!rec.meta.isFolder) { "Cannot download a folder: $id" }
        return ByteArrayInputStream(rec.bytes)
    }

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

    override suspend fun updateContent(
        id: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile = mutex.withLock {
        val existing = store[id] ?: error("Not found: $id")
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

    override suspend fun delete(id: String) = mutex.withLock {
        if (store.remove(id) != null) {
            changeLog += RemoteChange(file = null, removedId = id)
        }
    }

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
