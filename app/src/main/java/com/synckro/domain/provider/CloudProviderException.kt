package com.synckro.domain.provider

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
     * Callers (e.g. [com.synckro.data.worker.SyncWorker])
     * should treat this as a [com.synckro.domain.sync.SyncEngine.Result.Terminal]
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

    /**
     * The provider responded with HTTP 429 (Too Many Requests) or an equivalent
     * rate-limit signal.  Callers should honour the [retryAfterMs] delay before
     * the next attempt.
     *
     * @param retryAfterMs Milliseconds to wait before the next attempt.  0 means
     *   the caller may choose a default backoff.
     * @param message Human-readable description.
     * @param cause   Original exception, if any.
     */
    class RateLimited(
        val retryAfterMs: Long,
        message: String,
        cause: Throwable? = null,
    ) : CloudProviderException(message, cause)
}
