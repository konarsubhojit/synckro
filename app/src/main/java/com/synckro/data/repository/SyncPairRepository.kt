package com.synckro.data.repository

import android.content.ContentResolver
import android.content.Intent
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.local.entity.toDomain
import com.synckro.domain.auth.AccountKey
import com.synckro.domain.model.SyncPair
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository that coordinates sync-pair persistence between the domain layer
 * ([SyncPair]) and Room ([SyncPairEntity]).
 *
 * The [needsReLink] flag on returned [SyncPair] instances is computed at call
 * time from [ContentResolver.persistedUriPermissions]: a pair is flagged when
 * the system no longer holds read+write access to its [SyncPair.localTreeUri].
 */
@Singleton
class SyncPairRepository
    @Inject
    constructor(
        private val syncPairDao: SyncPairDao,
    ) {
        /**
         * Observes all sync pairs, ordered by [SyncPairEntity.id] ascending.
         * Each emitted list has [SyncPair.needsReLink] pre-computed against the
         * supplied [contentResolver].
         */
        fun observeAll(contentResolver: ContentResolver): Flow<List<SyncPair>> =
            syncPairDao.observeAll().map { entities ->
                toDomainList(entities, contentResolver)
            }

        /**
         * Returns all sync pairs as a snapshot, with [SyncPair.needsReLink]
         * pre-computed against [contentResolver].
         */
        suspend fun getAll(contentResolver: ContentResolver): List<SyncPair> {
            Timber.d("SyncPairRepository.getAll()")
            return toDomainList(syncPairDao.observeAll().first(), contentResolver)
        }

        /**
         * Returns the sync pair with [id], or null if not found.
         * [SyncPair.needsReLink] is set to `false` — callers that need the relink
         * flag should use [observeAll] or pass a [ContentResolver] explicitly.
         * This method is intended for the pair editor, which does not display
         * the relink status.
         */
        suspend fun getById(id: Long): SyncPair? {
            val entity = syncPairDao.getById(id) ?: return null
            return entity.toDomain(needsReLink = false)
        }

        /**
         * Inserts a new sync pair or updates an existing one.
         * [SyncPair.needsReLink] is derived at read time from URI permission state,
         * so that flag is not persisted.
         *
         * @param pair The sync pair to insert or update.
         *
         * @return The row ID of the inserted or updated entry.
         */
        suspend fun upsert(pair: SyncPair): Long {
            Timber.i(
                "SyncPairRepository.upsert(id=${pair.id}, displayName=${pair.displayName}, " +
                    "localTreeUri=${pair.localTreeUri})",
            )
            val entity = pair.toEntity()
            return if (pair.id == 0L) {
                syncPairDao.insert(entity)
            } else {
                syncPairDao.upsert(entity)
                pair.id
            }
        }

        /**
         * Deletes the sync pair with [id].
         */
        suspend fun delete(id: Long) {
            Timber.i("SyncPairRepository.delete(id=$id)")
            syncPairDao.delete(id)
        }

        /**
         * Observes the set of [AccountKey]s that currently have at least one
         * sync pair stuck in `NEEDS_REAUTH`. The account-aware replacement for
         * [SyncPairDao.observeProvidersNeedingReauth].
         */
        fun observeAccountsNeedingReauth(): Flow<Set<AccountKey>> =
            syncPairDao.observeAccountsNeedingReauth().map { rows ->
                rows.mapTo(LinkedHashSet(rows.size)) { row ->
                    AccountKey(provider = row.provider, accountId = row.accountId)
                }
            }

        /**
         * Clears the `NEEDS_REAUTH` outcome for every sync pair bound to
         * [accountId]. See [SyncPairDao.clearNeedsReauthForAccount].
         */
        suspend fun clearNeedsReauthForAccount(accountId: String) {
            Timber.i("SyncPairRepository.clearNeedsReauthForAccount(accountId=$accountId)")
            syncPairDao.clearNeedsReauthForAccount(accountId)
        }

        /**
         * Returns all sync pairs currently bound to [accountId]. Used by the
         * Accounts screen to surface which pairs would be orphaned by a
         * disconnect.
         */
        suspend fun getByAccountId(accountId: String): List<SyncPair> {
            Timber.d("SyncPairRepository.getByAccountId(accountId=$accountId)")
            return syncPairDao.getByAccountId(accountId).map { it.toDomain(needsReLink = false) }
        }

        /**
         * Reassigns every sync pair currently bound to [fromAccountId] so it
         * points at [toAccountId] instead. Used by the Accounts disconnect
         * confirmation flow.
         */
        suspend fun reassignAccountId(
            fromAccountId: String,
            toAccountId: String,
        ) {
            Timber.i(
                "SyncPairRepository.reassignAccountId(from=$fromAccountId, to=$toAccountId)",
            )
            syncPairDao.reassignAccountId(fromAccountId, toAccountId)
        }

        /**
         * Deletes every sync pair currently bound to [accountId]. Used by the
         * Accounts disconnect confirmation flow when the user chooses to drop
         * orphaned pairs along with the account.
         */
        suspend fun deleteByAccountId(accountId: String) {
            Timber.i("SyncPairRepository.deleteByAccountId(accountId=$accountId)")
            syncPairDao.deleteByAccountId(accountId)
        }

        /**
         * Returns the set of URI strings for which the system currently holds a
         * persisted read+write permission grant.  Only URIs that have **both**
         * [Intent.FLAG_GRANT_READ_URI_PERMISSION] and
         * [Intent.FLAG_GRANT_WRITE_URI_PERMISSION] are included, matching the
         * flags requested by [PickLocalFolderScreen].
         */
        private fun persistedUriStrings(contentResolver: ContentResolver): Set<String> =
            contentResolver.persistedUriPermissions
                .filter { perm ->
                    perm.isReadPermission && perm.isWritePermission
                }.mapTo(HashSet()) { it.uri.toString() }

        private fun toDomainList(
            entities: List<SyncPairEntity>,
            contentResolver: ContentResolver,
        ): List<SyncPair> {
            val granted = persistedUriStrings(contentResolver)
            return entities.map { it.toDomain(needsReLink = it.localTreeUri !in granted) }
        }
    }

private fun SyncPair.toEntity(): SyncPairEntity =
    SyncPairEntity(
        id = id,
        displayName = displayName,
        localTreeUri = localTreeUri,
        provider = provider,
        accountId = accountId,
        remoteFolderId = remoteFolderId,
        remoteFolderName = remoteFolderName,
        direction = direction,
        conflictPolicy = conflictPolicy,
        includeGlobs = includeGlobs.joinToString("\n"),
        excludeGlobs = excludeGlobs.joinToString("\n"),
        wifiOnly = wifiOnly,
        requiresCharging = requiresCharging,
        autoSyncEnabled = autoSyncEnabled,
        scheduleIntervalMinutes = scheduleIntervalMinutes,
        lastDeltaToken = deltaToken,
        lastFullScanAtMs = lastFullScanAtMs,
        retentionDays = retentionDays,
        excludeSubfolders = excludeSubfolders,
        excludeEmptyFolders = excludeEmptyFolders,
    )
