package com.synckro.providers.webdav

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.synckro.domain.auth.Account
import com.synckro.domain.auth.AuthManager
import com.synckro.domain.auth.AuthResult
import com.synckro.domain.auth.AuthUiHost
import com.synckro.domain.model.CloudProviderType
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server details typed by the user when linking a WebDAV / Nextcloud account.
 *
 * @property serverUrl Either the account's WebDAV collection URL
 *   (`https://cloud.example.com/remote.php/dav/files/alice`) or the bare
 *   Nextcloud base URL (`https://cloud.example.com`), in which case the
 *   Nextcloud files endpoint is derived from [username].
 * @property username Login name.
 * @property password Password, or — strongly recommended for Nextcloud — a
 *   per-device app password.
 */
data class WebDavCredentialInput(
    val serverUrl: String,
    val username: String,
    val password: String,
)

/**
 * Optional capability of an [AuthUiHost]: collects WebDAV server details from
 * the user.
 *
 * WebDAV has no interactive OAuth flow, so [WebDavAuthManager.signIn] needs the
 * host to supply a server URL / username / app password instead of launching a
 * browser tab. Hosts that do not implement this interface get a typed
 * [AuthResult.Error] telling the user how to link the account.
 */
interface WebDavCredentialPrompt {
    /** Returns the entered credentials, or `null` when the user cancelled. */
    suspend fun promptForWebDavCredentials(): WebDavCredentialInput?
}

/**
 * WebDAV / Nextcloud [AuthManager].
 *
 * Unlike the OAuth providers there is no SDK-owned token cache, so the account
 * credentials themselves are persisted — in [EncryptedSharedPreferences],
 * following the same pattern as
 * [com.synckro.providers.gdrive.GoogleDriveAuthManager] and
 * [com.synckro.providers.onedrive.OneDriveAuthManager]. Credentials never leave
 * this class except as a ready-made HTTP Basic `Authorization` header handed to
 * [WebDavProvider] via [acquireAccessToken].
 *
 * Plaintext HTTP is rejected for non-loopback hosts because HTTP Basic would
 * otherwise put the user's password on the wire in clear text; see
 * `docs/webdav-nextcloud-setup.md`.
 */
