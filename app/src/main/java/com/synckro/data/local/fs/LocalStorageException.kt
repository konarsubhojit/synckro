package com.synckro.data.local.fs

/**
 * Thrown when a SAF document-tree query fails due to a revoked URI permission,
 * unavailable storage, or any other condition that prevents the app from reading
 * the local folder.
 *
 * The sync pipeline catches this at the [com.synckro.domain.sync.SyncEngine] level
 * and converts it to a [com.synckro.domain.sync.SyncEngine.Result.Terminal] with
 * `needsReLink = true`, so the run is aborted rather than interpreting the missing
 * data as a genuinely empty folder.  The user is then prompted to re-link (re-grant
 * SAF permission for) the local folder.
 */
class LocalStorageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
