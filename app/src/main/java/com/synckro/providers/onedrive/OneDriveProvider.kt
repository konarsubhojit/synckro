package com.synckro.providers.onedrive

import com.synckro.domain.auth.AuthResult
import com.synckro.domain.provider.ChangesPage
import com.synckro.domain.provider.CloudProvider
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.provider.CloudProviderException
import com.synckro.domain.provider.RemoteChange
import com.synckro.domain.provider.RemoteFile
import timber.log.Timber
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * OneDrive provider backed by the Microsoft Graph API.
 *
 * [ensureAuthenticated] attempts silent token acquisition via [OneDriveAuthManager]
 * and throws a typed [CloudProviderException] when authentication fails — no raw
 * MSAL types cross the provider boundary.
 *
 * All Graph operations are delegated to [OneDriveGraphClient], which handles
 * chunked resumable uploads, delta-based change enumeration, and 429/5xx retries.
 */
class OneDriveProvider
    constructor(
        private val accountId: String,
        private val authManager: OneDriveAuthManager,
        private val graphClient: OneDriveGraphClient,
    ) : CloudProvider {
        override val displayName: String = "OneDrive"

        /**
         * Cached access token from the last successful [ensureAuthenticated] call.
         * Cleared whenever a new token is acquired.
         *
         * Note: [ensureAuthenticated] should not be called concurrently from multiple
         * threads; [CloudProvider] callers are expected to ensure sequential access.
         */
        @Volatile
        private var cachedAccessToken: String? = null

        /**
         * Returns the cached access token or throws [CloudProviderException.AuthenticationRequired]
         * if [ensureAuthenticated] has not been called successfully yet.
         */
        private fun requireToken(): String =
            cachedAccessToken
                ?: throw CloudProviderException.AuthenticationRequired(
                    "No cached access token. Call ensureAuthenticated() first.",
                )

        /**
         * Ensures a valid bearer token is cached and returns it. Used by
         * [com.synckro.domain.sync.RemoteEnumerator] implementations
         * that need to make Graph calls outside the [CloudProvider] surface.
         *
         * Follows the same concurrency contract as [ensureAuthenticated]: callers
         * must ensure sequential access (the cached-token field is `@Volatile`,
         * but this method does not serialize concurrent invocations itself).
         */
        internal suspend fun obtainAccessToken(): String {
            if (cachedAccessToken == null) ensureAuthenticated()
            return requireToken()
        }

        /**
         * Executes [block] and maps a [GraphApiException] with status 401 to
         * [CloudProviderException.AuthenticationRequired], clearing the cached token
         * so the next call to [ensureAuthenticated] will force a fresh acquisition.
         */
        private suspend fun <T> graphCall(block: suspend () -> T): T =
            try {
                block()
            } catch (e: GraphApiException) {
                if (e.statusCode == 401) {
                    cachedAccessToken = null
                    throw CloudProviderException.AuthenticationRequired(
                        "OneDrive access token rejected by Graph API (401). Please re-authenticate.",
                    )
                }
                throw e
            }

        /**
         * Ensures a valid access token is available.
         *
         * 1. Reads the currently cached MSAL account.
         * 2. Attempts silent token acquisition via [OneDriveAuthManager.acquireAccessToken].
         * 3. Caches the resulting token for use by subsequent Graph API calls.
         *
         * @return `true` if a token was acquired successfully.
         * @throws CloudProviderException.NotConfigured if MSAL client ID / redirect URI are missing.
         * @throws CloudProviderException.AuthenticationRequired if no account is signed in or the
         *   cached refresh token has expired and interactive sign-in is needed.
         * @throws CloudProviderException.AuthenticationFailed for unexpected MSAL errors.
         */
        override suspend fun ensureAuthenticated(): Boolean {
            val account =
                authManager.currentAccounts().firstOrNull { it.id == accountId }
                    ?: run {
                        val hint = authManager.getAccountHint(accountId)
                        val hintMsg = if (hint != null) " (last seen: $hint)" else ""
                        Timber.w("OneDriveProvider.ensureAuthenticated: account not found; accountId=%s%s", accountId, hintMsg)
                        throw CloudProviderException.AuthenticationRequired(
                            "No OneDrive account is linked for id=$accountId$hintMsg. Please sign in from the Accounts screen.",
                        )
                    }

            Timber.d("OneDriveProvider.ensureAuthenticated: acquiring token for ${account.id}")

            return when (val result = authManager.acquireAccessToken(account)) {
                is AuthResult.Success -> {
                    cachedAccessToken = result.value
                    Timber.d("OneDriveProvider.ensureAuthenticated: token acquired")
                    true
                }
                is AuthResult.NeedsInteractiveSignIn -> {
                    Timber.w("OneDriveProvider.ensureAuthenticated: interactive sign-in required")
                    throw CloudProviderException.AuthenticationRequired(
                        "OneDrive access token expired. Please sign in again from the Accounts screen.",
                    )
                }
                is AuthResult.NotConfigured -> {
                    Timber.e("OneDriveProvider.ensureAuthenticated: not configured — ${result.message}")
                    throw CloudProviderException.NotConfigured(result.message)
                }
                is AuthResult.Error -> {
                    Timber.e(result.cause, "OneDriveProvider.ensureAuthenticated: auth error — ${result.message}")
                    throw CloudProviderException.AuthenticationFailed(result.message, result.cause)
                }
                is AuthResult.Cancelled -> {
                    // Silent flow does not prompt the user so Cancelled should never happen here.
                    Timber.w("OneDriveProvider.ensureAuthenticated: unexpected Cancelled result")
                    false
                }
            }
        }

        override suspend fun list(folderId: String?): List<RemoteFile> =
            graphCall {
                graphClient.list(obtainAccessToken(), folderId).map { it.toRemoteFile() }
            }

        override suspend fun getMetadata(id: String): RemoteFile =
            graphCall {
                graphClient.getMetadata(obtainAccessToken(), id).toRemoteFile()
            }

        override suspend fun download(id: String): InputStream =
            graphCall {
                graphClient.download(obtainAccessToken(), id)
            }

        override suspend fun uploadNew(
            parentId: String,
            name: String,
            content: InputStream,
            size: Long,
            mimeType: String?,
        ): RemoteFile =
            graphCall {
                graphClient
                    .uploadNew(obtainAccessToken(), parentId, name, content, size, mimeType)
                    .toRemoteFile()
            }

        override suspend fun updateContent(
            id: String,
            content: InputStream,
            size: Long,
            mimeType: String?,
        ): RemoteFile =
            graphCall {
                graphClient.updateContent(obtainAccessToken(), id, content, size, mimeType).toRemoteFile()
            }

        override suspend fun createFolder(
            parentId: String,
            name: String,
        ): RemoteFile =
            graphCall {
                graphClient.createFolder(obtainAccessToken(), parentId, name).toRemoteFile()
            }

        override suspend fun delete(id: String): Unit =
            graphCall {
                graphClient.delete(obtainAccessToken(), id)
            }

        /**
         * Retrieves incremental changes since [token].
         *
         * Pass `null` on the first call to establish a baseline (returns empty changes with an
         * initial delta token). On subsequent calls pass the token returned by this method.
         *
         * @param token Delta token returned by a previous call, or `null` to initialise.
         * @return A [ChangesPage] containing the changes since [token] and the next token.
         * @throws IOException on network failures.
         * @throws CloudProviderException on authentication errors.
         */
        override suspend fun changesSince(token: String?): ChangesPage =
            graphCall {
                val (items, nextDeltaLink) = graphClient.changesSince(obtainAccessToken(), token)
                val changes =
                    items.mapNotNull { item ->
                        when {
                            item.deleted != null -> RemoteChange(file = null, removedId = item.id)
                            item.id.isNotEmpty() -> RemoteChange(file = item.toRemoteFile(), removedId = null)
                            else -> null
                        }
                    }
                ChangesPage(
                    changes = changes,
                    nextToken = nextDeltaLink,
                    hasMore = false,
                )
            }
    }

