package com.konarsubhojit.synckro.providers.onedrive

import com.konarsubhojit.synckro.domain.provider.ChangesPage
import com.konarsubhojit.synckro.domain.provider.CloudProvider
import com.konarsubhojit.synckro.domain.provider.RemoteFile
import java.io.InputStream

/**
 * OneDrive provider backed by the Microsoft Graph API.
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

    override suspend fun ensureAuthenticated(): Boolean = TODO("MSAL token acquisition")

    override suspend fun list(folderId: String?): List<RemoteFile> =
        TODO("GET /me/drive/items/{id}/children")

    override suspend fun getMetadata(id: String): RemoteFile =
        TODO("GET /me/drive/items/{id}")

    override suspend fun download(id: String): InputStream =
        TODO("GET /me/drive/items/{id}/content")

    override suspend fun uploadNew(
        parentId: String,
        name: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile = TODO("Create upload session and chunked PUT")

    override suspend fun updateContent(
        id: String,
        content: InputStream,
        size: Long,
        mimeType: String?,
    ): RemoteFile = TODO("Create upload session against existing item id")

    override suspend fun createFolder(parentId: String, name: String): RemoteFile =
        TODO("POST /me/drive/items/{parentId}/children with folder facet")

    override suspend fun delete(id: String): Unit = TODO("DELETE /me/drive/items/{id}")

    override suspend fun changesSince(token: String?): ChangesPage =
        TODO("GET /me/drive/root/delta (or continue with the token)")
}
