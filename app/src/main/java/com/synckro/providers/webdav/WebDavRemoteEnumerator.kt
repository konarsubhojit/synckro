package com.synckro.providers.webdav

import com.synckro.domain.provider.CloudProviderException
import com.synckro.domain.sync.AccountAwareRemoteEnumerator
import com.synckro.domain.sync.RemoteChange
import com.synckro.domain.sync.RemoteChangeType
import com.synckro.domain.sync.RemoteEnumerator
import com.synckro.domain.sync.RemoteSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RemoteEnumerator] for WebDAV / Nextcloud.
 *
 * WebDAV defines no delta endpoint, so — unlike
 * [com.synckro.providers.gdrive.GoogleDriveRemoteEnumerator] and
 * [com.synckro.providers.onedrive.OneDriveRemoteEnumerator], which resume from
 * a provider-issued token — every incremental call performs a **full
 * re-enumeration** of the sync root with recursive `Depth: 1` `PROPFIND`
 * requests and reports every file as [RemoteChangeType.MODIFY]. The diff stage
 * then classifies each entry against the local index, so unchanged files
 * produce no operations.
 *
 * Known gap (documented in `docs/webdav-nextcloud-setup.md`): a full listing
 * only reports what exists, so remote deletions are not emitted as
 * [RemoteChangeType.DELETE] changes the way a delta API would. They are picked
 * up when the pair is re-seeded (cold start with no delta token).
 */
@Singleton
class WebDavRemoteEnumerator
    @Inject
    constructor(
        private val providerFactory: WebDavProviderFactory,
        private val client: WebDavClient,
    ) : RemoteEnumerator,
        AccountAwareRemoteEnumerator {
        /** WebDAV credentials are per-account, so the account-unaware entrypoint is unsupported. */
        override suspend fun enumerate(deltaToken: String?, rootFolderId: String): RemoteSnapshot = throw CloudProviderException.AuthenticationRequired("WebDAV enumerate requires an account id.")

        /** WebDAV credentials are per-account, so the account-unaware entrypoint is unsupported. */
        override suspend fun enumerateFull(rootFolderId: String): RemoteSnapshot = throw CloudProviderException.AuthenticationRequired("WebDAV enumerateFull requires an account id.")

        override suspend fun enumerateForAccount(
            accountId: String,
            deltaToken: String?,
            rootFolderId: String,
        ): RemoteSnapshot = enumerateWithSession(providerFor(accountId).obtainSession(), rootFolderId)

        override suspend fun enumerateFullForAccount(
            accountId: String,
            rootFolderId: String,
        ): RemoteSnapshot = enumerateForAccount(accountId, deltaToken = null, rootFolderId = rootFolderId)

        /**
         * Test seam: enumerate using a directly-supplied session so tests can point
         * [client] at a `MockWebServer` and exercise the full HTTP round-trip.
         *
         * @param rootFolderId Server-absolute path of the sync root, or an empty
         *   string to enumerate the account's WebDAV root collection.
         */
        internal suspend fun enumerateWithSession(
            session: WebDavSession,
            rootFolderId: String,
        ): RemoteSnapshot {
            val rootPath = rootFolderId.ifEmpty { session.rootPath }
            val descendants =
                try {
                    client.listDescendants(session, rootPath)
                } catch (e: WebDavApiException) {
                    when (e.statusCode) {
                        401, 403 -> throw CloudProviderException.AuthenticationRequired(
                            "WebDAV server rejected the stored credentials (HTTP ${e.statusCode}). Please re-authenticate.",
                            e,
                        )
                        else -> throw e
                    }
                }
            val changes =
                descendants.map { (resource, relativePath) ->
                    RemoteChange(
                        relativePath = relativePath,
                        type = RemoteChangeType.MODIFY,
                        remoteId = resource.path,
                        sizeBytes = resource.contentLength,
                        mtimeMs = resource.lastModifiedMs,
                        // WebDAV's getetag is an opaque version tag, never a content
                        // hash, so contentHash stays null per the CloudProvider contract.
                        etag = resource.eTag,
                        contentHash = null,
                        isFolder = resource.isCollection,
                    )
                }
            return RemoteSnapshot(changes = changes, newDeltaToken = "${WebDavProvider.TOKEN_PREFIX}${System.currentTimeMillis()}")
        }

        private fun providerFor(accountId: String): WebDavProvider {
            val provider = providerFactory.providerFor(accountId)
            return provider as? WebDavProvider
                ?: error("WebDavProviderFactory returned unexpected provider type: ${provider::class.java.simpleName}")
        }
    }
