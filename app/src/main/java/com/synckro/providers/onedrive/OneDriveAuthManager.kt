package com.synckro.providers.onedrive

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.synckro.BuildConfig
import com.synckro.R
import com.synckro.domain.auth.Account
import com.synckro.domain.auth.AuthManager
import com.synckro.domain.auth.AuthResult
import com.synckro.domain.auth.AuthUiHost
import com.synckro.domain.model.CloudProviderType
import com.synckro.ui.auth.ActivityAuthUiHost
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * OneDrive [AuthManager] backed by MSAL (Microsoft Authentication Library).
 * Uses MSAL multi-account mode so callers can address specific OneDrive
 * principals. This class is responsible only for
 * MSAL token-cache operations; persistent database cleanup (Room) is the
 * responsibility of the caller (e.g. AccountsViewModel).
 *
 * The signed-in account hint (email) is persisted in [EncryptedSharedPreferences]
 * so that [com.synckro.providers.onedrive.OneDriveProvider] can
 * surface a user-friendly error when no account is cached.
 *
 * All failures are logged via Timber and reported through [AuthResult].
 */
@Singleton
class OneDriveAuthManager private constructor(
    @ApplicationContext private val context: Context,
    private val clientId: String,
    private val redirectUri: String,
    private val prefsOverride: SharedPreferences? = null,
) : AuthManager,
    OneDriveCacheCompatibilityChecker {
    /** Hilt-injected constructor (production path). */
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) :
        this(context, BuildConfig.MS_CLIENT_ID, BuildConfig.MSAL_REDIRECT_URI)

    override val providerType: CloudProviderType = CloudProviderType.ONEDRIVE
    override val displayName: String = "OneDrive"

    private var msalApp: IMultipleAccountPublicClientApplication? = null

    /**
     * Set to `true` after the first failed MSAL initialization attempt so that
     * subsequent calls (e.g. repeated [AccountsViewModel.refresh] ticks) return
     * immediately without spawning another MSAL call and its accompanying
     * stack-trace noise in the debug log.
     */
    private var msalInitFailed = false

    /**
     * Encrypted preferences used to persist the account hint (email / display
     * name) across process restarts. The hint is saved after a successful
     * interactive sign-in and cleared on sign-out.
     *
     * Initialisation is lazy so that tests and builds without the security
     * library on the class-path do not crash at class-load time.
     */
    private val accountHintPrefs by lazy {
        prefsOverride ?: createEncryptedPrefs()
    }

    @Suppress("DEPRECATION")
    private fun createEncryptedPrefs(): SharedPreferences? =
        try {
            val masterKey =
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Timber.e(e, "OneDriveAuthManager: failed to create EncryptedSharedPreferences")
            null
        }

    /** Stores [email] for [accountId], or clears it when [email] is null. */
    internal fun setAccountHint(
        accountId: String,
        email: String?,
    ) {
        val prefs = accountHintPrefs ?: return
        val hints = LinkedHashMap(readAccountHints(prefs))
        val shouldRemoveHint = email.isNullOrBlank()
        if (shouldRemoveHint) {
            hints.remove(accountId)
        } else {
            hints[accountId] = email
        }
        writeAccountHints(prefs, hints, latestAccountId = if (shouldRemoveHint) null else accountId)
    }

    /** Returns the persisted account hint, or null if none is stored. */
    fun getAccountHint(accountId: String? = null): String? {
        val prefs = accountHintPrefs ?: return null
        val hints = readAccountHints(prefs)
        if (hints.isEmpty()) return null
        if (accountId != null) return hints[accountId]
        val latestAccountId = prefs.getString(KEY_LATEST_ACCOUNT_HINT_ID, null)
        if (latestAccountId != null && !hints.containsKey(latestAccountId)) {
            Timber.w("OneDriveAuthManager: latest account hint id %s missing from stored hints", latestAccountId)
        }
        // readAccountHints() preserves insertion order so falling back to the
        // last value still yields the most recently written hint.
        return hints[latestAccountId] ?: hints.values.lastOrNull()
    }

    /**
     * `offline_access` is intentionally omitted here because it is an OIDC
     * scope, not a Microsoft Graph resource scope. The consumer / MSA endpoint
     * rejects it when MSAL prefixes Graph scopes with the resource URI, while
     * refresh tokens are still issued automatically for public clients.
     */
    private val scopes =
        listOf(
            "Files.ReadWrite",
            "User.Read",
        )

    override suspend fun isConfigured(): Boolean =
        OneDriveAuthConfig.validate(clientId, redirectUri) == OneDriveAuthConfig.ValidationResult.Valid

    /**
     * Lazily initializes the MSAL multi-account application. Returns `null` and
     * logs a warning if the config is missing or invalid, or if the first
     * initialization attempt already failed (cached to avoid log spam on repeated
     * [currentAccounts] / [acquireAccessToken] calls).
     */
    private suspend fun getOrCreateMsalApp(): IMultipleAccountPublicClientApplication? {
        if (msalApp != null) return msalApp
        if (msalInitFailed) return null

        when (val config = OneDriveAuthConfig.validate(clientId, redirectUri)) {
            is OneDriveAuthConfig.ValidationResult.NotConfigured -> {
                Timber.w(
                    "OneDriveAuthManager: MS_CLIENT_ID and MSAL_REDIRECT_URI are both unset — " +
                        "OneDrive sign-in is disabled in this build. See docs/login-setup.md.",
                )
                return null
            }
            is OneDriveAuthConfig.ValidationResult.Invalid -> {
                Timber.w("OneDriveAuthManager: MSAL config invalid — ${config.reason}")
                msalInitFailed = true
                return null
            }
            is OneDriveAuthConfig.ValidationResult.Valid -> { /* proceed to MSAL init */ }
        }

        return suspendCancellableCoroutine { cont ->
            try {
                PublicClientApplication.createMultipleAccountPublicClientApplication(
                    context,
                    R.raw.msal_config,
                    object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                        override fun onCreated(application: IMultipleAccountPublicClientApplication?) {
                            msalApp = application
                            Timber.d("OneDriveAuthManager: MSAL app created successfully")
                            cont.resume(application)
                        }

                        override fun onError(exception: MsalException?) {
                            Timber.e(exception, "OneDriveAuthManager: Failed to create MSAL app")
                            msalInitFailed = true
                            cont.resume(null)
                        }
                    },
                )
            } catch (e: Exception) {
                Timber.e(e, "OneDriveAuthManager: Exception creating MSAL app")
                msalInitFailed = true
                cont.resume(null)
            }
        }
    }

    override suspend fun signIn(host: AuthUiHost): AuthResult<Account> {
        if (!isConfigured()) {
            return AuthResult.NotConfigured(context.getString(R.string.onedrive_not_configured))
        }

        val activity = (host as? ActivityAuthUiHost)?.activity
        if (activity == null) {
            Timber.e("OneDriveAuthManager.signIn: host is not an ActivityAuthUiHost")
            return AuthResult.Error(context.getString(R.string.accounts_host_unavailable))
        }

        val app =
            getOrCreateMsalApp()
                ?: return AuthResult.Error(context.getString(R.string.onedrive_init_failed))

        Timber.d("OneDriveAuthManager.signIn: launching interactive sign-in")

        return suspendCancellableCoroutine { cont ->
            val params =
                AcquireTokenParameters.Builder()
                    .startAuthorizationFromActivity(activity)
                    .withScopes(scopes)
                    .withLoginHint(getAccountHint())
                    .withCallback(
                        object : AuthenticationCallback {
                            override fun onSuccess(authenticationResult: IAuthenticationResult?) {
                                if (!cont.isActive) return
                                if (authenticationResult == null) {
                                    Timber.e("OneDriveAuthManager.signIn: success but null result")
                                    cont.resume(AuthResult.Error(context.getString(R.string.onedrive_signin_null_result)))
                                    return
                                }

                                val msalAccount = authenticationResult.account
                                Timber.i("OneDriveAuthManager.signIn: success for ${msalAccount.username}")

                                val account =
                                    Account(
                                        id = msalAccount.id,
                                        provider = CloudProviderType.ONEDRIVE,
                                        displayName = msalAccount.username ?: msalAccount.id,
                                        email = msalAccount.username,
                                    )

                                setAccountHint(msalAccount.id, msalAccount.username)
                                cont.resume(AuthResult.Success(account))
                            }

                            override fun onError(exception: MsalException?) {
                                if (!cont.isActive) return
                                Timber.e(exception, "OneDriveAuthManager.signIn: error")
                                val result =
                                    when (exception) {
                                        is MsalClientException -> {
                                            if (exception.errorCode == "user_cancelled") {
                                                AuthResult.Cancelled
                                            } else {
                                                AuthResult.Error(
                                                    exception.message ?: "MSAL client error: ${exception.errorCode}",
                                                    exception,
                                                )
                                            }
                                        }
                                        is MsalUiRequiredException -> {
                                            // Should not happen during interactive sign-in, but handle it
                                            AuthResult.NeedsInteractiveSignIn
                                        }
                                        is MsalServiceException -> {
                                            AuthResult.Error(
                                                "MSAL service error: ${exception.errorCode} - ${exception.message}",
                                                exception,
                                            )
                                        }
                                        else -> {
                                            AuthResult.Error(
                                                exception?.message ?: "Unknown MSAL error",
                                                exception,
                                            )
                                        }
                                    }
                                cont.resume(result)
                            }

                            override fun onCancel() {
                                if (!cont.isActive) return
                                Timber.i("OneDriveAuthManager.signIn: user cancelled")
                                cont.resume(AuthResult.Cancelled)
                            }
                        },
                    )
                    .build()

            app.acquireToken(params)
        }
    }

    /**
     * Signs out from MSAL. Database cleanup is the responsibility of the caller
     * (e.g. [com.synckro.ui.screens.accounts.AccountsViewModel]).
     */
    override suspend fun signOut(account: Account): AuthResult<Unit> {
        Timber.i("OneDriveAuthManager.signOut: account=${account.id}")

        val app = getOrCreateMsalApp()
        if (app == null) {
            Timber.w("OneDriveAuthManager.signOut: MSAL app not available, treating as already signed out")
            setAccountHint(account.id, null)
            return AuthResult.Success(Unit)
        }

        return suspendCancellableCoroutine { cont ->
            app.getAccount(
                account.id,
                object : IMultipleAccountPublicClientApplication.GetAccountCallback {
                    override fun onTaskCompleted(matchingAccount: IAccount?) {
                        if (!cont.isActive) return
                        if (matchingAccount == null) {
                            Timber.i("OneDriveAuthManager.signOut: account already absent from MSAL cache")
                            setAccountHint(account.id, null)
                            cont.resume(AuthResult.Success(Unit))
                            return
                        }

                        app.removeAccount(
                            matchingAccount,
                            object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
                                override fun onRemoved() {
                                    if (!cont.isActive) return
                                    Timber.i("OneDriveAuthManager.signOut: MSAL sign-out successful")
                                    setAccountHint(account.id, null)
                                    cont.resume(AuthResult.Success(Unit))
                                }

                                override fun onError(exception: MsalException) {
                                    if (!cont.isActive) return
                                    Timber.e(exception, "OneDriveAuthManager.signOut: MSAL removeAccount error")
                                    cont.resume(AuthResult.Error(exception.message ?: "Sign-out failed", exception))
                                }
                            },
                        )
                    }

                    override fun onError(exception: MsalException) {
                        if (!cont.isActive) return
                        Timber.e(exception, "OneDriveAuthManager.signOut: MSAL lookup error")
                        cont.resume(AuthResult.Error(exception.message ?: "Sign-out failed", exception))
                    }
                },
            )
        }
    }

    override suspend fun currentAccounts(): List<Account> {
        val app = getOrCreateMsalApp() ?: return emptyList()

        Timber.d("OneDriveAuthManager.currentAccounts: checking MSAL cache")

        return suspendCancellableCoroutine { cont ->
            app.getAccounts(
                object : IPublicClientApplication.LoadAccountsCallback {
                    override fun onTaskCompleted(accounts: MutableList<IAccount>?) {
                        if (!cont.isActive) return
                        if (accounts.isNullOrEmpty()) {
                            Timber.d("OneDriveAuthManager.currentAccounts: no MSAL account")
                            cont.resume(emptyList())
                            return
                        }

                        Timber.d("OneDriveAuthManager.currentAccounts: found ${accounts.size} account(s)")
                        cont.resume(
                            accounts.map { activeAccount ->
                                Account(
                                    id = activeAccount.id,
                                    provider = CloudProviderType.ONEDRIVE,
                                    displayName = activeAccount.username ?: activeAccount.id,
                                    email = activeAccount.username,
                                )
                            },
                        )
                    }

                    override fun onError(exception: MsalException) {
                        if (!cont.isActive) return
                        Timber.e(exception, "OneDriveAuthManager.currentAccounts: error")
                        cont.resume(emptyList())
                    }
                },
            )
        }
    }

    override suspend fun probeMultiAccountCacheRead(): Result<Unit> {
        if (!isConfigured()) return Result.success(Unit)

        val app =
            if (msalApp != null) {
                msalApp
            } else {
                suspendCancellableCoroutine { cont ->
                    try {
                        PublicClientApplication.createMultipleAccountPublicClientApplication(
                            context,
                            R.raw.msal_config,
                            object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                                override fun onCreated(application: IMultipleAccountPublicClientApplication?) {
                                    msalApp = application
                                    cont.resume(Result.success(application))
                                }

                                override fun onError(exception: MsalException?) {
                                    msalInitFailed = true
                                    cont.resume(
                                        Result.failure(
                                            exception ?: IllegalStateException("Failed to create MSAL app"),
                                        ),
                                    )
                                }
                            },
                        )
                    } catch (e: Exception) {
                        msalInitFailed = true
                        cont.resume(Result.failure(e))
                    }
                }.getOrElse { return Result.failure(it) }
            } ?: return Result.success(Unit)

        return suspendCancellableCoroutine { cont ->
            app.getAccounts(
                object : IPublicClientApplication.LoadAccountsCallback {
                    override fun onTaskCompleted(accounts: MutableList<IAccount>?) {
                        if (!cont.isActive) return
                        cont.resume(Result.success(Unit))
                    }

                    override fun onError(exception: MsalException) {
                        if (!cont.isActive) return
                        cont.resume(Result.failure(exception))
                    }
                },
            )
        }
    }

    override suspend fun acquireAccessToken(account: Account): AuthResult<String> {
        if (!isConfigured()) {
            return AuthResult.NotConfigured(context.getString(R.string.onedrive_not_configured))
        }

        val app =
            getOrCreateMsalApp()
                ?: return AuthResult.Error(context.getString(R.string.onedrive_init_failed))

        Timber.d("OneDriveAuthManager.acquireAccessToken: attempting silent acquisition for ${account.id}")

        return suspendCancellableCoroutine { cont ->
            app.getAccount(
                account.id,
                object : IMultipleAccountPublicClientApplication.GetAccountCallback {
                    override fun onTaskCompleted(activeAccount: IAccount?) {
                        if (!cont.isActive) return
                        if (activeAccount == null) {
                            Timber.w("OneDriveAuthManager.acquireAccessToken: no cached account for ${account.id}")
                            cont.resume(AuthResult.NeedsInteractiveSignIn)
                            return
                        }

                        acquireTokenSilent(app, activeAccount, cont)
                    }

                    override fun onError(exception: MsalException) {
                        if (!cont.isActive) return
                        Timber.e(exception, "OneDriveAuthManager.acquireAccessToken: error loading account")
                        cont.resume(AuthResult.Error(exception.message ?: "Failed to load account", exception))
                    }
                },
            )
        }
    }

    /**
     * Issues a silent token request for [activeAccount] and resumes [cont] with the result.
     * The continuation is resumed at most once; every callback checks [cont]'s active state
     * first so that this helper is safe to call from another MSAL callback without risking
     * double-resume crashes.
     */
    private fun acquireTokenSilent(
        app: IMultipleAccountPublicClientApplication,
        activeAccount: IAccount,
        cont: kotlinx.coroutines.CancellableContinuation<AuthResult<String>>,
    ) {
        val params =
            AcquireTokenSilentParameters.Builder()
                .forAccount(activeAccount)
                .fromAuthority(activeAccount.authority)
                .withScopes(scopes)
                .withCallback(
                    object : SilentAuthenticationCallback {
                        override fun onSuccess(authenticationResult: IAuthenticationResult?) {
                            if (!cont.isActive) return
                            val token = authenticationResult?.accessToken
                            if (token.isNullOrBlank()) {
                                Timber.e("OneDriveAuthManager.acquireAccessToken: success but null/empty token")
                                cont.resume(AuthResult.NeedsInteractiveSignIn)
                            } else {
                                Timber.d("OneDriveAuthManager.acquireAccessToken: acquired token successfully")
                                cont.resume(AuthResult.Success(token))
                            }
                        }

                        override fun onError(exception: MsalException?) {
                            if (!cont.isActive) return
                            Timber.w(exception, "OneDriveAuthManager.acquireAccessToken: silent acquisition failed")
                            val result =
                                when (exception) {
                                    is MsalUiRequiredException -> AuthResult.NeedsInteractiveSignIn
                                    else ->
                                        AuthResult.Error(
                                            exception?.message ?: "Silent token acquisition failed",
                                            exception,
                                        )
                                }
                            cont.resume(result)
                        }
                    },
                )
                .build()

        app.acquireTokenSilentAsync(params)
    }

    private fun readAccountHints(prefs: SharedPreferences): LinkedHashMap<String, String> {
        val storedHints = prefs.getString(KEY_ACCOUNT_HINTS, null)
        if (storedHints.isNullOrBlank()) {
            val legacyHint = prefs.getString(KEY_ACCOUNT_HINT, null)
            if (legacyHint.isNullOrBlank()) return linkedMapOf()

            val migrated =
                linkedMapOf(
                    LEGACY_ACCOUNT_HINT_ID to legacyHint,
                )
            writeAccountHints(prefs, migrated, latestAccountId = LEGACY_ACCOUNT_HINT_ID)
            prefs.edit().remove(KEY_ACCOUNT_HINT).apply()
            return migrated
        }

        return try {
            val json = JSONObject(storedHints)
            linkedMapOf<String, String>().apply {
                json.keys().forEach { key ->
                    put(key, json.optString(key))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "OneDriveAuthManager: failed to parse stored account hints")
            linkedMapOf()
        }
    }

    private fun writeAccountHints(
        prefs: SharedPreferences,
        hints: Map<String, String>,
        latestAccountId: String?,
    ) {
        val json = JSONObject()
        hints.forEach { (accountId, hint) ->
            json.put(accountId, hint)
        }

        val resolvedLatestAccountId =
            latestAccountId
                ?: prefs.getString(KEY_LATEST_ACCOUNT_HINT_ID, null)
                    ?.takeIf { hints.containsKey(it) }
                ?: hints.keys.lastOrNull()

        prefs
            .edit()
            .putString(KEY_ACCOUNT_HINTS, json.toString())
            .apply {
                if (resolvedLatestAccountId == null) {
                    remove(KEY_LATEST_ACCOUNT_HINT_ID)
                } else {
                    putString(KEY_LATEST_ACCOUNT_HINT_ID, resolvedLatestAccountId)
                }
            }.apply()
    }

    companion object {
        private const val PREFS_FILE = "onedrive_auth_prefs"
        internal const val KEY_ACCOUNT_HINT = "account_hint"
        internal const val KEY_ACCOUNT_HINTS = "account_hints"
        private const val KEY_LATEST_ACCOUNT_HINT_ID = "latest_account_hint_id"
        private const val LEGACY_ACCOUNT_HINT_ID = "__legacy_hint_migrated_v1__"

        /**
         * Creates an instance with explicit [clientId] and [redirectUri] values
         * instead of reading from [com.synckro.BuildConfig]. Use this
         * in unit tests (Robolectric) to control the config without relying on
         * build-time values.
         *
         * Defaults both to `""` so [isConfigured] returns `false` unless the test
         * explicitly supplies values.
         */
        internal fun forTest(
            context: Context,
            clientId: String = "",
            redirectUri: String = "",
            testPrefs: SharedPreferences? = null,
        ) = OneDriveAuthManager(context, clientId, redirectUri, prefsOverride = testPrefs)
    }
}
