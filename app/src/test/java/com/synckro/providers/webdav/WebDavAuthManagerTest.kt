package com.synckro.providers.webdav

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.synckro.domain.auth.Account
import com.synckro.domain.auth.AuthResult
import com.synckro.domain.auth.AuthUiHost
import com.synckro.domain.model.CloudProviderType
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [WebDavAuthManager] using Robolectric so that
 * [android.content.Context] is available without a device.
 *
 * A plain [SharedPreferences] is injected via [WebDavAuthManager.forTest] to
 * avoid the Android Keystore requirement of
 * [androidx.security.crypto.EncryptedSharedPreferences], which is unavailable
 * in the Robolectric JVM environment — the same approach as
 * [com.synckro.providers.gdrive.GoogleDriveAuthManagerTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavAuthManagerTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var server: MockWebServer
    private lateinit var authManager: WebDavAuthManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("webdav_test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        server = MockWebServer()
        server.start()
        val client = WebDavClient(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
        authManager = WebDavAuthManager.forTest(context, client, prefs)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `linkAccount stores the account after a successful probe`() =
        runTest {
            server.enqueue(collectionResponse())

            val result = authManager.linkAccount(input(server.url("/dav/alice").toString()))

            assertTrue(result is AuthResult.Success)
            val account = (result as AuthResult.Success).value
            assertEquals(CloudProviderType.WEBDAV, account.provider)
            assertEquals(listOf(account), authManager.currentAccounts())
            val probe = server.takeRequest()
            assertEquals("PROPFIND", probe.method)
            assertEquals(Credentials.basic("alice", "app-password"), probe.getHeader("Authorization"))
        }

    @Test
    fun `acquireAccessToken returns the basic auth header for a linked account`() =
        runTest {
            server.enqueue(collectionResponse())
            val account = (authManager.linkAccount(input(server.url("/dav/alice").toString())) as AuthResult.Success).value

            val token = authManager.acquireAccessToken(account)

            assertEquals(Credentials.basic("alice", "app-password"), (token as AuthResult.Success).value)
        }

    @Test
    fun `acquireAccessToken needs re-linking for an unknown account`() =
        runTest {
            val result = authManager.acquireAccessToken(unknownAccount())

            assertEquals(AuthResult.NeedsInteractiveSignIn, result)
        }

    @Test
    fun `linkAccount maps rejected credentials to NeedsInteractiveSignIn`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

            val result = authManager.linkAccount(input(server.url("/dav/alice").toString()))

            assertEquals(AuthResult.NeedsInteractiveSignIn, result)
            assertTrue(authManager.currentAccounts().isEmpty())
        }

    @Test
    fun `linkAccount rejects plain http for non-loopback hosts`() =
        runTest {
            val result = authManager.linkAccount(input("http://cloud.example.com/dav/alice"))

            assertTrue(result is AuthResult.Error)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `linkAccount rejects blank credentials`() =
        runTest {
            assertTrue(authManager.linkAccount(input(server.url("/dav").toString(), username = " ")) is AuthResult.Error)
            assertTrue(authManager.linkAccount(input(server.url("/dav").toString(), password = "")) is AuthResult.Error)
        }

    @Test
    fun `sessionFor returns credentials for a linked account only`() =
        runTest {
            server.enqueue(collectionResponse())
            val account = (authManager.linkAccount(input(server.url("/dav/alice").toString())) as AuthResult.Success).value

            val session = authManager.sessionFor(account.id)

            assertEquals("/dav/alice", session?.rootPath)
            assertEquals(Credentials.basic("alice", "app-password"), session?.authorizationHeader)
            assertNull(authManager.sessionFor("nope"))
        }

    @Test
    fun `signOut forgets the stored credentials`() =
        runTest {
            server.enqueue(collectionResponse())
            val account = (authManager.linkAccount(input(server.url("/dav/alice").toString())) as AuthResult.Success).value

            authManager.signOut(account)

            assertTrue(authManager.currentAccounts().isEmpty())
            assertNull(authManager.sessionFor(account.id))
        }

    @Test
    fun `signIn without a credential-prompt host reports a typed error`() =
        runTest {
            val result = authManager.signIn(object : AuthUiHost {})

            assertTrue(result is AuthResult.Error)
        }

    @Test
    fun `signIn collects credentials from a prompt-capable host`() =
        runTest {
            server.enqueue(collectionResponse())
            val host =
                object : AuthUiHost, WebDavCredentialPrompt {
                    override suspend fun promptForWebDavCredentials() = input(server.url("/dav/alice").toString())
                }

            val result = authManager.signIn(host)

            assertTrue(result is AuthResult.Success)
        }

    @Test
    fun `signIn is cancelled when the user dismisses the prompt`() =
        runTest {
            val host =
                object : AuthUiHost, WebDavCredentialPrompt {
                    override suspend fun promptForWebDavCredentials(): WebDavCredentialInput? = null
                }

            assertEquals(AuthResult.Cancelled, authManager.signIn(host))
        }

    @Test
    fun `resolveBaseUrl derives the Nextcloud files endpoint from a bare host`() {
        val resolved = resolveBaseUrl("cloud.example.com", "alice")

        assertEquals("https://cloud.example.com/remote.php/dav/files/alice", resolved.toString())
        assertNull(resolveBaseUrl("   ", "alice"))
    }

    private fun input(
        serverUrl: String,
        username: String = "alice",
        password: String = "app-password",
    ) = WebDavCredentialInput(serverUrl = serverUrl, username = username, password = password)

    private fun unknownAccount() =
        Account(
            id = "missing@cloud.example.com/dav",
            provider = CloudProviderType.WEBDAV,
            displayName = "missing",
            email = null,
        )

    private fun collectionResponse(): MockResponse =
        MockResponse()
            .setResponseCode(207)
            .setHeader("Content-Type", "application/xml; charset=utf-8")
            .setBody(
                """
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:">
                  <d:response>
                    <d:href>/dav/alice/</d:href>
                    <d:propstat>
                      <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent(),
            )
}
