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
