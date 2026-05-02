package com.synckro.providers.gdrive

import com.synckro.domain.provider.CloudProviderException
import com.synckro.domain.sync.RemoteChange
import com.synckro.domain.sync.RemoteChangeType
import com.synckro.domain.sync.RemoteEnumerator
import com.synckro.domain.sync.RemoteSnapshot
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RemoteEnumerator] for Google Drive, backed by
 * [GoogleDriveRestClient.changesSince].
 *
 * Behaviour:
 * - On the first call (`deltaToken == null`), the underlying client requests
 *   `changes/startPageToken` to obtain a fresh baseline token without
 *   replaying history. The returned snapshot has an empty change list and the
 *   `startPageToken` as the new token.
 * - On subsequent calls the persisted page token is used; the client follows
 *   `nextPageToken` until `newStartPageToken` is present, collecting all
 *   changes.
 *
 * Items flagged `removed=true` or `file.trashed=true` become
 * [RemoteChangeType.DELETE]; all other items become [RemoteChangeType.MODIFY]
 * (the diff stage classifies ADD vs MODIFY against the local index).
 */
@Singleton
class GoogleDriveRemoteEnumerator
    @Inject
    constructor(
        private val provider: GoogleDriveProvider,
        private val restClient: GoogleDriveRestClient,
    ) : RemoteEnumerator {
        /**
         * Acquires an access token via [provider] and delegates to
         * [enumerateWithToken]. Authentication errors are surfaced as
         * [CloudProviderException] subtypes.
         */
        override suspend fun enumerate(deltaToken: String?): RemoteSnapshot {
            val token = provider.obtainAccessToken()
            return enumerateWithToken(token, deltaToken)
        }

        /**
         * Test seam: enumerate using a directly-supplied bearer token. Tests can
         * point [restClient] at a `MockWebServer` and exercise the full HTTP
         * round-trip without going through Google Identity.
         */
        internal suspend fun enumerateWithToken(
            token: String,
            deltaToken: String?,
        ): RemoteSnapshot {
            val (changes, nextToken) =
                try {
                    restClient.changesSince(token, deltaToken)
                } catch (e: DriveApiException) {
                    when (e.statusCode) {
                        401 -> throw CloudProviderException.AuthenticationRequired(
                            "Google Drive access token rejected (401). Please re-authenticate.",
                            e,
                        )
                        410 -> {
                            // Page token has expired — fall back to a fresh baseline so the next
                            // sync starts from a clean slate without replaying history.
                            // This is a normal operational scenario: tokens expire after ~7 days
                            // of inactivity per the Google Drive API contract.
                            Timber.w("Google Drive: changes page token expired (410); deltaToken=%s; falling back to baseline", deltaToken)
                            val (baseChanges, baseToken) = restClient.changesSince(token, null)
                            return RemoteSnapshot(
                                changes = baseChanges.mapNotNull { it.toRemoteChange() },
                                newDeltaToken = baseToken,
                            )
                        }
                        else -> throw e
                    }
                }

            val mapped = changes.mapNotNull { it.toRemoteChange() }
            return RemoteSnapshot(changes = mapped, newDeltaToken = nextToken)
        }
    }

/**
 * Maps a Drive v3 [DriveChange] entry to a [RemoteChange]. Returns `null` for
 * entries without a usable identifier.
 */
internal fun DriveChange.toRemoteChange(): RemoteChange? {
    val id = fileId ?: file?.id ?: return null
    val isRemoved = removed == true || file?.trashed == true
    if (isRemoved) {
        return RemoteChange(
            relativePath = file?.name ?: id,
            type = RemoteChangeType.DELETE,
            remoteId = id,
        )
    }
    val f = file ?: return null
    return RemoteChange(
        relativePath = f.name,
        type = RemoteChangeType.MODIFY,
        remoteId = id,
        sizeBytes = f.size?.toLongOrNull(),
        mtimeMs = f.modifiedTime?.let { parseIso8601(it) },
        etag = f.md5Checksum,
    )
}
