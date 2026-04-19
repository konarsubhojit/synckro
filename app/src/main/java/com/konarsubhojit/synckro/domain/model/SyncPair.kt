package com.konarsubhojit.synckro.domain.model

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
)
