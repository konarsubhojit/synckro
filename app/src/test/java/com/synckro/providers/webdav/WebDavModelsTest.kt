package com.synckro.providers.webdav

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the WebDAV multistatus parsing helpers in `WebDavModels.kt`. */
class WebDavModelsTest {
    private val baseUrl = "https://cloud.example.com/remote.php/dav/files/alice".toHttpUrl()

    @Test
    fun `parses hrefs given as absolute urls and percent-encoded paths`() {
        val body =
            """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>https://cloud.example.com/remote.php/dav/files/alice/my%20notes.txt</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype/>
                    <d:getcontentlength>7</d:getcontentlength>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()

        val resources = parseMultiStatus(body.byteInputStream(), baseUrl)

        assertEquals(1, resources.size)
        assertEquals("/remote.php/dav/files/alice/my notes.txt", resources[0].path)
        assertEquals("my notes.txt", resources[0].name)
        assertEquals("/remote.php/dav/files/alice", resources[0].parentPath)
        assertFalse(resources[0].isCollection)
    }

    @Test
    fun `ignores properties reported in non-2xx propstat blocks`() {
        val body =
            """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/remote.php/dav/files/alice/notes.txt</d:href>
                <d:propstat>
                  <d:prop><d:resourcetype/><d:getcontentlength>7</d:getcontentlength></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop><d:getcontenttype/></d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()

        val resource = parseMultiStatus(body.byteInputStream(), baseUrl).single()

        assertEquals(7L, resource.contentLength)
        assertNull(resource.contentType)
    }

    @Test
    fun `rejects documents containing a doctype declaration`() {
        val body =
            """
            <?xml version="1.0"?>
            <!DOCTYPE d [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/remote.php/dav/files/alice/&xxe;</d:href></d:response>
            </d:multistatus>
            """.trimIndent()

        val error = runCatching { parseMultiStatus(body.byteInputStream(), baseUrl) }.exceptionOrNull()

        assertTrue("Expected DTDs to be rejected, got $error", error != null)
    }

    @Test
    fun `toRemoteFile never reports an ETag as a content hash`() {
        val resource =
            WebDavResource(
                path = "/remote.php/dav/files/alice/notes.txt",
                isCollection = false,
                contentLength = 12,
                lastModifiedMs = 1_445_412_480_000L,
                eTag = "abc123",
                contentType = "text/plain",
            )

        val remoteFile = resource.toRemoteFile()

        assertEquals("abc123", remoteFile.eTag)
        assertNull(remoteFile.contentHash)
        assertEquals("/remote.php/dav/files/alice", remoteFile.parentId)
    }

    @Test
    fun `folders without a content type fall back to the collection mime type`() {
        val remoteFile =
            WebDavResource(path = "/remote.php/dav/files/alice/Photos", isCollection = true).toRemoteFile()

        assertEquals(WEBDAV_COLLECTION_MIME_TYPE, remoteFile.mimeType)
        assertTrue(remoteFile.isFolder)
    }

    @Test
    fun `normalizeETag strips quotes and weak validator prefix`() {
        assertEquals("abc", normalizeETag("\"abc\""))
        assertEquals("abc", normalizeETag("W/\"abc\""))
        assertNull(normalizeETag("\"\""))
    }

    @Test
    fun `parseRfc1123 converts http dates and tolerates junk`() {
        assertEquals(1_445_412_480_000L, parseRfc1123("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(parseRfc1123("not-a-date"))
    }

    @Test
    fun `normalizePath trims trailing slashes`() {
        assertEquals("/a/b", normalizePath("/a/b/"))
        assertEquals("/", normalizePath("/"))
    }
}
