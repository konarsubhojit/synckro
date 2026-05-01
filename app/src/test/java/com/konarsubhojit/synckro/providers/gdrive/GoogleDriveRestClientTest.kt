package com.konarsubhojit.synckro.providers.gdrive

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Integration tests for [GoogleDriveRestClient] using [MockWebServer] as a recorded fixture.
 *
 * These tests verify the full HTTP round-trip for:
 * - changes.list (initial baseline + incremental changes with pagination)
 * - Resumable chunked upload (new file and content update)
 * - Resume after a simulated mid-transfer network drop
 * - Download, createFolder, delete (trash + hard-delete)
 * - 429 / 5xx retry with Retry-After
 */
class GoogleDriveRestClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GoogleDriveRestClient

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
        client = GoogleDriveRestClient(okHttpClient)
        val base = server.url("/drive/v3").toString().trimEnd('/')
        client.driveBaseUrl = base
        client.uploadBaseUrl = server.url("/upload/drive/v3").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // -------------------------------------------------------------------------
    // changesSince
    // -------------------------------------------------------------------------

    @Test
    fun `changesSince null fetches startPageToken and returns empty changes`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"startPageToken": "token-1"}""")
        )

        val (changes, nextToken) = client.changesSince(token, pageToken = null)

        assertTrue(changes.isEmpty())
        assertEquals("token-1", nextToken)

        val req = server.takeRequest()
        assertTrue(
            "Expected changes/startPageToken in path: ${req.path}",
            req.path!!.contains("changes/startPageToken"),
        )
        assertEquals("Bearer $token", req.getHeader("Authorization"))
    }

    @Test
    fun `changesSince with token returns modified and removed items`() = runTest {
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
                          "file": ${driveFileJson("file-1", "notes.txt")}
                        },
                        {
                          "fileId": "file-deleted",
                          "removed": true
                        }
                      ],
                      "newStartPageToken": "token-2"
                    }
                    """.trimIndent()
                )
        )

        val (changes, nextToken) = client.changesSince(token, pageToken = "token-1")

        assertEquals(2, changes.size)
        assertEquals("file-1", changes[0].fileId)
        assertEquals(false, changes[0].removed)
        assertNotNull(changes[0].file)
        assertEquals("file-deleted", changes[1].fileId)
        assertEquals(true, changes[1].removed)
        assertEquals("token-2", nextToken)
    }

    @Test
    fun `changesSince follows nextPageToken before returning newStartPageToken`() = runTest {
        // Page 1: has nextPageToken, no newStartPageToken yet.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "changes": [{"fileId": "f1", "removed": false, "file": ${driveFileJson("f1", "a.txt")}}],
                      "nextPageToken": "page-token-2"
                    }
                    """.trimIndent()
                )
        )
        // Page 2: final page.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "changes": [{"fileId": "f2", "removed": false, "file": ${driveFileJson("f2", "b.txt")}}],
                      "newStartPageToken": "token-final"
                    }
                    """.trimIndent()
                )
        )

        val (changes, nextToken) = client.changesSince(token, pageToken = "token-1")

        assertEquals(2, changes.size)
        assertEquals("f1", changes[0].fileId)
        assertEquals("f2", changes[1].fileId)
        assertEquals("token-final", nextToken)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `changesSince treats trashed file as a removedId in provider mapping`() = runTest {
        // Verify the raw model: trashed field is parsed correctly.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "changes": [
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
                      "newStartPageToken": "next"
                    }
                    """.trimIndent()
                )
        )

        val (changes, _) = client.changesSince(token, pageToken = "tok")

        assertEquals(1, changes.size)
        assertEquals(true, changes[0].file?.trashed)
    }

    // -------------------------------------------------------------------------
    // Resumable upload — uploadNew
    // -------------------------------------------------------------------------

    @Test
    fun `uploadNew creates session and uploads all chunks`() = runTest {
        val chunkSize = GoogleDriveRestClient.CHUNK_SIZE
        // 1.5 × chunk size → two chunks.
        val totalSize = (chunkSize * 3 / 2).toLong()
        val fileBytes = ByteArray(totalSize.toInt()) { it.toByte() }

        val uploadUrl = server.url("/upload-session").toString()

        // 1. Initiate resumable upload → 200 with Location header.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Location", uploadUrl)
        )
        // 2. First chunk → 308 Resume Incomplete.
        server.enqueue(MockResponse().setResponseCode(308))
        // 3. Final chunk → 200 with DriveFile.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(driveFileJson("new-file-id", "bigfile.bin"))
        )

        val result = client.uploadNew(
            token = token,
            parentId = "parent-folder-id",
            name = "bigfile.bin",
            content = ByteArrayInputStream(fileBytes),
            size = totalSize,
            mimeType = "application/octet-stream",
        )

        assertEquals("new-file-id", result.id)
        assertEquals("bigfile.bin", result.name)

        // Verify session initiation.
        val sessionReq = server.takeRequest()
        assertTrue(
            "Expected ?uploadType=resumable in path: ${sessionReq.path}",
            sessionReq.path!!.contains("uploadType=resumable"),
        )
        assertEquals("Bearer $token", sessionReq.getHeader("Authorization"))
        assertEquals("application/octet-stream", sessionReq.getHeader("X-Upload-Content-Type"))
        assertEquals(totalSize.toString(), sessionReq.getHeader("X-Upload-Content-Length"))

        // Verify first chunk Content-Range.
        val chunk1Req = server.takeRequest()
        val chunk1End = chunkSize - 1
        assertEquals("bytes 0-$chunk1End/$totalSize", chunk1Req.getHeader("Content-Range"))
        assertEquals(chunkSize.toString(), chunk1Req.getHeader("Content-Length"))

        // Verify second (final) chunk Content-Range.
        val chunk2Req = server.takeRequest()
        val chunk2Start = chunkSize
        val chunk2End = totalSize - 1
        assertEquals(
            "bytes $chunk2Start-$chunk2End/$totalSize",
            chunk2Req.getHeader("Content-Range"),
        )
    }

    @Test
    fun `updateContent initiates session with PATCH and uploads content`() = runTest {
        val fileBytes = ByteArray(512) { 0 }
        val uploadUrl = server.url("/upload-session").toString()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Location", uploadUrl)
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(driveFileJson("existing-file-id", "updated.bin"))
        )

        val result = client.updateContent(
            token = token,
            id = "existing-file-id",
            content = ByteArrayInputStream(fileBytes),
            size = fileBytes.size.toLong(),
            mimeType = "application/octet-stream",
        )

        assertEquals("existing-file-id", result.id)

        // The initiation request must use PATCH and target the file's upload URL.
        val initReq = server.takeRequest()
        assertEquals("PATCH", initReq.method)
        assertTrue(initReq.path!!.contains("existing-file-id"))
        assertTrue(initReq.path!!.contains("uploadType=resumable"))
    }

    // -------------------------------------------------------------------------
    // Resumable upload — retry on 429
    // -------------------------------------------------------------------------

    @Test
    fun `uploadChunked retries chunk on 429 and honours Retry-After`() = runTest {
        val uploadUrl = server.url("/upload-session").toString()
        val totalSize = 512L
        val fileBytes = ByteArray(totalSize.toInt()) { it.toByte() }

        // First attempt → 429 with Retry-After: 0 for immediate retry in tests.
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "0")
        )
        // Second attempt → 200 complete.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(driveFileJson("file-after-retry", "small.bin"))
        )

        val result = client.uploadChunked(
            uploadUrl = uploadUrl,
            content = ByteArrayInputStream(fileBytes),
            totalSize = totalSize,
        )

        assertEquals("file-after-retry", result.id)
        assertEquals(2, server.requestCount)
    }

    // -------------------------------------------------------------------------
    // Resumable upload — resume after network drop
    // -------------------------------------------------------------------------

    /**
     * Verifies the mid-transfer resume scenario:
     *
     * 1. First chunk (0..CHUNK_SIZE-1) is accepted (308).
     * 2. Second chunk upload triggers a network disconnect.
     * 3. Client PUTs the upload URL with Content-Range: bytes * /total to query status.
     * 4. Server responds 308 with Range header showing chunk 1 committed.
     * 5. Client retries the second chunk and completes (200).
     */
    @Test
    fun `uploadChunked resumes from server offset after network drop`() = runTest {
        val chunkSize = GoogleDriveRestClient.CHUNK_SIZE
        val totalSize = (chunkSize * 3 / 2).toLong()
        val fileBytes = ByteArray(totalSize.toInt()) { it.toByte() }

        val uploadUrl = server.url("/upload-session").toString()

        // Chunk 1 succeeds.
        server.enqueue(MockResponse().setResponseCode(308))

        // Chunk 2: simulate network drop.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        // Status query (PUT with Content-Range: bytes */total) → server confirms chunk 1.
        server.enqueue(
            MockResponse()
                .setResponseCode(308)
                .addHeader("Range", "bytes=0-${chunkSize - 1}")
        )

        // Chunk 2 retry → upload complete.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(driveFileJson("resumed-file-id", "large.bin"))
        )

        val result = client.uploadChunked(
            uploadUrl = uploadUrl,
            content = ByteArrayInputStream(fileBytes),
            totalSize = totalSize,
        )

        assertEquals("resumed-file-id", result.id)

        // chunk1(308) + chunk2(disconnect) + status-GET(308) + chunk2-retry(200)
        assertEquals(4, server.requestCount)

        // Drain and inspect the status query.
        server.takeRequest()  // chunk 1
        server.takeRequest()  // chunk 2 (disconnect)
        val statusReq = server.takeRequest()
        assertEquals("PUT", statusReq.method)
        assertTrue(
            "Expected Content-Range: bytes */total in status query: ${statusReq.getHeader("Content-Range")}",
            statusReq.getHeader("Content-Range")?.startsWith("bytes */") == true,
        )
        // The upload URL is pre-authorized; no Authorization header required.
        assertTrue(statusReq.getHeader("Authorization").isNullOrEmpty())
    }

    // -------------------------------------------------------------------------
    // Download
    // -------------------------------------------------------------------------

    @Test
    fun `download returns response body stream`() = runTest {
        val content = "Hello, Google Drive!"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(content)
        )

        val stream = client.download(token, "file-123")
        val downloaded = stream.use { it.readBytes() }.toString(Charsets.UTF_8)

        assertEquals(content, downloaded)
        val req = server.takeRequest()
        assertTrue(
            "Expected alt=media and file id in path: ${req.path}",
            req.path!!.contains("file-123") && req.path!!.contains("alt=media"),
        )
        assertEquals("Bearer $token", req.getHeader("Authorization"))
    }

    @Test(expected = DriveApiException::class)
    fun `download throws DriveApiException on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error": {"code": 404}}"""))
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
                      "files": [
                        ${driveFileJson("item-1", "doc.txt")},
                        ${driveFolderJson("folder-1", "Photos")}
                      ]
                    }
                    """.trimIndent()
                )
        )

        val items = client.list(token, folderId = null)

        assertEquals(2, items.size)
        assertEquals("item-1", items[0].id)
        assertEquals("folder-1", items[1].id)
        assertTrue(items[1].mimeType == FOLDER_MIME_TYPE)

        val req = server.takeRequest()
        assertTrue(
            "Expected 'root' in query: ${req.path}",
            req.path!!.contains("root"),
        )
    }

    @Test
    fun `list follows nextPageToken for paginated results`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "files": [${driveFileJson("item-1", "a.txt")}],
                      "nextPageToken": "page-2-token"
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"files": [${driveFileJson("item-2", "b.txt")}]}""")
        )

        val items = client.list(token, folderId = "parent-id")

        assertEquals(2, items.size)
        assertEquals("item-1", items[0].id)
        assertEquals("item-2", items[1].id)
        assertEquals(2, server.requestCount)
    }

    // -------------------------------------------------------------------------
    // createFolder
    // -------------------------------------------------------------------------

    @Test
    fun `createFolder sends correct request and returns folder`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(driveFolderJson("new-folder-id", "Documents"))
        )

        val result = client.createFolder(token, "parent-id", "Documents")

        assertEquals("new-folder-id", result.id)
        assertEquals(FOLDER_MIME_TYPE, result.mimeType)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"Documents\""))
        assertTrue(body.contains(FOLDER_MIME_TYPE))
        assertTrue(body.contains("parent-id"))
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    @Test
    fun `delete with useTrash=true sends PATCH with trashed=true`() = runTest {
        client.useTrash = true
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(driveFileJson("item-id", "file.txt"))
        )

        client.delete(token, "item-id")

        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertTrue(req.path!!.contains("item-id"))
        assertTrue(req.body.readUtf8().contains("\"trashed\":true"))
    }

    @Test
    fun `delete with useTrash=false sends DELETE request`() = runTest {
        client.useTrash = false
        server.enqueue(MockResponse().setResponseCode(204))

        client.delete(token, "item-to-delete")

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertTrue(req.path!!.contains("item-to-delete"))
    }

    @Test(expected = DriveApiException::class)
    fun `delete throws DriveApiException on 404`() = runTest {
        client.useTrash = false
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error": {"code": 404}}"""))
        client.delete(token, "missing-item")
    }

    // -------------------------------------------------------------------------
    // Retry on 5xx
    // -------------------------------------------------------------------------

    @Test
    fun `getMetadata retries on 503 and succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(driveFileJson("meta-id", "file.txt"))
        )

        val result = client.getMetadata(token, "meta-id")

        assertEquals("meta-id", result.id)
        assertEquals(2, server.requestCount)
    }

    // -------------------------------------------------------------------------
    // toRemoteFile mapping
    // -------------------------------------------------------------------------

    @Test
    fun `toRemoteFile parses size string to Long`() {
        val file = DriveFile(id = "x", name = "x.bin", size = "123456789")
        val remote = file.toRemoteFile()
        assertEquals(123456789L, remote.size)
    }

    @Test
    fun `toRemoteFile uses md5Checksum as eTag`() {
        val file = DriveFile(id = "x", name = "x.bin", md5Checksum = "abc123")
        val remote = file.toRemoteFile()
        assertEquals("abc123", remote.eTag)
    }

    @Test
    fun `toRemoteFile parses ISO-8601 modifiedTime`() {
        val file = DriveFile(
            id = "x",
            name = "x.txt",
            modifiedTime = "2024-03-15T10:30:00Z",
        )
        val remote = file.toRemoteFile()
        assertNotNull(remote.lastModifiedMs)
        assertEquals(1710498600000L, remote.lastModifiedMs)
    }

    @Test
    fun `toRemoteFile marks folder correctly`() {
        val file = DriveFile(id = "f", name = "Photos", mimeType = FOLDER_MIME_TYPE)
        val remote = file.toRemoteFile()
        assertTrue(remote.isFolder)
    }

    @Test
    fun `toRemoteFile uses first parent as parentId`() {
        val file = DriveFile(id = "x", name = "x.txt", parents = listOf("parent-1", "parent-2"))
        val remote = file.toRemoteFile()
        assertEquals("parent-1", remote.parentId)
    }

    @Test
    fun `toRemoteFile returns null parentId when parents is empty`() {
        val file = DriveFile(id = "x", name = "x.txt", parents = null)
        val remote = file.toRemoteFile()
        assertNull(remote.parentId)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun driveFileJson(id: String, name: String): String =
        """
        {
          "id": "$id",
          "name": "$name",
          "parents": ["parent-ref-id"],
          "mimeType": "application/octet-stream",
          "size": "512",
          "modifiedTime": "2024-01-01T00:00:00Z",
          "md5Checksum": "md5-$id"
        }
        """.trimIndent()

    private fun driveFolderJson(id: String, name: String): String =
        """
        {
          "id": "$id",
          "name": "$name",
          "parents": ["parent-ref-id"],
          "mimeType": "$FOLDER_MIME_TYPE",
          "modifiedTime": "2024-01-01T00:00:00Z"
        }
        """.trimIndent()
}
