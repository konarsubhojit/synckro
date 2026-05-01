package com.konarsubhojit.synckro.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.SyncDirection

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: String,
    val providerType: CloudProviderType,
    val displayName: String,
    val email: String?,
    val createdAtMillis: Long,
)

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
    /** Human-readable outcome of the last sync run: "SUCCESS", "PARTIAL_FAILURE", or "FAILURE". */
    val lastSyncResult: String? = null,
    /** Desired periodic sync interval in minutes (minimum 15, WorkManager floor). */
    val scheduleIntervalMinutes: Long = 60,
)

@Entity(
    tableName = "conflict_record",
    foreignKeys = [
        ForeignKey(
            entity = SyncPairEntity::class,
            parentColumns = ["id"],
            childColumns = ["pairId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pairId"), Index(value = ["pairId", "relativePath"], unique = true)],
)
data class ConflictRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pairId: Long,
    val relativePath: String,
    val localLastModifiedMs: Long,
    val remoteLastModifiedMs: Long,
    val detectedAtMs: Long,
    /** Resolution chosen by the user: null = pending, "KEEP_LOCAL", "KEEP_REMOTE", "KEEP_BOTH". */
    val resolution: String? = null,
)

@Entity(
    tableName = "file_index",
    primaryKeys = ["pairId", "relativePath"],
    // The composite PK's leading column already indexes `pairId`; a
    // standalone index would only duplicate it. A foreign key with cascading
    // delete keeps the index consistent when a sync pair is removed.
    foreignKeys = [
        ForeignKey(
            entity = SyncPairEntity::class,
            parentColumns = ["id"],
            childColumns = ["pairId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
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
    /** MIME type as reported by the SAF content provider. Null for directories or when unknown. */
    val mimeType: String? = null,
)
