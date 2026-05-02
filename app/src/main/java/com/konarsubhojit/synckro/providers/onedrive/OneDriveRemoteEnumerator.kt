package com.konarsubhojit.synckro.providers.onedrive

import com.konarsubhojit.synckro.domain.provider.CloudProviderException
import com.konarsubhojit.synckro.domain.sync.RemoteChange
import com.konarsubhojit.synckro.domain.sync.RemoteChangeType
import com.konarsubhojit.synckro.domain.sync.RemoteEnumerator
import com.konarsubhojit.synckro.domain.sync.RemoteSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * [RemoteEnumerator] for OneDrive, backed by [OneDriveGraphClient.changesSince].
 *
 * Behaviour:
 * - On the first call (`deltaToken == null`), the underlying client requests
 *   `root/delta?$deltaToken=latest`, which establishes a baseline without
 *   replaying history. The returned snapshot has an empty change list and the
 *   initial `@odata.deltaLink` as the new token.
 * - On subsequent calls the persisted deltaLink is followed (with
 *   `@odata.nextLink` paging) and all collected items are mapped to
 *   [RemoteChange] entries.
 *
 * Items with a `deleted` facet become [RemoteChangeType.DELETE]; all other
 * items become [RemoteChangeType.MODIFY] (the diff stage classifies ADD vs
 * MODIFY against the local index).
 */
@Singleton
class OneDriveRemoteEnumerator @Inject constructor(
    private val provider: OneDriveProvider,
    private val graphClient: OneDriveGraphClient,
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
     * point [graphClient] at a `MockWebServer` and exercise the full HTTP
     * round-trip without going through MSAL.
     */
    internal suspend fun enumerateWithToken(
        token: String,
        deltaToken: String?,
    ): RemoteSnapshot {
        val (items, nextDeltaLink) = try {
            graphClient.changesSince(token, deltaToken)
        } catch (e: GraphApiException) {
            when (e.statusCode) {
                401 -> throw CloudProviderException.AuthenticationRequired(
                    "OneDrive access token rejected by Graph API (401). Please re-authenticate.",
                    e,
                )
                410 -> {
                    // Delta link has expired — fall back to a fresh baseline so the next
                    // sync starts from a clean slate without replaying history.
                    // This is a normal operational scenario: delta links expire after ~30
                    // days of inactivity per the Microsoft Graph API contract.
                    Timber.w("OneDrive: delta link expired (410); deltaToken=%s; falling back to baseline", deltaToken)
                    val (baseItems, baseDeltaLink) = graphClient.changesSince(token, null)
                    return RemoteSnapshot(
                        changes = baseItems.mapNotNull { it.toRemoteChange() },
                        newDeltaToken = baseDeltaLink,
                    )
                }
                else -> throw e
            }
        }

        val changes = items.mapNotNull { it.toRemoteChange() }
        return RemoteSnapshot(changes = changes, newDeltaToken = nextDeltaLink)
    }
}

/**
 * Maps a Graph API [GraphDriveItem] from a delta response to a [RemoteChange].
 * Returns `null` for items with no usable identifier.
 */
internal fun GraphDriveItem.toRemoteChange(): RemoteChange? {
    if (id.isEmpty()) return null
    return if (deleted != null) {
        RemoteChange(
            relativePath = name.ifEmpty { id },
            type = RemoteChangeType.DELETE,
            remoteId = id,
        )
    } else {
        RemoteChange(
            relativePath = name,
            type = RemoteChangeType.MODIFY,
            remoteId = id,
            sizeBytes = size,
            mtimeMs = lastModifiedDateTime?.let { parseIso8601(it) },
            etag = eTag?.trim('"'),
        )
    }
}
