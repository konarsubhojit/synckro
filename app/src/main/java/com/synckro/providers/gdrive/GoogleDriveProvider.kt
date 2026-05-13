package com.synckro.providers.gdrive

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
 * Google Drive provider backed by the Drive REST v3 API.
 *
 * [ensureAuthenticated] attempts silent token acquisition via [GoogleDriveAuthManager]
 * and throws a typed [CloudProviderException] when authentication fails — no raw
 * Google Identity types cross the provider boundary.
 *
 * All Drive operations are delegated to [GoogleDriveRestClient], which handles
 * resumable uploads, changes-based change enumeration, and 429/5xx retries.
 */
class GoogleDriveProvider
    constructor(
        private val accountId: String,
        private val authManager: GoogleDriveAuthManager,
        private val restClient: GoogleDriveRestClient,
        private val clock: () -> Long = System::currentTimeMillis,
        private val tokenExpiryThresholdMs: Long = TOKEN_EXPIRY_THRESHOLD_MS,
    ) : CloudProvider {
        override val displayName: String = "Google Drive"

        /**
         * Cached access token from the last successful [ensureAuthenticated] call.
         * Cleared whenever a 401 is returned by the Drive API.
         */
        @Volatile
        private var cachedAccessToken: String? = null

        /**
         * Epoch-millis timestamp at which [cachedAccessToken] was last acquired.
         * Reset to 0 whenever the token is cleared.
         */
        @Volatile
        private var tokenAcquiredAtMs: Long = 0L

        /**
         * Returns `true` when the cached token exists but is older than
         * [tokenExpiryThresholdMs] and should be proactively refreshed.
         * Only meaningful when [cachedAccessToken] is non-null.
         */
        private fun isTokenStale(): Boolean = (clock() - tokenAcquiredAtMs) >= tokenExpiryThresholdMs

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
         * [RemoteEnumerator] implementations
         * that need to make Drive calls outside the [CloudProvider] surface.
         *
         * Proactively refreshes the token when it is older than [tokenExpiryThresholdMs]
         * to avoid reactive 401 round-trips on nominal operation.
         *
         * Follows the same concurrency contract as [ensureAuthenticated]: callers
         * must ensure sequential access (the cached-token field is `@Volatile`,
         * but this method does not serialize concurrent invocations itself).
         */
        internal suspend fun obtainAccessToken(): String {
            when {
                cachedAccessToken == null -> ensureAuthenticated()
                isTokenStale() -> {
                    Timber.d(
                        "GoogleDriveProvider.obtainAccessToken: token is stale " +
                            "(age ${clock() - tokenAcquiredAtMs}ms ≥ threshold ${tokenExpiryThresholdMs}ms); refreshing proactively",
                    )
                    ensureAuthenticated()
                }
            }
            return requireToken()
        }

        /**
         * Executes [block] and maps a [DriveApiException] with status 401 to
         * [CloudProviderException.AuthenticationRequired], clearing the cached token
         * so the next call to [ensureAuthenticated] will force a fresh acquisition.
         */
        private suspend fun <T> driveCall(block: suspend () -> T): T =
            try {
                block()
            } catch (e: DriveApiException) {
                if (e.statusCode == 401) {
                    cachedAccessToken = null
                    tokenAcquiredAtMs = 0L
                    throw CloudProviderException.AuthenticationRequired(
                        "Google Drive access token rejected (401). Please re-authenticate.",
                    )
                }
                throw e
            }

        /**
         * Ensures a valid access token is available.
         *
         * 1. Reads the currently stored account from [GoogleDriveAuthManager].
         * 2. Attempts silent token acquisition via [GoogleDriveAuthManager.acquireAccessToken].
         * 3. Caches the resulting token for use by subsequent Drive API calls.
         *
         * @return `true` if a token was acquired successfully.
         * @throws CloudProviderException.AuthenticationRequired if no account is signed in or
         *   interactive re-authorization is needed.
         * @throws CloudProviderException.NotConfigured if the web client ID is missing.
         * @throws CloudProviderException.AuthenticationFailed for unexpected auth errors.
         */
        override suspend fun ensureAuthenticated(): Boolean {
            val account =
                authManager.currentAccounts().firstOrNull { it.id == accountId }
                    ?: run {
                        Timber.w("GoogleDriveProvider.ensureAuthenticated: account not found; accountId=%s", accountId)
                        throw CloudProviderException.AuthenticationRequired(
                            "No Google Drive account is linked for id=$accountId. Please sign in from the Accounts screen.",
                        )
                    }

            Timber.d("GoogleDriveProvider.ensureAuthenticated: acquiring token for ${account.id}")

            return when (val result = authManager.acquireAccessToken(account)) {
                is AuthResult.Success -> {
                    cachedAccessToken = result.value
                    tokenAcquiredAtMs = clock()
                    Timber.d("GoogleDriveProvider.ensureAuthenticated: token acquired")
                    true
                }
                is AuthResult.NeedsInteractiveSignIn -> {
                    Timber.w("GoogleDriveProvider.ensureAuthenticated: interactive sign-in required")
                    throw CloudProviderException.AuthenticationRequired(
                        "Google Drive access token expired. Please sign in again from the Accounts screen.",
                    )
                }
                is AuthResult.NotConfigured -> {
                    Timber.e("GoogleDriveProvider.ensureAuthenticated: not configured — ${result.message}")
                    throw CloudProviderException.NotConfigured(result.message)
                }
                is AuthResult.Error -> {
                    Timber.e(result.cause, "GoogleDriveProvider.ensureAuthenticated: auth error — ${result.message}")
                    throw CloudProviderException.AuthenticationFailed(result.message, result.cause)
                }
                is AuthResult.Cancelled -> {
                    Timber.w("GoogleDriveProvider.ensureAuthenticated: unexpected Cancelled result")
                    false
                }
            }
        }

        override suspend fun list(folderId: String?): List<RemoteFile> =
            driveCall {
                restClient.list(obtainAccessToken(), folderId).map { it.toRemoteFile() }
            }

        override suspend fun getMetadata(id: String): RemoteFile =
            driveCall {
                restClient.getMetadata(obtainAccessToken(), id).toRemoteFile()
            }

        override suspend fun download(id: String): InputStream =
            driveCall {
                restClient.download(obtainAccessToken(), id)
            }

        override suspend fun uploadNew(
            parentId: String,
            name: String,
            content: InputStream,
            size: Long,
            mimeType: String?,
        ): RemoteFile =
            driveCall {
                restClient
                    .uploadNew(obtainAccessToken(), parentId, name, content, size, mimeType)
                    .toRemoteFile()
            }

        override suspend fun updateContent(
            id: String,
            content: InputStream,
            size: Long,
            mimeType: String?,
        ): RemoteFile =
            driveCall {
                restClient.updateContent(obtainAccessToken(), id, content, size, mimeType).toRemoteFile()
            }

        override suspend fun createFolder(
            parentId: String,
            name: String,
        ): RemoteFile =
            driveCall {
                restClient.createFolder(obtainAccessToken(), parentId, name).toRemoteFile()
            }

        override suspend fun delete(id: String): Unit =
            driveCall {
                restClient.delete(obtainAccessToken(), id)
            }

        /**
         * Retrieves incremental changes since [token].
         *
         * Pass `null` on the first call to establish a baseline (returns empty changes with an
         * initial page token). On subsequent calls pass the token returned by this method.
         *
         * @param token Page token returned by a previous call, or `null` to initialise.
         * @return A [ChangesPage] containing the changes since [token] and the next token.
         */
        override suspend fun changesSince(token: String?): ChangesPage =
            driveCall {
                val (changes, nextToken) = restClient.changesSince(obtainAccessToken(), token)
                val remoteChanges =
                    changes.mapNotNull { change ->
                        when {
                            change.removed == true ->
                                RemoteChange(
                                    file = null,
                                    removedId = change.fileId ?: return@mapNotNull null,
                                )
                            change.file?.trashed == true ->
                                RemoteChange(
                                    file = null,
                                    removedId = change.fileId ?: return@mapNotNull null,
                                )
                            change.file != null ->
                                RemoteChange(
                                    file = change.file.toRemoteFile(),
                                    removedId = null,
                                )
                            else -> null
                        }
                    }
                ChangesPage(
                    changes = remoteChanges,
                    nextToken = nextToken,
                    hasMore = false,
                )
            }

        companion object {
            /** Tokens older than this threshold are proactively refreshed before any API call. */
            internal const val TOKEN_EXPIRY_THRESHOLD_MS = 50L * 60 * 1_000 // 50 minutes
        }
    }

