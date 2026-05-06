package com.synckro.providers.onedrive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown by [OneDriveGraphClient] when the Graph API returns an HTTP error.
 *
 * @param statusCode The HTTP status code returned by the server.
 * @param body The response body text (for logging / callers to inspect).
 */
internal class GraphApiException(
    val statusCode: Int,
    val body: String,
) : IOException("Graph API error $statusCode: $body")

/**
 * OkHttp-based client for Microsoft Graph `/me/drive` endpoints.
 *
 * Every public method takes an explicit [token] (Bearer token) so the class
 * stays stateless with respect to authentication — token management lives in
 * [OneDriveProvider].
 *
 * The primary `@Inject` constructor is used by Hilt in production.
 * Tests construct the class directly with a `graphBaseUrl` pointing at a
 * [okhttp3.mockwebserver.MockWebServer].
 *
 * @param okHttpClient The OkHttp client to use for all requests.
 * @param graphBaseUrl Base URL for Graph API (default: production endpoint).
 */
@Singleton
class OneDriveGraphClient
    @Inject
    constructor(
        private val okHttpClient: OkHttpClient,
    ) {
        internal var graphBaseUrl: String = GRAPH_BASE_URL

        companion object {
            const val GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0/me/drive"

            /** Files up to this size are uploaded with a simple PUT; larger files use an upload session. */
            internal const val SMALL_FILE_THRESHOLD = 4 * 1024 * 1024L // 4 MiB

            /** Chunk size for resumable uploads (10 MiB as recommended by Graph API docs). */
            internal const val CHUNK_SIZE = 10 * 1024 * 1024 // 10 MiB

            private const val MAX_RETRIES = 4
            private val CONTENT_TYPE_JSON = "application/json; charset=utf-8".toMediaType()
            private val CONTENT_TYPE_OCTET = "application/octet-stream".toMediaType()
        }

        private val json = Json { ignoreUnknownKeys = true }

        // -------------------------------------------------------------------------
        // Internal API (accessible to OneDriveProvider and tests in the same module)
        // -------------------------------------------------------------------------

        /**
         * Lists the direct children of [folderId], or the drive root when [folderId] is `null`.
         * Follows `@odata.nextLink` to collect all pages.
         */
        internal suspend fun list(
            token: String,
            folderId: String?,
        ): List<GraphDriveItem> {
            val root = if (folderId == null) "root" else "items/$folderId"
            val items = mutableListOf<GraphDriveItem>()
            var nextUrl: String? = "$graphBaseUrl/$root/children"
            while (nextUrl != null) {
                val resp = executeWithRetry(buildGetRequest(nextUrl, token))
                val page = parseBody<GraphItemCollection>(resp)
                items += page.value
                nextUrl = page.nextLink
            }
            return items
        }

        /** Fetches the metadata for a single DriveItem by [id]. */
        internal suspend fun getMetadata(
            token: String,
            id: String,
        ): GraphDriveItem {
            val resp = executeWithRetry(buildGetRequest("$graphBaseUrl/items/$id", token))
            return parseBody(resp)
        }

        /**
         * Opens an InputStream for the contents of [id].
         *
         * OkHttp follows the Graph API's 302 redirect to the download URL automatically.
         * The caller owns the returned stream and must close it.
         */
        internal suspend fun download(
            token: String,
            id: String,
        ): InputStream {
            val req = buildGetRequest("$graphBaseUrl/items/$id/content", token)
            val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }
            if (!resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                resp.close()
                throw GraphApiException(resp.code, body)
            }
            return resp.body!!.byteStream()
        }

        /**
         * Uploads [content] as a new file named [name] under parent [parentId].
         *
         * Uses a simple PUT for files ≤ [SMALL_FILE_THRESHOLD]; otherwise creates a
         * resumable upload session and uploads in [CHUNK_SIZE]-byte chunks.
         */
        internal suspend fun uploadNew(
            token: String,
            parentId: String,
            name: String,
            content: InputStream,
            size: Long,
            mimeType: String?,
        ): GraphDriveItem =
            if (size in 0..SMALL_FILE_THRESHOLD) {
                simplePut(token, "$graphBaseUrl/items/$parentId:/$name:/content", content, mimeType)
            } else {
                val sessionUrl =
                    createUploadSession(
                        token,
                        "$graphBaseUrl/items/$parentId:/$name:/createUploadSession",
                    )
                uploadChunked(sessionUrl, content, size)
            }

        /**
         * Replaces the content of an existing file identified by [id].
         *
         * Uses a simple PUT for files ≤ [SMALL_FILE_THRESHOLD]; otherwise uses a
         * resumable upload session.
         */
        internal suspend fun updateContent(
            token: String,
            id: String,
            content: InputStream,
            size: Long,
            mimeType: String?,
        ): GraphDriveItem =
            if (size in 0..SMALL_FILE_THRESHOLD) {
                simplePut(token, "$graphBaseUrl/items/$id/content", content, mimeType)
            } else {
                val sessionUrl =
                    createUploadSession(
                        token,
                        "$graphBaseUrl/items/$id/createUploadSession",
                    )
                uploadChunked(sessionUrl, content, size)
            }

        /**
         * Creates a folder named [name] under [parentId].
         */
        internal suspend fun createFolder(
            token: String,
            parentId: String,
            name: String,
        ): GraphDriveItem {
            val body = json.encodeToString(GraphCreateFolderRequest(name = name))
            val req =
                Request
                    .Builder()
                    .url("$graphBaseUrl/items/$parentId/children")
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody(CONTENT_TYPE_JSON))
                    .build()
            val resp = executeWithRetry(req)
            return parseBody(resp)
        }

        /**
         * Deletes the item identified by [id].
         */
        internal suspend fun delete(
            token: String,
            id: String,
        ) {
            val req =
                Request
                    .Builder()
                    .url("$graphBaseUrl/items/$id")
                    .header("Authorization", "Bearer $token")
                    .delete()
                    .build()
            val resp = executeWithRetry(req)
            // 204 No Content is the expected success response for DELETE.
            if (!resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                resp.close()
                throw GraphApiException(resp.code, body)
            }
            resp.close()
        }

        /**
         * Retrieves incremental changes since [deltaToken].
         *
         * - `deltaToken == null`: calls `root/delta?$deltaToken=latest` to establish a baseline
         *   without replaying the full history. Returns an empty change list with the new deltaLink.
         * - `deltaToken != null`: the token is the full deltaLink URL returned by a previous call.
         *   Follows `@odata.nextLink` until `@odata.deltaLink` is present, collecting all changes.
         *
         * @return A pair of (changes, nextDeltaLinkUrl).
         * @throws GraphApiException on HTTP errors.
         * @throws IOException on network failures.
         */
        internal suspend fun changesSince(
            token: String,
            deltaToken: String?,
        ): Pair<List<GraphDriveItem>, String> {
            val startUrl =
                if (deltaToken == null) {
                    // Use $deltaToken=latest to skip existing files and get only the deltaLink.
                    "$graphBaseUrl/root/delta?\$deltaToken=latest"
                } else {
                    deltaToken
                }

            val allItems = mutableListOf<GraphDriveItem>()
            var currentUrl: String? = startUrl
            var finalDeltaLink: String? = null

            while (currentUrl != null) {
                val req = buildGetRequest(currentUrl, token)
                val resp = executeWithRetry(req)
                val delta = parseBody<GraphDeltaResponse>(resp)

                allItems += delta.value
                finalDeltaLink = delta.deltaLink
                currentUrl = delta.nextLink
            }

            val nextLink =
                finalDeltaLink
                    ?: throw IOException("Delta response did not include @odata.deltaLink")
            return Pair(allItems, nextLink)
        }

        /**
         * Retrieves the complete current state of the folder identified by [rootFolderId]
         * by calling `items/{rootFolderId}/delta` **without** `$deltaToken=latest`.
         *
         * Unlike [changesSince] with a null token (which skips existing items to establish a
         * baseline), this method returns **all** items currently under [rootFolderId] — suitable
         * for seeding a brand-new sync pair.  The returned deltaLink can subsequently be passed
         * to [changesSince] for incremental polling.
         *
         * @param token        Bearer access token.
         * @param rootFolderId Provider-specific folder ID to enumerate from.
         * @return A pair of (all current items, deltaLink for future incremental syncs).
         * @throws GraphApiException on HTTP errors.
         * @throws IOException on network failures.
         */
        internal suspend fun listAll(
            token: String,
            rootFolderId: String,
        ): Pair<List<GraphDriveItem>, String> {
            val allItems = mutableListOf<GraphDriveItem>()
            // Calling /delta without $deltaToken=latest returns the full current state.
            var currentUrl: String? = "$graphBaseUrl/items/$rootFolderId/delta"
            var finalDeltaLink: String? = null

            while (currentUrl != null) {
                val req = buildGetRequest(currentUrl, token)
                val resp = executeWithRetry(req)
                val delta = parseBody<GraphDeltaResponse>(resp)

                allItems += delta.value
                finalDeltaLink = delta.deltaLink
                currentUrl = delta.nextLink
            }

            val nextLink =
                finalDeltaLink
                    ?: throw IOException("Delta response did not include @odata.deltaLink")
            return Pair(allItems, nextLink)
        }

        // -------------------------------------------------------------------------
        // Internal helpers
        // -------------------------------------------------------------------------

        /** Builds a GET request with a Bearer Authorization header. */
        private fun buildGetRequest(
            url: String,
            token: String,
        ): Request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()

        /**
         * Executes [request] and retries on 429 (Too Many Requests) and 5xx server
         * errors, honouring the `Retry-After` response header when present.
         *
         * Returns the final response (caller must close / consume the body).
         */
        private suspend fun executeWithRetry(request: Request): okhttp3.Response {
            var backoffMs = 1_000L
            repeat(MAX_RETRIES) { attempt ->
                val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
                val shouldRetry = response.code == 429 || response.code in 500..599
                if (!shouldRetry) return response

                val retryAfterMs =
                    response.header("Retry-After")?.toLongOrNull()?.times(1_000) ?: backoffMs
                Timber.w("Graph API ${response.code}; retrying in ${retryAfterMs}ms (attempt ${attempt + 1}/$MAX_RETRIES)")
                response.close()
                delay(retryAfterMs)
                backoffMs = minOf(backoffMs * 2, 32_000L)
            }
            return withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
        }

        /** Parses the response body as [T] and closes the response. Throws [GraphApiException] on error. */
        private inline fun <reified T> parseBody(response: okhttp3.Response): T {
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                response.close()
                throw GraphApiException(response.code, body)
            }
            val text = response.body!!.use { it.string() }
            return json.decodeFromString(text)
        }

        // -------------------------------------------------------------------------
        // Upload helpers
        // -------------------------------------------------------------------------

        /** Simple single-request PUT upload for small files. */
        private suspend fun simplePut(
            token: String,
            url: String,
            content: InputStream,
            mimeType: String?,
        ): GraphDriveItem {
            val bytes = content.use { it.readBytes() }
            val mediaType = (mimeType ?: "application/octet-stream").toMediaType()
            val req =
                Request
                    .Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .put(bytes.toRequestBody(mediaType))
                    .build()
            val resp = executeWithRetry(req)
            return parseBody(resp)
        }

        /**
         * Creates a resumable upload session and returns the `uploadUrl`.
         *
         * @param token Bearer access token for the Graph API request.
         * @param sessionEndpoint Full URL for the `createUploadSession` call.
         */
        private suspend fun createUploadSession(
            token: String,
            sessionEndpoint: String,
        ): String {
            val body =
                json.encodeToString(
                    GraphUploadSessionRequest(item = GraphUploadItemProperties(conflictBehavior = "replace")),
                )
            val req =
                Request
                    .Builder()
                    .url(sessionEndpoint)
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody(CONTENT_TYPE_JSON))
                    .build()
            val resp = executeWithRetry(req)
            val session = parseBody<GraphUploadSession>(resp)
            return session.uploadUrl
        }

        /**
         * Uploads [content] to an already-created upload session at [uploadUrl] in
         * [CHUNK_SIZE]-byte chunks.
         *
         * On 429 or 5xx responses the chunk is retried with back-off, honouring
         * `Retry-After`. On [IOException] (network drop) the method queries the
         * upload URL to find the server's next expected range and resumes from there,
         * replaying the last chunk if necessary.
         *
         * @param uploadUrl Pre-authorized upload session URL (no auth header needed).
         * @param content   Stream to upload; caller must not read from it concurrently.
         * @param totalSize Exact byte length of [content].
         * @return The finalised [GraphDriveItem] from the server's 200/201 response.
         */
        internal suspend fun uploadChunked(
            uploadUrl: String,
            content: InputStream,
            totalSize: Long,
        ): GraphDriveItem {
            val chunkBuffer = ByteArray(CHUNK_SIZE)
            var offset = 0L

            content.use { stream ->
                while (offset < totalSize) {
                    val chunkSize = minOf(CHUNK_SIZE.toLong(), totalSize - offset).toInt()

                    // Fill buffer — a single read() call may return fewer bytes than requested.
                    var bytesInBuffer = 0
                    while (bytesInBuffer < chunkSize) {
                        val n = stream.read(chunkBuffer, bytesInBuffer, chunkSize - bytesInBuffer)
                        if (n < 0) break
                        bytesInBuffer += n
                    }
                    if (bytesInBuffer <= 0) break

                    // The range header uses inclusive end byte.
                    val end = offset + bytesInBuffer - 1

                    var backoffMs = 1_000L
                    var attempt = 0
                    var chunkCommitted = false

                    while (!chunkCommitted) {
                        // Build a fresh RequestBody from the buffered chunk on every attempt.
                        val chunkBody =
                            chunkBuffer.copyOf(bytesInBuffer).toRequestBody(CONTENT_TYPE_OCTET)
                        val req =
                            Request
                                .Builder()
                                .url(uploadUrl)
                                .put(chunkBody)
                                .header("Content-Range", "bytes $offset-$end/$totalSize")
                                .header("Content-Length", bytesInBuffer.toString())
                                .build()

                        try {
                            val resp =
                                withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }
                            when (resp.code) {
                                200, 201 -> {
                                    // Final chunk — server returns the completed DriveItem.
                                    return parseBody(resp)
                                }
                                202 -> {
                                    // Chunk accepted; advance to the next chunk.
                                    resp.close()
                                    offset += bytesInBuffer
                                    chunkCommitted = true
                                }
                                429 -> {
                                    val retryAfterMs =
                                        resp.header("Retry-After")?.toLongOrNull()?.times(1_000)
                                            ?: backoffMs
                                    Timber.w("Upload session 429; retrying in ${retryAfterMs}ms")
                                    resp.close()
                                    delay(retryAfterMs)
                                    backoffMs = minOf(backoffMs * 2, 32_000L)
                                    attempt++
                                }
                                in 500..599 -> {
                                    Timber.w("Upload session ${resp.code}; retrying (attempt $attempt)")
                                    resp.close()
                                    delay(backoffMs)
                                    backoffMs = minOf(backoffMs * 2, 32_000L)
                                    attempt++
                                }
                                else -> {
                                    val errBody = resp.body?.string() ?: ""
                                    resp.close()
                                    throw GraphApiException(resp.code, errBody)
                                }
                            }
                        } catch (e: IOException) {
                            if (e is GraphApiException) throw e
                            if (attempt >= MAX_RETRIES) throw e
                            Timber.w(e, "Upload chunk IOException at offset $offset; querying server")
                            delay(backoffMs)
                            backoffMs = minOf(backoffMs * 2, 32_000L)
                            attempt++

                            // Ask the server which byte it expects next.
                            val serverNext = queryUploadStatus(uploadUrl)
                            if (serverNext != null && serverNext > end + 1) {
                                // Server already committed this chunk and possibly more; skip ahead.
                                val toSkip = serverNext - (end + 1)
                                if (toSkip > 0) withContext(Dispatchers.IO) { stream.skipFully(toSkip) }
                                offset = serverNext
                                chunkCommitted = true
                            }
                            // Otherwise retry the same chunk (serverNext <= offset means resend).
                        }

                        if (attempt > MAX_RETRIES && !chunkCommitted) {
                            throw IOException("Upload chunk at offset $offset failed after $MAX_RETRIES retries")
                        }
                    }
                }
            }
            throw IOException("Upload ended without a 200/201 final response")
        }

        /**
         * GETs the active upload session URL to find out which byte range the server
         * expects next.
         *
         * @return The first expected byte offset, or `null` if the status cannot be determined.
         */
        private suspend fun queryUploadStatus(uploadUrl: String): Long? {
            return try {
                val req = Request.Builder().url(uploadUrl).build()
                val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }
                if (!resp.isSuccessful) {
                    resp.close()
                    return null
                }
                val status = json.decodeFromString<GraphUploadStatus>(resp.body!!.use { it.string() })
                // nextExpectedRanges entries are "start-end" or "start-" (open-ended).
                status.nextExpectedRanges
                    .firstOrNull()
                    ?.substringBefore('-')
                    ?.toLongOrNull()
            } catch (e: Exception) {
                Timber.w(e, "Could not query upload status")
                null
            }
        }
    }

/**
 * Reliably skips [n] bytes from [this] stream by looping until the requested number of bytes
 * have been consumed. Falls back to read-and-discard when [InputStream.skip] returns fewer
 * bytes than requested (which is permitted by the [InputStream] contract).
 */
private fun InputStream.skipFully(n: Long) {
    var remaining = n
    val discard = ByteArray(minOf(n, 8192L).toInt())
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        // skip() returned 0 or negative — fall back to read-and-discard.
        val read = read(discard, 0, minOf(remaining, discard.size.toLong()).toInt())
        if (read < 0) break
        remaining -= read
    }
}
