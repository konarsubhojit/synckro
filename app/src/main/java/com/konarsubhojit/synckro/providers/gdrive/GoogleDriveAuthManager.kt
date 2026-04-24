package com.konarsubhojit.synckro.providers.gdrive

import android.app.Activity
import com.konarsubhojit.synckro.BuildConfig
import com.konarsubhojit.synckro.domain.auth.Account
import com.konarsubhojit.synckro.domain.auth.AuthManager
import com.konarsubhojit.synckro.domain.auth.AuthResult
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Placeholder Google Drive [AuthManager]. The real Credential-Manager +
 * Drive-authorization implementation lands in PR #2; this version exists so
 * the UI can be wired end-to-end against a stable interface now.
 */
@Singleton
class GoogleDriveAuthManager @Inject constructor() : AuthManager {
    override val providerType: CloudProviderType = CloudProviderType.GOOGLE_DRIVE
    override val displayName: String = "Google Drive"

    override suspend fun isConfigured(): Boolean = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    override suspend fun signIn(activity: Activity): AuthResult<Account> {
        if (!isConfigured()) {
            return AuthResult.NotConfigured(
                "Google Drive is not configured. Set GOOGLE_WEB_CLIENT_ID before building."
            )
        }
        return AuthResult.Error(
            "Google Drive sign-in is coming in the next update."
        )
    }

    override suspend fun signOut(account: Account): AuthResult<Unit> = AuthResult.Success(Unit)

    override suspend fun currentAccounts(): List<Account> = emptyList()

    override suspend fun acquireAccessToken(account: Account): AuthResult<String> =
        AuthResult.NeedsInteractiveSignIn
}
