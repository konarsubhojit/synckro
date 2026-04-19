package com.konarsubhojit.synckro.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.konarsubhojit.synckro.data.local.entity.FileIndexEntity
import com.konarsubhojit.synckro.data.local.entity.SyncPairEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncPairDao {
    @Query("SELECT * FROM sync_pair ORDER BY id ASC")
    fun observeAll(): Flow<List<SyncPairEntity>>

    @Query("SELECT * FROM sync_pair WHERE id = :id")
    suspend fun getById(id: Long): SyncPairEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pair: SyncPairEntity): Long

    @Upsert
    suspend fun upsert(pair: SyncPairEntity)

    @Query("DELETE FROM sync_pair WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface FileIndexDao {
    @Query("SELECT * FROM file_index WHERE pairId = :pairId")
    suspend fun getForPair(pairId: Long): List<FileIndexEntity>

    @Upsert
    suspend fun upsertAll(entries: List<FileIndexEntity>)

    @Query("DELETE FROM file_index WHERE pairId = :pairId AND relativePath = :path")
    suspend fun delete(pairId: Long, path: String)

    @Query("DELETE FROM file_index WHERE pairId = :pairId")
    suspend fun clearForPair(pairId: Long)
}
