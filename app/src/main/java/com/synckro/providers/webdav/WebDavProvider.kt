package com.synckro.providers.webdav

import com.synckro.domain.provider.ChangesPage
import com.synckro.domain.provider.CloudProvider
import com.synckro.domain.provider.CloudProviderException
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.provider.RemoteChange
import com.synckro.domain.provider.RemoteFile
import com.synckro.domain.provider.StorageQuota
import timber.log.Timber
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV / Nextcloud provider backed by [WebDavClient] (RFC 4918).
 *
 * Item ids are the server-absolute, percent-decoded resource paths
 * (`/remote.php/dav/files/alice/Photos/a.jpg`). WebDAV exposes no
 * rename-stable resource identifier, so a move on the server is observed by
 * the sync engine as a delete of the old path plus an add of the new one.
 *
 * ### `changesSince()` and the missing delta API
 * WebDAV has no delta/changes endpoint — that is the one real capability gap
 * versus Microsoft Graph's `/delta` and Google Drive's `changes.list`. The
 * fallback implemented here is a **full re-enumeration**: every non-baseline
 * call walks the whole sync root with `Depth: 1` `PROPFIND` requests and
 * reports each file as a change, exactly like the periodic re-enumeration the
 * sync engine performs for delta providers
 * (`SyncEngine.shouldRunPeriodicRemoteReenumeration`). Two consequences follow
 * and are documented in `docs/webdav-nextcloud-setup.md`:
 * - Remote **deletions** cannot be reported (a full listing only says what
 *   exists), so deletions propagate on the next cold start / re-pair rather
 *   than incrementally.
 * - Cost grows with the size of the remote tree instead of with the number of
 *   changes, so WebDAV pairs should use longer sync intervals than Graph/Drive
 *   pairs.
 */
