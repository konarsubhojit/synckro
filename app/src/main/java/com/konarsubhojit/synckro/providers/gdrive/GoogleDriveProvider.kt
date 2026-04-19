package com.konarsubhojit.synckro.providers.gdrive

import com.konarsubhojit.synckro.domain.provider.ChangesPage
import com.konarsubhojit.synckro.domain.provider.CloudProvider
import com.konarsubhojit.synckro.domain.provider.RemoteFile
import com.konarsubhojit.synckro.providers.onedrive.NotYetImplementedException
import java.io.InputStream

/**
 * Google Drive provider backed by the Drive REST v3 API.
 *
 * All methods currently throw [NotYetImplementedException] so the sync worker
 * can treat them as a terminal configuration error instead of retrying
 * forever. See the OneDrive provider for the rationale.
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

    private fun unsupported(op: String): Nothing =
        throw NotYetImplementedException("GoogleDriveProvider.$op is not implemented yet")

    override suspend fun ensureAuthenticated(): Boolean = unsupported("ensureAuthenticated")
    override suspend fun list(folderId: String?): List<RemoteFile> = unsupported("list")
    override suspend fun getMetadata(id: String): RemoteFile = unsupported("getMetadata")
    override suspend fun download(id: String): InputStream = unsupported("download")

    override suspend fun uploadNew(
        parentId: String,
        name: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile = unsupported("uploadNew")

    override suspend fun updateContent(
        id: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile = unsupported("updateContent")

    override suspend fun createFolder(parentId: String, name: String): RemoteFile =
        unsupported("createFolder")

    override suspend fun delete(id: String): Unit = unsupported("delete")

    override suspend fun changesSince(token: String?): ChangesPage = unsupported("changesSince")
}
