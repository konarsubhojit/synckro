package com.konarsubhojit.synckro.providers.onedrive

import android.content.Context
import com.konarsubhojit.synckro.BuildConfig
import com.konarsubhojit.synckro.R
import com.konarsubhojit.synckro.domain.auth.Account
import com.konarsubhojit.synckro.domain.auth.AuthManager
import com.konarsubhojit.synckro.domain.auth.AuthResult
import com.konarsubhojit.synckro.domain.auth.AuthUiHost
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Placeholder OneDrive [AuthManager]. The real MSAL-backed implementation
 * lands in PR #2; this version exists so the UI can be wired end-to-end
 * against a stable interface now.
 *
 * [signIn] deliberately returns [AuthResult.Error] with a clear, user-facing
 * message instead of silently failing — this is what the snackbar shows when
 * the user taps "Connect OneDrive" in the current PR.
 */
@Singleton
class OneDriveAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : AuthManager {
    override val providerType: CloudProviderType = CloudProviderType.ONEDRIVE
    override val displayName: String = "OneDrive"

    override suspend fun isConfigured(): Boolean =
        BuildConfig.MS_CLIENT_ID.isNotBlank() && BuildConfig.MSAL_REDIRECT_URI.isNotBlank()

    override suspend fun signIn(host: AuthUiHost): AuthResult<Account> {
        if (!isConfigured()) {
            return AuthResult.NotConfigured(context.getString(R.string.onedrive_not_configured))
        }
        return AuthResult.Error(context.getString(R.string.onedrive_signin_coming_soon))
    }

    override suspend fun signOut(account: Account): AuthResult<Unit> = AuthResult.Success(Unit)

    override suspend fun currentAccounts(): List<Account> = emptyList()

    override suspend fun acquireAccessToken(account: Account): AuthResult<String> {
        if (!isConfigured()) {
            return AuthResult.NotConfigured(context.getString(R.string.onedrive_not_configured))
        }
        return AuthResult.NeedsInteractiveSignIn
    }
}
