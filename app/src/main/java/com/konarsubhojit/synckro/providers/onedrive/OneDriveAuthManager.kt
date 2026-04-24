package com.konarsubhojit.synckro.providers.onedrive

import android.app.Activity
import com.konarsubhojit.synckro.BuildConfig
import com.konarsubhojit.synckro.domain.auth.Account
import com.konarsubhojit.synckro.domain.auth.AuthManager
import com.konarsubhojit.synckro.domain.auth.AuthResult
import com.konarsubhojit.synckro.domain.model.CloudProviderType
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
class OneDriveAuthManager @Inject constructor() : AuthManager {
    override val providerType: CloudProviderType = CloudProviderType.ONEDRIVE
    override val displayName: String = "OneDrive"

    override suspend fun isConfigured(): Boolean = BuildConfig.MS_CLIENT_ID.isNotBlank()

    override suspend fun signIn(activity: Activity): AuthResult<Account> {
        if (!isConfigured()) {
            return AuthResult.NotConfigured(
                "OneDrive is not configured. Set MS_CLIENT_ID and MSAL_REDIRECT_URI before building."
            )
        }
        return AuthResult.Error(
            "OneDrive sign-in is coming in the next update."
        )
    }

    override suspend fun signOut(account: Account): AuthResult<Unit> = AuthResult.Success(Unit)

    override suspend fun currentAccounts(): List<Account> = emptyList()

    override suspend fun acquireAccessToken(account: Account): AuthResult<String> =
        AuthResult.NeedsInteractiveSignIn
}
