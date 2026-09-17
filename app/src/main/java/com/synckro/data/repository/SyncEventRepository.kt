package com.synckro.data.repository

import com.synckro.data.local.dao.SyncEventDao
import com.synckro.data.local.entity.SyncEventEntity
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTaxonomy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for structured sync-event log entries.
 *
 * Provides domain-level read / write access to the `sync_event` Room table, mapping
 * between [SyncEventEntity] (storage model) and [SyncEvent] (domain model), and
 * enforcing the rolling-deletion cap via [SyncEventDao.insertAndPrune].
 */
@Singleton
class SyncEventRepository
    @Inject
    constructor(
        private val dao: SyncEventDao,
    ) {
        private val rateLimitLock = Any()
        private val rateLimitLastLoggedAtMs = ConcurrentHashMap<String, Long>()

        /**
         * Logs a new [SyncEvent], automatically pruning the oldest entries if the table
         * exceeds its global or per-pair cap.
         *
         * @param event The event to persist.
         */
        suspend fun log(event: SyncEvent) {
            dao.insertAndPrune(event.sanitized().toEntity())
        }

        /**
         * Convenience overload that constructs a [SyncEvent] from its components.
         *
         * @param pairId    Owning sync-pair id, or `null` for global events.
         * @param level     Severity level.
         * @param tag       Short source label.
         * @param message   Human-readable description.
         * @param bytesTransferred Total transfer bytes for a completed sync run.
         */
        suspend fun log(
            pairId: Long?,
            level: SyncEventLevel,
            tag: String,
            message: String,
            bytesTransferred: Long? = null,
        ) {
            log(
                SyncEvent(
                    pairId = pairId,
                    timestampMs = System.currentTimeMillis(),
                    level = level,
                    tag = tag,
                    message = message,
                    bytesTransferred = bytesTransferred,
                ),
            )
        }

        /**
         * Logs [message] only if the same [throttleKey] has not been persisted
         * inside [windowMs]. Intended for noisy Instant Sync deferral/fallback
         * notices while still relying on the normal sync-event pruning caps.
         *
         * @return `true` when an event was written, `false` when it was suppressed.
         */
        suspend fun logRateLimited(
            pairId: Long?,
            level: SyncEventLevel,
            tag: String,
            message: String,
            throttleKey: String,
            windowMs: Long = DEFAULT_RATE_LIMIT_WINDOW_MS,
            nowMs: Long = System.currentTimeMillis(),
        ): Boolean {
            val key = "${pairId ?: 0L}|$tag|$throttleKey"
            val shouldLog =
                synchronized(rateLimitLock) {
                    val lastLoggedAt = rateLimitLastLoggedAtMs[key]
                    if (lastLoggedAt != null && nowMs - lastLoggedAt < windowMs) {
                        false
                    } else {
                        rateLimitLastLoggedAtMs[key] = nowMs
                        true
                    }
                }
            if (shouldLog) {
                log(
                    SyncEvent(
                        pairId = pairId,
                        timestampMs = nowMs,
                        level = level,
                        tag = tag,
                        message = message,
                    ),
                )
            }
            return shouldLog
        }

        /**
         * Observes all log entries, newest first, up to [limit] rows.
         *
         * @param limit Maximum rows to stream (defaults to [SyncEventDao.MAX_EVENTS_GLOBAL]).
         * @return A [Flow] that re-emits whenever the table changes.
         */
        fun observeAll(limit: Int = SyncEventDao.MAX_EVENTS_GLOBAL): Flow<List<SyncEvent>> = dao.observeAll(limit).map { list -> list.map { it.toDomain() } }

        /**
         * Returns a one-shot snapshot of all log entries, newest first, up to [limit] rows.
         * Use this for export rather than observing changes.
         *
         * @param limit Maximum rows to return (defaults to [SyncEventDao.MAX_EVENTS_GLOBAL]).
         */
        suspend fun getAll(limit: Int = SyncEventDao.MAX_EVENTS_GLOBAL): List<SyncEvent> = dao.getAll(limit).map { it.toDomain() }

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
        ): Flow<List<SyncEvent>> = dao.observeForPair(pairId, limit).map { list -> list.map { it.toDomain() } }

        /**
         * Observes log entries for [pairId] and [tag], newest first, up to [limit]
         * rows. This supports targeted searches without adding another log store.
         */
        fun observeForPairAndTag(
            pairId: Long,
            tag: String,
            limit: Int = SyncEventDao.MAX_EVENTS_PER_PAIR,
        ): Flow<List<SyncEvent>> = dao.observeForPairAndTag(pairId, tag, limit).map { list -> list.map { it.toDomain() } }

        /**
         * Returns a one-shot snapshot of events for [pairId] and [tag], newest
         * first, up to [limit] rows.
         */
        suspend fun getForPairAndTag(
            pairId: Long,
            tag: String,
            limit: Int = SyncEventDao.MAX_EVENTS_PER_PAIR,
        ): List<SyncEvent> = dao.getForPairAndTag(pairId, tag, limit).map { it.toDomain() }

        // -------------------------------------------------------------------------
        // Mapping helpers
        // -------------------------------------------------------------------------

        private fun SyncEvent.toEntity() =
            SyncEventEntity(
                id = id,
                pairId = pairId,
                timestampMs = timestampMs,
                level = level.name,
                tag = tag,
                message = message,
                bytesTransferred = bytesTransferred,
            )

        private fun SyncEvent.sanitized(): SyncEvent = copy(message = SyncEventTaxonomy.sanitizeMessage(message))

        private fun SyncEventEntity.toDomain() =
            SyncEvent(
                id = id,
                pairId = pairId,
                timestampMs = timestampMs,
                level = SyncEventLevel.valueOf(level),
                tag = tag,
                message = message,
                bytesTransferred = bytesTransferred,
            )

        companion object {
            const val DEFAULT_RATE_LIMIT_WINDOW_MS: Long = 60_000L
        }
    }
