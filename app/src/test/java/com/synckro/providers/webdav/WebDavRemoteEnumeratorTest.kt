package com.synckro.providers.webdav

import com.synckro.domain.provider.CloudProviderException
import com.synckro.domain.sync.RemoteChangeType
import io.mockk.mockk
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

/**
 * Tests for [WebDavRemoteEnumerator], mirroring
 * [com.synckro.providers.gdrive.GoogleDriveRemoteEnumeratorTest] but exercising
 * the full-re-enumeration fallback used in place of a delta API.
 */
class WebDavRemoteEnumeratorTest {
    private lateinit var server: MockWebServer
    private lateinit var enumerator: WebDavRemoteEnumerator
    private lateinit var session: WebDavSession

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = WebDavClient(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
        enumerator = WebDavRemoteEnumerator(providerFactory = mockk(), client = client)
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

    @Test
    fun `enumerate reports every descendant with its relative path`() =
        runTest {
            server.enqueue(multiStatus(WebDavFixtures.ROOT_LISTING))
            server.enqueue(multiStatus(WebDavFixtures.PHOTOS_LISTING))

            val snapshot = enumerator.enumerateWithSession(session, rootFolderId = "")

            assertEquals(
                listOf("Photos", "Photos/holiday.jpg", "notes.txt"),
                snapshot.changes.map { it.relativePath }.sorted(),
            )
            assertTrue(snapshot.changes.all { it.type == RemoteChangeType.MODIFY })
            val file = snapshot.changes.first { it.relativePath == "notes.txt" }
            assertEquals("/remote.php/dav/files/alice/notes.txt", file.remoteId)
            assertEquals(12L, file.sizeBytes)
            assertEquals(1_445_412_480_000L, file.mtimeMs)
            assertEquals("abc123", file.etag)
            // WebDAV ETags are version tags, not content hashes.
            assertNull(file.contentHash)
            assertTrue(snapshot.changes.first { it.relativePath == "Photos" }.isFolder)
            assertTrue(snapshot.newDeltaToken.startsWith(WebDavProvider.TOKEN_PREFIX))
        }

    @Test
    fun `enumerate honours an explicit root folder id`() =
        runTest {
            server.enqueue(multiStatus(WebDavFixtures.PHOTOS_LISTING))

            val snapshot =
                enumerator.enumerateWithSession(session, rootFolderId = "/remote.php/dav/files/alice/Photos")

            assertEquals(listOf("holiday.jpg"), snapshot.changes.map { it.relativePath })
            assertEquals("/remote.php/dav/files/alice/Photos", server.takeRequest().path)
        }

    @Test
    fun `401 during enumeration is mapped to AuthenticationRequired`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

            val error =
                runCatching { enumerator.enumerateWithSession(session, rootFolderId = "") }.exceptionOrNull()

            assertTrue(error is CloudProviderException.AuthenticationRequired)
        }

    @Test
    fun `account-unaware entrypoints are unsupported`() =
        runTest {
            assertTrue(
                runCatching { enumerator.enumerate(null) }.exceptionOrNull()
                    is CloudProviderException.AuthenticationRequired,
            )
            assertTrue(
                runCatching { enumerator.enumerateFull() }.exceptionOrNull()
                    is CloudProviderException.AuthenticationRequired,
            )
        }

    private fun multiStatus(body: String): MockResponse =
        MockResponse()
            .setResponseCode(207)
            .setHeader("Content-Type", "application/xml; charset=utf-8")
            .setBody(body)
}
