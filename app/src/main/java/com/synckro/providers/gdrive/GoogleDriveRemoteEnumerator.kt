package com.synckro.providers.gdrive

import com.synckro.domain.provider.CloudProviderException
import com.synckro.domain.sync.AccountAwareRemoteEnumerator
import com.synckro.domain.sync.RemoteChange
import com.synckro.domain.sync.RemoteChangeType
import com.synckro.domain.sync.RemoteEnumerator
import com.synckro.domain.sync.RemoteSnapshot
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RemoteEnumerator] for Google Drive, backed by
 * [GoogleDriveRestClient.changesSince].
 *
 * Behaviour:
 * - On the first call (`deltaToken == null`), the underlying client requests
 *   `changes/startPageToken` to obtain a fresh baseline token without
 *   replaying history. The returned snapshot has an empty change list and the
 *   `startPageToken` as the new token.
 * - On subsequent calls the persisted page token is used; the client follows
 *   `nextPageToken` until `newStartPageToken` is present, collecting all
 *   changes.
 *
 * Items flagged `removed=true` or `file.trashed=true` become
 * [RemoteChangeType.DELETE]; all other items become [RemoteChangeType.MODIFY]
 * (the diff stage classifies ADD vs MODIFY against the local index).
 */
@Singleton
class GoogleDriveRemoteEnumerator
    @Inject
    constructor(
        private val providerFactory: GoogleDriveProviderFactory,
        private val restClient: GoogleDriveRestClient,
    ) : RemoteEnumerator, AccountAwareRemoteEnumerator {
        /**
         * Acquires an access token via [provider] and delegates to
         * [enumerateWithToken]. Authentication errors are surfaced as
         * [CloudProviderException] subtypes.
         */
        override suspend fun enumerate(deltaToken: String?, rootFolderId: String): RemoteSnapshot {
            throw CloudProviderException.AuthenticationRequired("Google Drive enumerate requires an account id.")
        }

        /**
         * Performs a full initial listing of [rootFolderId] by recursively walking the
         * folder tree via [GoogleDriveRestClient.listAllDescendants], then fetches a fresh
         * `startPageToken` for subsequent incremental polling.
         *
         * When [rootFolderId] is empty, falls back to the baseline [enumerate]
         * (empty changes + fresh startPageToken).
         */
        override suspend fun enumerateFull(rootFolderId: String): RemoteSnapshot {
            throw CloudProviderException.AuthenticationRequired("Google Drive enumerateFull requires an account id.")
        }

        override suspend fun enumerateForAccount(
            accountId: String,
            deltaToken: String?,
            rootFolderId: String,
        ): RemoteSnapshot {
            val token = providerFor(accountId).obtainAccessToken()
            return enumerateWithToken(token, deltaToken, rootFolderId)
        }

        override suspend fun enumerateFullForAccount(
            accountId: String,
            rootFolderId: String,
        ): RemoteSnapshot {
            if (rootFolderId.isEmpty()) return enumerateForAccount(accountId, null, rootFolderId)
            val token = providerFor(accountId).obtainAccessToken()
            return enumerateAllWithToken(token, rootFolderId)
        }

        /**
         * Test seam: enumerate using a directly-supplied bearer token. Tests can
         * point [restClient] at a `MockWebServer` and exercise the full HTTP
         * round-trip without going through Google Identity.
         */
        internal suspend fun enumerateWithToken(
            token: String,
            deltaToken: String?,
            rootFolderId: String = "",
        ): RemoteSnapshot {
            val (changes, nextToken) =
                try {
                    restClient.changesSince(token, deltaToken)
                } catch (e: DriveApiException) {
                    when (e.statusCode) {
                        401 -> throw CloudProviderException.AuthenticationRequired(
                            "Google Drive access token rejected (401). Please re-authenticate.",
                            e,
                        )
                        410 -> {
                            // Page token has expired — fall back to a fresh baseline so the next
                            // sync starts from a clean slate without replaying history.
                            // This is a normal operational scenario: tokens expire after ~7 days
                            // of inactivity per the Google Drive API contract.
                            Timber.w("Google Drive: changes page token expired (410); deltaToken=%s; falling back to baseline", deltaToken)
                            val (baseChanges, baseToken) = restClient.changesSince(token, null)
                            return RemoteSnapshot(
                                changes = baseChanges.mapNotNull { it.toRemoteChange(buildPathCache(baseChanges, rootFolderId)) },
                                newDeltaToken = baseToken,
                            )
                        }
                        else -> throw e
                    }
                }

            val pathCache = buildPathCache(changes, rootFolderId)
            val mapped = changes.mapNotNull { it.toRemoteChange(pathCache) }
            return RemoteSnapshot(changes = mapped, newDeltaToken = nextToken)
        }

        /**
         * Test seam: perform a full initial listing using a directly-supplied bearer token.
         * Calls [GoogleDriveRestClient.listAllDescendants] which recursively lists all
         * non-folder files under [rootFolderId] with their relative paths.
         */
        internal suspend fun enumerateAllWithToken(
            token: String,
            rootFolderId: String,
        ): RemoteSnapshot {
            return try {
                val (filesWithPaths, startPageToken) = restClient.listAllDescendants(token, rootFolderId)
                val changes =
                    filesWithPaths.mapNotNull { (file, relativePath) ->
                        if (file.id.isEmpty()) return@mapNotNull null
                        RemoteChange(
                            relativePath = relativePath,
                            type = RemoteChangeType.MODIFY,
                            remoteId = file.id,
                            sizeBytes = file.size?.toLongOrNull(),
                            mtimeMs = file.modifiedTime?.let { parseIso8601(it) },
                            etag = file.md5Checksum,
                        )
                    }
                RemoteSnapshot(changes = changes, newDeltaToken = startPageToken)
            } catch (e: DriveApiException) {
                when (e.statusCode) {
                    401 -> throw CloudProviderException.AuthenticationRequired(
                        "Google Drive access token rejected (401). Please re-authenticate.",
                        e,
                    )
                    else -> throw e
                }
            }
        }

        private fun providerFor(accountId: String): GoogleDriveProvider = providerFactory.providerFor(accountId) as GoogleDriveProvider
    }

