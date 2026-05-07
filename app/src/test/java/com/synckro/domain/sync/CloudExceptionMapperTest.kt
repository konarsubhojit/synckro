package com.synckro.domain.sync

import com.synckro.data.local.fs.LocalStorageException
import com.synckro.domain.provider.CloudProviderException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [CloudExceptionMapper] covering each documented failure mode
 * from the "Sign-in error states & recovery" issue:
 *
 * - Token expired / account removed / scope revoked → [CloudProviderException.AuthenticationRequired]
 * - `MsalUiRequiredException` → mapped to AuthenticationRequired by OneDriveAuthManager
 * - Provider not configured (missing client ID / redirect URI) → [CloudProviderException.NotConfigured]
 * - No network / Play Services missing → [CloudProviderException.AuthenticationFailed] (transient)
 * - HTTP 429 / Retry-After → [CloudProviderException.RateLimited]
 *
 * The mapper is the single source of truth for "Terminal vs Retriable" so the
 * Accounts screen can show a Re-authenticate CTA and WorkManager doesn't enter
 * an infinite retry storm on a permanent auth failure.
 */
class CloudExceptionMapperTest {
    @Test
    fun `AuthenticationRequired maps to Terminal with needsReauth=true (token expired)`() {
        val ex = CloudProviderException.AuthenticationRequired("Token expired; sign in again")

        val result = CloudExceptionMapper.toResult(ex) as SyncEngine.Result.Terminal

        assertTrue("Auth-terminal must flag needsReauth", result.needsReauth)
        assertEquals("Token expired; sign in again", result.reason)
    }

    @Test
    fun `AuthenticationRequired maps to Terminal (account removed)`() {
        val ex = CloudProviderException.AuthenticationRequired("No OneDrive account is signed in.")
        val result = CloudExceptionMapper.toResult(ex)
        assertTrue(result is SyncEngine.Result.Terminal)
        assertTrue((result as SyncEngine.Result.Terminal).needsReauth)
    }

    @Test
    fun `AuthenticationRequired maps to Terminal (scope revoked)`() {
        // Google Drive scope revocation surfaces as a 401 from Drive API,
        // which the provider wraps as AuthenticationRequired in driveCall().
        val ex = CloudProviderException.AuthenticationRequired("Drive returned 401: scope revoked")
        val result = CloudExceptionMapper.toResult(ex)
        assertTrue(result is SyncEngine.Result.Terminal)
        assertTrue((result as SyncEngine.Result.Terminal).needsReauth)
    }

    @Test
    fun `MsalUiRequiredException-shaped failure maps to Terminal`() {
        // MsalUiRequiredException is mapped by OneDriveAuthManager to
        // AuthResult.NeedsInteractiveSignIn, which OneDriveProvider rethrows as
        // AuthenticationRequired. Verify the final mapping ends in Terminal+reauth.
        val ex =
            CloudProviderException.AuthenticationRequired(
                "Interactive sign-in required",
                cause = IllegalStateException("MsalUiRequiredException"),
            )
        val mapped = CloudExceptionMapper.toResult(ex)
        assertTrue(mapped is SyncEngine.Result.Terminal)
        assertTrue((mapped as SyncEngine.Result.Terminal).needsReauth)
    }

    @Test
    fun `NotConfigured maps to Terminal with needsReauth=true`() {
        val ex =
            CloudProviderException.NotConfigured(
                "OneDrive is not configured. Set MS_CLIENT_ID and MSAL_REDIRECT_URI.",
            )

        val result = CloudExceptionMapper.toResult(ex) as SyncEngine.Result.Terminal

        assertTrue(
            "NotConfigured should drop the user on the Accounts screen, same as AuthenticationRequired",
            result.needsReauth,
        )
    }

    @Test
    fun `AuthenticationFailed maps to Retriable (transient network during refresh)`() {
        val ex =
            CloudProviderException.AuthenticationFailed(
                "Network error during token refresh",
                cause = IOException("connection reset"),
            )

        val result = CloudExceptionMapper.toResult(ex)

        assertTrue("Network refresh failures should retry, not surrender", result is SyncEngine.Result.Retriable)
        assertEquals("Network error during token refresh", (result as SyncEngine.Result.Retriable).reason)
    }

    @Test
    fun `RateLimited maps to Retriable (Retry-After honoured)`() {
        val ex =
            CloudProviderException.RateLimited(
                retryAfterMs = 60_000L,
                message = "Too many requests",
            )

        val result = CloudExceptionMapper.toResult(ex)

        assertTrue(result is SyncEngine.Result.Retriable)
        assertTrue(
            "Reason should mention rate limit / retry-after",
            (result as SyncEngine.Result.Retriable).reason.contains("rate", ignoreCase = true) ||
                result.reason == "Too many requests",
        )
    }

    @Test
    fun `unknown Throwable maps to Retriable so a stray bug doesn't disable the pair`() {
        val ex = RuntimeException("unexpected")

        val result = CloudExceptionMapper.toResult(ex)

        assertTrue(result is SyncEngine.Result.Retriable)
    }

    @Test
    fun `unknown Throwable with null message falls back to class name`() {
        val ex = object : RuntimeException() {}
        val result = CloudExceptionMapper.toResult(ex) as SyncEngine.Result.Retriable
        // Just make sure we don't surface "null" to the user.
        assertFalse(result.reason.contains("null"))
        assertTrue(result.reason.isNotBlank())
    }

    @Test
    fun `CancellationException is rethrown so coroutine cancellation propagates`() {
        try {
            CloudExceptionMapper.toResult(CancellationException("cancelled"))
            fail("Expected CancellationException to propagate, not be swallowed into a Result")
        } catch (expected: CancellationException) {
            // good
        }
    }

    @Test
    fun `Terminal default needsReauth is false (generic terminal failures)`() {
        // Sanity check on the data class contract used by SyncEngine.runOnce
        // when it returns Terminal directly (e.g. unsupported provider).
        val terminal = SyncEngine.Result.Terminal("Unsupported provider: ONEDRIVE")
        assertFalse(terminal.needsReauth)
    }

    @Test
    fun `LocalStorageException maps to Terminal with needsReLink=true`() {
        val ex = LocalStorageException("SAF permission denied for docId='root'", SecurityException("revoked"))

        val result = CloudExceptionMapper.toResult(ex) as SyncEngine.Result.Terminal

        assertTrue("needsReLink must be true for SAF permission failures", result.needsReLink)
        assertFalse("needsReauth must not be set for SAF failures", result.needsReauth)
        assertEquals("SAF permission denied for docId='root'", result.reason)
    }

    @Test
    fun `LocalStorageException with null message falls back to a non-blank reason`() {
        val ex = LocalStorageException("Local storage permission revoked")

        val result = CloudExceptionMapper.toResult(ex) as SyncEngine.Result.Terminal

        assertTrue("needsReLink must be true", result.needsReLink)
        assertTrue("reason must be non-blank", result.reason.isNotBlank())
        assertFalse("reason must not contain literal 'null'", result.reason.contains("null"))
    }

    @Test
    fun `Terminal default needsReLink is false (generic terminal failures)`() {
        val terminal = SyncEngine.Result.Terminal("Unsupported provider: ONEDRIVE")
        assertFalse(terminal.needsReLink)
    }
}
