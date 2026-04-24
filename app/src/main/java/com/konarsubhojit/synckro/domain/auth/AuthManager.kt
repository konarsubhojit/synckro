package com.konarsubhojit.synckro.domain.auth

import android.app.Activity

/**
 * Outcome of an authentication operation. Using a sealed type here (instead
 * of returning nullable or throwing) forces every caller to handle the four
 * real-world cases distinctly — which is how we avoid the "nothing happens
 * when I tap +" class of bugs.
 */
sealed interface AuthResult<out T> {
    /** Authentication succeeded. [value] carries the relevant payload (e.g. the account or access token). */
    data class Success<T>(val value: T) : AuthResult<T>

    /** The user explicitly cancelled (e.g. closed the OAuth browser tab). Not an error. */
    data object Cancelled : AuthResult<Nothing>

    /**
     * A silent / cached token is no longer valid; the caller must trigger an
     * interactive sign-in with an [Activity] context.
     */
    data object NeedsInteractiveSignIn : AuthResult<Nothing>

    /** Authentication failed for a reason the user can't resolve by retrying silently. */
    data class Error(val message: String, val cause: Throwable? = null) : AuthResult<Nothing>

    /** The provider is not configured (e.g. missing client id). Tell the user to configure it. */
    data class NotConfigured(val message: String) : AuthResult<Nothing>
}

/**
 * Per-provider authentication manager. Implementations are expected to own a
 * reference to the provider's SDK (MSAL for OneDrive, Credential Manager +
 * Drive authorization for Google) and to persist only non-secret metadata.
 *
 * All methods are suspend so callers can compose them from `viewModelScope`
 * without leaking threads.
 */
interface AuthManager {

    /** Stable key identifying this manager (matches [Account.provider]). */
    val providerType: com.konarsubhojit.synckro.domain.model.CloudProviderType

    /** Human-readable name for the provider, used in UI strings. */
    val displayName: String

    /** True if the provider's client id / redirect URI are present. */
    suspend fun isConfigured(): Boolean

    /**
     * Launches the interactive sign-in flow. Requires a real [Activity] so the
     * OAuth browser tab / credential chooser can be shown.
     */
    suspend fun signIn(activity: Activity): AuthResult<Account>

    /** Revokes local credentials and forgets [account]. */
    suspend fun signOut(account: Account): AuthResult<Unit>

    /** Returns the accounts currently known to this manager (post-sign-in). */
    suspend fun currentAccounts(): List<Account>

    /**
     * Tries to acquire an access token silently using the cached refresh
     * token. If silent acquisition is not possible, returns
     * [AuthResult.NeedsInteractiveSignIn] so the caller can prompt the user.
     */
    suspend fun acquireAccessToken(account: Account): AuthResult<String>
}
