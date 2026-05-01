package com.konarsubhojit.synckro.providers.gdrive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * Thrown by [GoogleDriveRestClient] when the Drive API returns an HTTP error.
 *
 * @param statusCode The HTTP status code returned by the server.
 * @param body The response body text (for logging / callers to inspect).
 */
internal class DriveApiException(
    val statusCode: Int,
    val body: String,
) : IOException("Drive API error $statusCode: $body")

/**
 * OkHttp-based client for the Google Drive v3 REST API.
 *
 * Every public method takes an explicit [token] (Bearer token) so the class
 * stays stateless with respect to authentication — token management lives in
 * [GoogleDriveProvider].
 *
 * The primary `@Inject` constructor is used by Hilt in production.
 * Tests construct the class directly with [driveBaseUrl] / [uploadBaseUrl]
 * pointing at a [okhttp3.mockwebserver.MockWebServer].
 *
 * @param okHttpClient The OkHttp client to use for all requests.
 */
@Singleton
class GoogleDriveRestClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {

    /** Base URL for Drive v3 metadata endpoints. Override in tests. */
    internal var driveBaseUrl: String = DRIVE_BASE_URL

    /** Base URL for Drive v3 upload endpoints. Override in tests. */
    internal var uploadBaseUrl: String = UPLOAD_BASE_URL

    /**
     * When `true` (the default), [delete] moves files to the trash.
     * When `false`, files are permanently deleted.
     */
    internal var useTrash: Boolean = true

    companion object {
        const val DRIVE_BASE_URL = "https://www.googleapis.com/drive/v3"
        const val UPLOAD_BASE_URL = "https://www.googleapis.com/upload/drive/v3"

        /** Chunk size for resumable uploads (8 MiB as per the issue specification). */
        internal const val CHUNK_SIZE = 8 * 1024 * 1024  // 8 MiB

        private const val MAX_RETRIES = 4
        private val CONTENT_TYPE_JSON = "application/json; charset=utf-8".toMediaType()
        private val CONTENT_TYPE_OCTET = "application/octet-stream".toMediaType()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // -------------------------------------------------------------------------
    // Internal API (accessible to GoogleDriveProvider and tests)
    // -------------------------------------------------------------------------

    /**
     * Lists the direct children of [folderId] (or the drive root when [folderId] is `null`),
     * excluding trashed items. Follows `nextPageToken` to collect all pages.
     */
    internal suspend fun list(token: String, folderId: String?): List<DriveFile> {
        val parentId = folderId ?: "root"
        val items = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val urlBuilder = "$driveBaseUrl/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "'$parentId' in parents and trashed=false")
                .addQueryParameter("fields", DRIVE_LIST_FIELDS)
                .addQueryParameter("pageSize", "1000")
            pageToken?.let { urlBuilder.addQueryParameter("pageToken", it) }
            val resp = executeWithRetry(buildGetRequest(urlBuilder.build().toString(), token))
            val page = parseBody<DriveFileList>(resp)
            items += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return items
    }

    /** Fetches the metadata for a single Drive file or folder by [id]. */
    internal suspend fun getMetadata(token: String, id: String): DriveFile {
        val url = "$driveBaseUrl/files/$id".toHttpUrl().newBuilder()
            .addQueryParameter("fields", DRIVE_FILE_FIELDS)
            .build()
        val resp = executeWithRetry(buildGetRequest(url.toString(), token))
        return parseBody(resp)
    }

    /**
     * Opens a download stream for the file identified by [id] via `files.get?alt=media`.
     *
     * The caller owns the returned stream and must close it.
     */
    internal suspend fun download(token: String, id: String): InputStream {
        val url = "$driveBaseUrl/files/$id".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .build()
        val req = buildGetRequest(url.toString(), token)
        val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }
        if (!resp.isSuccessful) {
            val body = resp.body?.string() ?: ""
            resp.close()
            throw DriveApiException(resp.code, body)
        }
        return resp.body!!.byteStream()
    }

