package com.synckro.data.repository

import com.synckro.data.local.dao.PendingUploadDao
import com.synckro.data.local.entity.PendingUploadEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingUploadRepository
    @Inject
    constructor(
        private val pendingUploadDao: PendingUploadDao,
    ) {
        /**
         * Records a watcher candidate in Room using `(pairId, relativePath)` as the stable key.
         *
         * Repeated observations refresh the existing row, reset it to pending to invalidate any
         * in-flight claim, and preserve the stored creation time and retry attempts.
         * [observedAtMs] is used as both timestamps only when a new row is inserted.
         */
        suspend fun upsertCandidate(
            pairId: Long,
            relativePath: String,
            documentIdHint: String?,
            observedSizeBytes: Long,
            observedMtimeMs: Long,
            eligibleAtMs: Long,
            observedAtMs: Long = System.currentTimeMillis(),
        ) {
            pendingUploadDao.upsert(
                PendingUploadEntity(
                    pairId = pairId,
                    relativePath = relativePath,
                    documentIdHint = documentIdHint,
                    observedSizeBytes = observedSizeBytes,
                    observedMtimeMs = observedMtimeMs,
                    eligibleAtMs = eligibleAtMs,
                    createdAtMs = observedAtMs,
                    updatedAtMs = observedAtMs,
                ),
            )
        }
    }
