package com.synckro.providers.onedrive

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
 * Unit tests for the [OneDriveProvider.ensureAuthenticated] error-mapping layer.
 *
 * Each [AuthResult] variant returned by [OneDriveAuthManager] is mapped to the
 * appropriate [CloudProviderException] subclass (or to a `true` return value on
 * success). The tests use MockK to mock [OneDriveAuthManager] so no MSAL types
 * cross the test boundary.
 */
class OneDriveProviderAuthTest {
    private lateinit var authManager: OneDriveAuthManager
    private lateinit var graphClient: OneDriveGraphClient
    private lateinit var provider: OneDriveProvider

    private val fakeAccount =
        Account(
            id = "test-account-id",
            provider = CloudProviderType.ONEDRIVE,
            displayName = "Test User",
            email = "test@example.com",
        )

    @Before
    fun setUp() {
        authManager = mockk()
        graphClient = mockk(relaxed = true)
        provider = OneDriveProvider(fakeAccount.id, authManager, graphClient)
    }

    // -------------------------------------------------------------------------
    // Success path
    // -------------------------------------------------------------------------

    @Test
    fun `ensureAuthenticated returns true when token acquired silently`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Success("access-token-123")

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
            coEvery { authManager.getAccountHint(fakeAccount.id) } returns null

