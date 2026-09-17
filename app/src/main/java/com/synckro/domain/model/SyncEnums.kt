package com.synckro.domain.model

/** Direction of data flow for a [SyncPair]. */
enum class SyncDirection {
    LOCAL_TO_REMOTE,
    REMOTE_TO_LOCAL,
    BIDIRECTIONAL,

    /**
     * Upload-only backup mode: local files are uploaded to remote but remote
     * changes are never downloaded. After a file has been confirmed in the
     * remote (i.e. it has a remote ID in the index) and its local last-modified
     * time is older than [SyncPair.retentionDays] days, the local copy is
     * deleted. Setting [SyncPair.retentionDays] to `null` disables automatic
     * local deletion, making this equivalent to a pure upload-only mode.
     */
    UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,

    /**
     * Download-only offload mode: remote files are downloaded to local but
     * local changes are never uploaded. After a file has been confirmed in the
     * local index and its remote last-modified time is older than
     * [SyncPair.retentionDays] days, the remote copy is deleted. Setting
     * [SyncPair.retentionDays] to `null` disables automatic remote deletion,
     * making this equivalent to a pure download-only mode.
     */
    DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
}

/**
 * Returns `true` when this direction involves automatic file deletion after a retention period.
 *
 * Both [SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS] and
 * [SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS] are considered destructive because
 * they can permanently remove files without direct user action. Use this property to gate
 * confirmation dialogs, safety checks, and audit-log markers.
 */
val SyncDirection.isDestructive: Boolean
    get() =
        this == SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS ||
            this == SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS

/**
 * Returns `true` when this direction permits upload operations (local → remote).
 *
 * The download-only modes suppress uploads; all other modes allow them. This is the
 * single source of truth shared by the differ, the engine's targeted upload entry
 * point, and the instant-sync watcher.
 */
val SyncDirection.allowsUpload: Boolean
    get() =
        when (this) {
            SyncDirection.LOCAL_TO_REMOTE,
            SyncDirection.BIDIRECTIONAL,
            SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
            -> true
            SyncDirection.REMOTE_TO_LOCAL,
            SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
            -> false
        }

/**
 * Returns `true` when this direction permits download operations (remote → local).
 *
 * The upload-only modes suppress downloads; all other modes allow them.
 */
val SyncDirection.allowsDownload: Boolean
    get() =
        when (this) {
            SyncDirection.REMOTE_TO_LOCAL,
            SyncDirection.BIDIRECTIONAL,
            SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS,
            -> true
            SyncDirection.LOCAL_TO_REMOTE,
            SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS,
            -> false
        }

/** How to resolve a file that was modified on both sides since the last sync. */
enum class ConflictPolicy {
    NEWEST_WINS,
    PREFER_LOCAL,
    PREFER_REMOTE,
    KEEP_BOTH,
}

/** Cloud provider backing a [SyncPair]. */
enum class CloudProviderType {
    /** In-memory provider used for testing and offline development (no auth required). */
    FAKE,
    ONEDRIVE,
    GOOGLE_DRIVE,

    /** Self-hosted WebDAV endpoint (RFC 4918), including Nextcloud and ownCloud. */
    WEBDAV,
}
