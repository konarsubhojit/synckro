package com.synckro.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.synckro.data.local.dao.AccountDao
import com.synckro.data.local.dao.ConflictRecordDao
import com.synckro.data.local.dao.FileIndexDao
import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.dao.SyncEventDao
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.entity.AccountEntity
import com.synckro.data.local.entity.ConflictRecordEntity
import com.synckro.data.local.entity.FileIndexEntity
import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.data.local.entity.SyncEventEntity
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection

class EnumConverters {
    /**
     * Converts a stored enum name into the corresponding SyncDirection.
     *
     * @param s The enum name as stored in the database.
     * @return The `SyncDirection` represented by `s`.
     */
    @TypeConverter fun dirFromString(s: String): SyncDirection = SyncDirection.valueOf(s)

    /**
     * Convert a SyncDirection to its persisted string representation.
     *
     * @return The enum constant's name as stored in the database.
     */
    @TypeConverter fun dirToString(d: SyncDirection): String = d.name

    /**
     * Converts a persisted string into a ConflictPolicy enum.
     *
     * @param s The persisted enum name.
     * @return The ConflictPolicy corresponding to the provided enum name.
     */
    @TypeConverter fun policyFromString(s: String): ConflictPolicy = ConflictPolicy.valueOf(s)

    /**
     * Converts a ConflictPolicy enum value to its persisted string representation.
     *
     * @param p The conflict resolution policy to convert.
     * @return The enum's name used for storage.
     */
    @TypeConverter fun policyToString(p: ConflictPolicy): String = p.name

    /**
     * Converts a persisted enum name into its corresponding CloudProviderType.
     *
     * @param s The enum name as stored in the database.
     * @return The matching CloudProviderType.
     * @throws IllegalArgumentException If `s` does not match any CloudProviderType constant.
     */
    @TypeConverter fun providerFromString(s: String): CloudProviderType = CloudProviderType.valueOf(s)

    /**
     * Converts a CloudProviderType to its persisted string representation.
     *
     * @param p The cloud provider enum to convert.
     * @return The enum's name as stored in the database.
     */
    @TypeConverter fun providerToString(p: CloudProviderType): String = p.name
}

@Database(
    entities = [AccountEntity::class, SyncPairEntity::class, FileIndexEntity::class, SyncEventEntity::class, ConflictRecordEntity::class, LocalIndexEntity::class],
    version = 12,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class)
abstract class SynckroDatabase : RoomDatabase() {
    /**
     * Returns the DAO used to access and modify account entities.
     *
     * @return The [AccountDao] for performing operations on account data.
     */
    abstract fun accountDao(): AccountDao

    /**
     * Returns the DAO used to access and modify sync pair entities.
     *
     * @return The [SyncPairDao] for performing operations on sync pair data.
     */
    abstract fun syncPairDao(): SyncPairDao

    /**
     * Provides the DAO for performing CRUD operations on file index records.
     *
     * @return The {@link FileIndexDao} used to access and modify file index data.
     */
    abstract fun fileIndexDao(): FileIndexDao

    /**
     * Returns the DAO used to access and modify conflict record entities.
     *
     * @return The [ConflictRecordDao] for performing operations on conflict inbox data.
     */
    abstract fun conflictRecordDao(): ConflictRecordDao

    /**
     * Returns the DAO for reading and writing structured sync-event log entries.
     *
     * @return The [SyncEventDao] for the `sync_event` table.
     */
    abstract fun syncEventDao(): SyncEventDao

    /**
     * Returns the DAO used to access and modify local-index entries.
     *
     * @return The [LocalIndexDao] for the `local_index` table.
     */
    abstract fun localIndexDao(): LocalIndexDao

