package com.synckro.providers.onedrive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.synckro.domain.auth.Account
import com.synckro.domain.auth.AuthResult
import com.synckro.domain.auth.AuthUiHost
import com.synckro.domain.model.CloudProviderType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [OneDriveAuthManager] using Robolectric so that
 * [android.content.Context] is available without a device.
 *
 * [OneDriveAuthManager.forTest] is used to inject explicit [clientId] /
 * [redirectUri] values, avoiding any dependency on
 * [com.synckro.BuildConfig] values set at CI build time.
 *
 * Tests that exercise MSAL directly (interactive sign-in, token refresh) are
 * not covered here because they require Play Services / the device browser.
 * Those paths are exercised by instrumentation tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OneDriveAuthManagerTest {
    private lateinit var context: Context
    private val fakeHost: AuthUiHost = object : AuthUiHost {}
    private val fakeAccount =
        Account(
            id = "test-oid",
            provider = CloudProviderType.ONEDRIVE,
            displayName = "Test User",
            email = "test@example.com",
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // -------------------------------------------------------------------------
    // isConfigured()
    // -------------------------------------------------------------------------

    @Test
    fun `isConfigured returns false when both fields are blank`() =
        runTest {
            val mgr = OneDriveAuthManager.forTest(context, clientId = "", redirectUri = "")
            assertFalse(mgr.isConfigured())
        }

    @Test
    fun `isConfigured returns false when clientId is blank`() =
        runTest {
            val mgr =
                OneDriveAuthManager.forTest(
                    context,
                    clientId = "",
                    redirectUri = "msauth://com.synckro.debug/abc=",
                )
            assertFalse(mgr.isConfigured())
        }

    @Test
    fun `isConfigured returns false when redirectUri is blank`() =
        runTest {
            val mgr =
                OneDriveAuthManager.forTest(
                    context,
                    clientId = "some-client-id",
                    redirectUri = "",
                )
            assertFalse(mgr.isConfigured())
        }

    @Test
    fun `isConfigured returns false when redirectUri has wrong scheme`() =
        runTest {
            val mgr =
                OneDriveAuthManager.forTest(
                    context,
                    clientId = "some-client-id",
                    redirectUri = "https://com.example/abc",
                )
            assertFalse(mgr.isConfigured())
        }

    @Test
    fun `isConfigured returns true for valid config`() =
        runTest {
            val mgr =
                OneDriveAuthManager.forTest(
                    context,
                    clientId = "aaaabbbb-cccc-dddd-eeee-ffffffffffff",
                    redirectUri = "msauth://com.synckro.debug/DtQuXuc=",
                )
            assertTrue(mgr.isConfigured())
        }

    @Test
    fun `runtime scopes omit offline access for consumer compatibility`() =
        runTest {
            val mgr =
                OneDriveAuthManager.forTest(
                    context,
                    clientId = "aaaabbbb-cccc-dddd-eeee-ffffffffffff",
                    redirectUri = "msauth://com.synckro.debug/DtQuXuc=",
                )

            @Suppress("UNCHECKED_CAST")
            val scopes =
                OneDriveAuthManager::class.java
                    .getDeclaredField("scopes")
                    .apply { isAccessible = true }
                    .get(mgr) as List<String>

            assertEquals(listOf("Files.ReadWrite", "User.Read"), scopes)
        }

    // -------------------------------------------------------------------------
    // signIn() — not-configured short-circuit (no MSAL call)
    // -------------------------------------------------------------------------

    @Test
    fun `signIn returns NotConfigured when both fields are blank`() =
        runTest {
            val mgr = OneDriveAuthManager.forTest(context, clientId = "", redirectUri = "")
            val result = mgr.signIn(fakeHost)
            assertTrue("Expected NotConfigured, got $result", result is AuthResult.NotConfigured)
        }

    @Test
    fun `signIn returns NotConfigured when only clientId is blank`() =
        runTest {
            val mgr =
                OneDriveAuthManager.forTest(
                    context,
                    clientId = "",
                    redirectUri = "msauth://com.synckro.debug/abc=",
                )
            val result = mgr.signIn(fakeHost)
            assertTrue("Expected NotConfigured, got $result", result is AuthResult.NotConfigured)
        }

    @Test
    fun `signIn returns NotConfigured when only redirectUri is blank`() =
        runTest {
            val mgr =
                OneDriveAuthManager.forTest(
                    context,
                    clientId = "some-client-id",
                    redirectUri = "",
                )
            val result = mgr.signIn(fakeHost)
            assertTrue("Expected NotConfigured, got $result", result is AuthResult.NotConfigured)
        }

    @Test
    fun `signIn NotConfigured message is non-blank`() =
        runTest {
            val mgr = OneDriveAuthManager.forTest(context, clientId = "", redirectUri = "")
            val result = mgr.signIn(fakeHost) as AuthResult.NotConfigured
            assertTrue("NotConfigured message should be non-blank", result.message.isNotBlank())
        }

    // -------------------------------------------------------------------------
    // acquireAccessToken() — not-configured short-circuit (no MSAL call)
    // -------------------------------------------------------------------------

    @Test
    fun `acquireAccessToken returns NotConfigured when blank config`() =
        runTest {
            val mgr = OneDriveAuthManager.forTest(context, clientId = "", redirectUri = "")
            val result = mgr.acquireAccessToken(fakeAccount)
            assertTrue("Expected NotConfigured, got $result", result is AuthResult.NotConfigured)
        }

    // -------------------------------------------------------------------------
    // currentAccounts() — returns empty list without touching MSAL when unconfigured
    // -------------------------------------------------------------------------

    @Test
    fun `currentAccounts returns empty list when not configured`() =
        runTest {
            val mgr = OneDriveAuthManager.forTest(context, clientId = "", redirectUri = "")
            val accounts = mgr.currentAccounts()
            assertTrue("Expected empty, got $accounts", accounts.isEmpty())
        }
}