            try {
                provider.ensureAuthenticated()
                fail("Expected CloudProviderException.AuthenticationRequired")
            } catch (e: CloudProviderException.AuthenticationRequired) {
                assertTrue(e.message!!.contains("No OneDrive account is linked"))
            }
        }

    @Test
    fun `ensureAuthenticated includes account hint in message when available`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns emptyList()
            coEvery { authManager.getAccountHint(fakeAccount.id) } returns "last@example.com"

            try {
                provider.ensureAuthenticated()
                fail("Expected CloudProviderException.AuthenticationRequired")
            } catch (e: CloudProviderException.AuthenticationRequired) {
                assertTrue(e.message!!.contains("last@example.com"))
            }
        }

    // -------------------------------------------------------------------------
    // NeedsInteractiveSignIn → AuthenticationRequired (only after threshold)
    // -------------------------------------------------------------------------

    @Test
    fun `ensureAuthenticated throws AuthenticationFailed on first NeedsInteractiveSignIn`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.NeedsInteractiveSignIn

            try {
                provider.ensureAuthenticated()
                fail("Expected CloudProviderException.AuthenticationFailed")
            } catch (e: CloudProviderException.AuthenticationFailed) {
                assertTrue(e.message!!.contains("transient"))
            }
        }

    @Test
    fun `ensureAuthenticated throws AuthenticationRequired after threshold NeedsInteractiveSignIn`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.NeedsInteractiveSignIn

            try {
                provider.ensureAuthenticated()
                fail("Expected AuthenticationFailed")
            } catch (_: CloudProviderException.AuthenticationFailed) {
                // ok
            }

            try {
                provider.ensureAuthenticated()
                fail("Expected CloudProviderException.AuthenticationRequired")
            } catch (e: CloudProviderException.AuthenticationRequired) {
                assertTrue(e.message!!.contains("expired"))
            }
        }

    @Test
    fun `consecutive NeedsInteractiveSignIn counter resets on Success`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returnsMany
                listOf(
                    AuthResult.NeedsInteractiveSignIn,
                    AuthResult.Success("token-ok"),
                    AuthResult.NeedsInteractiveSignIn,
                )

            try {
                provider.ensureAuthenticated()
                fail("Expected AuthenticationFailed")
            } catch (_: CloudProviderException.AuthenticationFailed) {
                // ok
            }

            assertTrue(provider.ensureAuthenticated())

            try {
                provider.ensureAuthenticated()
                fail("Expected AuthenticationFailed")
            } catch (_: CloudProviderException.AuthenticationFailed) {
                // ok
            }
        }

    // -------------------------------------------------------------------------
    // NotConfigured → CloudProviderException.NotConfigured
    // -------------------------------------------------------------------------

    @Test
    fun `ensureAuthenticated throws NotConfigured when provider is not configured`() =
        runTest {
            val configMsg = "OneDrive client ID is not configured"
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
            val errorMsg = "MSAL service error: invalid_client"
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
            val errorMsg = "Unexpected MSAL error"
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
    // Cancelled → returns false (edge case; silent flow should not cancel)
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
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Success("access-token-123")
            coEvery { graphClient.list("access-token-123", null) } returns emptyList()

            val result = provider.list(null)

            assertTrue(result.isEmpty())
            coVerify(exactly = 1) { authManager.acquireAccessToken(fakeAccount) }
            coVerify(exactly = 1) { graphClient.list("access-token-123", null) }
        }

    // -------------------------------------------------------------------------
    // Proactive token refresh
    // -------------------------------------------------------------------------

    @Test
    fun `obtainAccessToken proactively refreshes when token is stale`() =
        runTest {
            var fakeTime = 0L
            val testProvider =
                OneDriveProvider(
                    fakeAccount.id,
                    authManager,
                    graphClient,
                    clock = { fakeTime },
                    tokenExpiryThresholdMs = OneDriveProvider.TOKEN_EXPIRY_THRESHOLD_MS,
                )
            // First authentication — acquires a fresh token.
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Success("token-fresh")
            coEvery { graphClient.list(any(), null) } returns emptyList()

            testProvider.ensureAuthenticated()

            // Advance clock past the 50-minute threshold.
            fakeTime = OneDriveProvider.TOKEN_EXPIRY_THRESHOLD_MS

            // Next API call must trigger a proactive refresh.
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Success("token-refreshed")
            coEvery { graphClient.list("token-refreshed", null) } returns emptyList()

            testProvider.list(null)

            coVerify(exactly = 2) { authManager.acquireAccessToken(fakeAccount) }
        }

    @Test
    fun `obtainAccessToken does not refresh when token is still fresh`() =
        runTest {
            var fakeTime = 0L
            val testProvider =
                OneDriveProvider(
                    fakeAccount.id,
                    authManager,
                    graphClient,
                    clock = { fakeTime },
                    tokenExpiryThresholdMs = OneDriveProvider.TOKEN_EXPIRY_THRESHOLD_MS,
                )
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Success("token-abc")
            coEvery { graphClient.list("token-abc", null) } returns emptyList()

            testProvider.ensureAuthenticated()

            // Advance clock to just under the threshold.
            fakeTime = OneDriveProvider.TOKEN_EXPIRY_THRESHOLD_MS - 1

            testProvider.list(null)

            // acquireAccessToken called only once (during ensureAuthenticated).
            coVerify(exactly = 1) { authManager.acquireAccessToken(fakeAccount) }
        }

    // -------------------------------------------------------------------------
    // Exception hierarchy — ensure subtypes are correct
    // -------------------------------------------------------------------------

    @Test
    fun `AuthenticationRequired is a CloudProviderException`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns emptyList()
            coEvery { authManager.getAccountHint(fakeAccount.id) } returns null

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

    // -------------------------------------------------------------------------
    // 401 silent refresh + replay (sub-issue #138)
    // -------------------------------------------------------------------------

    @Test
    fun `graphCall transparently recovers when first attempt returns 401 but refresh succeeds`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returnsMany
                listOf(AuthResult.Success("token-old"), AuthResult.Success("token-new"))
            coEvery { graphClient.list("token-old", null) } throws GraphApiException(401, "unauthorized")
            coEvery { graphClient.list("token-new", null) } returns emptyList()

            val result = provider.list(null)

            assertTrue(result.isEmpty())
            coVerify(exactly = 2) { authManager.acquireAccessToken(fakeAccount) }
            coVerify(exactly = 1) { graphClient.list("token-old", null) }
            coVerify(exactly = 1) { graphClient.list("token-new", null) }
        }

    @Test
    fun `graphCall surfaces AuthenticationRequired when retry also returns 401`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returnsMany
                listOf(AuthResult.Success("token-1"), AuthResult.Success("token-2"))
            coEvery { graphClient.list(any(), null) } throws GraphApiException(401, "unauthorized")

            try {
                provider.list(null)
                fail("Expected CloudProviderException.AuthenticationRequired")
            } catch (e: CloudProviderException.AuthenticationRequired) {
                assertTrue(e.message!!.contains("after silent refresh"))
            }
            coVerify(exactly = 2) { authManager.acquireAccessToken(fakeAccount) }
        }

    @Test
    fun `graphCall propagates non-401 errors without retry`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns listOf(fakeAccount)
            coEvery { authManager.acquireAccessToken(fakeAccount) } returns AuthResult.Success("token-x")
            coEvery { graphClient.list("token-x", null) } throws GraphApiException(500, "server error")

            try {
                provider.list(null)
                fail("Expected GraphApiException to propagate")
            } catch (e: GraphApiException) {
                assertEquals(500, e.statusCode)
            }
            coVerify(exactly = 1) { authManager.acquireAccessToken(fakeAccount) }
        }
}
