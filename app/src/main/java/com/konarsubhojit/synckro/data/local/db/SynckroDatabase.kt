package com.konarsubhojit.synckro.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.konarsubhojit.synckro.data.local.dao.FileIndexDao
import com.konarsubhojit.synckro.data.local.dao.SyncPairDao
import com.konarsubhojit.synckro.data.local.entity.FileIndexEntity
import com.konarsubhojit.synckro.data.local.entity.SyncPairEntity
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.model.ConflictPolicy
import com.konarsubhojit.synckro.domain.model.SyncDirection
import androidx.room.TypeConverter

class EnumConverters {
    @TypeConverter fun dirFromString(s: String): SyncDirection = SyncDirection.valueOf(s)
    @TypeConverter fun dirToString(d: SyncDirection): String = d.name
    @TypeConverter fun policyFromString(s: String): ConflictPolicy = ConflictPolicy.valueOf(s)
    @TypeConverter fun policyToString(p: ConflictPolicy): String = p.name
    @TypeConverter fun providerFromString(s: String): CloudProviderType = CloudProviderType.valueOf(s)
    @TypeConverter fun providerToString(p: CloudProviderType): String = p.name
}

@Database(
    entities = [SyncPairEntity::class, FileIndexEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class)
abstract class SynckroDatabase : RoomDatabase() {
    abstract fun syncPairDao(): SyncPairDao
    abstract fun fileIndexDao(): FileIndexDao

    companion object {
        const val NAME = "synckro.db"
    }
}
