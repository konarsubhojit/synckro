package com.synckro.util.notification

import com.synckro.data.repository.SettingsRepository
import com.synckro.domain.model.SyncPair
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wiring-only helper for future sync result notifications.
 *
 * Phase 8a intentionally does not post any notifications yet; these methods only honour the
 * existing settings gates so later phases can fill in the actual notification content without
 * changing call sites.
 */
@Singleton
class SyncStatusNotifier
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) {
        suspend fun notifyFailure(
            _pair: SyncPair,
            _reason: String,
        ) {
            if (!settingsRepository.notifyOnFailure.first()) return
        }

        suspend fun notifySuccessSummary(
            _pair: SyncPair,
            _applied: Int,
            _conflicts: Int,
        ) {
            if (!settingsRepository.notifyOnSuccess.first()) return
        }

        companion object {
            /** Notification channel ID reserved for sync success/failure result notifications. */
            const val SYNC_STATUS_CHANNEL_ID = "sync_status"
        }
    }
