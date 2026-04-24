package com.konarsubhojit.synckro.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.konarsubhojit.synckro.data.local.dao.AccountDao
import com.konarsubhojit.synckro.data.local.dao.FileIndexDao
import com.konarsubhojit.synckro.data.local.dao.SyncPairDao
import com.konarsubhojit.synckro.data.local.entity.AccountEntity
import com.konarsubhojit.synckro.data.local.entity.FileIndexEntity
import com.konarsubhojit.synckro.data.local.entity.SyncPairEntity
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.SyncDirection
import androidx.room.TypeConverter

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
    entities = [AccountEntity::class, SyncPairEntity::class, FileIndexEntity::class],
    version = 2,
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

    companion object {
        const val NAME = "synckro.db"

        /**
         * Migrates the database from version 1 to 2 by creating the `account` table.
         * This is the explicit migration required for release builds upgrading from v1.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `account` (" +
                        "`id` TEXT NOT NULL, " +
                        "`providerType` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`email` TEXT, " +
                        "`createdAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }
    }
}