/**
 * Builds an in-batch ID→relative-path cache for the files reported in a single
 * Drive changes response. Files whose first parent ID matches [rootFolderId]
 * are direct children of the sync root and receive a single-segment path.
 * Files whose parent is already in the cache receive a compound path. Files
 * whose parent cannot be resolved fall back to their leaf name.
 *
 * Google Drive's `changes.list` response does not guarantee hierarchy order,
 * but most delta responses return items in a useful order. Items whose parent
 * is not in this batch fall back to their leaf name; the SyncEngine then
 * resolves the canonical path via the stable remoteId → pre-scan index lookup
 * for items that have already been synced.
 *
 * @param changes      All [DriveChange] entries from one or more change pages.
 * @param rootFolderId Provider ID of the sync root folder. Pass an empty string
 *   to skip hierarchy resolution (every item gets its leaf name).
 */
internal fun buildPathCache(
    changes: List<DriveChange>,
    rootFolderId: String,
): Map<String, String> {
    val cache = mutableMapOf<String, String>()
    for (change in changes) {
        val file = change.file ?: continue
        if (file.id.isEmpty()) continue
        val parentId = file.parents?.firstOrNull()
        val path =
            when {
                rootFolderId.isNotEmpty() && parentId == rootFolderId -> file.name
                parentId != null && cache.containsKey(parentId) -> "${cache[parentId]}/${file.name}"
                else -> file.name // parent not in this batch; fall back to leaf name
            }
        cache[file.id] = path
    }
    return cache
}

/**
 * Maps a Drive v3 [DriveChange] entry to a [RemoteChange]. Returns `null` for
 * entries without a usable identifier.
 *
 * @param pathCache Optional ID→relative-path cache built from the same change
 *   batch via [buildPathCache]. When present, the file's relative path is
 *   resolved hierarchically; otherwise the leaf name is used.
 */
internal fun DriveChange.toRemoteChange(pathCache: Map<String, String> = emptyMap()): RemoteChange? {
    val id = fileId ?: file?.id ?: return null
    val isRemoved = removed == true || file?.trashed == true
    val resolvedPath = pathCache[id] ?: file?.name ?: id
    if (isRemoved) {
        return RemoteChange(
            relativePath = resolvedPath.ifEmpty { id },
            type = RemoteChangeType.DELETE,
            remoteId = id,
        )
    }
    val f = file ?: return null
    return RemoteChange(
        relativePath = resolvedPath,
        type = RemoteChangeType.MODIFY,
        remoteId = id,
        sizeBytes = f.size?.toLongOrNull(),
        mtimeMs = f.modifiedTime?.let { parseIso8601(it) },
        etag = f.md5Checksum,
        isFolder = f.mimeType == FOLDER_MIME_TYPE,
    )
}
