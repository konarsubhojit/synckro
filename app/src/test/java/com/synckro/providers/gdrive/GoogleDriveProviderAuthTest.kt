package com.synckro.providers.gdrive

import com.synckro.domain.auth.Account
import com.synckro.domain.auth.AuthResult
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.provider.CloudProviderException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the [GoogleDriveProvider.ensureAuthenticated] error-mapping layer.
 *
 * Each [AuthResult] variant returned by [GoogleDriveAuthManager] is mapped to the
 * appropriate [CloudProviderException] subclass (or a `true` / `false` return value
 * on success / unexpected-cancel). Tests use MockK so no Google Identity types
 * cross the test boundary.
 */
class GoogleDriveProviderAuthTest {
    private lateinit var authManager: GoogleDriveAuthManager
    private lateinit var restClient: GoogleDriveRestClient
    private lateinit var provider: GoogleDriveProvider

    private val fakeAccount =
        Account(
            id = "test@example.com",
            provider = CloudProviderType.GOOGLE_DRIVE,
            displayName = "Test User",
            email = "test@example.com",
        )

    @Before
    fun setUp() {
        authManager = mockk()
        restClient = mockk(relaxed = true)
        provider = GoogleDriveProvider(fakeAccount.id, authManager, restClient)
    }

    // -------------------------------------------------------------------------
    // Success path
    // -------------------------------------------------------------------------

    @Test
    fun `ensureAuthenticated returns true when token acquired silently`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Success("token-123")

            val result = provider.ensureAuthenticated()

