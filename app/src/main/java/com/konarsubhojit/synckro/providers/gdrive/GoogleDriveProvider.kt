package com.konarsubhojit.synckro.providers.gdrive

import com.konarsubhojit.synckro.domain.provider.ChangesPage
import com.konarsubhojit.synckro.domain.provider.CloudProvider
import com.konarsubhojit.synckro.domain.provider.RemoteFile
import java.io.InputStream

/**
 * Google Drive provider backed by the Drive REST v3 API.
 *
 * TODO (next milestones):
 *  - Sign in with Credential Manager + Google Identity Services; request scope
 *    `https://www.googleapis.com/auth/drive.file` (preferred) or
 *    `drive` if the user needs to select pre-existing folders.
 *  - Store refresh token in EncryptedSharedPreferences.
 *  - Call Drive v3 endpoints under `https://www.googleapis.com/drive/v3/`:
 *      - `files.list` with `q='parentId' in parents` for listing.
 *      - `files.get?alt=media` for download.
 *      - `upload/drive/v3/files?uploadType=resumable` for resumable upload.
 *      - `files.update?uploadType=resumable` for overwrite.
 *      - `changes.list` with `startPageToken` for incremental sync.
 *  - Retry 429/5xx with exponential backoff honoring `Retry-After`.
 */
class GoogleDriveProvider : CloudProvider {
    override val displayName: String = "Google Drive"

    override suspend fun ensureAuthenticated(): Boolean = TODO("OAuth + access token")

    override suspend fun list(folderId: String?): List<RemoteFile> =
        TODO("files.list with parent filter")

    override suspend fun getMetadata(id: String): RemoteFile = TODO("files.get")

    override suspend fun download(id: String): InputStream =
        TODO("files.get?alt=media")

    override suspend fun uploadNew(
        parentId: String,
        name: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile = TODO("Resumable multipart upload")

    override suspend fun updateContent(
        id: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile = TODO("files.update resumable upload")

    override suspend fun createFolder(parentId: String, name: String): RemoteFile =
        TODO("files.create with mimeType application/vnd.google-apps.folder")

    override suspend fun delete(id: String): Unit = TODO("files.delete")

    override suspend fun changesSince(token: String?): ChangesPage =
        TODO("changes.list; if token is null fetch changes.getStartPageToken")
}
