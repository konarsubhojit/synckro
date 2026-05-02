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
        provider = OneDriveProvider(authManager, graphClient)
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
            coEvery { authManager.getAccountHint() } returns null

            try {
                provider.ensureAuthenticated()
                fail("Expected CloudProviderException.AuthenticationRequired")
            } catch (e: CloudProviderException.AuthenticationRequired) {
                assertTrue(e.message!!.contains("No OneDrive account is signed in"))
            }
        }

    @Test
    fun `ensureAuthenticated includes account hint in message when available`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns emptyList()
            coEvery { authManager.getAccountHint() } returns "last@example.com"

            try {
                provider.ensureAuthenticated()
                fail("Expected CloudProviderException.AuthenticationRequired")
            } catch (e: CloudProviderException.AuthenticationRequired) {
                assertTrue(e.message!!.contains("last@example.com"))
            }
        }

    // -------------------------------------------------------------------------
    // NeedsInteractiveSignIn → AuthenticationRequired
    // -------------------------------------------------------------------------

    @Test
    fun `ensureAuthenticated throws AuthenticationRequired when silent auth returns NeedsInteractiveSignIn`() =
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
    // Exception hierarchy — ensure subtypes are correct
    // -------------------------------------------------------------------------

    @Test
    fun `AuthenticationRequired is a CloudProviderException`() =
        runTest {
            coEvery { authManager.currentAccounts() } returns emptyList()
            coEvery { authManager.getAccountHint() } returns null

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
