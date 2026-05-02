package com.synckro.providers.onedrive

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Integration tests for [OneDriveGraphClient] using [MockWebServer] as a recorded fixture.
 *
 * These tests verify the full HTTP round-trip for:
 * - Delta endpoint (initial baseline + incremental changes)
 * - Resumable chunked upload session
 * - Resume after a simulated mid-transfer network drop
 * - Download, createFolder, delete
 */
class OneDriveGraphClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OneDriveGraphClient

    private val token = "test-bearer-token"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Disable OkHttp's automatic retry-on-connection-failure so that the
        // simulated network-drop in the resume test triggers our own retry path.
        val okHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .build()
        client = OneDriveGraphClient(okHttpClient)
        client.graphBaseUrl = server.url("/me/drive").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // -------------------------------------------------------------------------
    // Delta (changesSince)
    // -------------------------------------------------------------------------

    @Test
    fun `changesSince null calls delta with deltaToken=latest and returns empty changes`() = runTest {
        // Recorded fixture: server returns empty value list + deltaLink on first call.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "value": [],
                      "@odata.deltaLink": "https://graph.example.com/delta-link-1"
                    }
                    """.trimIndent()
                )
        )

        val (items, nextLink) = client.changesSince(token, deltaToken = null)

        // No history should be replayed on the first call.
        assertTrue(items.isEmpty())
        assertEquals("https://graph.example.com/delta-link-1", nextLink)

        // Verify the request used $deltaToken=latest.
        val request = server.takeRequest()
        assertTrue(
            "Expected \$deltaToken=latest in URL: ${request.path}",
            request.path!!.contains("%24deltaToken=latest") ||
                request.path!!.contains("\$deltaToken=latest")
        )
        assertEquals("Bearer $token", request.getHeader("Authorization"))
    }

    @Test
    fun `changesSince with deltaLink returns changed and deleted items`() = runTest {
        // The delta token is a full URL served by our MockWebServer.
        val deltaLinkUrl = server.url("/me/drive/root/delta?skiptoken=abc").toString()

        // Recorded fixture: delta call returns two changed files and one deletion.
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
                          "id": "file-2",
                          "name": "photo.jpg",
                          "parentReference": {"id": "root-id"},
                          "file": {"mimeType": "image/jpeg"},
                          "size": 204800,
                          "lastModifiedDateTime": "2024-03-15T11:00:00Z",
                          "eTag": "\"def456\""
                        },
                        {
                          "id": "file-deleted",
                          "name": "old.txt",
                          "deleted": {}
                        }
                      ],
                      "@odata.deltaLink": "https://graph.example.com/delta-link-2"
                    }
                    """.trimIndent()
                )
        )

        val (items, nextLink) = client.changesSince(token, deltaToken = deltaLinkUrl)

        assertEquals(3, items.size)
        assertEquals("file-1", items[0].id)
        assertEquals("notes.txt", items[0].name)
        assertEquals("file-2", items[1].id)
        assertEquals("file-deleted", items[2].id)
        assertNotNull(items[2].deleted)
        assertEquals("https://graph.example.com/delta-link-2", nextLink)
    }

    @Test
    fun `changesSince follows nextLink pages before returning deltaLink`() = runTest {
        // Page 1: has a nextLink pointing to page 2.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "value": [{"id": "item-1", "name": "a.txt", "file": {}, "size": 1}],
                      "@odata.nextLink": "${server.url("/me/drive/root/delta?skip=1")}"
                    }
                    """.trimIndent()
                )
        )
        // Page 2: has the deltaLink, no more pages.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "value": [{"id": "item-2", "name": "b.txt", "file": {}, "size": 2}],
                      "@odata.deltaLink": "https://graph.example.com/delta-link-final"
                    }
                    """.trimIndent()
                )
        )

        val (items, nextLink) = client.changesSince(token, deltaToken = null)

        assertEquals(2, items.size)
        assertEquals("item-1", items[0].id)
        assertEquals("item-2", items[1].id)
        assertEquals("https://graph.example.com/delta-link-final", nextLink)
        assertEquals(2, server.requestCount)
    }

    // -------------------------------------------------------------------------
    // Resumable upload session
    // -------------------------------------------------------------------------

    @Test
    fun `uploadNew large file creates session and uploads all chunks`() = runTest {
        val chunkSize = OneDriveGraphClient.CHUNK_SIZE
        // Use a file 1.5× the chunk size so we get 2 chunks.
        val totalSize = (chunkSize * 3 / 2).toLong()
        val fileBytes = ByteArray(totalSize.toInt()) { it.toByte() }

        // 1. Create upload session.
        val uploadUrl = server.url("/upload-session").toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"uploadUrl": "$uploadUrl", "expirationDateTime": "2099-01-01T00:00:00Z"}""")
        )
        // 2. First chunk → 202 Accepted.
        server.enqueue(MockResponse().setResponseCode(202))
        // 3. Final chunk → 201 Created with DriveItem.
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(driveItemJson("new-file-id", "bigfile.bin"))
        )

        val parentId = "parent-folder-id"
        val result = client.uploadNew(
            token = token,
            parentId = parentId,
            name = "bigfile.bin",
            content = ByteArrayInputStream(fileBytes),
            size = totalSize,
            mimeType = "application/octet-stream",
        )

        assertEquals("new-file-id", result.id)
        assertEquals("bigfile.bin", result.name)

        // Verify session creation request.
        val sessionReq = server.takeRequest()
        assertTrue(sessionReq.path!!.contains("createUploadSession"))
        assertEquals("Bearer $token", sessionReq.getHeader("Authorization"))

        // Verify first chunk Content-Range header.
        val chunk1Req = server.takeRequest()
        val chunk1End = chunkSize - 1
        assertEquals("bytes 0-$chunk1End/$totalSize", chunk1Req.getHeader("Content-Range"))
        assertEquals(chunkSize.toString(), chunk1Req.getHeader("Content-Length"))

        // Verify final chunk Content-Range header.
        val chunk2Req = server.takeRequest()
        val chunk2Start = chunkSize
        val chunk2End = totalSize - 1
        assertEquals("bytes $chunk2Start-$chunk2End/$totalSize", chunk2Req.getHeader("Content-Range"))
    }

    /**
     * Tests that [OneDriveGraphClient.uploadChunked] retries a chunk on 429 and
     * honours the `Retry-After` response header (set to 0 for immediate retry in tests).
     */
    @Test
    fun `uploadChunked retries chunk on 429 and honours Retry-After`() = runTest {
        val uploadUrl = server.url("/upload-session").toString()
        val totalSize = 512L
        val fileBytes = ByteArray(totalSize.toInt()) { it.toByte() }

        // First attempt → 429 with Retry-After: 0 (immediate retry for test speed).
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "0")
        )
        // Second attempt → 201 complete.
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(driveItemJson("file-after-retry", "small.bin"))
        )

        val result = client.uploadChunked(
            uploadUrl = uploadUrl,
            content = ByteArrayInputStream(fileBytes),
            totalSize = totalSize,
        )

        assertEquals("file-after-retry", result.id)
        assertEquals(2, server.requestCount) // failed chunk attempt + successful retry
    }

    /**
     * Verifies the mid-transfer resume scenario described in the issue:
     *
     * 1. First chunk (0..CHUNK_SIZE-1) is accepted (202).
     * 2. Second chunk upload triggers a network disconnect (simulated).
     * 3. Client GETs the upload URL to find the next expected range.
     * 4. Client retries the second chunk from the server-reported offset and completes (201).
     */
    @Test
    fun `uploadChunked resumes from server offset after network drop`() = runTest {
        val chunkSize = OneDriveGraphClient.CHUNK_SIZE
        val totalSize = (chunkSize * 3 / 2).toLong()
        val fileBytes = ByteArray(totalSize.toInt()) { it.toByte() }

        val uploadUrl = server.url("/upload-session").toString()

        // Chunk 1 succeeds.
        server.enqueue(MockResponse().setResponseCode(202))

        // Chunk 2: simulate network drop by disconnecting immediately after the request.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        // Status query (GET uploadUrl) → server reports chunk 1 committed, chunk 2 expected next.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"nextExpectedRanges": ["$chunkSize-"]}""")
        )

        // Chunk 2 retry → upload complete (201).
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(driveItemJson("resumed-file-id", "large.bin"))
        )

        val result = client.uploadChunked(
            uploadUrl = uploadUrl,
            content = ByteArrayInputStream(fileBytes),
            totalSize = totalSize,
        )

        assertEquals("resumed-file-id", result.id)

        // chunk1 (202) + chunk2 disconnect + status GET + chunk2 retry (201)
        assertEquals(4, server.requestCount)

        // Drain the first three requests so we can inspect the status GET.
        server.takeRequest() // chunk 1
        server.takeRequest() // chunk 2 (disconnect)
        val statusReq = server.takeRequest()
        assertEquals("GET", statusReq.method)
        // The upload URL is pre-authorized; no Authorization header needed.
        assertTrue(statusReq.getHeader("Authorization").isNullOrEmpty())
    }

    // -------------------------------------------------------------------------
    // Download
    // -------------------------------------------------------------------------

    @Test
    fun `download returns response body stream`() = runTest {
        val content = "Hello, OneDrive!"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(content)
        )

        val stream = client.download(token, "file-123")
        val downloaded = stream.use { it.readBytes() }.toString(Charsets.UTF_8)

        assertEquals(content, downloaded)
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("file-123"))
        assertEquals("Bearer $token", req.getHeader("Authorization"))
    }

    @Test(expected = GraphApiException::class)
    fun `download throws GraphApiException on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error": "itemNotFound"}"""))
        client.download(token, "missing-file")
    }

    // -------------------------------------------------------------------------
    // list
    // -------------------------------------------------------------------------

    @Test
    fun `list returns items for root when folderId is null`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "value": [
                        ${driveItemJson("item-1", "doc.txt")},
                        ${folderItemJson("folder-1", "Photos")}
                      ]
                    }
                    """.trimIndent()
                )
        )

        val items = client.list(token, folderId = null)

        assertEquals(2, items.size)
        assertEquals("item-1", items[0].id)
        assertEquals("folder-1", items[1].id)
        assertTrue(items[1].folder != null)

        val req = server.takeRequest()
        assertTrue("Expected /root/children in path: ${req.path}", req.path!!.contains("root/children"))
    }

    @Test
    fun `list follows nextLink for paginated results`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "value": [${driveItemJson("item-page-1", "a.txt")}],
                      "@odata.nextLink": "${server.url("/me/drive/items/parent/children?skip=1")}"
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"value": [${driveItemJson("item-page-2", "b.txt")}]}""")
        )

        val items = client.list(token, folderId = "parent")

        assertEquals(2, items.size)
        assertEquals("item-page-1", items[0].id)
        assertEquals("item-page-2", items[1].id)
        assertEquals(2, server.requestCount)
    }

    // -------------------------------------------------------------------------
    // createFolder
    // -------------------------------------------------------------------------

    @Test
    fun `createFolder sends correct request and returns folder item`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(folderItemJson("new-folder-id", "Documents"))
        )

        val result = client.createFolder(token, "parent-id", "Documents")

        assertEquals("new-folder-id", result.id)
        assertNotNull(result.folder)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.contains("parent-id/children"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"Documents\""))
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    @Test
    fun `delete sends DELETE request and succeeds on 204`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        client.delete(token, "item-to-delete")

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertTrue(req.path!!.contains("item-to-delete"))
    }

    @Test(expected = GraphApiException::class)
    fun `delete throws GraphApiException on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error": "itemNotFound"}"""))
        client.delete(token, "missing-item")
    }

    // -------------------------------------------------------------------------
    // Retry on 5xx
    // -------------------------------------------------------------------------

    @Test
    fun `getMetadata retries on 503 and succeeds`() = runTest {
        // First attempt: 503 with Retry-After: 0 for immediate retry in tests.
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
        // Second attempt: success.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(driveItemJson("meta-id", "file.txt"))
        )

        val result = client.getMetadata(token, "meta-id")

        assertEquals("meta-id", result.id)
        assertEquals(2, server.requestCount)
    }

    // -------------------------------------------------------------------------
    // toRemoteFile mapping
    // -------------------------------------------------------------------------

    @Test
    fun `toRemoteFile strips quotes from eTag`() {
        val item = GraphDriveItem(
            id = "x",
            name = "x.txt",
            eTag = "\"etag-value\"",
            file = GraphFileInfo("text/plain"),
        )
        val remote = item.toRemoteFile()
        assertEquals("etag-value", remote.eTag)
    }

    @Test
    fun `toRemoteFile parses ISO-8601 lastModifiedDateTime`() {
        val item = GraphDriveItem(
            id = "x",
            name = "x.txt",
            lastModifiedDateTime = "2024-03-15T10:30:00Z",
            file = GraphFileInfo(),
        )
        val remote = item.toRemoteFile()
        assertNotNull(remote.lastModifiedMs)
        // 2024-03-15T10:30:00Z in millis
        assertEquals(1710498600000L, remote.lastModifiedMs)
    }

    @Test
    fun `toRemoteFile marks folder correctly`() {
        val item = GraphDriveItem(id = "f", name = "Photos", folder = GraphFolderInfo(childCount = 5))
        val remote = item.toRemoteFile()
        assertTrue(remote.isFolder)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun driveItemJson(id: String, name: String): String =
        """
        {
          "id": "$id",
          "name": "$name",
          "parentReference": {"id": "parent-ref-id"},
          "file": {"mimeType": "application/octet-stream"},
          "size": 512,
          "lastModifiedDateTime": "2024-01-01T00:00:00Z",
          "eTag": "\"etag-$id\""
        }
        """.trimIndent()

    private fun folderItemJson(id: String, name: String): String =
        """
        {
          "id": "$id",
          "name": "$name",
          "parentReference": {"id": "parent-ref-id"},
          "folder": {"childCount": 0},
          "lastModifiedDateTime": "2024-01-01T00:00:00Z"
        }
        """.trimIndent()
}
