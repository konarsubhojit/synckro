package com.synckro.domain.model

/**
 * A single local-folder ↔ cloud-folder pair managed by the user.
 *
 * @param localTreeUri A persisted Storage Access Framework tree URI
 *   (from `ACTION_OPEN_DOCUMENT_TREE`). Works for both internal storage and
 *   removable SD cards without requiring `MANAGE_EXTERNAL_STORAGE`.
 * @param remoteFolderId Provider-specific opaque folder identifier
 *   (OneDrive `driveItem.id`, Google Drive `file.id`).
 */
data class SyncPair(
    val id: Long,
    val displayName: String,
    val localTreeUri: String,
    val provider: CloudProviderType,
    val remoteFolderId: String,
    val direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.NEWEST_WINS,
    val includeGlobs: List<String> = emptyList(),
    val excludeGlobs: List<String> = emptyList(),
    val wifiOnly: Boolean = true,
    val requiresCharging: Boolean = false,
    /** Whether automatic periodic sync is enabled for this pair. When false the WorkManager
     *  periodic job is cancelled; manual "Sync now" remains available regardless. */
    val autoSyncEnabled: Boolean = true,
    /** Desired periodic sync interval in minutes (minimum 15, WorkManager floor). */
    val scheduleIntervalMinutes: Long = 60,
    /**
     * True when the persisted SAF tree-URI permission for [localTreeUri] is no
     * longer held (e.g. the user revoked it or moved the SD card). Not stored
     * in the database; recomputed at runtime from
     * [ContentResolver.persistedUriPermissions].
     */
    val needsReLink: Boolean = false,
    /** Epoch-milliseconds timestamp of the last completed sync run, or null if never synced. */
    val lastSyncAtMs: Long? = null,
    /** Human-readable outcome of the last sync run: "SUCCESS", "PARTIAL_FAILURE", or "FAILURE". Null if never synced. */
    val lastSyncResult: String? = null,
    /** Opaque delta/page token from the cloud provider, persisted across sync runs. Null until the first sync completes. */
    val deltaToken: String? = null,
    /** Epoch-milliseconds timestamp of the last completed full local scan, or null if never scanned. */
    val lastFullScanAtMs: Long? = null,
)
