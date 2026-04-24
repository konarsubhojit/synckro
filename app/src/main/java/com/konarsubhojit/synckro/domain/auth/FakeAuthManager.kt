package com.konarsubhojit.synckro.domain.auth

import android.app.Activity
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [AuthManager] used by unit tests and as a placeholder until the
 * real provider SDKs are wired up in PR #2. Behaviour is fully deterministic
 * and configurable per-test via [nextSignInResult].
 */
class FakeAuthManager(
    override val providerType: CloudProviderType,
    override val displayName: String = providerType.name,
    private var configured: Boolean = true,
) : AuthManager {

    private val mutex = Mutex()
    private val accounts = mutableListOf<Account>()

    /** If set, the next [signIn] call returns this result and then clears it. */
    var nextSignInResult: AuthResult<Account>? = null

    fun setConfigured(value: Boolean) { configured = value }

    override suspend fun isConfigured(): Boolean = configured

    override suspend fun signIn(activity: Activity): AuthResult<Account> = mutex.withLock {
        val override = nextSignInResult
        if (override != null) {
            nextSignInResult = null
            if (override is AuthResult.Success) accounts += override.value
            return override
        }
        if (!configured) return AuthResult.NotConfigured("$displayName client id is not configured.")
        val acct = Account(
            id = "fake-${providerType.name}-${accounts.size + 1}",
            provider = providerType,
            displayName = "Test ${providerType.name.lowercase()} user ${accounts.size + 1}",
            email = "user${accounts.size + 1}@example.invalid",
        )
        accounts += acct
        AuthResult.Success(acct)
    }

    override suspend fun signOut(account: Account): AuthResult<Unit> = mutex.withLock {
        accounts.removeAll { it.id == account.id }
        AuthResult.Success(Unit)
    }

    override suspend fun currentAccounts(): List<Account> = mutex.withLock { accounts.toList() }

    override suspend fun acquireAccessToken(account: Account): AuthResult<String> = mutex.withLock {
        if (accounts.none { it.id == account.id }) return AuthResult.NeedsInteractiveSignIn
        if (!configured) return AuthResult.NotConfigured("$displayName client id is not configured.")
        AuthResult.Success("fake-token-for-${account.id}")
    }
}