@Singleton
class WebDavAuthManager private constructor(
    private val context: Context,
    private val client: WebDavClient,
    private val prefsOverride: SharedPreferences?,
) : AuthManager {
    /** Hilt-injected constructor (production path). */
    @Inject
    constructor(
        @ApplicationContext context: Context,
        client: WebDavClient,
    ) : this(context, client, prefsOverride = null)

    companion object {
        private const val PREFS_NAME = "webdav_accounts"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_USERNAME = "username"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_PASSWORD = "password"

        /** Path template Nextcloud/ownCloud expose per user under the instance base URL. */
        internal const val NEXTCLOUD_FILES_PATH = "remote.php/dav/files"

        /**
         * Creates an instance backed by a plain [SharedPreferences] instead of
         * [EncryptedSharedPreferences]. Use this in unit tests (Robolectric) where
         * the Android Keystore is unavailable.
         */
        internal fun forTest(
            context: Context,
            client: WebDavClient,
            testPrefs: SharedPreferences,
        ) = WebDavAuthManager(context, client, prefsOverride = testPrefs)
    }

    override val providerType: CloudProviderType = CloudProviderType.WEBDAV
    override val displayName: String = "WebDAV / Nextcloud"

    private val accountPrefs: SharedPreferences by lazy { prefsOverride ?: createEncryptedPrefs() }

    @Suppress("DEPRECATION") // Mirrors GoogleDriveAuthManager: the 5-arg factory has no stable replacement yet.
    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey =
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** WebDAV needs no build-time client id, so the provider is always configurable. */
    override suspend fun isConfigured(): Boolean = true

    /**
     * Collects server details from [host] (when it implements
     * [WebDavCredentialPrompt]) and links the account via [linkAccount].
     */
    override suspend fun signIn(host: AuthUiHost): AuthResult<Account> {
        val prompt =
            host as? WebDavCredentialPrompt
                ?: return AuthResult.Error(
                    "This screen cannot collect WebDAV server details. " +
                        "See docs/webdav-nextcloud-setup.md for how to link a WebDAV account.",
                )
        val input = prompt.promptForWebDavCredentials() ?: return AuthResult.Cancelled
        return linkAccount(input)
    }

    /**
     * Validates [input] against the server with a `Depth: 0` `PROPFIND` and, on
     * success, persists the account and its credentials.
     *
     * @return [AuthResult.Success] with the linked account,
     *   [AuthResult.NeedsInteractiveSignIn] when the server rejected the
     *   credentials (401/403), or [AuthResult.Error] for malformed input and
     *   network/server failures.
     */
    suspend fun linkAccount(input: WebDavCredentialInput): AuthResult<Account> {
        val username = input.username.trim()
        if (username.isEmpty()) return AuthResult.Error("WebDAV username must not be empty.")
        if (input.password.isEmpty()) return AuthResult.Error("WebDAV password must not be empty.")
        val baseUrl =
            resolveBaseUrl(input.serverUrl, username)
                ?: return AuthResult.Error(
                    "'${input.serverUrl}' is not a valid WebDAV URL. Use https://host/path " +
                        "(plain http is only allowed for localhost).",
                )

        val session =
            WebDavSession(
                baseUrl = baseUrl,
                authorizationHeader = Credentials.basic(username, input.password),
            )
        val probe = runCatching { client.getMetadata(session, session.rootPath) }
        val failure = probe.exceptionOrNull()
        if (failure != null) {
            val statusCode = (failure as? WebDavApiException)?.statusCode
            Timber.w(failure, "WebDavAuthManager.linkAccount: probe failed with status=%s", statusCode ?: "n/a")
            return when (statusCode) {
                401, 403 ->
                    AuthResult.NeedsInteractiveSignIn
                404 ->
                    AuthResult.Error(
                        "The server has no WebDAV collection at ${baseUrl.encodedPath}. Check the URL and username.",
                    )
                else ->
                    AuthResult.Error("Could not reach the WebDAV server: ${failure.message}", failure)
            }
        }

        val account =
            Account(
                id = accountIdFor(baseUrl, username),
                provider = CloudProviderType.WEBDAV,
                displayName = "$username@${baseUrl.host}",
                email = null,
            )
        val stored = readStoredAccounts().filterNot { it.account.id == account.id }
        writeStoredAccounts(
            stored +
                StoredWebDavAccount(
                    account = account,
                    username = username,
                    serverUrl = baseUrl.toString(),
                    password = input.password,
                ),
        )
        return AuthResult.Success(account)
    }

    override suspend fun signOut(account: Account): AuthResult<Unit> {
        if (account.provider != CloudProviderType.WEBDAV) {
            return AuthResult.Error("Account ${account.id} does not belong to $displayName.")
        }
        writeStoredAccounts(readStoredAccounts().filterNot { it.account.id == account.id })
        return AuthResult.Success(Unit)
    }

    override suspend fun currentAccounts(): List<Account> = readStoredAccounts().map { it.account }

    /**
     * Returns the HTTP Basic `Authorization` header for [account].
     *
     * WebDAV has no refreshable token: the header is derived from the stored
     * credentials on every call, and a missing entry means the user must link
     * the account again.
     */
    override suspend fun acquireAccessToken(account: Account): AuthResult<String> {
        if (account.provider != CloudProviderType.WEBDAV) {
            return AuthResult.Error("Account ${account.id} does not belong to $displayName.")
        }
        val stored =
            readStoredAccounts().firstOrNull { it.account.id == account.id }
                ?: return AuthResult.NeedsInteractiveSignIn
        return AuthResult.Success(Credentials.basic(stored.username, stored.password))
    }

    /**
     * Returns the [WebDavSession] for [accountId], or `null` when no credentials
     * are stored for it.
     */
    internal fun sessionFor(accountId: String): WebDavSession? {
        val stored = readStoredAccounts().firstOrNull { it.account.id == accountId } ?: return null
        val baseUrl = stored.serverUrl.toHttpUrlOrNull() ?: return null
        return WebDavSession(
            baseUrl = baseUrl,
            authorizationHeader = Credentials.basic(stored.username, stored.password),
        )
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private data class StoredWebDavAccount(
        val account: Account,
        val username: String,
        val serverUrl: String,
        val password: String,
    )

    private fun readStoredAccounts(): List<StoredWebDavAccount> {
        val raw = accountPrefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            buildList(json.length()) {
                repeat(json.length()) { index ->
                    val item = json.optJSONObject(index) ?: return@repeat
                    val id = item.optString(KEY_ACCOUNT_ID)
                    val serverUrl = item.optString(KEY_SERVER_URL)
                    val username = item.optString(KEY_USERNAME)
                    if (id.isBlank() || serverUrl.isBlank() || username.isBlank()) return@repeat
                    add(
                        StoredWebDavAccount(
                            account =
                                Account(
                                    id = id,
                                    provider = CloudProviderType.WEBDAV,
                                    displayName = item.optString(KEY_DISPLAY_NAME).ifBlank { id },
                                    email = null,
                                ),
                            username = username,
                            serverUrl = serverUrl,
                            password = item.optString(KEY_PASSWORD),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "WebDavAuthManager: failed to parse stored accounts")
            emptyList()
        }
    }

    private fun writeStoredAccounts(accounts: List<StoredWebDavAccount>) {
        val json =
            JSONArray().apply {
                accounts.forEach { stored ->
                    put(
                        JSONObject()
                            .put(KEY_ACCOUNT_ID, stored.account.id)
                            .put(KEY_DISPLAY_NAME, stored.account.displayName)
                            .put(KEY_USERNAME, stored.username)
                            .put(KEY_SERVER_URL, stored.serverUrl)
                            .put(KEY_PASSWORD, stored.password),
                    )
                }
            }
        accountPrefs.edit().putString(KEY_ACCOUNTS, json.toString()).apply()
    }
}

/**
 * Normalizes user input into the account's WebDAV collection URL.
 *
 * - A URL without a scheme is assumed to be `https`.
 * - A URL without a path (or with only `/`) is treated as a Nextcloud/ownCloud
 *   base URL and expanded to `remote.php/dav/files/<username>`.
 * - Plain `http` is rejected for non-loopback hosts so HTTP Basic credentials
 *   are never sent in clear text over the network.
 *
 * @return the normalized URL, or `null` when the input is unusable.
 */
internal fun resolveBaseUrl(
    rawUrl: String,
    username: String,
): HttpUrl? {
    val trimmed = rawUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val parsed = withScheme.toHttpUrlOrNull() ?: return null
    if (!parsed.isHttps && !isLoopbackHost(parsed.host)) return null
    val hasPath = parsed.pathSegments.any { it.isNotEmpty() }
    return if (hasPath) {
        parsed
    } else {
        parsed
            .newBuilder()
            .addPathSegments(WebDavAuthManager.NEXTCLOUD_FILES_PATH)
            .addPathSegment(username)
            .build()
    }
}

private fun isLoopbackHost(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1"

/** Stable account id: user + host + collection path, so one server can host several accounts. */
private fun accountIdFor(
    baseUrl: HttpUrl,
    username: String,
): String = "$username@${baseUrl.host}${normalizePath("/" + baseUrl.pathSegments.joinToString("/"))}"
