package com.synckro.providers.onedrive

import com.synckro.domain.sync.RemoteChangeType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for [OneDriveRemoteEnumerator] using [MockWebServer] as a
 * recorded fixture.
 *
 * Verifies the full HTTP round-trip for:
 * - Initial baseline (`deltaToken == null` → `$deltaToken=latest`).
 * - Incremental delta with mixed modify / delete entries.
 * - Multi-page response following `@odata.nextLink` before `@odata.deltaLink`.
 */
class OneDriveRemoteEnumeratorTest {
    private lateinit var server: MockWebServer
    private lateinit var graphClient: OneDriveGraphClient
    private lateinit var enumerator: OneDriveRemoteEnumerator

    private val token = "test-bearer-token"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val okHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .build()
        graphClient = OneDriveGraphClient(okHttpClient)
        graphClient.graphBaseUrl = server.url("/me/drive").toString().trimEnd('/')
        // ProviderFactory is unused on the test seam path (`enumerateWithToken`).
        enumerator = OneDriveRemoteEnumerator(providerFactory = mockk(relaxed = true), graphClient = graphClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `enumerate null establishes baseline with empty changes and deltaLink`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "value": [],
                          "@odata.deltaLink": "https://graph.example.com/delta-link-1"
                        }
                        """.trimIndent(),
                    ),
            )

            val snapshot = enumerator.enumerateWithToken(token, deltaToken = null)

            assertTrue(snapshot.changes.isEmpty())
            assertEquals("https://graph.example.com/delta-link-1", snapshot.newDeltaToken)

            val request = server.takeRequest()
            assertTrue(
                "Expected \$deltaToken=latest in URL: ${request.path}",
                request.path!!.contains("%24deltaToken=latest") ||
                    request.path!!.contains("\$deltaToken=latest"),
            )
        }

    @Test
    fun `enumerate with deltaLink maps modified and deleted items to RemoteChange`() =
        runTest {
            val deltaLinkUrl = server.url("/me/drive/root/delta?skiptoken=abc").toString()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "value": [
                            {
                              "id": "file-1",
                              "name": "notes.txt",
                              "parentReference": {"id": "root-id"},
                              "file": {"mimeType": "text/plain"},
                              "size": 1024,
                              "lastModifiedDateTime": "2024-03-15T10:00:00Z",
                              "eTag": "\"abc123\""
                            },
                            {
                              "id": "file-deleted",
                              "name": "old.txt",
                              "deleted": {}
                            }
                          ],
                          "@odata.deltaLink": "https://graph.example.com/delta-link-2"
                        }
                        """.trimIndent(),
                    ),
            )

            // Pass rootFolderId = "root-id" so that file-1 (with parentReference.id = "root-id")
            // resolves to the flat path "notes.txt".
            val snapshot = enumerator.enumerateWithToken(token, deltaToken = deltaLinkUrl, rootFolderId = "root-id")

            assertEquals(2, snapshot.changes.size)
            val modify = snapshot.changes[0]
            assertEquals(RemoteChangeType.MODIFY, modify.type)
            assertEquals("file-1", modify.remoteId)
            assertEquals("notes.txt", modify.relativePath)
            assertEquals(1024L, modify.sizeBytes)
            assertEquals("abc123", modify.etag) // surrounding quotes stripped
            assertEquals(
                java.time.Instant.parse("2024-03-15T10:00:00Z").toEpochMilli(),
                modify.mtimeMs,
            )

            val deleted = snapshot.changes[1]
            assertEquals(RemoteChangeType.DELETE, deleted.type)
            assertEquals("file-deleted", deleted.remoteId)
            assertEquals("old.txt", deleted.relativePath)

            assertEquals("https://graph.example.com/delta-link-2", snapshot.newDeltaToken)
        }

    @Test
    fun `enumerate resolves nested path from parentReference when rootFolderId is provided`() =
        runTest {
            val deltaLinkUrl = server.url("/me/drive/root/delta?skiptoken=nested").toString()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "value": [
                            {
                              "id": "folder-docs",
                              "name": "docs",
                              "parentReference": {"id": "sync-root-id"},
                              "folder": {}
                            },
                            {
                              "id": "file-report",
                              "name": "report.txt",
                              "parentReference": {"id": "folder-docs"},
                              "file": {"mimeType": "text/plain"},
                              "size": 512,
                              "lastModifiedDateTime": "2024-05-01T09:00:00Z",
                              "eTag": "\"etag-report\""
                            }
                          ],
                          "@odata.deltaLink": "https://graph.example.com/delta-link-nested"
                        }
                        """.trimIndent(),
                    ),
            )

            val snapshot = enumerator.enumerateWithToken(token, deltaToken = deltaLinkUrl, rootFolderId = "sync-root-id")

            assertEquals(2, snapshot.changes.size)
            val folderChange = snapshot.changes[0]
            assertEquals("folder-docs", folderChange.remoteId)
            // "docs" folder is a direct child of sync root → path = "docs"
            assertEquals("docs", folderChange.relativePath)

            val fileChange = snapshot.changes[1]
            assertEquals("file-report", fileChange.remoteId)
            // "report.txt" is under "docs" → full path = "docs/report.txt"
            assertEquals("docs/report.txt", fileChange.relativePath)
        }

    @Test
    fun `enumerate follows nextLink across multiple pages before returning deltaLink`() =
        runTest {
            // Page 1: nextLink → page 2.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "value": [
                            {"id": "item-1", "name": "a.txt", "file": {}, "size": 1}
                          ],
                          "@odata.nextLink": "${server.url("/me/drive/root/delta?skip=1")}"
                        }
                        """.trimIndent(),
                    ),
            )
            // Page 2: deltaLink, no more pages.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "value": [
                            {"id": "item-2", "name": "b.txt", "file": {}, "size": 2}
                          ],
                          "@odata.deltaLink": "https://graph.example.com/delta-link-final"
                        }
                        """.trimIndent(),
                    ),
            )

            val snapshot = enumerator.enumerateWithToken(token, deltaToken = null)

            assertEquals(2, snapshot.changes.size)
            assertEquals("item-1", snapshot.changes[0].remoteId)
            assertEquals("item-2", snapshot.changes[1].remoteId)
            snapshot.changes.forEach { assertEquals(RemoteChangeType.MODIFY, it.type) }
            assertEquals("https://graph.example.com/delta-link-final", snapshot.newDeltaToken)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `enumerate falls back to baseline when delta link returns 410 Gone`() =
        runTest {
            val expiredDeltaLink = server.url("/me/drive/root/delta?token=expired").toString()

            // First request (with expired delta link) returns 410 Gone.
            server.enqueue(
                MockResponse()
                    .setResponseCode(410)
                    .setBody("""{"error": {"code": "resyncRequired", "message": "Resync required."}}"""),
            )
            // Baseline fallback: request with $deltaToken=latest returns a fresh deltaLink.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "value": [],
                          "@odata.deltaLink": "https://graph.example.com/delta-link-fresh"
                        }
                        """.trimIndent(),
                    ),
            )

            val snapshot = enumerator.enumerateWithToken(token, deltaToken = expiredDeltaLink)

            assertTrue("Fallback snapshot should have no changes", snapshot.changes.isEmpty())
            assertEquals(
                "Fallback should return fresh delta link",
                "https://graph.example.com/delta-link-fresh",
                snapshot.newDeltaToken,
            )
            assertEquals("Two requests should be made (expired + baseline)", 2, server.requestCount)
        }
}
