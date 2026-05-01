package com.konarsubhojit.synckro.domain.provider

/**
 * Typed exceptions surfaced by [CloudProvider] implementations. These are the
 * only exception types that should cross the provider boundary — raw SDK
 * exceptions (MSAL, Google Sign-In, …) must be caught inside the provider
 * and wrapped in an appropriate subclass here.
 */
sealed class CloudProviderException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * The provider has no valid token and interactive sign-in is required.
     * Callers (e.g. [com.konarsubhojit.synckro.data.worker.SyncWorker])
     * should treat this as a [com.konarsubhojit.synckro.domain.sync.SyncEngine.Result.Terminal]
     * and redirect the user to the Accounts screen.
     */
    class AuthenticationRequired(
        message: String,
        cause: Throwable? = null,
    ) : CloudProviderException(message, cause)

    /**
     * Authentication failed for a reason the user cannot resolve silently
     * (e.g. network error during token refresh, unexpected MSAL error).
     */
    class AuthenticationFailed(
        message: String,
        cause: Throwable? = null,
    ) : CloudProviderException(message, cause)

    /**
     * The provider is not configured (e.g. missing client ID or redirect URI).
     * The user needs to configure the app before authentication is possible.
     */
    class NotConfigured(
        message: String,
    ) : CloudProviderException(message)
}
