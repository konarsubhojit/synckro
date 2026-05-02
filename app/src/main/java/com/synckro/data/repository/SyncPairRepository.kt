package com.synckro.data.repository

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.entity.SyncPairEntity
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
class SyncPairRepository @Inject constructor(
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
     * flag is not persisted.
     *
     * @return The row ID of the inserted or updated entry.
     */
    suspend fun upsert(pair: SyncPair): Long {
        Timber.i(
            "SyncPairRepository.upsert(id=${pair.id}, displayName=${pair.displayName}, " +
                "localTreeUri=${pair.localTreeUri})"
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
            }
            .mapTo(HashSet()) { it.uri.toString() }

    private fun toDomainList(
        entities: List<SyncPairEntity>,
        contentResolver: ContentResolver,
    ): List<SyncPair> {
        val granted = persistedUriStrings(contentResolver)
        return entities.map { it.toDomain(needsReLink = it.localTreeUri !in granted) }
    }
}

private fun SyncPairEntity.toDomain(needsReLink: Boolean): SyncPair = SyncPair(
    id = id,
    displayName = displayName,
    localTreeUri = localTreeUri,
    provider = provider,
    remoteFolderId = remoteFolderId,
    direction = direction,
    conflictPolicy = conflictPolicy,
    includeGlobs = includeGlobs.split('\n').filter { it.isNotBlank() },
    excludeGlobs = excludeGlobs.split('\n').filter { it.isNotBlank() },
    wifiOnly = wifiOnly,
    requiresCharging = requiresCharging,
    scheduleIntervalMinutes = scheduleIntervalMinutes,
    needsReLink = needsReLink,
    lastSyncAtMs = lastSyncAtMs,
    lastSyncResult = lastSyncResult,
    deltaToken = lastDeltaToken,
    lastFullScanAtMs = lastFullScanAtMs,
)

private fun SyncPair.toEntity(): SyncPairEntity = SyncPairEntity(
    id = id,
    displayName = displayName,
    localTreeUri = localTreeUri,
    provider = provider,
    remoteFolderId = remoteFolderId,
    direction = direction,
    conflictPolicy = conflictPolicy,
    includeGlobs = includeGlobs.joinToString("\n"),
    excludeGlobs = excludeGlobs.joinToString("\n"),
    wifiOnly = wifiOnly,
    requiresCharging = requiresCharging,
    scheduleIntervalMinutes = scheduleIntervalMinutes,
    lastDeltaToken = deltaToken,
    lastFullScanAtMs = lastFullScanAtMs,
)
