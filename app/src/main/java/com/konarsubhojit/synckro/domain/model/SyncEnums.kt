package com.konarsubhojit.synckro.domain.model

/** Direction of data flow for a [SyncPair]. */
enum class SyncDirection {
    LOCAL_TO_REMOTE,
    REMOTE_TO_LOCAL,
    BIDIRECTIONAL,
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
    ONEDRIVE,
    GOOGLE_DRIVE,
}
