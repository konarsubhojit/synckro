package com.konarsubhojit.synckro.providers.onedrive

import com.konarsubhojit.synckro.domain.provider.ChangesPage
import com.konarsubhojit.synckro.domain.provider.CloudProvider
import com.konarsubhojit.synckro.domain.provider.NotYetImplementedException
import com.konarsubhojit.synckro.domain.provider.RemoteFile
import java.io.InputStream

/**
 * OneDrive provider backed by the Microsoft Graph API.
 *
 * [ensureAuthenticated] returns false for now and the remaining operations
 * throw [NotYetImplementedException] so callers (notably
 * [com.konarsubhojit.synckro.data.worker.SyncWorker]) can treat them as a
 * terminal configuration error instead of retrying forever.
 *
 * TODO (next milestones):
 *  - Add MSAL dependency (`com.microsoft.identity.client:msal`) + Azure AD
 *    client registration and `res/raw/msal_config.json`.
 *  - Acquire tokens silently via MSAL, fall back to interactive flow.
 *  - Call Graph endpoints under `https://graph.microsoft.com/v1.0/me/drive`:
 *      - `/items/{id}:/children` for list
 *      - `/items/{id}/content` for download
 *      - Upload sessions (`/items/{id}:/{name}:/createUploadSession`) for
 *        files > 4 MB; simple PUT for small files.
 *      - `/items/{id}:/delta` (or `/root/delta`) for incremental changes.
 *  - Retry 429/5xx with backoff honoring `Retry-After`.
 */
class OneDriveProvider : CloudProvider {
    override val displayName: String = "OneDrive"

    private fun unsupported(op: String): Nothing =
        throw NotYetImplementedException("OneDriveProvider.$op is not implemented yet")

    private fun unsupported(op: String, content: InputStream): Nothing {
        try {
            throw NotYetImplementedException("OneDriveProvider.$op is not implemented yet")
        } finally {
            content.close()
        }
    }

    override suspend fun ensureAuthenticated(): Boolean = false
    override suspend fun list(folderId: String?): List<RemoteFile> = unsupported("list")
    override suspend fun getMetadata(id: String): RemoteFile = unsupported("getMetadata")
    override suspend fun download(id: String): InputStream = unsupported("download")

    override suspend fun uploadNew(
        parentId: String,
        name: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile = unsupported("uploadNew", content)

    override suspend fun updateContent(
        id: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile = unsupported("updateContent", content)

    override suspend fun createFolder(parentId: String, name: String): RemoteFile =
        unsupported("createFolder")

    override suspend fun delete(id: String): Unit = unsupported("delete")

    override suspend fun changesSince(token: String?): ChangesPage = unsupported("changesSince")
}
