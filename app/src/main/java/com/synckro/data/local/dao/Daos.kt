package com.synckro.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.synckro.data.local.entity.AccountEntity
import com.synckro.data.local.entity.ConflictRecordEntity
import com.synckro.data.local.entity.FileIndexEntity
import com.synckro.data.local.entity.LocalIndexEntity
import com.synckro.data.local.entity.SyncEventEntity
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.domain.model.CloudProviderType
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
     * Observes all accounts for the given provider type, ordered by creation time ascending.
     *
     * Emits the current snapshot and any subsequent updates whenever the
     * `account` table changes (insert / update / delete).
     */
    @Query("SELECT * FROM account WHERE providerType = :providerType ORDER BY createdAtMillis ASC")
    fun observeByProvider(providerType: CloudProviderType): Flow<List<AccountEntity>>

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
            "email = excluded.email",
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
     * Row projection used by [SyncPairDao.observeAccountsNeedingReauth] to
     * carry the `(provider, accountId)` pair for each sync_pair row that has
     * landed in `NEEDS_REAUTH`. Mapped to a domain
     * [com.synckro.domain.auth.AccountKey] by callers.
     */
    data class AccountReauthRow(
        val provider: CloudProviderType,
        val accountId: String?,
    )

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

    /**
     * Updates the last sync timestamp and result for the given pair.
     *
     * @param pairId      The primary key of the sync pair.
     * @param timestampMs The epoch-millisecond time at which the sync run completed.
     * @param result      A short outcome string: "SUCCESS", "PARTIAL_FAILURE", or "FAILURE".
     */
    @Query("UPDATE sync_pair SET lastSyncAtMs = :timestampMs, lastSyncResult = :result WHERE id = :pairId")
    suspend fun updateLastSyncResult(
        pairId: Long,
        timestampMs: Long,
        result: String,
    )

    /**
     * Persists the provider delta/page token for [pairId].
     *
     * @param pairId The primary key of the sync pair.
     * @param token  The new delta token from the cloud provider, or null to clear it.
     */
    @Query("UPDATE sync_pair SET lastDeltaToken = :token WHERE id = :pairId")
    suspend fun updateDeltaToken(
        pairId: Long,
        token: String?,
    )

    /**
     * Records the epoch-millisecond timestamp of the last completed full local scan for [pairId].
     *
     * @param pairId      The primary key of the sync pair.
     * @param timestampMs The epoch-millisecond time the scan completed, or null to clear it.
     */
    @Query("UPDATE sync_pair SET lastFullScanAtMs = :timestampMs WHERE id = :pairId")
    suspend fun updateLastFullScanAtMs(
        pairId: Long,
        timestampMs: Long?,
    )

    /**
     * Observes which providers currently have at least one sync pair stuck in a
     * "needs re-authentication" state (see
     * [com.synckro.data.worker.SyncWorker.RESULT_NEEDS_REAUTH]).
     *
     * The accounts screen consumes this to render a "Re-authenticate" CTA on the
     * affected provider card.
     *
     * @return A Flow emitting the set of providers (as their enum names) with at
     *   least one pair whose last sync ended in [RESULT_NEEDS_REAUTH]. The list is
     *   distinct.
     */
    @Query("SELECT DISTINCT provider FROM sync_pair WHERE lastSyncResult = 'NEEDS_REAUTH'")
    fun observeProvidersNeedingReauth(): Flow<List<CloudProviderType>>

    /**
     * Observes the (provider, accountId) pairs that currently have at least one
     * sync pair stuck in [com.synckro.data.worker.SyncWorker.RESULT_NEEDS_REAUTH].
     *
     * This is the account-aware replacement for [observeProvidersNeedingReauth];
     * both methods are exposed during the multi-account migration so callers can
     * be ported one at a time.
     *
     * `accountId` may be `null` for pairs that were never bound to a specific
     * account (e.g. legacy pairs created before multi-account support, or pairs
     * orphaned when their account was disconnected); such rows are still
     * surfaced so the UI can render a re-link CTA against the provider card.
     */
    @Query(
        "SELECT DISTINCT provider AS provider, accountId AS accountId " +
            "FROM sync_pair WHERE lastSyncResult = 'NEEDS_REAUTH' " +
            "ORDER BY provider ASC, accountId ASC",
    )
    fun observeAccountsNeedingReauth(): Flow<List<AccountReauthRow>>

    /**
     * Clears the [com.synckro.data.worker.SyncWorker.RESULT_NEEDS_REAUTH]
     * outcome for every sync pair belonging to [providerType]. Called after a
     * successful interactive sign-in so the "Re-authenticate" CTA on the Accounts
     * screen disappears immediately, instead of waiting for the next periodic run
     * to overwrite `lastSyncResult`.
     *
     * @param providerType The provider whose pairs should have their
     *   `NEEDS_REAUTH` outcome cleared.
     */
    @Query("UPDATE sync_pair SET lastSyncResult = NULL WHERE provider = :providerType AND lastSyncResult = 'NEEDS_REAUTH'")
    suspend fun clearNeedsReauthForProvider(providerType: CloudProviderType)

    /**
     * Clears the [com.synckro.data.worker.SyncWorker.RESULT_NEEDS_REAUTH]
     * outcome for every sync pair bound to [accountId].
     *
     * Called after a successful interactive sign-in for a specific account so
     * the per-account "Re-authenticate" CTA disappears immediately, instead of
     * waiting for the next periodic run to overwrite `lastSyncResult`.
     */
    @Query(
        "UPDATE sync_pair SET lastSyncResult = NULL " +
            "WHERE accountId = :accountId AND lastSyncResult = 'NEEDS_REAUTH'",
    )
    suspend fun clearNeedsReauthForAccount(accountId: String)
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
    suspend fun delete(
        pairId: Long,
        path: String,
    )

    /**
     * Deletes all file index entries associated with the given sync pair.
     *
     * @param pairId The ID of the sync pair whose file_index rows will be removed.
     */
    @Query("DELETE FROM file_index WHERE pairId = :pairId")
    suspend fun clearForPair(pairId: Long)

    /**
     * Deletes file index entries for [pairId] whose [relativePath] is NOT in [seenPaths].
     *
     * Only call this when [seenPaths] is non-empty; an empty list would result in
     * invalid SQL (`NOT IN ()`) — use [clearForPair] instead.
     *
     * @param pairId    The sync pair whose stale entries should be removed.
     * @param seenPaths Paths that were found during the latest scan and must be kept.
     */
    @Query("DELETE FROM file_index WHERE pairId = :pairId AND relativePath NOT IN (:seenPaths)")
    suspend fun deleteStaleForPair(
        pairId: Long,
        seenPaths: List<String>,
    )

    /**
     * Atomically reconciles the Room index for [pairId] after a scan:
     * - Upserts all changed/new entries in [toUpsert].
     * - Deletes entries whose relative path is no longer present on disk.
     *   When [seenPaths] is empty the entire pair index is cleared; otherwise
     *   only rows whose path is absent from [seenPaths] are removed.
     *
     * Runs as a single Room transaction so the index is never observed in a
     * half-updated state, and any failure rolls back all writes.
     *
     * @param pairId    The ID of the sync pair being reconciled.
     * @param toUpsert  New or changed [FileIndexEntity] rows to write.
     * @param seenPaths All relative paths discovered during the scan.
     */
    @Transaction
    suspend fun reconcileForPair(
        pairId: Long,
        toUpsert: List<FileIndexEntity>,
        seenPaths: List<String>,
    ) {
        if (toUpsert.isNotEmpty()) {
            upsertAll(toUpsert)
        }
        if (seenPaths.isEmpty()) {
            clearForPair(pairId)
        } else {
            deleteStaleForPair(pairId, seenPaths)
        }
    }
}