class WebDavProvider
    constructor(
        private val accountId: String,
        private val authManager: WebDavAuthManager,
        private val client: WebDavClient,
        private val clock: () -> Long = System::currentTimeMillis,
    ) : CloudProvider {
        override val displayName: String = "WebDAV / Nextcloud"

        /** Session resolved by the last successful [ensureAuthenticated] call. */
        @Volatile
        private var cachedSession: WebDavSession? = null

        /**
         * Resolves (and caches) the [WebDavSession] for [accountId].
         *
         * @throws CloudProviderException.AuthenticationRequired when no credentials
         *   are stored for the account.
         */
        internal suspend fun obtainSession(): WebDavSession {
            cachedSession?.let { return it }
            ensureAuthenticated()
            return cachedSession
                ?: throw CloudProviderException.AuthenticationRequired(
                    "No WebDAV credentials for id=$accountId. Please link the account from the Accounts screen.",
                )
        }

        override suspend fun ensureAuthenticated(): Boolean {
            val session =
                authManager.sessionFor(accountId)
                    ?: run {
                        Timber.w("WebDavProvider.ensureAuthenticated: no stored credentials; accountId=%s", accountId)
                        throw CloudProviderException.AuthenticationRequired(
                            "No WebDAV account is linked for id=$accountId. " +
                                "Please add it from the Accounts screen.",
                        )
                    }
            cachedSession = session
            return true
        }

        /**
         * Runs [block] and maps [WebDavApiException]s onto the typed
         * [CloudProviderException] hierarchy so no raw HTTP failures cross the
         * provider boundary.
         */
        private suspend fun <T> webDavCall(block: suspend (WebDavSession) -> T): T {
            val session = obtainSession()
            return try {
                block(session)
            } catch (e: WebDavApiException) {
                when (e.statusCode) {
                    401, 403 -> {
                        cachedSession = null
                        throw CloudProviderException.AuthenticationRequired(
                            "WebDAV server rejected the stored credentials (HTTP ${e.statusCode}). " +
                                "Please re-enter the password or app password.",
                            e,
                        )
                    }
                    429 ->
                        throw CloudProviderException.RateLimited(
                            retryAfterMs = 0,
                            message = "WebDAV server returned HTTP 429 (too many requests).",
                            cause = e,
                        )
                    else -> throw e
                }
            }
        }

        override suspend fun list(folderId: String?): List<RemoteFile> =
            webDavCall { session ->
                client
                    .listChildren(session, folderId ?: session.rootPath)
                    .map { it.toRemoteFile() }
            }

        override suspend fun getMetadata(id: String): RemoteFile = webDavCall { session -> client.getMetadata(session, id).toRemoteFile() }

        override suspend fun download(id: String): InputStream = webDavCall { session -> client.download(session, id) }

        override suspend fun uploadNew(
            parentId: String,
            name: String,
            content: InputStream,
            size: Long,
            mimeType: String?,
        ): RemoteFile =
            webDavCall { session ->
                client.put(session, childPath(parentId, name), content, size, mimeType).toRemoteFile()
            }

        override suspend fun updateContent(
            id: String,
            content: InputStream,
            size: Long,
            mimeType: String?,
        ): RemoteFile = webDavCall { session -> client.put(session, id, content, size, mimeType).toRemoteFile() }

        override suspend fun createFolder(
            parentId: String,
            name: String,
        ): RemoteFile = webDavCall { session -> client.mkcol(session, childPath(parentId, name)).toRemoteFile() }

        override suspend fun delete(id: String): Unit = webDavCall { session -> client.delete(session, id) }

        /**
         * Full-re-enumeration fallback for WebDAV's missing delta API (see the
         * class KDoc).
         *
         * - `token == null` establishes a baseline without replaying history,
         *   matching the Graph/Drive providers: the change list is empty.
         * - Otherwise the whole sync root is re-listed and every file is reported
         *   as a change.
         *
         * The returned token is opaque, as required by [CloudProvider.changesSince];
         * it carries the enumeration timestamp purely for diagnostics.
         */
        override suspend fun changesSince(token: String?): ChangesPage {
            if (token == null) {
                return ChangesPage(changes = emptyList(), nextToken = newToken(), hasMore = false)
            }
            return webDavCall { session ->
                val changes =
                    client
                        .listDescendants(session, session.rootPath)
                        .filterNot { (resource, _) -> resource.isCollection }
                        .map { (resource, _) -> RemoteChange(file = resource.toRemoteFile(), removedId = null) }
                ChangesPage(changes = changes, nextToken = newToken(), hasMore = false)
            }
        }

        /**
         * Reads the account quota from the RFC 4331 `quota-used-bytes` /
         * `quota-available-bytes` properties. Returns `null` when the server does
         * not report them (e.g. unlimited storage) or the request fails.
         */
        override suspend fun getStorageQuota(): StorageQuota? =
            runCatching {
                webDavCall { session ->
                    val quota = client.quota(session, session.rootPath) ?: return@webDavCall null
                    StorageQuota(
                        usedBytes = quota.usedBytes,
                        totalBytes = quota.usedBytes + quota.availableBytes,
                    )
                }
            }.getOrNull()

        private fun newToken(): String = "$TOKEN_PREFIX${clock()}"

        companion object {
            /** Prefix of the opaque token returned by [changesSince]. */
            internal const val TOKEN_PREFIX = "webdav-full:"
        }
    }

/** Joins a parent collection path and a child name into a normalized absolute path. */
internal fun childPath(
    parentPath: String,
    name: String,
): String {
    val parent = normalizePath(parentPath)
    val leaf = name.trim('/')
    return if (parent == "/") "/$leaf" else "$parent/$leaf"
}

@Singleton
class WebDavProviderFactory
    @Inject
    constructor(
        private val authManager: WebDavAuthManager,
        private val client: WebDavClient,
    ) : CloudProviderFactory {
        private val providersByAccount = ConcurrentHashMap<String, CloudProvider>()

        override fun providerFor(accountId: String): CloudProvider =
            providersByAccount.computeIfAbsent(accountId) {
                WebDavProvider(accountId = it, authManager = authManager, client = client)
            }
    }
