package com.synckro.data.repository

import com.synckro.data.local.dao.SyncEventDao
import com.synckro.data.local.entity.SyncEventEntity
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for structured sync-event log entries.
 *
 * Provides domain-level read / write access to the `sync_event` Room table, mapping
 * between [SyncEventEntity] (storage model) and [SyncEvent] (domain model), and
 * enforcing the rolling-deletion cap via [SyncEventDao.insertAndPrune].
 */
@Singleton
class SyncEventRepository @Inject constructor(
    private val dao: SyncEventDao,
) {

    /**
     * Logs a new [SyncEvent], automatically pruning the oldest entries if the table
     * exceeds its global or per-pair cap.
     *
     * @param event The event to persist.
     */
    suspend fun log(event: SyncEvent) {
        dao.insertAndPrune(event.toEntity())
    }

    /**
     * Convenience overload that constructs a [SyncEvent] from its components.
     *
     * @param pairId    Owning sync-pair id, or `null` for global events.
     * @param level     Severity level.
     * @param tag       Short source label.
     * @param message   Human-readable description.
     */
    suspend fun log(
        pairId: Long?,
        level: SyncEventLevel,
        tag: String,
        message: String,
    ) {
        log(
            SyncEvent(
                pairId = pairId,
                timestampMs = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
            ),
        )
    }

    /**
     * Observes all log entries, newest first, up to [limit] rows.
     *
     * @param limit Maximum rows to stream (defaults to [SyncEventDao.MAX_EVENTS_GLOBAL]).
     * @return A [Flow] that re-emits whenever the table changes.
     */
    fun observeAll(limit: Int = SyncEventDao.MAX_EVENTS_GLOBAL): Flow<List<SyncEvent>> =
        dao.observeAll(limit).map { list -> list.map { it.toDomain() } }

    /**
     * Observes log entries for [pairId], newest first, up to [limit] rows.
     *
     * @param pairId The sync pair whose events should be observed.
     * @param limit  Maximum rows to stream (defaults to [SyncEventDao.MAX_EVENTS_PER_PAIR]).
     * @return A [Flow] that re-emits whenever the table changes.
     */
    fun observeForPair(
        pairId: Long,
        limit: Int = SyncEventDao.MAX_EVENTS_PER_PAIR,
    ): Flow<List<SyncEvent>> =
        dao.observeForPair(pairId, limit).map { list -> list.map { it.toDomain() } }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private fun SyncEvent.toEntity() = SyncEventEntity(
        id = id,
        pairId = pairId,
        timestampMs = timestampMs,
        level = level.name,
        tag = tag,
        message = message,
    )

    private fun SyncEventEntity.toDomain() = SyncEvent(
        id = id,
        pairId = pairId,
        timestampMs = timestampMs,
        level = SyncEventLevel.valueOf(level),
        tag = tag,
        message = message,
    )
}
