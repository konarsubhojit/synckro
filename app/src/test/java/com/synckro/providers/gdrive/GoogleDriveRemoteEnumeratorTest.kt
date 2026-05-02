package com.synckro.providers.gdrive

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
 * Integration tests for [GoogleDriveRemoteEnumerator] using [MockWebServer] as
 * a recorded fixture.
 *
 * Verifies the full HTTP round-trip for:
 * - Initial baseline (`deltaToken == null` → `changes/startPageToken`).
 * - Incremental delta with mixed modify / removed / trashed entries.
 * - Multi-page response following `nextPageToken` before `newStartPageToken`.
 */
class GoogleDriveRemoteEnumeratorTest {
    private lateinit var server: MockWebServer
    private lateinit var restClient: GoogleDriveRestClient
    private lateinit var enumerator: GoogleDriveRemoteEnumerator

    private val token = "test-bearer-token"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val okHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .build()
        restClient = GoogleDriveRestClient(okHttpClient)
        restClient.driveBaseUrl = server.url("/drive/v3").toString().trimEnd('/')
        restClient.uploadBaseUrl = server.url("/upload/drive/v3").toString().trimEnd('/')
        // Provider is unused on the test seam path (`enumerateWithToken`).
        enumerator = GoogleDriveRemoteEnumerator(provider = mockk(relaxed = true), restClient = restClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `enumerate null fetches startPageToken and returns empty baseline`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"startPageToken": "token-1"}"""),
            )

            val snapshot = enumerator.enumerateWithToken(token, deltaToken = null)

            assertTrue(snapshot.changes.isEmpty())
            assertEquals("token-1", snapshot.newDeltaToken)

            val req = server.takeRequest()
            assertTrue(
                "Expected changes/startPageToken in path: ${req.path}",
                req.path!!.contains("changes/startPageToken"),
            )
        }

    @Test
    fun `enumerate with token maps modified, removed, and trashed entries`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "changes": [
                            {
                              "fileId": "file-1",
                              "removed": false,
                              "file": {
                                "id": "file-1",
                                "name": "notes.txt",
                                "parents": ["parent-id"],
                                "mimeType": "text/plain",
                                "size": "512",
                                "modifiedTime": "2024-01-01T00:00:00Z",
                                "md5Checksum": "md5-1"
                              }
                            },
                            {
                              "fileId": "file-deleted",
                              "removed": true
                            },
                            {
                              "fileId": "file-trashed",
                              "removed": false,
                              "file": {
                                "id": "file-trashed",
                                "name": "old.txt",
                                "mimeType": "text/plain",
                                "trashed": true
                              }
                            }
                          ],
                          "newStartPageToken": "token-2"
                        }
                        """.trimIndent(),
                    ),
            )

            val snapshot = enumerator.enumerateWithToken(token, deltaToken = "token-1")

            assertEquals(3, snapshot.changes.size)

            val modify = snapshot.changes[0]
            assertEquals(RemoteChangeType.MODIFY, modify.type)
            assertEquals("file-1", modify.remoteId)
            assertEquals("notes.txt", modify.relativePath)
            assertEquals(512L, modify.sizeBytes)
            assertEquals("md5-1", modify.etag)
            assertEquals(
                java.time.Instant.parse("2024-01-01T00:00:00Z").toEpochMilli(),
                modify.mtimeMs,
            )

            val removed = snapshot.changes[1]
            assertEquals(RemoteChangeType.DELETE, removed.type)
            assertEquals("file-deleted", removed.remoteId)

            val trashed = snapshot.changes[2]
            assertEquals(RemoteChangeType.DELETE, trashed.type)
            assertEquals("file-trashed", trashed.remoteId)
            assertEquals("old.txt", trashed.relativePath)

            assertEquals("token-2", snapshot.newDeltaToken)
        }

    @Test
    fun `enumerate follows nextPageToken before returning newStartPageToken`() =
        runTest {
            // Page 1: has nextPageToken, no newStartPageToken yet.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "changes": [
                            {
                              "fileId": "f1",
                              "removed": false,
                              "file": {"id": "f1", "name": "a.txt", "mimeType": "text/plain"}
                            }
                          ],
                          "nextPageToken": "page-token-2"
                        }
                        """.trimIndent(),
                    ),
            )
            // Page 2: final page.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "changes": [
                            {
                              "fileId": "f2",
                              "removed": false,
                              "file": {"id": "f2", "name": "b.txt", "mimeType": "text/plain"}
                            }
                          ],
                          "newStartPageToken": "token-final"
                        }
                        """.trimIndent(),
                    ),
            )

            val snapshot = enumerator.enumerateWithToken(token, deltaToken = "token-1")

            assertEquals(2, snapshot.changes.size)
            assertEquals("f1", snapshot.changes[0].remoteId)
            assertEquals("f2", snapshot.changes[1].remoteId)
            snapshot.changes.forEach { assertEquals(RemoteChangeType.MODIFY, it.type) }
            assertEquals("token-final", snapshot.newDeltaToken)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `enumerate falls back to baseline when page token returns 410 Gone`() =
        runTest {
            // First request (with stale page token) returns 410 Gone.
            server.enqueue(
                MockResponse()
                    .setResponseCode(410)
                    .setBody("""{"error": {"code": "tokenExpired", "message": "The sync token is no longer valid."}}"""),
            )
            // Baseline fallback: changes/startPageToken returns a fresh start token.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"startPageToken": "fresh-token-1"}"""),
            )

            val snapshot = enumerator.enumerateWithToken(token, deltaToken = "stale-token")

            assertTrue("Fallback snapshot should have no changes", snapshot.changes.isEmpty())
            assertEquals(
                "Fallback should return fresh start token",
                "fresh-token-1",
                snapshot.newDeltaToken,
            )
            assertEquals("Two requests should be made (stale + baseline)", 2, server.requestCount)
        }
}
