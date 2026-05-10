package com.synckro.providers.onedrive

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
 * [RemoteEnumerator] for OneDrive, backed by [OneDriveGraphClient.changesSince].
 *
 * Behaviour:
 * - On the first call (`deltaToken == null`), the underlying client requests
 *   `root/delta?$deltaToken=latest`, which establishes a baseline without
 *   replaying history. The returned snapshot has an empty change list and the
 *   initial `@odata.deltaLink` as the new token.
 * - On subsequent calls the persisted deltaLink is followed (with
 *   `@odata.nextLink` paging) and all collected items are mapped to
 *   [RemoteChange] entries.
 *
 * Items with a `deleted` facet become [RemoteChangeType.DELETE]; all other
 * items become [RemoteChangeType.MODIFY] (the diff stage classifies ADD vs
 * MODIFY against the local index).
 */
@Singleton
class OneDriveRemoteEnumerator
    @Inject
    constructor(
        private val providerFactory: OneDriveProviderFactory,
        private val graphClient: OneDriveGraphClient,
    ) : RemoteEnumerator, AccountAwareRemoteEnumerator {
        /**
         * Account-unaware entrypoint is unsupported for OneDrive after
         * multi-account migration; callers must use
         * [enumerateForAccount] via [AccountAwareRemoteEnumerator].
         */
        override suspend fun enumerate(deltaToken: String?, rootFolderId: String): RemoteSnapshot {
            throw CloudProviderException.AuthenticationRequired("OneDrive enumerate requires an account id.")
        }

        /**
         * Account-unaware entrypoint is unsupported for OneDrive after
         * multi-account migration; callers must use
         * [enumerateFullForAccount] via [AccountAwareRemoteEnumerator].
         */
        override suspend fun enumerateFull(rootFolderId: String): RemoteSnapshot {
            throw CloudProviderException.AuthenticationRequired("OneDrive enumerateFull requires an account id.")
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
         * point [graphClient] at a `MockWebServer` and exercise the full HTTP
         * round-trip without going through MSAL.
         */
        internal suspend fun enumerateWithToken(
            token: String,
            deltaToken: String?,
            rootFolderId: String = "",
        ): RemoteSnapshot {
            val (items, nextDeltaLink) =
                try {
                    graphClient.changesSince(token, deltaToken)
                } catch (e: GraphApiException) {
                    when (e.statusCode) {
                        401 -> throw CloudProviderException.AuthenticationRequired(
                            "OneDrive access token rejected by Graph API (401). Please re-authenticate.",
                            e,
                        )
                        410 -> {
                            // Delta link has expired — fall back to a fresh baseline so the next
                            // sync starts from a clean slate without replaying history.
                            // This is a normal operational scenario: delta links expire after ~30
                            // days of inactivity per the Microsoft Graph API contract.
                            Timber.w("OneDrive: delta link expired (410); deltaToken=%s; falling back to baseline", deltaToken)
                            val (baseItems, baseDeltaLink) = graphClient.changesSince(token, null)
                            return RemoteSnapshot(
                                changes = baseItems.mapNotNull { it.toRemoteChange(buildPathCache(baseItems, rootFolderId)) },
                                newDeltaToken = baseDeltaLink,
                            )
                        }
                        else -> throw e
                    }
                }

            val pathCache = buildPathCache(items, rootFolderId)
            val changes = items.mapNotNull { it.toRemoteChange(pathCache) }
            return RemoteSnapshot(changes = changes, newDeltaToken = nextDeltaLink)
        }

        /**
         * Test seam: perform a full initial listing using a directly-supplied bearer token.
         * Calls [OneDriveGraphClient.listAll] which uses the `/delta` endpoint without
         * `$deltaToken=latest` to return all current items under [rootFolderId].
         */
        internal suspend fun enumerateAllWithToken(
            token: String,
            rootFolderId: String,
        ): RemoteSnapshot {
            return try {
                val (items, nextDeltaLink) = graphClient.listAll(token, rootFolderId)
                // Filter out the root folder itself (included by the delta endpoint) and folder items;
                // only files are relevant for the sync diff.
                val fileItems = items.filter { it.folder == null && it.deleted == null }
                val pathCache = buildPathCache(fileItems, rootFolderId)
                val changes = fileItems.mapNotNull { it.toRemoteChange(pathCache) }
                RemoteSnapshot(changes = changes, newDeltaToken = nextDeltaLink)
            } catch (e: GraphApiException) {
                when (e.statusCode) {
                    401 -> throw CloudProviderException.AuthenticationRequired(
                        "OneDrive access token rejected by Graph API (401). Please re-authenticate.",
                        e,
                    )
                    else -> throw e
                }
            }
        }

        private fun providerFor(accountId: String): OneDriveProvider {
            val provider = providerFactory.providerFor(accountId)
            return provider as? OneDriveProvider
                ?: error("OneDriveProviderFactory returned unexpected provider type: ${provider::class.java.simpleName}")
        }
    }

/**
 * Builds an in-batch ID→relative-path cache for the items returned by a single
 * delta response. Items whose `parentReference.id` matches [rootFolderId] are
 * direct children of the sync root and get a single-segment path. Items whose
 * parent is already in the cache get a compound path. Items whose parent cannot
 * be resolved fall back to their leaf name.
 *
 * OneDrive's delta API returns items in hierarchy order (parents before
 * children) within a single page, so this linear pass is sufficient for items
 * within the same batch.
 *
 * @param items       All [GraphDriveItem] entries from one or more delta pages.
 * @param rootFolderId Provider ID of the sync root folder. Pass an empty string
 *   to skip hierarchy resolution (every item gets its leaf name).
 */
internal fun buildPathCache(
    items: List<GraphDriveItem>,
    rootFolderId: String,
): Map<String, String> {
    val cache = mutableMapOf<String, String>()
    for (item in items) {
        if (item.id.isEmpty()) continue
        val parentId = item.parentReference?.id
        val path =
            when {
                rootFolderId.isNotEmpty() && parentId == rootFolderId -> item.name
                parentId != null && cache.containsKey(parentId) -> "${cache[parentId]}/${item.name}"
                else -> item.name // parent not in this batch; fall back to leaf name
            }
        cache[item.id] = path
    }
    return cache
}

/**
 * Maps a Graph API [GraphDriveItem] from a delta response to a [RemoteChange].
 * Returns `null` for items with no usable identifier.
 *
 * @param pathCache Optional ID→relative-path cache built from the same delta
 *   batch via [buildPathCache]. When present, the item's relative path is
 *   resolved hierarchically; otherwise the leaf name is used.
 */
internal fun GraphDriveItem.toRemoteChange(pathCache: Map<String, String> = emptyMap()): RemoteChange? {
    if (id.isEmpty()) return null
    val resolvedPath = pathCache[id] ?: name.ifEmpty { id }
    return if (deleted != null) {
        RemoteChange(
            relativePath = resolvedPath.ifEmpty { id },
            type = RemoteChangeType.DELETE,
            remoteId = id,
        )
    } else {
        RemoteChange(
            relativePath = resolvedPath,
            type = RemoteChangeType.MODIFY,
            remoteId = id,
            sizeBytes = size,
            mtimeMs = lastModifiedDateTime?.let { parseIso8601(it) },
            etag = eTag?.trim('"'),
            isFolder = folder != null,
        )
    }
}
