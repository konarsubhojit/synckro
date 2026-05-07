package com.synckro.data.local.entity

import com.synckro.domain.model.SyncPair

/**
 * Converts a [SyncPairEntity] to a [SyncPair] domain model, mapping ALL
 * persistable fields so that every sync run (periodic or one-shot) has access
 * to the full pair configuration — including [SyncPair.deltaToken] for
 * incremental sync, [SyncPair.retentionDays] for retention-cleanup modes, and
 * [SyncPair.autoSyncEnabled] for cancellation logic.
 *
 * @param needsReLink `true` when the SAF tree-URI permission for
 *   [SyncPairEntity.localTreeUri] is no longer held by the system.  Callers
 *   that do not have access to [android.content.ContentResolver.persistedUriPermissions]
 *   (e.g. [com.synckro.data.worker.SyncWorker]) should pass `false`.
 */
internal fun SyncPairEntity.toDomain(needsReLink: Boolean = false): SyncPair =
    SyncPair(
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
        autoSyncEnabled = autoSyncEnabled,
        scheduleIntervalMinutes = scheduleIntervalMinutes,
        needsReLink = needsReLink,
        lastSyncAtMs = lastSyncAtMs,
        lastSyncResult = lastSyncResult,
        deltaToken = lastDeltaToken,
        lastFullScanAtMs = lastFullScanAtMs,
        retentionDays = retentionDays,
        excludeSubfolders = excludeSubfolders,
        excludeEmptyFolders = excludeEmptyFolders,
    )