@Singleton
class GoogleDriveProviderFactory
    @Inject
    constructor(
        private val authManager: GoogleDriveAuthManager,
        private val restClient: GoogleDriveRestClient,
    ) : CloudProviderFactory {
        private val providersByAccount = ConcurrentHashMap<String, CloudProvider>()

        override fun providerFor(accountId: String): CloudProvider =
            providersByAccount.computeIfAbsent(accountId) {
                GoogleDriveProvider(accountId = it, authManager = authManager, restClient = restClient)
            }
    }

// ---------------------------------------------------------------------------
// Extension: map DriveFile → RemoteFile
// ---------------------------------------------------------------------------

/**
 * Maps a Drive v3 [DriveFile] to the provider-agnostic [RemoteFile].
 *
 * - Drive returns [DriveFile.size] as a decimal string; it is parsed to [Long].
 * - [DriveFile.md5Checksum] is used as the eTag equivalent.
 * - [DriveFile.modifiedTime] is parsed from ISO-8601 (UTC) to epoch millis.
 */
internal fun DriveFile.toRemoteFile(): RemoteFile =
    RemoteFile(
        id = id,
        name = name,
        parentId = parents?.firstOrNull(),
        isFolder = mimeType == FOLDER_MIME_TYPE,
        size = size?.toLongOrNull(),
        lastModifiedMs = modifiedTime?.let { parseIso8601(it) },
        eTag = md5Checksum,
        mimeType = mimeType,
    )