    /**
     * Uploads a new file named [name] under parent [parentId] via a resumable upload session.
     *
     * The session is initiated with a POST to `upload/drive/v3/files?uploadType=resumable`,
     * then [content] is uploaded in [CHUNK_SIZE]-byte chunks to the returned location URL.
     */
    internal suspend fun uploadNew(
        token: String,
        parentId: String,
        name: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): DriveFile {
        val metadata = DriveUploadMetadata(
            name = name,
            parents = listOf(parentId),
            mimeType = mimeType,
        )
        val uploadUrl = initiateResumableUpload(
            token = token,
            method = "POST",
            endpoint = "$uploadBaseUrl/files",
            metadata = metadata,
            contentType = mimeType ?: "application/octet-stream",
            size = size,
        )
        return uploadChunked(uploadUrl, content, size)
    }

    /**
     * Replaces the content of an existing file identified by [id] via a resumable upload session.
     *
     * The session is initiated with a PATCH to
     * `upload/drive/v3/files/{id}?uploadType=resumable`, then [content] is uploaded in chunks.
     */
    internal suspend fun updateContent(
        token: String,
        id: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): DriveFile {
        val metadata = DriveUploadMetadata(mimeType = mimeType)
        val uploadUrl = initiateResumableUpload(
            token = token,
            method = "PATCH",
            endpoint = "$uploadBaseUrl/files/$id",
            metadata = metadata,
            contentType = mimeType ?: "application/octet-stream",
            size = size,
        )
        return uploadChunked(uploadUrl, content, size)
    }

