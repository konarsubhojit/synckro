package com.konarsubhojit.synckro.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.konarsubhojit.synckro.data.local.entity.AccountEntity
import com.konarsubhojit.synckro.data.local.entity.FileIndexEntity
import com.konarsubhojit.synckro.data.local.entity.SyncPairEntity
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    /**
     * Observes all rows in the `account` table ordered by `createdAtMillis` ascending.
     *
     * Emits the current list of `AccountEntity` rows and subsequent updates whenever the table changes.
     *
     * @return A Flow that emits lists of `AccountEntity` containing all rows ordered by creation time; emits a new list on table changes.
     */
    @Query("SELECT * FROM account ORDER BY createdAtMillis ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    /**
     * Fetches all accounts synchronously.
     *
     * @return A list of all `AccountEntity` rows, or an empty list if none exist.
     */
    @Query("SELECT * FROM account ORDER BY createdAtMillis ASC")
    suspend fun getAll(): List<AccountEntity>

    /**
     * Fetches all accounts for the given provider type.
     *
     * @param providerType The cloud provider type to filter by.
     * @return A list of `AccountEntity` rows matching the provider, ordered by creation time.
     */
    @Query("SELECT * FROM account WHERE providerType = :providerType ORDER BY createdAtMillis ASC")
    suspend fun getByProvider(providerType: CloudProviderType): List<AccountEntity>

    /**
     * Fetches the account with the given id.
     *
     * @param id The primary key id of the account to fetch.
     * @return The matching `AccountEntity`, or `null` if no row matches.
     */
    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun getById(id: String): AccountEntity?

    /**
     * Inserts the given AccountEntity into the account table, replacing any existing row on primary-key conflict.
     *
     * @param account The AccountEntity to insert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity)

    /**
     * Inserts or updates the given AccountEntity or updates an existing row with the same primary key.
     * On conflict, all fields are updated **except** `createdAtMillis`, which is preserved from the
     * existing row so that creation-time ordering remains stable.
     *
     * @param account The AccountEntity to insert or update in the `account` table.
     */
    @Query(
        "INSERT INTO account (id, providerType, displayName, email, createdAtMillis) " +
            "VALUES (:id, :providerType, :displayName, :email, :createdAtMillis) " +
            "ON CONFLICT(id) DO UPDATE SET " +
            "providerType = excluded.providerType, " +
            "displayName = excluded.displayName, " +
            "email = excluded.email"
    )
    suspend fun upsertPreservingCreatedAt(
        id: String,
        providerType: CloudProviderType,
        displayName: String,
        email: String?,
        createdAtMillis: Long,
    )

    /**
     * Inserts the given AccountEntity or updates an existing row with the same primary key.
     *
     * @param account The AccountEntity to insert or update in the `account` table.
     */
    @Upsert
    suspend fun upsert(account: AccountEntity)

    /**
     * Deletes the account row with the specified id from the database.
     *
     * @param id The primary key of the account row to delete.
     */
    @Query("DELETE FROM account WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Atomically reconciles persisted accounts for [providerType] with [cached] (the provider's
     * current token-cache snapshot):
     * - upserts any cached account that is missing or whose metadata (displayName/email) has drifted,
     * - deletes any persisted account not present in the cache.
     *
     * Runs as a single Room transaction so the database is never observed in a half-updated state,
     * and failures mid-way roll back all writes.
     */
    @Transaction
    suspend fun reconcileProvider(
        providerType: CloudProviderType,
        cached: List<AccountEntity>,
    ) {
        val persistedById = getByProvider(providerType).associateBy { it.id }
        val cachedIds = HashSet<String>(cached.size)

        cached.forEach { c ->
            cachedIds.add(c.id)
            val existing = persistedById[c.id]
            if (existing == null ||
                existing.displayName != c.displayName ||
                existing.email != c.email
            ) {
                upsertPreservingCreatedAt(
                    id = c.id,
                    providerType = c.providerType,
                    displayName = c.displayName,
                    email = c.email,
                    createdAtMillis = c.createdAtMillis,
                )
            }
        }

        persistedById.keys.forEach { id ->
            if (id !in cachedIds) delete(id)
        }
    }
}

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
