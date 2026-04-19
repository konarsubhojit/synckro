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
    /**
     * Observes all rows in the `sync_pair` table ordered by `id` ascending.
     *
     * Emits the current list of `SyncPairEntity` rows and subsequent updates whenever the table changes.
     *
     * @return A Flow that emits lists of `SyncPairEntity` containing all rows ordered by `id` ascending; emits a new list on table changes.
     */
    @Query("SELECT * FROM sync_pair ORDER BY id ASC")
    fun observeAll(): Flow<List<SyncPairEntity>>

    /**
     * Fetches the sync pair row for the given id.
     *
     * @param id The primary key id of the sync pair to fetch.
     * @return The matching `SyncPairEntity`, or `null` if no row matches.
     */
    @Query("SELECT * FROM sync_pair WHERE id = :id")
    suspend fun getById(id: Long): SyncPairEntity?

    /**
     * Inserts the given SyncPairEntity into the sync_pair table, replacing any existing row on primary-key conflict.
     *
     * @param pair The SyncPairEntity to insert.
     * @return The row ID of the inserted or replaced entry.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pair: SyncPairEntity): Long

    /**
     * Inserts the given SyncPairEntity or updates an existing row with the same primary key.
     *
     * @param pair The SyncPairEntity to insert or update in the `sync_pair` table.
     */
    @Upsert
    suspend fun upsert(pair: SyncPairEntity)

    /**
     * Deletes the SyncPair row with the specified id from the database.
     *
     * @param id The primary key of the sync_pair row to delete.
     */
    @Query("DELETE FROM sync_pair WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface FileIndexDao {
    /**
     * Retrieves all file index entries for a specific sync pair.
     *
     * @param pairId The sync pair's ID used to filter file index entries.
     * @return A list of FileIndexEntity rows matching the given `pairId`, or an empty list if none exist.
     */
    @Query("SELECT * FROM file_index WHERE pairId = :pairId")
    suspend fun getForPair(pairId: Long): List<FileIndexEntity>

    /**
     * Upserts multiple FileIndexEntity rows into the file index for their associated pair.
     *
     * @param entries The list of FileIndexEntity to insert or update; rows whose primary key matches an existing row will be updated, and rows without a match will be inserted.
     */
    @Upsert
    suspend fun upsertAll(entries: List<FileIndexEntity>)

    /**
     * Deletes the file index entry for the specified sync pair and relative path.
     *
     * @param pairId The ID of the sync pair owning the file index entry.
     * @param path The relative path of the file to delete.
     */
    @Query("DELETE FROM file_index WHERE pairId = :pairId AND relativePath = :path")
    suspend fun delete(pairId: Long, path: String)

    /**
     * Deletes all file index entries associated with the given sync pair.
     *
     * @param pairId The ID of the sync pair whose file_index rows will be removed.
     */
    @Query("DELETE FROM file_index WHERE pairId = :pairId")
    suspend fun clearForPair(pairId: Long)
}
