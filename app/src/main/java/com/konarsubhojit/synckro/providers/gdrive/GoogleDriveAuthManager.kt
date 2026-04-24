package com.konarsubhojit.synckro.providers.gdrive

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
 * Placeholder Google Drive [AuthManager]. The real Credential-Manager +
 * Drive-authorization implementation lands in PR #2; this version exists so
 * the UI can be wired end-to-end against a stable interface now.
 */
@Singleton
class GoogleDriveAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : AuthManager {
    override val providerType: CloudProviderType = CloudProviderType.GOOGLE_DRIVE
    override val displayName: String = "Google Drive"

    override suspend fun isConfigured(): Boolean = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    override suspend fun signIn(host: AuthUiHost): AuthResult<Account> {
        if (!isConfigured()) {
            return AuthResult.NotConfigured(context.getString(R.string.gdrive_not_configured))
        }
        return AuthResult.Error(context.getString(R.string.gdrive_signin_coming_soon))
    }

    override suspend fun signOut(account: Account): AuthResult<Unit> = AuthResult.Success(Unit)

    override suspend fun currentAccounts(): List<Account> = emptyList()

    override suspend fun acquireAccessToken(account: Account): AuthResult<String> {
        if (!isConfigured()) {
            return AuthResult.NotConfigured(context.getString(R.string.gdrive_not_configured))
        }
        return AuthResult.NeedsInteractiveSignIn
    }
}