    /**
     * Creates a folder named [name] under [parentId].
     */
    internal suspend fun createFolder(token: String, parentId: String, name: String): DriveFile {
        val body = json.encodeToString(
            DriveCreateFolderRequest(name = name, parents = listOf(parentId), mimeType = FOLDER_MIME_TYPE)
        )
        val req = Request.Builder()
            .url("$driveBaseUrl/files")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(CONTENT_TYPE_JSON))
            .build()
        val resp = executeWithRetry(req)
        return parseBody(resp)
    }

    /**
     * Deletes the file identified by [id].
     *
     * When [useTrash] is `true` (the default), the file is moved to the trash
     * (`PATCH files/{id}` with `{"trashed": true}`). When `false`, the file is
     * permanently deleted (`DELETE files/{id}`).
     */
    internal suspend fun delete(token: String, id: String) {
        if (useTrash) {
            val body = json.encodeToString(DriveTrashRequest(trashed = true))
            val req = Request.Builder()
                .url("$driveBaseUrl/files/$id")
                .header("Authorization", "Bearer $token")
                .patch(body.toRequestBody(CONTENT_TYPE_JSON))
                .build()
            val resp = executeWithRetry(req)
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
                resp.close()
                throw DriveApiException(resp.code, errBody)
            }
            resp.close()
        } else {
            val req = Request.Builder()
                .url("$driveBaseUrl/files/$id")
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
            val resp = executeWithRetry(req)
            // Drive returns 204 No Content for a successful permanent delete.
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
                resp.close()
                throw DriveApiException(resp.code, errBody)
            }
            resp.close()
        }
    }

    /**
     * Retrieves incremental changes since [pageToken].
     *
     * - `pageToken == null`: calls `changes/startPageToken` to establish a baseline
     *   without replaying history. Returns an empty change list with the new start token.
     * - `pageToken != null`: follows `nextPageToken` pages until `newStartPageToken`
     *   is present, collecting all changes.
     *
     * @return A pair of (changes, nextPageTokenForFuturePolls).
     * @throws DriveApiException on HTTP errors.
     * @throws IOException on network failures.
     */
    internal suspend fun changesSince(
        token: String,
        pageToken: String?,
    ): Pair<List<DriveChange>, String> {
        if (pageToken == null) {
            // Establish a baseline: get the start page token without replaying history.
            val resp = executeWithRetry(buildGetRequest("$driveBaseUrl/changes/startPageToken", token))
            val startTokenResp = parseBody<DriveStartPageTokenResponse>(resp)
            return Pair(emptyList(), startTokenResp.startPageToken)
        }

        val allChanges = mutableListOf<DriveChange>()
        var currentToken: String? = pageToken
        var finalToken: String? = null

        while (currentToken != null) {
            val urlBuilder = "$driveBaseUrl/changes".toHttpUrl().newBuilder()
                .addQueryParameter("pageToken", currentToken)
                .addQueryParameter("fields", DRIVE_CHANGES_FIELDS)
                .addQueryParameter("includeRemoved", "true")
            val resp = executeWithRetry(buildGetRequest(urlBuilder.build().toString(), token))
            val changesResp = parseBody<DriveChangesListResponse>(resp)
            allChanges += changesResp.changes
            finalToken = changesResp.newStartPageToken
            currentToken = changesResp.nextPageToken
        }

        val nextToken = finalToken
            ?: throw IOException("Drive changes response did not include newStartPageToken")
        return Pair(allChanges, nextToken)
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Builds a GET request with a Bearer Authorization header. */
    private fun buildGetRequest(url: String, token: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

    /**
     * Executes [request] and retries on 429 (Too Many Requests) and 5xx server
     * errors, honouring the `Retry-After` response header when present.
     */
    private suspend fun executeWithRetry(request: Request): okhttp3.Response {
        var backoffMs = 1_000L
        repeat(MAX_RETRIES) { attempt ->
            val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
            val shouldRetry = response.code == 429 || response.code in 500..599
            if (!shouldRetry) return response
            val retryAfterMs =
                response.header("Retry-After")?.toLongOrNull()?.times(1_000) ?: backoffMs
            Timber.w("Drive API ${response.code}; retrying in ${retryAfterMs}ms (attempt ${attempt + 1}/$MAX_RETRIES)")
            response.close()
            delay(retryAfterMs)
            backoffMs = minOf(backoffMs * 2, 32_000L)
        }
        return withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
    }

    /** Parses the response body as [T] and closes the response. Throws [DriveApiException] on error. */
    private inline fun <reified T> parseBody(response: okhttp3.Response): T {
        if (!response.isSuccessful) {
            val body = response.body?.string() ?: ""
            response.close()
            throw DriveApiException(response.code, body)
        }
        val text = response.body!!.use { it.string() }
        return json.decodeFromString(text)
    }

    // -------------------------------------------------------------------------
    // Upload helpers
    // -------------------------------------------------------------------------

    /**
     * Initiates a resumable upload session and returns the upload URL from the
     * `Location` response header.
     *
     * @param token         Bearer access token.
     * @param method        HTTP method for the initiation request (`POST` for new files,
     *                      `PATCH` for updates).
     * @param endpoint      Drive upload endpoint URL (without `?uploadType=resumable`).
     * @param metadata      File metadata to include in the request body.
     * @param contentType   MIME type of the file content.
     * @param size          Exact byte length of the content to be uploaded.
     * @return The upload URL to use for subsequent chunk PUT requests.
     */
    private suspend fun initiateResumableUpload(
        token: String,
        method: String,
        endpoint: String,
        metadata: DriveUploadMetadata,
        contentType: String,
        size: Long,
    ): String {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .build()
        val metaJson = json.encodeToString(metadata)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", contentType)
            .header("X-Upload-Content-Length", size.toString())
            .method(method, metaJson.toRequestBody(CONTENT_TYPE_JSON))
            .build()
        val resp = executeWithRetry(req)
        if (!resp.isSuccessful) {
            val body = resp.body?.string() ?: ""
            resp.close()
            throw DriveApiException(resp.code, body)
        }
        val location = resp.header("Location")
            ?: run {
                resp.close()
                throw IOException("Drive resumable upload initiation response missing Location header")
            }
        resp.close()
        return location
    }

    /**
     * Uploads [content] to an already-created resumable upload session at [uploadUrl] in
     * [CHUNK_SIZE]-byte chunks.
     *
     * On 429 or 5xx responses, the chunk is retried with back-off, honouring `Retry-After`.
     * On [IOException] (network drop), the method queries the upload URL to find the server's
     * next expected range and resumes from there.
     *
     * Drive v3 uses HTTP 308 (Resume Incomplete) to acknowledge intermediate chunks, and
     * 200/201 for the final (committed) chunk.
     *
     * @param uploadUrl Pre-authorized upload session URL (no auth header needed).
     * @param content   Stream to upload; caller must not read from it concurrently.
     * @param totalSize Exact byte length of [content].
     * @return The finalised [DriveFile] from the server's 200/201 response.
     */
    internal suspend fun uploadChunked(
        uploadUrl: String,
        content: InputStream,
        totalSize: Long,
    ): DriveFile {
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

                // The range header uses an inclusive end byte index.
                val end = offset + bytesInBuffer - 1

                var backoffMs = 1_000L
                var attempt = 0
                var chunkCommitted = false

                while (!chunkCommitted) {
                    val chunkBody =
                        chunkBuffer.copyOf(bytesInBuffer).toRequestBody(CONTENT_TYPE_OCTET)
                    val req = Request.Builder()
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
                                // Final chunk — server returns the completed DriveFile.
                                return parseBody(resp)
                            }
                            308 -> {
                                // Drive's "Resume Incomplete": chunk accepted, send the next one.
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
                                throw DriveApiException(resp.code, errBody)
                            }
                        }
                    } catch (e: IOException) {
                        if (e is DriveApiException) throw e
                        if (attempt >= MAX_RETRIES) throw e
                        Timber.w(e, "Upload chunk IOException at offset $offset; querying server")
                        delay(backoffMs)
                        backoffMs = minOf(backoffMs * 2, 32_000L)
                        attempt++

                        // Query the server to find out which byte it expects next.
                        try {
                            val statusReq = Request.Builder()
                                .url(uploadUrl)
                                .put(ByteArray(0).toRequestBody(CONTENT_TYPE_OCTET))
                                .header("Content-Range", "bytes */$totalSize")
                                .build()
                            val statusResp =
                                withContext(Dispatchers.IO) { okHttpClient.newCall(statusReq).execute() }
                            when {
                                statusResp.code == 200 || statusResp.code == 201 -> {
                                    // Server already has the complete file — parse and return.
                                    return parseBody(statusResp)
                                }
                                statusResp.code == 308 -> {
                                    // Range header is "bytes=0-N"; next expected byte is N+1.
                                    val range = statusResp.header("Range")
                                    statusResp.close()
                                    val serverNext =
                                        range?.substringAfter('-')?.toLongOrNull()?.plus(1)
                                    // `end` here is the inclusive last byte of the chunk that
                                    // just failed. serverNext > end+1 means the server already
                                    // committed this chunk (and possibly part of the next one).
                                    if (serverNext != null && serverNext > end + 1) {
                                        // Server committed more data than the client thought —
                                        // skip ahead in the stream and advance the offset.
                                        val toSkip = serverNext - (end + 1)
                                        if (toSkip > 0) {
                                            withContext(Dispatchers.IO) {
                                                stream.skipFully(toSkip)
                                            }
                                        }
                                        offset = serverNext
                                        chunkCommitted = true
                                    }
                                    // If serverNext <= offset, the server hasn't committed
                                    // this chunk yet; retry it from the same offset.
                                }
                                else -> statusResp.close()
                            }
                        } catch (statusEx: Exception) {
                            Timber.w(statusEx, "Could not query Drive upload status")
                        }
                    }

                    if (attempt > MAX_RETRIES && !chunkCommitted) {
                        throw IOException(
                            "Upload chunk at offset $offset failed after $MAX_RETRIES retries"
                        )
                    }
                }
            }
        }
        throw IOException("Upload ended without a 200/201 final response")
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
