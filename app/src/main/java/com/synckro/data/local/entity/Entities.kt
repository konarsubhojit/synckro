package com.synckro.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection

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
    /** Epoch-milliseconds timestamp of the last completed full local scan, or null if never scanned. */
    val lastFullScanAtMs: Long? = null,
    /**
     * Number of days after which source files may be deleted by the retention-cleanup
     * sync modes. Null disables automatic deletion. See [SyncPair.retentionDays].
     */
    val retentionDays: Int? = null,
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

/**
 * A lightweight local-file snapshot stored in the `local_index` table, used to
 * compute diffs between sync runs without re-hashing every file.
 *
 * Uses a composite primary key on `(pairId, relativePath)`, which enforces
 * uniqueness and acts as the upsert key for [LocalIndexDao].
 *
 * Rows are removed automatically (CASCADE) when the parent [SyncPairEntity] is
 * deleted.
 *
 * Remote metadata fields ([remoteSizeBytes], [remoteMtimeMs], [remoteEtag]) are
 * populated after each successful sync operation by [com.synckro.domain.sync.SyncOpApplier].
 * They allow [com.synckro.domain.sync.SyncDiffer] to distinguish a genuine
 * remote change from an echo of a locally-initiated upload in the provider's change log.
 */
@Entity(
    tableName = "local_index",
    primaryKeys = ["pairId", "relativePath"],
    foreignKeys = [
        ForeignKey(
            entity = SyncPairEntity::class,
            parentColumns = ["id"],
            childColumns = ["pairId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LocalIndexEntity(
    val pairId: Long,
    val relativePath: String,
    val sizeBytes: Long,
    val mtimeMs: Long,
    /** Optional content hash (e.g. SHA-256 or provider quickXorHash). */
    val contentHash: String? = null,
    /** Provider-specific remote item ID; null until the item has been synced at least once. */
    val remoteId: String? = null,
    /** Remote file size in bytes, as reported by the provider on the last successful sync. */
    val remoteSizeBytes: Long? = null,
    /** Remote last-modified timestamp (epoch ms) from the last successful sync. */
    val remoteMtimeMs: Long? = null,
    /** Provider content fingerprint (ETag / md5Checksum) from the last successful sync. */
    val remoteEtag: String? = null,
)
