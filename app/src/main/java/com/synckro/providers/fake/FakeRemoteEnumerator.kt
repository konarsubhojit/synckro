package com.synckro.providers.fake

import com.synckro.domain.sync.RemoteChange
import com.synckro.domain.sync.RemoteChangeType
import com.synckro.domain.sync.RemoteEnumerator
import com.synckro.domain.sync.RemoteSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RemoteEnumerator] backed by [FakeCloudProvider]'s in-memory change log.
 *
 * Re-uses the provider's existing `changesSince` semantics — `null` token
 * yields an empty baseline snapshot, otherwise the slice of the log since the
 * recorded index is returned.
 *
 * Items with a non-null `file` become [RemoteChangeType.MODIFY]; items with a
 * non-null `removedId` become [RemoteChangeType.DELETE].
 */
@Singleton
class FakeRemoteEnumerator @Inject constructor(
    private val provider: FakeCloudProvider,
) : RemoteEnumerator {
    override suspend fun enumerate(deltaToken: String?): RemoteSnapshot {
        val page = provider.changesSince(deltaToken)
        val mapped = page.changes.mapNotNull { c ->
            when {
                c.removedId != null -> RemoteChange(
                    relativePath = c.removedId,
                    type = RemoteChangeType.DELETE,
                    remoteId = c.removedId,
                )
                c.file != null -> RemoteChange(
                    relativePath = c.file.name,
                    type = RemoteChangeType.MODIFY,
                    remoteId = c.file.id,
                    sizeBytes = c.file.size,
                    mtimeMs = c.file.lastModifiedMs,
                    etag = c.file.eTag,
                )
                else -> null
            }
        }
        return RemoteSnapshot(changes = mapped, newDeltaToken = page.nextToken)
    }
}
