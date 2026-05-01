package com.konarsubhojit.synckro.data.repository

import com.konarsubhojit.synckro.data.local.dao.ConflictRecordDao
import com.konarsubhojit.synckro.data.local.entity.ConflictRecordEntity
import com.konarsubhojit.synckro.domain.model.ConflictRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for [ConflictRecord] persistence. Bridges the domain layer and Room.
 */
@Singleton
class ConflictRepository @Inject constructor(
    private val dao: ConflictRecordDao,
) {

    /** Observes all unresolved (pending) conflict records across all pairs, newest first. */
    fun observeUnresolved(): Flow<List<ConflictRecord>> =
        dao.observeUnresolved().map { list -> list.map { it.toDomain() } }

    /** Observes all conflict records (resolved and pending) for a specific pair. */
    fun observeForPair(pairId: Long): Flow<List<ConflictRecord>> =
        dao.observeForPair(pairId).map { list -> list.map { it.toDomain() } }

    /** Returns all resolved records for a specific pair (used by the sync engine). */
    suspend fun getResolvedForPair(pairId: Long): List<ConflictRecord> =
        dao.getResolvedForPair(pairId).map { it.toDomain() }

    /**
     * Persists a new conflict detected by the sync engine.
     *
     * @return The generated primary-key id of the inserted record.
     */
    suspend fun insert(record: ConflictRecord): Long =
        dao.insert(record.toEntity())

    /** Records the user's chosen resolution for the conflict with [id]. */
    suspend fun resolve(id: Long, resolution: String) =
        dao.resolve(id, resolution)

    /** Removes the conflict record with [id] after the engine has applied its resolution. */
    suspend fun delete(id: Long) =
        dao.delete(id)

    /** Removes all resolved records for [pairId] after they have been applied. */
    suspend fun deleteResolvedForPair(pairId: Long) =
        dao.deleteResolvedForPair(pairId)
}

private fun ConflictRecordEntity.toDomain(): ConflictRecord = ConflictRecord(
    id = id,
    pairId = pairId,
    relativePath = relativePath,
    localLastModifiedMs = localLastModifiedMs,
    remoteLastModifiedMs = remoteLastModifiedMs,
    detectedAtMs = detectedAtMs,
    resolution = resolution,
)

private fun ConflictRecord.toEntity(): ConflictRecordEntity = ConflictRecordEntity(
    id = id,
    pairId = pairId,
    relativePath = relativePath,
    localLastModifiedMs = localLastModifiedMs,
    remoteLastModifiedMs = remoteLastModifiedMs,
    detectedAtMs = detectedAtMs,
    resolution = resolution,
)
