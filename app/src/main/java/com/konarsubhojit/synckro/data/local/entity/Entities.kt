package com.konarsubhojit.synckro.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.SyncDirection

@Entity(tableName = "sync_pair")
data class SyncPairEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val localTreeUri: String,
    val provider: CloudProviderType,
    val remoteFolderId: String,
    val direction: SyncDirection,
    val conflictPolicy: ConflictPolicy,
    val includeGlobs: String, // newline-separated
    val excludeGlobs: String,
    val wifiOnly: Boolean,
    val requiresCharging: Boolean,
    /** Opaque delta/changes token from the provider. */
    val lastDeltaToken: String? = null,
    val lastSyncAtMs: Long? = null,
)

@Entity(
    tableName = "file_index",
    primaryKeys = ["pairId", "relativePath"],
    indices = [Index(value = ["pairId"])],
)
data class FileIndexEntity(
    val pairId: Long,
    val relativePath: String,
    val localSize: Long,
    val localLastModifiedMs: Long,
    val localHash: String?,
    val remoteId: String?,
    val remoteETag: String?,
    val remoteSize: Long?,
    val remoteLastModifiedMs: Long?,
)
