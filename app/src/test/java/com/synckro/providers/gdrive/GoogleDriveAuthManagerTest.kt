package com.synckro.providers.gdrive

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.synckro.domain.auth.Account
import com.synckro.domain.auth.AuthResult
import com.synckro.domain.auth.AuthUiHost
import com.synckro.domain.model.CloudProviderType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [GoogleDriveAuthManager] using Robolectric so that
 * [android.content.Context] is available without a device.
 *
 * A plain [SharedPreferences] is injected via [GoogleDriveAuthManager.forTest] to
 * avoid the Android Keystore requirement of [EncryptedSharedPreferences] which
 * is not available in the Robolectric JVM environment.
 *
 * [GoogleDriveAuthManager.forTest] defaults [webClientId] to `""` so
 * [GoogleDriveAuthManager.isConfigured] always returns `false` in these tests
 * regardless of the [com.synckro.BuildConfig.GOOGLE_WEB_CLIENT_ID]
 * value set in the CI build environment.
 *
 * Tests that exercise the credential-manager / authorization-client paths
 * require Play Services and are covered by instrumentation tests on a real device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleDriveAuthManagerTest {
    private lateinit var context: Context
    private lateinit var testPrefs: SharedPreferences
    private lateinit var authManager: GoogleDriveAuthManager
    private val fakeHost: AuthUiHost = object : AuthUiHost {}

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testPrefs = context.getSharedPreferences("gdrive_test", Context.MODE_PRIVATE)
        testPrefs.edit().clear().apply()
        authManager = GoogleDriveAuthManager.forTest(context, testPrefs)
    }

    @Test
    fun `isConfigured returns false when webClientId is blank`() =
        runTest {
            // forTest() passes webClientId = "" by default, so isConfigured() is false
            // regardless of what BuildConfig.GOOGLE_WEB_CLIENT_ID contains at build time.
            assertTrue(!authManager.isConfigured())
        }

    @Test
    fun `signIn returns NotConfigured when not configured`() =
        runTest {
            val result = authManager.signIn(fakeHost)
            assertTrue(result is AuthResult.NotConfigured)
        }

    @Test
    fun `acquireAccessToken returns NotConfigured when not configured`() =
        runTest {
            val account =
                Account(
                    id = "test@example.com",
                    provider = CloudProviderType.GOOGLE_DRIVE,
                    displayName = "Test User",
                    email = "test@example.com",
                )
            val result = authManager.acquireAccessToken(account)
            assertTrue(result is AuthResult.NotConfigured)
        }

    @Test
    fun `currentAccounts returns empty list when no account is stored`() =
        runTest {
            assertTrue(authManager.currentAccounts().isEmpty())
        }

    @Test
    fun `currentAccounts returns stored account after prefs are populated`() =
        runTest {
            testPrefs
                .edit()
                .putString("account_id", "user@gmail.com")
                .putString("display_name", "Test User")
                .putString("email", "user@gmail.com")
                .apply()

            val accounts = authManager.currentAccounts()
            assertEquals(1, accounts.size)
            val stored = accounts.single()
            assertEquals("user@gmail.com", stored.id)
            assertEquals("Test User", stored.displayName)
            assertEquals("user@gmail.com", stored.email)
            assertEquals(CloudProviderType.GOOGLE_DRIVE, stored.provider)
        }

    @Test
    fun `signOut clears stored account and returns Success`() =
        runTest {
            testPrefs
                .edit()
                .putString("account_id", "user@gmail.com")
                .putString("display_name", "Test User")
                .putString("email", "user@gmail.com")
                .apply()

            val beforeSignOut = authManager.currentAccounts()
            assertEquals(1, beforeSignOut.size)

            val account =
                Account(
                    id = "user@gmail.com",
                    provider = CloudProviderType.GOOGLE_DRIVE,
                    displayName = "Test User",
                    email = "user@gmail.com",
                )
            val result = authManager.signOut(account)

            assertTrue(result is AuthResult.Success)
            assertTrue(authManager.currentAccounts().isEmpty())
        }

    @Test
    fun `currentAccounts falls back to id when display_name is missing`() =
        runTest {
            testPrefs
                .edit()
                .putString("account_id", "user@gmail.com")
                // display_name deliberately omitted
                .apply()

            val accounts = authManager.currentAccounts()
            assertEquals(1, accounts.size)
            assertEquals("user@gmail.com", accounts.single().displayName)
        }
}