@Dao
interface ConflictRecordDao {
    /** Observes all unresolved (resolution IS NULL) conflict records across all pairs. */
    @Query("SELECT * FROM conflict_record WHERE resolution IS NULL ORDER BY detectedAtMs DESC")
    fun observeUnresolved(): Flow<List<ConflictRecordEntity>>

    /**
     * Observes the number of unresolved (pending) conflict records across all pairs.
     *
     * Prefer this over `observeUnresolved().map { it.size }` when only the count is
     * needed: Room evaluates `COUNT(*)` directly in SQL without materialising every
     * row into a [ConflictRecordEntity], which keeps Home-screen aggregation cheap
     * even when the conflict table grows large.
     */
    @Query("SELECT COUNT(*) FROM conflict_record WHERE resolution IS NULL")
    fun observeUnresolvedCount(): Flow<Int>

    /** Observes all conflict records (resolved and unresolved) for a specific pair. */
    @Query("SELECT * FROM conflict_record WHERE pairId = :pairId ORDER BY detectedAtMs DESC")
    fun observeForPair(pairId: Long): Flow<List<ConflictRecordEntity>>

    /** Returns all resolved records for a specific pair (resolution IS NOT NULL). */
    @Query("SELECT * FROM conflict_record WHERE pairId = :pairId AND resolution IS NOT NULL")
    suspend fun getResolvedForPair(pairId: Long): List<ConflictRecordEntity>

