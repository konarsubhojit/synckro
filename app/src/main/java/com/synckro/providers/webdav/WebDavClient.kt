package com.synckro.providers.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown by [WebDavClient] when the server returns an HTTP error.
 *
 * @param statusCode The HTTP status code returned by the server.
 * @param body The response body text (for logging / callers to inspect).
 */
internal class WebDavApiException(
    val statusCode: Int,
    val body: String,
) : IOException("WebDAV error $statusCode: $body")

/**
 * Everything [WebDavClient] needs to talk to one account's WebDAV endpoint.
 *
 * @param baseUrl Absolute URL of the account's WebDAV collection root, e.g.
 *   `https://cloud.example.com/remote.php/dav/files/alice`.
 * @param authorizationHeader Pre-computed `Authorization` header value (HTTP
 *   Basic with a Nextcloud app password, per
 *   `docs/webdav-nextcloud-setup.md`).
 */
internal data class WebDavSession(
    val baseUrl: HttpUrl,
    val authorizationHeader: String,
) {
    /** Percent-decoded, normalized absolute path of [baseUrl]. */
    val rootPath: String get() = normalizePath("/" + baseUrl.pathSegments.joinToString("/"))
}

/**
 * OkHttp-based client for the WebDAV subset Synckro needs (RFC 4918):
 * `PROPFIND` for listing and metadata, `GET`/`PUT` for transfers, `MKCOL` for
 * folder creation and `DELETE` for removal, plus the RFC 4331 quota
 * properties.
 *
 * The class is stateless with respect to credentials — every call takes an
 * explicit [WebDavSession] — which mirrors
 * [com.synckro.providers.gdrive.GoogleDriveRestClient] and keeps credential
 * handling inside [WebDavAuthManager] / [WebDavProvider].
 */