@Singleton
class OneDriveProviderFactory
    @Inject
    constructor(
        private val authManager: OneDriveAuthManager,
        private val graphClient: OneDriveGraphClient,
    ) : CloudProviderFactory {
        private val providersByAccount = ConcurrentHashMap<String, CloudProvider>()

        override fun providerFor(accountId: String): CloudProvider =
            providersByAccount.computeIfAbsent(accountId) {
                OneDriveProvider(accountId = it, authManager = authManager, graphClient = graphClient)
            }
    }

// ---------------------------------------------------------------------------
// Extension: map GraphDriveItem → RemoteFile
// ---------------------------------------------------------------------------

/**
 * Maps a Graph API [GraphDriveItem] to the provider-agnostic [RemoteFile].
 *
 * - The `eTag` from Graph is wrapped in double-quotes; they are stripped here.
 * - `lastModifiedDateTime` is parsed from ISO-8601 (UTC) to epoch millis.
 */
internal fun GraphDriveItem.toRemoteFile(): RemoteFile =
    RemoteFile(
        id = id,
        name = name,
        parentId = parentReference?.id,
        isFolder = folder != null,
        size = size,
        lastModifiedMs = lastModifiedDateTime?.let { parseIso8601(it) },
        eTag = eTag?.trim('"'),
        mimeType = file?.mimeType,
    )
