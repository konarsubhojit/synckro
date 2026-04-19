package com.konarsubhojit.synckro.providers.onedrive

import com.konarsubhojit.synckro.domain.provider.ChangesPage
import com.konarsubhojit.synckro.domain.provider.CloudProvider
import com.konarsubhojit.synckro.domain.provider.RemoteFile
import java.io.InputStream

/**
 * OneDrive provider backed by the Microsoft Graph API.
 *
 * All methods currently throw [NotYetImplementedException] so callers
 * (notably [com.konarsubhojit.synckro.data.worker.SyncWorker]) can treat them
 * as a terminal configuration error instead of retrying forever. This type is
 * intentionally distinct from Kotlin's `NotImplementedError` (which is a
 * subclass of `Error` and should not be caught in production code).
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

/**
 * Thrown by provider stubs to signal that the requested operation hasn't been
 * implemented yet. Distinct from `kotlin.NotImplementedError` (an [Error])
 * because callers MAY catch it and treat it as a terminal sync result.
 */
class NotYetImplementedException(message: String) : UnsupportedOperationException(message)
