package com.synckro.providers.fake

import com.synckro.domain.provider.RemoteFile
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
 * Items with a non-null `file` become [RemoteChangeType.MODIFY] with the canonical
 * relative path resolved via [FakeCloudProvider.resolvePath]; items with a non-null
 * `removedId` become [RemoteChangeType.DELETE] (the path is resolved from the stable
 * remote ID by the sync engine using the pre-scan index).
 */
@Singleton
class FakeRemoteEnumerator
    @Inject
    constructor(
        private val provider: FakeCloudProvider,
    ) : RemoteEnumerator {
        override suspend fun enumerate(deltaToken: String?, rootFolderId: String): RemoteSnapshot {
            val page = provider.changesSince(deltaToken)
            val mapped =
                page.changes.mapNotNull { c ->
                    when {
                        c.removedId != null ->
                            RemoteChange(
                                // Deleted items are no longer in the store; the sync engine
                                // looks up the canonical path via the stable remoteId in the
                                // pre-scan index, so we use the remoteId as a fallback here.
                                relativePath = c.removedId,
                                type = RemoteChangeType.DELETE,
                                remoteId = c.removedId,
                            )
                        c.file != null ->
                            RemoteChange(
                                // Resolve the full path relative to the sync root so that
                                // nested files are represented with their complete path
                                // (e.g. "docs/subdir/report.pdf" rather than "report.pdf").
                                relativePath = provider.resolvePath(c.file.id).ifEmpty { c.file.name },
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

        /**
         * Returns the complete current remote state for a brand-new pair by recursively
         * listing all files under [rootFolderId] via [FakeCloudProvider.list].
         *
         * When [rootFolderId] is empty, falls back to the baseline [enumerate] (empty
         * changes + current token), because there is no root to list from.
         */
        override suspend fun enumerateFull(rootFolderId: String): RemoteSnapshot {
            if (rootFolderId.isEmpty()) return enumerate(null, rootFolderId)
            val baselineToken = provider.changesSince(null).nextToken
            val changes = listAllFiles(rootFolderId)
            return RemoteSnapshot(changes = changes, newDeltaToken = baselineToken)
        }

        /**
         * Recursively collects all non-folder items under [folderId] as
         * [RemoteChangeType.MODIFY] [RemoteChange] entries.
         */
        private suspend fun listAllFiles(folderId: String): List<RemoteChange> {
            val items: List<RemoteFile> = provider.list(folderId)
            return items.flatMap { item ->
                if (item.isFolder) {
                    listAllFiles(item.id)
                } else {
                    listOf(
                        RemoteChange(
                            relativePath = provider.resolvePath(item.id).ifEmpty { item.name },
                            type = RemoteChangeType.MODIFY,
                            remoteId = item.id,
                            sizeBytes = item.size,
                            mtimeMs = item.lastModifiedMs,
                            etag = item.eTag,
                        ),
                    )
                }
            }
        }
    }
