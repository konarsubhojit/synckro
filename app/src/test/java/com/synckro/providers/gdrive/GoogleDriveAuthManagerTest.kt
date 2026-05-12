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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

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
    private val accountA =
        Account(
            id = "alpha@gmail.com",
            provider = CloudProviderType.GOOGLE_DRIVE,
            displayName = "Alpha",
            email = "alpha@gmail.com",
        )
    private val accountB =
        Account(
            id = "beta@gmail.com",
            provider = CloudProviderType.GOOGLE_DRIVE,
            displayName = "Beta",
            email = "beta@gmail.com",
        )

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
            authManager.storeAccount(accountA)

            val accounts = authManager.currentAccounts()
            assertEquals(1, accounts.size)
            val stored = accounts.single()
            assertEquals(accountA.id, stored.id)
            assertEquals(accountA.displayName, stored.displayName)
            assertEquals(accountA.email, stored.email)
            assertEquals(CloudProviderType.GOOGLE_DRIVE, stored.provider)
        }

    @Test
    fun `currentAccounts returns both stored accounts in insertion order`() =
        runTest {
            authManager.storeAccount(accountA)
            authManager.storeAccount(accountB)

            val accounts = authManager.currentAccounts()

            assertEquals(listOf(accountA, accountB), accounts)
        }

    @Test
    fun `signOut removes only requested account and later token lookup needs interactive sign in`() =
        runTest {
            authManager.storeAccount(accountA)
            authManager.storeAccount(accountB)

            val result = authManager.signOut(accountA)

            assertTrue(result is AuthResult.Success)
            assertEquals(listOf(accountB), authManager.currentAccounts())

            val configuredManager =
                GoogleDriveAuthManager.forTest(context, testPrefs, webClientId = "configured")
            val tokenResult = configuredManager.acquireAccessToken(accountA)
            assertTrue(tokenResult is AuthResult.NeedsInteractiveSignIn)
        }

    @Test
    fun `currentAccounts falls back to id when display_name is missing`() =
        runTest {
            testPrefs
                .edit()
                .putString("accounts", """[{"account_id":"user@gmail.com","email":"user@gmail.com"}]""")
                .apply()

            val accounts = authManager.currentAccounts()
            assertEquals(1, accounts.size)
            assertEquals("user@gmail.com", accounts.single().displayName)
        }

    @Test
    fun `acquireAccessToken returns NeedsInteractiveSignIn for unknown stored id before touching SDK`() =
        runTest {
            authManager.storeAccount(accountB)

            val configuredManager =
                GoogleDriveAuthManager.forTest(context, testPrefs, webClientId = "configured")
            val result = configuredManager.acquireAccessToken(accountA)

            assertTrue(result is AuthResult.NeedsInteractiveSignIn)
        }

    @Test
    fun `currentAccounts migrates legacy single account prefs on first read`() =
        runTest {
            testPrefs
                .edit()
                .putString("account_id", accountA.id)
                .putString("display_name", accountA.displayName)
                .putString("email", accountA.email)
                .apply()

            val accounts = authManager.currentAccounts()

            assertEquals(listOf(accountA), accounts)
            assertTrue(testPrefs.contains("accounts"))
            assertFalse(testPrefs.contains("account_id"))
            assertFalse(testPrefs.contains("display_name"))
            assertFalse(testPrefs.contains("email"))
        }

    @Test
    fun `resolveGoogleAccountEmail prefers credential id when it is an email`() {
        val resolved =
            authManager.resolveGoogleAccountEmail(
                credentialId = "user@gmail.com",
                idToken = null,
            )

        assertEquals("user@gmail.com", resolved)
    }

    @Test
    fun `resolveGoogleAccountEmail falls back to id token email claim`() {
        val payload =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("""{"email":"claim@gmail.com"}""".toByteArray())
        val token = "header.$payload.signature"

        val resolved =
            authManager.resolveGoogleAccountEmail(
                credentialId = "opaque-subject-id",
                idToken = token,
            )

        assertEquals("claim@gmail.com", resolved)
    }

    @Test
    fun `resolveGoogleAccountEmail returns null when neither credential id nor token has email`() {
        val resolved =
            authManager.resolveGoogleAccountEmail(
                credentialId = "opaque-subject-id",
                idToken = null,
            )

        assertEquals(null, resolved)
    }

    @Test
    fun `extractEmailFromIdToken returns null for malformed token`() {
        assertEquals(null, authManager.extractEmailFromIdToken("not-a-jwt"))
    }

    @Test
    fun `extractEmailFromIdToken returns null for invalid payload encoding or json`() {
        val invalidBase64Token = "header.@@@.signature"
        val nonJsonPayload =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("not-json".toByteArray())
        val nonJsonToken = "header.$nonJsonPayload.signature"

        assertEquals(null, authManager.extractEmailFromIdToken(invalidBase64Token))
        assertEquals(null, authManager.extractEmailFromIdToken(nonJsonToken))
    }

    @Test
    fun `hasEmailShape validates basic email pattern`() {
        assertTrue(authManager.hasEmailShape("user@domain.com"))
        assertFalse(authManager.hasEmailShape("@domain.com"))
        assertFalse(authManager.hasEmailShape("user@"))
        assertFalse(authManager.hasEmailShape("user-domain.com"))
        assertFalse(authManager.hasEmailShape(null))
        assertFalse(authManager.hasEmailShape(""))
    }

    @Test
    fun `resolveSilentAuthEmail prefers stored email then requested email then ids`() {
        val storedWithEmail = accountA.copy(email = "stored@gmail.com", id = "stored-id")
        val requestedWithEmail = accountB.copy(email = "requested@gmail.com", id = "requested-id")
        assertEquals(
            "stored@gmail.com",
            authManager.resolveSilentAuthEmail(storedWithEmail, requestedWithEmail),
        )

        val storedWithoutEmail = storedWithEmail.copy(email = null)
        assertEquals(
            "requested@gmail.com",
            authManager.resolveSilentAuthEmail(storedWithoutEmail, requestedWithEmail),
        )

        val requestedWithEmailIdOnly = requestedWithEmail.copy(email = null, id = "requested-id@gmail.com")
        assertEquals(
            "requested-id@gmail.com",
            authManager.resolveSilentAuthEmail(storedWithoutEmail, requestedWithEmailIdOnly),
        )

        val storedWithEmailIdOnly = storedWithoutEmail.copy(id = "stored-id@gmail.com")
        val requestedNoEmailShape = requestedWithEmail.copy(email = null, id = "requested-id")
        assertEquals(
            "stored-id@gmail.com",
            authManager.resolveSilentAuthEmail(storedWithEmailIdOnly, requestedNoEmailShape),
        )

        assertEquals(
            null,
            authManager.resolveSilentAuthEmail(
                storedWithoutEmail.copy(id = "stored-id"),
                requestedNoEmailShape,
            ),
        )
    }
}
