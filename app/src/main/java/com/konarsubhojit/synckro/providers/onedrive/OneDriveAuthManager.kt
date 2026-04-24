package com.konarsubhojit.synckro.providers.onedrive

import android.content.Context
import com.konarsubhojit.synckro.BuildConfig
import com.konarsubhojit.synckro.R
import com.konarsubhojit.synckro.data.repository.AccountRepository
import com.konarsubhojit.synckro.domain.auth.Account
import com.konarsubhojit.synckro.domain.auth.AuthManager
import com.konarsubhojit.synckro.domain.auth.AuthResult
import com.konarsubhojit.synckro.domain.auth.AuthUiHost
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.ui.auth.ActivityAuthUiHost
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * OneDrive [AuthManager] backed by MSAL (Microsoft Authentication Library).
 * Uses single-account mode for simplicity; sign-out removes the account from
 * MSAL's token cache and the persisted [AccountRepository].
 *
 * All failures are logged via Timber and reported through [AuthResult].
 */
@Singleton
class OneDriveAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
) : AuthManager {
    override val providerType: CloudProviderType = CloudProviderType.ONEDRIVE
    override val displayName: String = "OneDrive"

    private var msalApp: ISingleAccountPublicClientApplication? = null

    private val scopes = listOf(
        "Files.ReadWrite",
        "offline_access",
        "User.Read",
    )

    override suspend fun isConfigured(): Boolean =
        BuildConfig.MS_CLIENT_ID.isNotBlank() && BuildConfig.MSAL_REDIRECT_URI.isNotBlank()

    /**
     * Lazily initializes the MSAL single-account application. Returns null and
     * logs an error if configuration fails.
     */
    private suspend fun getOrCreateMsalApp(): ISingleAccountPublicClientApplication? {
        if (msalApp != null) return msalApp

        if (!isConfigured()) {
            Timber.w("OneDriveAuthManager: MSAL not configured (missing MS_CLIENT_ID or MSAL_REDIRECT_URI)")
            return null
        }

        return suspendCancellableCoroutine { cont ->
            try {
                PublicClientApplication.createSingleAccountPublicClientApplication(
                    context,
                    R.raw.msal_config,
                    object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                        override fun onCreated(application: ISingleAccountPublicClientApplication?) {
                            msalApp = application
                            Timber.d("OneDriveAuthManager: MSAL app created successfully")
                            cont.resume(application)
                        }

                        override fun onError(exception: MsalException?) {
                            Timber.e(exception, "OneDriveAuthManager: Failed to create MSAL app")
                            cont.resume(null)
                        }
                    },
                )
            } catch (e: Exception) {
                Timber.e(e, "OneDriveAuthManager: Exception creating MSAL app")
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

        val app = getOrCreateMsalApp()
            ?: return AuthResult.Error("Failed to initialize MSAL")

        Timber.d("OneDriveAuthManager.signIn: launching interactive sign-in")

        return suspendCancellableCoroutine { cont ->
            val params = SignInParameters.builder()
                .withActivity(activity)
                .withScopes(scopes)
                .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult?) {
                        if (authenticationResult == null || authenticationResult.account == null) {
                            Timber.e("OneDriveAuthManager.signIn: success but null result/account")
                            cont.resume(AuthResult.Error("Sign-in returned null account"))
                            return
                        }

                        val msalAccount = authenticationResult.account
                        Timber.i("OneDriveAuthManager.signIn: success for ${msalAccount.username}")

                        val account = Account(
                            id = msalAccount.id,
                            provider = CloudProviderType.ONEDRIVE,
                            displayName = msalAccount.username ?: msalAccount.id,
                            email = msalAccount.username,
                        )

                        // Persist to Room (done in AccountsViewModel after this returns)
                        cont.resume(AuthResult.Success(account))
                    }

                    override fun onError(exception: MsalException?) {
                        Timber.e(exception, "OneDriveAuthManager.signIn: error")
                        val result = when (exception) {
                            is MsalClientException -> {
                                if (exception.errorCode == "user_cancelled") {
                                    AuthResult.Cancelled
                                } else {
                                    AuthResult.Error(
                                        exception.message ?: "MSAL client error: ${exception.errorCode}",
                                        exception
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
                                    exception
                                )
                            }
                            else -> {
                                AuthResult.Error(
                                    exception?.message ?: "Unknown MSAL error",
                                    exception
                                )
                            }
                        }
                        cont.resume(result)
                    }

                    override fun onCancel() {
                        Timber.i("OneDriveAuthManager.signIn: user cancelled")
                        cont.resume(AuthResult.Cancelled)
                    }
                })
                .build()

            app.signIn(params)
        }
    }

    override suspend fun signOut(account: Account): AuthResult<Unit> {
        Timber.i("OneDriveAuthManager.signOut: account=${account.id}")

        val app = getOrCreateMsalApp()
        if (app == null) {
            Timber.w("OneDriveAuthManager.signOut: MSAL app not available, removing from DB only")
            accountRepository.delete(account.id)
            return AuthResult.Success(Unit)
        }

        return suspendCancellableCoroutine { cont ->
            app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                override fun onSignOut() {
                    Timber.i("OneDriveAuthManager.signOut: MSAL sign-out successful")
                    cont.resume(AuthResult.Success(Unit))
                }

                override fun onError(exception: MsalException) {
                    Timber.e(exception, "OneDriveAuthManager.signOut: MSAL error")
                    // Even if MSAL fails, remove from our DB
                    cont.resume(AuthResult.Error(exception.message ?: "Sign-out failed", exception))
                }
            })
        }
    }

    override suspend fun currentAccounts(): List<Account> {
        val app = getOrCreateMsalApp() ?: return emptyList()

        Timber.d("OneDriveAuthManager.currentAccounts: checking MSAL cache")

        return suspendCancellableCoroutine { cont ->
            app.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                override fun onAccountLoaded(activeAccount: IAccount?) {
                    if (activeAccount == null) {
                        Timber.d("OneDriveAuthManager.currentAccounts: no MSAL account")
                        cont.resume(emptyList())
                        return
                    }

                    Timber.d("OneDriveAuthManager.currentAccounts: found ${activeAccount.username}")
                    val account = Account(
                        id = activeAccount.id,
                        provider = CloudProviderType.ONEDRIVE,
                        displayName = activeAccount.username ?: activeAccount.id,
                        email = activeAccount.username,
                    )
                    cont.resume(listOf(account))
                }

                override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                    Timber.d("OneDriveAuthManager.currentAccounts: account changed")
                    if (currentAccount == null) {
                        cont.resume(emptyList())
                    } else {
                        val account = Account(
                            id = currentAccount.id,
                            provider = CloudProviderType.ONEDRIVE,
                            displayName = currentAccount.username ?: currentAccount.id,
                            email = currentAccount.username,
                        )
                        cont.resume(listOf(account))
                    }
                }

                override fun onError(exception: MsalException) {
                    Timber.e(exception, "OneDriveAuthManager.currentAccounts: error")
                    cont.resume(emptyList())
                }
            })
        }
    }

    override suspend fun acquireAccessToken(account: Account): AuthResult<String> {
        if (!isConfigured()) {
            return AuthResult.NotConfigured(context.getString(R.string.onedrive_not_configured))
        }

        val app = getOrCreateMsalApp()
            ?: return AuthResult.Error("Failed to initialize MSAL")

        Timber.d("OneDriveAuthManager.acquireAccessToken: attempting silent acquisition for ${account.id}")

        return suspendCancellableCoroutine { cont ->
            app.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                override fun onAccountLoaded(activeAccount: IAccount?) {
                    if (activeAccount == null) {
                        Timber.w("OneDriveAuthManager.acquireAccessToken: no active account")
                        cont.resume(AuthResult.NeedsInteractiveSignIn)
                        return
                    }

                    val params = AcquireTokenSilentParameters.Builder()
                        .forAccount(activeAccount)
                        .fromAuthority(activeAccount.authority)
                        .withScopes(scopes)
                        .withCallback(object : SilentAuthenticationCallback {
                            override fun onSuccess(authenticationResult: IAuthenticationResult?) {
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
                                Timber.w(exception, "OneDriveAuthManager.acquireAccessToken: silent acquisition failed")
                                val result = when (exception) {
                                    is MsalUiRequiredException -> AuthResult.NeedsInteractiveSignIn
                                    else -> AuthResult.Error(
                                        exception?.message ?: "Silent token acquisition failed",
                                        exception
                                    )
                                }
                                cont.resume(result)
                            }
                        })
                        .build()

                    app.acquireTokenSilentAsync(params)
                }

                override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                    Timber.w("OneDriveAuthManager.acquireAccessToken: account changed during acquisition")
                    cont.resume(AuthResult.NeedsInteractiveSignIn)
                }

                override fun onError(exception: MsalException) {
                    Timber.e(exception, "OneDriveAuthManager.acquireAccessToken: error loading account")
                    cont.resume(AuthResult.Error(exception.message ?: "Failed to load account", exception))
                }
            })
        }
    }
}
