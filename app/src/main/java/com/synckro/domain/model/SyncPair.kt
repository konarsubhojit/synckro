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
    /**
     * The id of the [com.synckro.domain.auth.Account] this pair is bound to,
     * or `null` if the pair has not yet been linked to a specific account
     * (for example, pairs created before multi-account support, or pairs whose
     * account was disconnected). The UI surfaces null-accountId pairs as
     * "needs re-link".
     */
    val accountId: String? = null,
    val remoteFolderId: String,
    /**
     * Human-readable display name of the cloud folder selected by the user
     * (e.g. "Camera Roll", "Documents/Receipts"). Persisted at pair-creation
     * time from the remote folder picker so the Synced Folders / Status UI
     * can render a meaningful label instead of the opaque provider id once a
     * sync run starts. `null` for pairs created before this field existed —
     * callers should fall back to [remoteFolderId] in that case.
     */
    val remoteFolderName: String? = null,
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
    /**
     * Number of days after which source files may be deleted in retention-cleanup modes.
     *
     * Only meaningful for [SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS] and
     * [SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS]. A value of `null`
     * disables automatic deletion even in those modes. A value of `0` requests
     * immediate deletion on the first sync (not recommended — the UI should
     * require explicit confirmation before saving zero).
     *
     * The retention clock is based on the source file's last-modified timestamp
     * (local mtime for upload modes, remote mtime for download modes).
     */
    val retentionDays: Int? = null,
    /**
     * When `true`, only files located directly inside the sync root folder are
     * included in the sync scope.  Nested sub-directories (and all files within
     * them) are ignored on both the local and remote sides.  Combine with
     * [excludeGlobs] for fine-grained path-based filtering.
     */
    val excludeSubfolders: Boolean = false,
    /**
     * When `true`, empty directories are excluded from the sync scope.
     *
     * **Remote side**: folder entries reported by the remote enumerator are
     * explicitly filtered out of the delta before the sync differ sees them,
     * preventing empty remote folders from being processed as sync operations.
     *
     * **Local side**: [LocalFsEnumerator] already only emits file entries;
     * empty local directories produce no snapshot entries and therefore never
     * trigger remote folder creation (folders are created on-demand by
     * [SyncOpApplier.ensureRemoteFolderPath] only when a file needs to be
     * uploaded into them).
     */
    val excludeEmptyFolders: Boolean = false,
)
