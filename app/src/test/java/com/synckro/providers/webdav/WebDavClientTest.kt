package com.synckro.providers.webdav

import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Integration tests for [WebDavClient] using [MockWebServer] with recorded
 * Nextcloud-shaped `PROPFIND` fixtures, mirroring
 * [com.synckro.providers.gdrive.GoogleDriveRestClientTest].
 */
class WebDavClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient
    private lateinit var session: WebDavSession

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WebDavClient(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
        session =
            WebDavSession(
                baseUrl = server.url("/remote.php/dav/files/alice"),
                authorizationHeader = Credentials.basic("alice", "app-password"),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // -------------------------------------------------------------------------
    // PROPFIND
    // -------------------------------------------------------------------------

    @Test
    fun `listChildren parses multistatus and excludes the collection itself`() =
        runTest {
            server.enqueue(multiStatus(ROOT_LISTING))

            val children = client.listChildren(session, "/remote.php/dav/files/alice")

            assertEquals(2, children.size)
            val folder = children.first { it.isCollection }
            assertEquals("/remote.php/dav/files/alice/Photos", folder.path)
            assertEquals("Photos", folder.name)
            val file = children.first { !it.isCollection }
            assertEquals("/remote.php/dav/files/alice/notes.txt", file.path)
            assertEquals(12L, file.contentLength)
            assertEquals("text/plain", file.contentType)
            assertEquals("abc123", file.eTag)
            assertEquals(1_445_412_480_000L, file.lastModifiedMs)

            val request = server.takeRequest()
            assertEquals("PROPFIND", request.method)
            assertEquals("1", request.getHeader("Depth"))
            assertEquals(Credentials.basic("alice", "app-password"), request.getHeader("Authorization"))
            assertTrue(request.body.readUtf8().contains("getlastmodified"))
        }

    @Test
    fun `getMetadata issues a depth zero PROPFIND`() =
        runTest {
            server.enqueue(multiStatus(SINGLE_FILE))

            val resource = client.getMetadata(session, "/remote.php/dav/files/alice/notes.txt")

            assertEquals("/remote.php/dav/files/alice/notes.txt", resource.path)
            assertFalse(resource.isCollection)
            val request = server.takeRequest()
            assertEquals("PROPFIND", request.method)
            assertEquals("0", request.getHeader("Depth"))
            assertEquals("/remote.php/dav/files/alice/notes.txt", request.path)
        }

    @Test
    fun `getMetadata throws typed exception on error status`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

            val error =
                runCatching { client.getMetadata(session, "/remote.php/dav/files/alice/notes.txt") }
                    .exceptionOrNull()

            assertTrue(error is WebDavApiException)
            assertEquals(401, (error as WebDavApiException).statusCode)
        }

    @Test
    fun `listDescendants walks nested collections with repeated depth one requests`() =
        runTest {
            server.enqueue(multiStatus(ROOT_LISTING))
            server.enqueue(multiStatus(PHOTOS_LISTING))

            val descendants = client.listDescendants(session, "/remote.php/dav/files/alice")

            assertEquals(
                listOf("Photos", "Photos/holiday.jpg", "notes.txt"),
                descendants.map { it.second }.sorted(),
            )
            assertEquals(2, server.requestCount)
            server.takeRequest()
            assertEquals("/remote.php/dav/files/alice/Photos", server.takeRequest().path)
        }

    @Test
    fun `percent encodes path segments containing spaces`() =
        runTest {
            server.enqueue(multiStatus(SINGLE_FILE))

            client.getMetadata(session, "/remote.php/dav/files/alice/my notes.txt")

            assertEquals("/remote.php/dav/files/alice/my%20notes.txt", server.takeRequest().path)
        }

    // -------------------------------------------------------------------------
    // Transfers
    // -------------------------------------------------------------------------

    @Test
    fun `download streams the response body`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("hello webdav"))

            val content = client.download(session, "/remote.php/dav/files/alice/notes.txt").use { it.readBytes() }

            assertEquals("hello webdav", String(content))
            assertEquals("GET", server.takeRequest().method)
        }

    @Test
    fun `put uploads the stream and reads metadata back`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(multiStatus(SINGLE_FILE))

            val resource =
                client.put(
                    session,
                    "/remote.php/dav/files/alice/notes.txt",
                    ByteArrayInputStream("hello webdav".toByteArray()),
                    size = 12,
                    mimeType = "text/plain",
                )

            assertEquals("/remote.php/dav/files/alice/notes.txt", resource.path)
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertEquals("hello webdav", put.body.readUtf8())
            assertEquals("text/plain", put.getHeader("Content-Type"))
            assertEquals("PROPFIND", server.takeRequest().method)
        }

    @Test
    fun `put closes the content stream on failure`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(507).setBody("insufficient storage"))
            val content = ByteArrayInputStream("hello".toByteArray())

            val error =
                runCatching {
                    client.put(session, "/remote.php/dav/files/alice/notes.txt", content, size = 5, mimeType = null)
                }.exceptionOrNull()

            assertEquals(507, (error as WebDavApiException).statusCode)
            assertEquals(-1, content.read())
        }

    // -------------------------------------------------------------------------
    // MKCOL / DELETE / quota
    // -------------------------------------------------------------------------

    @Test
    fun `mkcol creates the collection and returns its metadata`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(multiStatus(SINGLE_FOLDER))

            val folder = client.mkcol(session, "/remote.php/dav/files/alice/Photos")

            assertTrue(folder.isCollection)
            assertEquals("MKCOL", server.takeRequest().method)
        }

    @Test
    fun `delete succeeds on 204 and tolerates 404`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

            client.delete(session, "/remote.php/dav/files/alice/notes.txt")
            client.delete(session, "/remote.php/dav/files/alice/gone.txt")

            assertEquals("DELETE", server.takeRequest().method)
            assertEquals("DELETE", server.takeRequest().method)
        }

    @Test
    fun `quota parses RFC 4331 properties`() =
        runTest {
            server.enqueue(multiStatus(QUOTA_RESPONSE))

            val quota = client.quota(session, "/remote.php/dav/files/alice")

            assertEquals(WebDavQuota(usedBytes = 1_024L, availableBytes = 9_216L), quota)
            val request = server.takeRequest()
            assertTrue(request.body.readUtf8().contains("quota-available-bytes"))
        }

    @Test
    fun `quota returns null when the server reports unlimited storage`() =
        runTest {
            server.enqueue(multiStatus(UNLIMITED_QUOTA_RESPONSE))

            assertNull(client.quota(session, "/remote.php/dav/files/alice"))
        }

    @Test
    fun `retries on 429 honouring Retry-After`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
            server.enqueue(multiStatus(SINGLE_FILE))

            val resource = client.getMetadata(session, "/remote.php/dav/files/alice/notes.txt")

            assertEquals("/remote.php/dav/files/alice/notes.txt", resource.path)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `retries on 5xx before succeeding`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(multiStatus(SINGLE_FILE))

            client.getMetadata(session, "/remote.php/dav/files/alice/notes.txt")

            assertEquals(3, server.requestCount)
        }

    @Test
    fun `resolvePathUrl builds an absolute url under the server origin`() {
        val base = "https://cloud.example.com/remote.php/dav/files/alice".toHttpUrl()

        val url = resolvePathUrl(base, "/remote.php/dav/files/alice/Photos/holiday.jpg")

        assertEquals("https://cloud.example.com/remote.php/dav/files/alice/Photos/holiday.jpg", url.toString())
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
        val UNLIMITED_QUOTA_RESPONSE = WebDavFixtures.UNLIMITED_QUOTA_RESPONSE
    }
}