    /**
     * Inserts a new conflict record, replacing any existing row with the same
     * `(pairId, relativePath)` pair. The unique index on those columns ensures
     * at most one pending conflict exists per file per sync pair.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ConflictRecordEntity): Long

    /** Sets the resolution for the conflict with the given id. */
    @Query("UPDATE conflict_record SET resolution = :resolution WHERE id = :id")
    suspend fun resolve(
        id: Long,
        resolution: String,
    )

    /** Deletes the conflict record with the given id (e.g. after the engine has applied its resolution). */
    @Query("DELETE FROM conflict_record WHERE id = :id")
    suspend fun delete(id: Long)

    /** Deletes all resolved conflict records for a pair (called after the engine has applied them). */
    @Query("DELETE FROM conflict_record WHERE pairId = :pairId AND resolution IS NOT NULL")
    suspend fun deleteResolvedForPair(pairId: Long)
}

@Dao
interface SyncEventDao {
    /**
     * Inserts a single [SyncEventEntity] into the database.
     *
     * @param event The event to persist.
     * @return The row id of the inserted entry.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: SyncEventEntity): Long

    /**
     * Returns all events, newest first, up to [limit] rows.
     *
     * @param limit Maximum number of rows to return.
     * @return A [Flow] that re-emits the list whenever the table changes.
     */
    @Query("SELECT * FROM sync_event ORDER BY timestampMs DESC LIMIT :limit")
    fun observeAll(limit: Int = MAX_EVENTS_GLOBAL): Flow<List<SyncEventEntity>>

    /**
     * Returns all events as a one-shot snapshot, newest first, up to [limit] rows.
     * Use this when you need the list once (e.g. for export) rather than observing changes.
     *
     * @param limit Maximum number of rows to return.
     */
    @Query("SELECT * FROM sync_event ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getAll(limit: Int = MAX_EVENTS_GLOBAL): List<SyncEventEntity>

    /**
     * Returns events for the given pair, newest first, up to [limit] rows.
     *
     * @param pairId  The sync pair whose events should be observed.
     * @param limit   Maximum number of rows to return.
     * @return A [Flow] that re-emits whenever the table changes.
     */
    @Query(
        "SELECT * FROM sync_event WHERE pairId = :pairId ORDER BY timestampMs DESC LIMIT :limit",
    )
    fun observeForPair(
        pairId: Long,
        limit: Int = MAX_EVENTS_PER_PAIR,
    ): Flow<List<SyncEventEntity>>

    /**
     * Deletes the oldest global rows beyond [maxRows], keeping the most-recent ones.
     * Called after every insert to enforce the rolling global cap.
     *
     * @param maxRows Maximum number of rows to retain in the table.
     */
    @Query(
        "DELETE FROM sync_event WHERE id NOT IN " +
            "(SELECT id FROM sync_event ORDER BY timestampMs DESC LIMIT :maxRows)",
    )
    suspend fun pruneGlobal(maxRows: Int = MAX_EVENTS_GLOBAL)

    /**
     * Deletes the oldest rows for [pairId] beyond [maxRows].
     * Called after every pair-scoped insert to enforce the per-pair rolling cap.
     *
     * @param pairId  The pair whose log should be pruned.
     * @param maxRows Maximum rows to keep for this pair.
     */
    @Query(
        "DELETE FROM sync_event WHERE pairId = :pairId AND id NOT IN " +
            "(SELECT id FROM sync_event WHERE pairId = :pairId " +
            "ORDER BY timestampMs DESC LIMIT :maxRows)",
    )
    suspend fun pruneForPair(
        pairId: Long,
        maxRows: Int = MAX_EVENTS_PER_PAIR,
    )

    /**
     * Inserts a log entry and immediately prunes the table to stay within the
     * rolling caps.  Runs as a single transaction so reads never see an over-full table.
     *
     * @param event    The event to persist.
     */
    @Transaction
    suspend fun insertAndPrune(event: SyncEventEntity) {
        insert(event)
        if (event.pairId != null) {
            pruneForPair(event.pairId)
        }
        pruneGlobal()
    }

    companion object {
        /** Maximum events retained per sync-pair (oldest are discarded first). */
        const val MAX_EVENTS_PER_PAIR = 500

        /**
         * Maximum total events retained across all pairs.
         *
         * Raised from 2 000 to 5 000 to accommodate the new Auth / Account / PairEditor /
         * Scheduler / Export instrumentation added in the structured-logging pass (issue #121-E).
         * The rolling-deletion in [insertAndPrune] keeps the table bounded, so no periodic
         * prune worker is needed at this cap level.
         */
        const val MAX_EVENTS_GLOBAL = 5_000
    }
}

@Dao
interface LocalIndexDao {
    /**
     * Returns all local-index entries for [pairId], ordered by [relativePath] ascending.
     *
     * @param pairId The sync pair whose local index entries should be returned.
     * @return A list of [LocalIndexEntity] rows for the given pair.
     */
    @Query("SELECT * FROM local_index WHERE pairId = :pairId ORDER BY relativePath ASC")
    suspend fun getForPair(pairId: Long): List<LocalIndexEntity>