@Singleton
class WebDavClient
    @Inject
    constructor(
        private val okHttpClient: OkHttpClient,
    ) {
        companion object {
            private const val MAX_RETRIES = 4

            /** Properties requested by every listing / metadata `PROPFIND`. */
            internal val PROPFIND_BODY =
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:resourcetype/>
                    <d:getcontentlength/>
                    <d:getlastmodified/>
                    <d:getetag/>
                    <d:getcontenttype/>
                  </d:prop>
                </d:propfind>
                """.trimIndent()

            /** Properties requested by the quota `PROPFIND` (RFC 4331). */
            internal val QUOTA_PROPFIND_BODY =
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:quota-used-bytes/>
                    <d:quota-available-bytes/>
                  </d:prop>
                </d:propfind>
                """.trimIndent()

            private val CONTENT_TYPE_XML: MediaType? = "application/xml; charset=utf-8".toMediaTypeOrNull()
            private const val DEFAULT_UPLOAD_CONTENT_TYPE = "application/octet-stream"
        }

        /**
         * Fetches metadata for a single resource via `PROPFIND` with `Depth: 0`.
         *
         * @throws WebDavApiException when the server responds with an error status
         *   or an empty multistatus document.
         */
        internal suspend fun getMetadata(
            session: WebDavSession,
            path: String,
        ): WebDavResource {
            val url = resolvePathUrl(session.baseUrl, path)
            val resources = propfind(session, url, depth = "0", body = PROPFIND_BODY)
            return resources.firstOrNull()
                ?: throw WebDavApiException(404, "PROPFIND on $path returned no resources")
        }

        /**
         * Lists the direct children of the collection at [path] via `PROPFIND`
         * with `Depth: 1`. The collection itself — which servers always include as
         * the first response entry — is filtered out.
         */
        internal suspend fun listChildren(
            session: WebDavSession,
            path: String,
        ): List<WebDavResource> {
            val url = resolvePathUrl(session.baseUrl, path)
            val self = normalizePath(path)
            return propfind(session, url, depth = "1", body = PROPFIND_BODY)
                .filterNot { it.path == self }
        }

        /**
         * Recursively lists every descendant of [path], depth-first, and returns
         * each resource paired with its path relative to [path].
         *
         * `Depth: infinity` is deliberately not used: Nextcloud and many other
         * servers reject it with `403 Forbidden` (RFC 4918 §9.1 explicitly allows
         * that), so the walk is performed with repeated `Depth: 1` requests.
         *
         * @return `(resource, relativePath)` pairs for all descendants, including
         *   collections.
         */
        internal suspend fun listDescendants(
            session: WebDavSession,
            path: String,
        ): List<Pair<WebDavResource, String>> {
            val result = mutableListOf<Pair<WebDavResource, String>>()
            collectDescendants(session, normalizePath(path), parentRelPath = "", result = result)
            return result
        }

        private suspend fun collectDescendants(
            session: WebDavSession,
            path: String,
            parentRelPath: String,
            result: MutableList<Pair<WebDavResource, String>>,
        ) {
            for (child in listChildren(session, path)) {
                val childRelPath = if (parentRelPath.isEmpty()) child.name else "$parentRelPath/${child.name}"
                result += child to childRelPath
                if (child.isCollection) {
                    collectDescendants(session, child.path, childRelPath, result)
                }
            }
        }

        /**
         * Opens a download stream for the file at [path].
         *
         * The caller owns the returned stream and must close it.
         */
        internal suspend fun download(
            session: WebDavSession,
            path: String,
        ): InputStream {
            val request =
                requestBuilder(session, resolvePathUrl(session.baseUrl, path))
                    .get()
                    .build()
            val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
            if (!response.isSuccessful) throw response.toApiException()
            return response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
        }

        /**
         * Uploads [content] to [path] with `PUT`, creating or overwriting the
         * resource, and returns its post-upload metadata.
         *
         * WebDAV `PUT` responses carry no property document, so metadata is read
         * back with a follow-up `PROPFIND`.
         *
         * [content] is always closed, including on failure.
         */
        internal suspend fun put(
            session: WebDavSession,
            path: String,
            content: InputStream,
            size: Long,
            mimeType: String?,
        ): WebDavResource {
            val mediaType = (mimeType ?: DEFAULT_UPLOAD_CONTENT_TYPE).toMediaTypeOrNull()
            content.use { stream ->
                val request =
                    requestBuilder(session, resolvePathUrl(session.baseUrl, path))
                        .put(StreamingRequestBody(stream, size, mediaType))
                        .build()
                // A streaming body can only be transmitted once, so this request is
                // intentionally not routed through executeWithRetry().
                val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
                if (!response.isSuccessful) throw response.toApiException()
                response.close()
            }
            return getMetadata(session, path)
        }

        /** Creates the collection at [path] with `MKCOL` and returns its metadata. */
        internal suspend fun mkcol(
            session: WebDavSession,
            path: String,
        ): WebDavResource {
            val request =
                requestBuilder(session, resolvePathUrl(session.baseUrl, path))
                    .method("MKCOL", EMPTY_BODY)
                    .build()
            val response = executeWithRetry(request)
            if (!response.isSuccessful) throw response.toApiException()
            response.close()
            return getMetadata(session, path)
        }

        /** Deletes the resource at [path] with `DELETE`. */
        internal suspend fun delete(
            session: WebDavSession,
            path: String,
        ) {
            val request =
                requestBuilder(session, resolvePathUrl(session.baseUrl, path))
                    .delete()
                    .build()
            val response = executeWithRetry(request)
            // 404 is treated as success: the resource is already gone, which is the
            // intended post-condition and happens routinely when two sync runs race.
            if (!response.isSuccessful && response.code != 404) throw response.toApiException()
            response.close()
        }

        /**
         * Reads the RFC 4331 quota properties of the collection at [path].
         *
         * Returns `null` when the server does not report both properties.
         */
        internal suspend fun quota(
            session: WebDavSession,
            path: String,
        ): WebDavQuota? {
            val url = resolvePathUrl(session.baseUrl, path)
            val request =
                requestBuilder(session, url)
                    .method("PROPFIND", QUOTA_PROPFIND_BODY.toRequestBody(CONTENT_TYPE_XML))
                    .header("Depth", "0")
                    .build()
            val response = executeWithRetry(request)
            if (!response.isSuccessful) throw response.toApiException()
            val body = response.body ?: return null
            return body.byteStream().use { parseQuota(it, url) }
        }

        // -------------------------------------------------------------------------
        // Internal helpers
        // -------------------------------------------------------------------------

        private suspend fun propfind(
            session: WebDavSession,
            url: HttpUrl,
            depth: String,
            body: String,
        ): List<WebDavResource> {
            val request =
                requestBuilder(session, url)
                    .method("PROPFIND", body.toRequestBody(CONTENT_TYPE_XML))
                    .header("Depth", depth)
                    .build()
            val response = executeWithRetry(request)
            if (!response.isSuccessful) throw response.toApiException()
            val responseBody = response.body ?: throw WebDavApiException(response.code, "empty PROPFIND body")
            return responseBody.byteStream().use { parseMultiStatus(it, url) }
        }

        private fun requestBuilder(
            session: WebDavSession,
            url: HttpUrl,
        ): Request.Builder =
            Request
                .Builder()
                .url(url)
                .header("Authorization", session.authorizationHeader)

        /**
         * Executes [request] and retries on 429 (Too Many Requests) and 5xx server
         * errors, honouring the `Retry-After` response header when present.
         *
         * Only safe for requests with a repeatable (in-memory) body.
         */
        private suspend fun executeWithRetry(request: Request): Response {
            var backoffMs = 1_000L
            repeat(MAX_RETRIES) { attempt ->
                val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
                val shouldRetry = response.code == 429 || response.code in 500..599
                if (!shouldRetry) return response
                val retryAfterMs = response.header("Retry-After")?.toLongOrNull()?.times(1_000) ?: backoffMs
                Timber.w(
                    "WebDAV ${response.code}; retrying in ${retryAfterMs}ms (attempt ${attempt + 1}/$MAX_RETRIES)",
                )
                response.close()
                delay(retryAfterMs)
                backoffMs = minOf(backoffMs * 2, 32_000L)
            }
            return withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
        }
    }

private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

/** Reads the (bounded) error body and converts the response into a typed exception. */
private fun Response.toApiException(): WebDavApiException {
    val text = runCatching { body?.string().orEmpty() }.getOrDefault("")
    close()
    return WebDavApiException(code, text)
}

/**
 * Streams [stream] to the server without buffering it in memory.
 *
 * Marked one-shot so OkHttp never attempts to replay the body after the stream
 * has been consumed.
 */
private class StreamingRequestBody(
    private val stream: InputStream,
    private val size: Long,
    private val mediaType: MediaType?,
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = if (size >= 0) size else -1L

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        stream.source().use { source -> sink.writeAll(source) }
    }
}

/**
 * Builds the absolute request URL for the server-absolute, percent-decoded
 * [path]. Path segments are added individually so OkHttp percent-encodes names
 * containing spaces, `#`, `?` and other reserved characters.
 */
internal fun resolvePathUrl(
    baseUrl: HttpUrl,
    path: String,
): HttpUrl {
    val builder =
        baseUrl
            .newBuilder()
            .encodedPath("/")
    normalizePath(path)
        .split('/')
        .filter { it.isNotEmpty() }
        .forEach { builder.addPathSegment(it) }
    return builder.build()
}