            assertTrue(result)
            coVerify(exactly = 1) { authManager.acquireAccessToken(fakeAccount) }
        }

    // -------------------------------------------------------------------------
    // No account cached
    // -------------------------------------------------------------------------

    @Test
    fun `ensureAuthenticated throws AuthenticationRequired when no account is signed in`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns emptyList()

            try {
                provider.ensureAuthenticated()
                fail("Expected CloudProviderException.AuthenticationRequired")
            } catch (e: CloudProviderException.AuthenticationRequired) {
                assertTrue(e.message!!.contains("No Google Drive account is linked"))
            }
        }

    // -------------------------------------------------------------------------
    // NeedsInteractiveSignIn → AuthenticationRequired
    // -------------------------------------------------------------------------

    @Test
    fun `ensureAuthenticated throws AuthenticationRequired when NeedsInteractiveSignIn`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.NeedsInteractiveSignIn

            try {
                provider.ensureAuthenticated()
                fail("Expected CloudProviderException.AuthenticationRequired")
            } catch (e: CloudProviderException.AuthenticationRequired) {
                assertTrue(e.message!!.contains("expired"))
            }
        }

    // -------------------------------------------------------------------------
    // NotConfigured → CloudProviderException.NotConfigured
    // -------------------------------------------------------------------------

    @Test
    fun `ensureAuthenticated throws NotConfigured when provider is not configured`() =
        runTest {
            val configMsg = "Google Drive web client ID is not configured"
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.NotConfigured(configMsg)

            try {
                provider.ensureAuthenticated()
                fail("Expected CloudProviderException.NotConfigured")
            } catch (e: CloudProviderException.NotConfigured) {
                assertEquals(configMsg, e.message)
            }
        }

    // -------------------------------------------------------------------------
    // Error → AuthenticationFailed
    // -------------------------------------------------------------------------

    @Test
    fun `ensureAuthenticated throws AuthenticationFailed when auth returns Error`() =
        runTest {
            val errorMsg = "Google Identity error: invalid_client"
            val cause = RuntimeException("underlying cause")
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Error(errorMsg, cause)

            try {
                provider.ensureAuthenticated()
                fail("Expected CloudProviderException.AuthenticationFailed")
            } catch (e: CloudProviderException.AuthenticationFailed) {
                assertEquals(errorMsg, e.message)
                assertEquals(cause, e.cause)
            }
        }

    @Test
    fun `ensureAuthenticated throws AuthenticationFailed when error has no cause`() =
        runTest {
            val errorMsg = "Unexpected auth error"
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Error(errorMsg)

            try {
                provider.ensureAuthenticated()
                fail("Expected CloudProviderException.AuthenticationFailed")
            } catch (e: CloudProviderException.AuthenticationFailed) {
                assertEquals(errorMsg, e.message)
            }
        }

    // -------------------------------------------------------------------------
    // Cancelled → returns false
    // -------------------------------------------------------------------------

    @Test
    fun `ensureAuthenticated returns false when auth is unexpectedly cancelled`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Cancelled

            val result = provider.ensureAuthenticated()

            assertFalse(result)
        }

    @Test
    fun `list silently authenticates when no token is cached`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Success("token-123")
            coEvery { restClient.list("token-123", null) } returns emptyList()

            val result = provider.list(null)

            assertTrue(result.isEmpty())
            coVerify(exactly = 1) { authManager.acquireAccessToken(fakeAccount) }
            coVerify(exactly = 1) { restClient.list("token-123", null) }
        }

    // -------------------------------------------------------------------------
    // Proactive token refresh
    // -------------------------------------------------------------------------

    @Test
    fun `obtainAccessToken proactively refreshes when token is stale`() =
        runTest {
            var fakeTime = 0L
            val testProvider = GoogleDriveProvider(
                fakeAccount.id, authManager, restClient,
                clock = { fakeTime },
                tokenExpiryThresholdMs = GoogleDriveProvider.TOKEN_EXPIRY_THRESHOLD_MS,
            )
            // First authentication — acquires a fresh token.
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Success("token-fresh")
            coEvery { restClient.list(any(), null) } returns emptyList()

            testProvider.ensureAuthenticated()

            // Advance clock past the 50-minute threshold.
            fakeTime = GoogleDriveProvider.TOKEN_EXPIRY_THRESHOLD_MS

            // Next API call must trigger a proactive refresh.
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Success("token-refreshed")
            coEvery { restClient.list("token-refreshed", null) } returns emptyList()

            testProvider.list(null)

            coVerify(exactly = 2) { authManager.acquireAccessToken(fakeAccount) }
        }

    @Test
    fun `obtainAccessToken does not refresh when token is still fresh`() =
        runTest {
            var fakeTime = 0L
            val testProvider = GoogleDriveProvider(
                fakeAccount.id, authManager, restClient,
                clock = { fakeTime },
                tokenExpiryThresholdMs = GoogleDriveProvider.TOKEN_EXPIRY_THRESHOLD_MS,
            )
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Success("token-abc")
            coEvery { restClient.list("token-abc", null) } returns emptyList()

            testProvider.ensureAuthenticated()

            // Advance clock to just under the threshold.
            fakeTime = GoogleDriveProvider.TOKEN_EXPIRY_THRESHOLD_MS - 1

            testProvider.list(null)

            // acquireAccessToken called only once (during ensureAuthenticated).
            coVerify(exactly = 1) { authManager.acquireAccessToken(fakeAccount) }
        }

    // -------------------------------------------------------------------------
    // Exception hierarchy checks
    // -------------------------------------------------------------------------

    @Test
    fun `AuthenticationRequired is a CloudProviderException`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns emptyList()

            try {
                provider.ensureAuthenticated()
                fail("Expected exception")
            } catch (e: CloudProviderException) {
                assertTrue(e is CloudProviderException.AuthenticationRequired)
            }
        }

    @Test
    fun `NotConfigured is a CloudProviderException`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.NotConfigured("missing config")

            try {
                provider.ensureAuthenticated()
                fail("Expected exception")
            } catch (e: CloudProviderException) {
                assertTrue(e is CloudProviderException.NotConfigured)
            }
        }

    @Test
    fun `AuthenticationFailed is a CloudProviderException`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Error("network error")

            try {
                provider.ensureAuthenticated()
                fail("Expected exception")
            } catch (e: CloudProviderException) {
                assertTrue(e is CloudProviderException.AuthenticationFailed)
            }
        }
}