    /**
     * Inserts or updates a single [LocalIndexEntity].  On conflict the existing row is
     * replaced in its entirety.
     *
     * @param entry The entry to persist.
     */
    @Upsert
    suspend fun upsert(entry: LocalIndexEntity)

    /**
     * Inserts or updates all entries in [entries].  On conflict each row is replaced.
     *
     * @param entries The entries to persist.
     */
    @Upsert
    suspend fun upsertAll(entries: List<LocalIndexEntity>)

    /**
     * Deletes the local-index entry identified by [pairId] and [relativePath].
     *
     * @param pairId       The sync pair that owns the entry.
     * @param relativePath The relative path of the file to remove.
     */
    @Query("DELETE FROM local_index WHERE pairId = :pairId AND relativePath = :relativePath")
    suspend fun delete(
        pairId: Long,
        relativePath: String,
    )

    /**
     * Deletes all local-index entries for [pairId].
     * Call this before a full rescan so stale entries are not left behind.
     *
     * @param pairId The sync pair whose local index should be cleared.
     */
    @Query("DELETE FROM local_index WHERE pairId = :pairId")
    suspend fun clearForPair(pairId: Long)

    /**
     * Deletes local-index entries for [pairId] whose [relativePath] is **not**
     * present in [seenPaths].  Used internally by [reconcileForPair] to remove
     * stale entries in a single batch.
     *
     * @param pairId    The sync pair whose stale entries should be removed.
     * @param seenPaths Relative paths that are still present on the filesystem.
     */
    @Query(
        "DELETE FROM local_index WHERE pairId = :pairId AND relativePath NOT IN (:seenPaths)",
    )
    suspend fun deleteStaleForPair(
        pairId: Long,
        seenPaths: List<String>,
    )

    /**
     * Atomically upserts [toUpsert] and removes any `local_index` rows for
     * [pairId] whose path is absent from [seenPaths].
     *
     * Running both operations inside a `@Transaction` ensures the index is
     * never observed in a partially-updated state and that a failure rolls
     * back all writes.
     *
     * @param pairId    The sync pair whose index is being reconciled.
     * @param toUpsert  Entries to insert or update (new and modified files).
     * @param seenPaths Full set of relative paths still present on disk.
     */
    @Transaction
    suspend fun reconcileForPair(
        pairId: Long,
        toUpsert: List<LocalIndexEntity>,
        seenPaths: List<String>,
    ) {
        if (toUpsert.isNotEmpty()) {
            upsertAll(toUpsert)
        }
        if (seenPaths.isEmpty()) {
            clearForPair(pairId)
        } else {
            deleteStaleForPair(pairId, seenPaths)
        }
    }
}