    companion object {
        const val NAME = "synckro.db"

        /**
         * Migrates the database from version 1 to 2 by creating the `account` table.
         * This is the explicit migration required for release builds upgrading from v1.
         */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `account` (" +
                            "`id` TEXT NOT NULL, " +
                            "`providerType` TEXT NOT NULL, " +
                            "`displayName` TEXT NOT NULL, " +
                            "`email` TEXT, " +
                            "`createdAtMillis` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`))",
                    )
                }
            }

        /**
         * Migrates the database from version 2 to 3 by adding the `mimeType` column
         * to `file_index`.  Existing rows will have NULL for the new column.
         */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `file_index` ADD COLUMN `mimeType` TEXT")
                }
            }

        /**
         * Migrates the database from version 3 to 4 by adding the `lastSyncResult` column
         * to `sync_pair`. Existing rows will have NULL for the new column.
         */
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sync_pair` ADD COLUMN `lastSyncResult` TEXT")
                }
            }

        /**
         * Migrates the database from version 4 to 5 by creating the `sync_event` table.
         * This table stores structured log entries associated with sync-pair runs.
         */
        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sync_event` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`pairId` INTEGER, " +
                            "`timestampMs` INTEGER NOT NULL, " +
                            "`level` TEXT NOT NULL, " +
                            "`tag` TEXT NOT NULL, " +
                            "`message` TEXT NOT NULL, " +
                            "FOREIGN KEY(`pairId`) REFERENCES `sync_pair`(`id`) ON DELETE CASCADE)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_event_pairId` ON `sync_event` (`pairId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_event_timestampMs` ON `sync_event` (`timestampMs`)")
                }
            }

        /**
         * Migrates the database from version 5 to 6:
         * - Adds `scheduleIntervalMinutes` column to `sync_pair` (default 60 minutes).
         * - Creates the `conflict_record` table for the conflict inbox with a unique
         *   constraint on `(pairId, relativePath)` to prevent duplicate entries.
         */
        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `sync_pair` ADD COLUMN `scheduleIntervalMinutes` INTEGER NOT NULL DEFAULT 60",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `conflict_record` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`pairId` INTEGER NOT NULL, " +
                            "`relativePath` TEXT NOT NULL, " +
                            "`localLastModifiedMs` INTEGER NOT NULL, " +
                            "`remoteLastModifiedMs` INTEGER NOT NULL, " +
                            "`detectedAtMs` INTEGER NOT NULL, " +
                            "`resolution` TEXT, " +
                            "FOREIGN KEY(`pairId`) REFERENCES `sync_pair`(`id`) ON DELETE CASCADE, " +
                            "UNIQUE(`pairId`, `relativePath`))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_conflict_record_pairId` " +
                            "ON `conflict_record` (`pairId`)",
                    )
                }
            }

        /**
         * Migrates the database from version 6 to 7:
         * - Adds `lastFullScanAtMs` column to `sync_pair` (epoch-ms timestamp of the last full
         *   local scan; NULL until the first scan completes).
         * - Creates the `local_index` table for lightweight per-file local snapshots used by
         *   the sync engine to compute diffs without re-hashing every file on every run.
         */
        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `sync_pair` ADD COLUMN `lastFullScanAtMs` INTEGER",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `local_index` (" +
                            "`pairId` INTEGER NOT NULL, " +
                            "`relativePath` TEXT NOT NULL, " +
                            "`sizeBytes` INTEGER NOT NULL, " +
                            "`mtimeMs` INTEGER NOT NULL, " +
                            "`contentHash` TEXT, " +
                            "`remoteId` TEXT, " +
                            "PRIMARY KEY(`pairId`, `relativePath`), " +
                            "FOREIGN KEY(`pairId`) REFERENCES `sync_pair`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE)",
                    )
                }
            }

        /**
         * Migrates the database from version 7 to 8:
         * - Adds three nullable remote-metadata columns to `local_index`:
         *   `remoteSizeBytes`, `remoteMtimeMs`, `remoteEtag`.
         * These columns let [com.synckro.domain.sync.SyncDiffer] distinguish
         * genuine remote changes from echoes of locally-initiated uploads in the provider's
         * change log.  Existing rows receive NULL for all three columns; they will be
         * populated by [com.synckro.domain.sync.SyncOpApplier] on the next sync.
         */
        val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `local_index` ADD COLUMN `remoteSizeBytes` INTEGER")
                    db.execSQL("ALTER TABLE `local_index` ADD COLUMN `remoteMtimeMs` INTEGER")
                    db.execSQL("ALTER TABLE `local_index` ADD COLUMN `remoteEtag` TEXT")
                }
            }

        /**
         * Migrates the database from version 8 to 9:
         * - Adds `autoSyncEnabled` column to `sync_pair` (default 1 = enabled).
         *   Existing pairs default to auto-sync enabled so their schedules keep running.
         */
        val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `sync_pair` ADD COLUMN `autoSyncEnabled` INTEGER NOT NULL DEFAULT 1",
                    )
                }
            }

        /**
         * Migrates the database from version 9 to 10:
         * - Adds `retentionDays` nullable column to `sync_pair`.
         *   Stores the per-pair retention period (in days) used by the
         *   [com.synckro.domain.model.SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS] and
         *   [com.synckro.domain.model.SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS] modes.
         *   Existing rows receive NULL, meaning no automatic deletion is performed.
         */
        val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sync_pair` ADD COLUMN `retentionDays` INTEGER")
                }
            }

        /**
         * Migrates the database from version 10 to 11:
         * - Adds `excludeSubfolders` column to `sync_pair` (default 0 = false).
         *   When true, only root-level files are synced; nested sub-directories are
         *   excluded from both local enumeration and remote delta inputs.
         * - Adds `excludeEmptyFolders` column to `sync_pair` (default 0 = false).
         *   When true, empty directories are excluded from the sync scope.
         *   Existing rows keep the previous behaviour (false for both flags).
         */
        val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `sync_pair` ADD COLUMN `excludeSubfolders` INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "ALTER TABLE `sync_pair` ADD COLUMN `excludeEmptyFolders` INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        /**
         * Migrates the database from version 11 to 12 (multi-account support):
         * - Adds nullable `accountId` column to `sync_pair` so each pair can be
         *   bound to a specific [com.synckro.data.local.entity.AccountEntity].
         * - Backfills `accountId` for existing pairs from the (formerly implicit)
         *   single-account-per-provider assumption: pairs whose provider has
         *   exactly one persisted account get that account's id. Pairs whose
         *   provider has zero accounts at upgrade time keep `accountId = NULL`
         *   and are surfaced as "needs re-link" by the UI. The
         *   `WHERE (SELECT COUNT(*) ...) = 1` guard intentionally leaves pairs
         *   ambiguous when multiple accounts already exist for the same provider
         *   so the user can pick one explicitly.
         * - Adds the `index_sync_pair_accountId` index used by Room for the
         *   `@Index("accountId")` declaration on the entity.
         */
        val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sync_pair` ADD COLUMN `accountId` TEXT")
                    db.execSQL(
                        "UPDATE `sync_pair` SET `accountId` = (" +
                            "SELECT a.id FROM `account` a " +
                            "WHERE a.providerType = `sync_pair`.provider LIMIT 1) " +
                            "WHERE (SELECT COUNT(*) FROM `account` a " +
                            "WHERE a.providerType = `sync_pair`.provider) = 1",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_sync_pair_accountId` " +
                            "ON `sync_pair` (`accountId`)",
                    )
                }
            }
    }
}
