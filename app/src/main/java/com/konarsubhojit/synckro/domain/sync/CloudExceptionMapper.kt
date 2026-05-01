package com.konarsubhojit.synckro.domain.sync

import com.konarsubhojit.synckro.domain.provider.CloudProviderException
import kotlinx.coroutines.CancellationException

/**
 * Maps an exception thrown by a [com.konarsubhojit.synckro.domain.provider.CloudProvider]
 * (or anything called from inside the sync pipeline) to a [SyncEngine.Result].
 *
 * Decision matrix — kept here, not buried inside [SyncEngine.runOnce], so it can
 * be unit-tested without spinning up an Android sandbox and reused by anyone
 * who calls a provider directly:
 *
 * | Exception                                   | Result                                  | Why |
 * |---------------------------------------------|-----------------------------------------|-----|
 * | [CloudProviderException.AuthenticationRequired] | `Terminal(needsReauth = true)`      | Token expired / account removed / scope revoked / `MsalUiRequiredException`. The user has to re-authenticate; retrying in the background will never recover. |
 * | [CloudProviderException.NotConfigured]      | `Terminal(needsReauth = true)`          | Missing client ID / redirect URI. Surface the same "Re-authenticate" CTA so the user lands on the Accounts screen and sees the configuration error. |
 * | [CloudProviderException.AuthenticationFailed]| `Retriable`                             | Network blip during token refresh, transient MSAL/IDS error. WorkManager backoff is the right answer. |
 * | [CloudProviderException.RateLimited]        | `Retriable` (Retry-After honoured)      | Server-driven backoff — the engine and WorkManager will respect [CloudProviderException.RateLimited.retryAfterMs] separately. |
 * | [CancellationException]                     | rethrown                                | Cooperative cancellation must propagate; never swallow it. |
 * | Any other [Throwable]                       | `Retriable`                             | Unknown errors are assumed transient — better than a Terminal that silently disables the pair. |
 *
 * Authentication-terminal results carry [SyncEngine.Result.Terminal.needsReauth]=true
 * so the worker can persist a distinct outcome and the Accounts screen can show a
 * "Re-authenticate" CTA for the affected provider.
 */
object CloudExceptionMapper {

    /**
     * Convert [t] into a [SyncEngine.Result]. Pure / synchronous; safe to call from any thread.
     *
     * @throws CancellationException unchanged — coroutine cancellation must always propagate.
     */
    fun toResult(t: Throwable): SyncEngine.Result {
        if (t is CancellationException) throw t
        return when (t) {
            is CloudProviderException.AuthenticationRequired ->
                SyncEngine.Result.Terminal(
                    reason = t.message ?: "Re-authentication required",
                    needsReauth = true,
                )
            is CloudProviderException.NotConfigured ->
                SyncEngine.Result.Terminal(
                    reason = t.message ?: "Provider not configured",
                    needsReauth = true,
                )
            is CloudProviderException.AuthenticationFailed ->
                SyncEngine.Result.Retriable(
                    reason = t.message ?: "Authentication failed",
                )
            is CloudProviderException.RateLimited ->
                SyncEngine.Result.Retriable(
                    reason = t.message ?: "Rate limited (retry in ${t.retryAfterMs} ms)",
                )
            else ->
                SyncEngine.Result.Retriable(
                    reason = t.message
                        ?: t.javaClass.simpleName.takeIf { it.isNotBlank() }
                        ?: "Unknown error",
                )
        }
    }
}
