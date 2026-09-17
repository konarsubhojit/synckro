package com.synckro.providers.webdav

import com.synckro.domain.provider.CloudProviderException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Tests for [WebDavProvider]: the `CloudProvider` surface, the typed-exception
 * boundary and the full-re-enumeration `changesSince()` fallback.
 *
 * Mirrors the structure of
 * [com.synckro.providers.gdrive.GoogleDriveProviderAuthTest], using
 * [MockWebServer] for the HTTP layer and a stubbed [WebDavAuthManager] for
 * credentials.
 */
class WebDavProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient
    private lateinit var authManager: WebDavAuthManager
    private lateinit var provider: WebDavProvider

    private val accountId = "alice@cloud/remote.php/dav/files/alice"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WebDavClient(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
        val session =
            WebDavSession(
                baseUrl = server.url("/remote.php/dav/files/alice"),
                authorizationHeader = Credentials.basic("alice", "app-password"),
            )
        authManager = mockk()
        every { authManager.sessionFor(accountId) } returns session
        provider =
            WebDavProvider(
                accountId = accountId,
                authManager = authManager,
                client = client,
                clock = { 1_700_000_000_000L },
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `ensureAuthenticated fails when no credentials are stored`() =
        runTest {
            every { authManager.sessionFor(accountId) } returns null

            val error = runCatching { provider.ensureAuthenticated() }.exceptionOrNull()

            assertTrue(error is CloudProviderException.AuthenticationRequired)
        }

    @Test
    fun `list maps collections and files onto RemoteFile`() =
        runTest {
            server.enqueue(multiStatus(ROOT_LISTING))

            val files = provider.list(null)

            assertEquals(2, files.size)
            val folder = files.first { it.isFolder }
            assertEquals("/remote.php/dav/files/alice/Photos", folder.id)
            assertEquals("/remote.php/dav/files/alice", folder.parentId)
            val file = files.first { !it.isFolder }
            assertEquals("notes.txt", file.name)
            assertEquals(12L, file.size)
            assertEquals("abc123", file.eTag)
            // getetag is an opaque version tag, never a content hash.
            assertNull(file.contentHash)
        }

    @Test
    fun `401 is mapped to AuthenticationRequired`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

            val error = runCatching { provider.list(null) }.exceptionOrNull()

            assertTrue(error is CloudProviderException.AuthenticationRequired)
        }

    @Test
    fun `429 is mapped to RateLimited`() =
        runTest {
            repeat(5) { server.enqueue(MockResponse().setResponseCode(429)) }

            val error = runCatching { provider.list(null) }.exceptionOrNull()

            assertTrue(error is CloudProviderException.RateLimited)
        }

    @Test
    fun `uploadNew puts the content under the parent path`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(multiStatus(SINGLE_FILE))

            val uploaded =
                provider.uploadNew(
                    parentId = "/remote.php/dav/files/alice",
                    name = "notes.txt",
                    content = ByteArrayInputStream("hello webdav".toByteArray()),
                    size = 12,
                    mimeType = "text/plain",
                )

            assertEquals("/remote.php/dav/files/alice/notes.txt", uploaded.id)
            assertEquals("/remote.php/dav/files/alice/notes.txt", server.takeRequest().path)
        }

    @Test
    fun `createFolder issues MKCOL under the parent path`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(multiStatus(SINGLE_FOLDER))

            val folder = provider.createFolder("/remote.php/dav/files/alice", "Photos")

            assertTrue(folder.isFolder)
            val request = server.takeRequest()
            assertEquals("MKCOL", request.method)
            assertEquals("/remote.php/dav/files/alice/Photos", request.path)
        }

    @Test
    fun `changesSince null establishes a baseline without listing the server`() =
        runTest {
            val page = provider.changesSince(null)

            assertTrue(page.changes.isEmpty())
            assertTrue(page.nextToken.startsWith(WebDavProvider.TOKEN_PREFIX))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `changesSince with a token re-enumerates the whole tree`() =
        runTest {
            server.enqueue(multiStatus(ROOT_LISTING))
            server.enqueue(multiStatus(PHOTOS_LISTING))

            val page = provider.changesSince("${WebDavProvider.TOKEN_PREFIX}1")

            // Only files are reported; collections are skipped.
            assertEquals(
                listOf("/remote.php/dav/files/alice/Photos/holiday.jpg", "/remote.php/dav/files/alice/notes.txt"),
                page.changes.mapNotNull { it.file?.id }.sorted(),
            )
            assertTrue(page.changes.none { it.isDeletion })
            assertEquals(false, page.hasMore)
        }

    @Test
    fun `getStorageQuota converts available bytes into a total`() =
        runTest {
            server.enqueue(multiStatus(QUOTA_RESPONSE))

            val quota = provider.getStorageQuota()

            assertEquals(1_024L, quota?.usedBytes)
            assertEquals(10_240L, quota?.totalBytes)
        }

    @Test
    fun `getStorageQuota returns null when the request fails`() =
        runTest {
            repeat(5) { server.enqueue(MockResponse().setResponseCode(500)) }

            assertNull(provider.getStorageQuota())
        }

    @Test
    fun `childPath joins parent and leaf without duplicate separators`() {
        assertEquals("/dav/alice/notes.txt", childPath("/dav/alice/", "notes.txt"))
        assertEquals("/dav/alice/notes.txt", childPath("/dav/alice", "/notes.txt"))
        assertEquals("/notes.txt", childPath("/", "notes.txt"))
    }

    @Test
    fun `factory reuses one provider per account id`() {
        val factory = WebDavProviderFactory(authManager = mockk(), client = client)

        val a1 = factory.providerFor("acct-a")
        val a2 = factory.providerFor("acct-a")
        val b1 = factory.providerFor("acct-b")

        assertSame(a1, a2)
        assertNotSame(a1, b1)
    }

    private fun multiStatus(body: String): MockResponse =
        MockResponse()
            .setResponseCode(207)
            .setHeader("Content-Type", "application/xml; charset=utf-8")
            .setBody(body)

    private companion object {
        val ROOT_LISTING = WebDavFixtures.ROOT_LISTING
        val PHOTOS_LISTING = WebDavFixtures.PHOTOS_LISTING
        val SINGLE_FILE = WebDavFixtures.SINGLE_FILE
        val SINGLE_FOLDER = WebDavFixtures.SINGLE_FOLDER
        val QUOTA_RESPONSE = WebDavFixtures.QUOTA_RESPONSE
    }
}
