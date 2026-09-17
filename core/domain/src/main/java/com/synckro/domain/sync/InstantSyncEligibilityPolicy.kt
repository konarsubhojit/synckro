package com.synckro.domain.sync

import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair

/**
 * Central policy for deciding whether a local change may trigger Instant Sync.
 *
 * Instant Sync is automatic background work, not a manual sync action. Therefore
 * the global background-sync master ([globalAutoSyncEnabled]) intentionally gates
 * Instant Sync in addition to the dedicated global Instant Sync opt-in and the
 * per-pair Instant Sync toggle. This keeps the existing "pause background sync"
 * control authoritative for every automatic sync trigger.
 *
 * Callers provide [accountState] after resolving provider authentication and
 * SAF re-link status, keeping this policy independent of persisted result
 * strings or UI labels.
 */
class InstantSyncEligibilityPolicy {
    fun evaluate(
        pair: SyncPair,
        globalAutoSyncEnabled: Boolean,
        globalInstantSyncEnabled: Boolean,
        accountState: InstantSyncAccountState,
        relativePath: String? = null,
    ): InstantSyncEligibilityDecision {
        val reasons =
            buildSet {
                if (!globalAutoSyncEnabled) add(InstantSyncIneligibilityReason.GLOBAL_AUTO_SYNC_DISABLED)
                if (!globalInstantSyncEnabled) add(InstantSyncIneligibilityReason.GLOBAL_INSTANT_SYNC_DISABLED)
                if (!pair.instantSyncEnabled) add(InstantSyncIneligibilityReason.PAIR_INSTANT_SYNC_DISABLED)
                if (!pair.direction.allowsUpload()) add(InstantSyncIneligibilityReason.DIRECTION_NOT_UPLOAD_CAPABLE)
                when (accountState) {
                    InstantSyncAccountState.READY -> Unit
                    InstantSyncAccountState.ACCOUNT_NOT_LINKED ->
                        add(InstantSyncIneligibilityReason.ACCOUNT_NOT_LINKED)
                    InstantSyncAccountState.NEEDS_REAUTH -> add(InstantSyncIneligibilityReason.NEEDS_REAUTH)
                    InstantSyncAccountState.NEEDS_RELINK -> add(InstantSyncIneligibilityReason.NEEDS_RELINK)
                }
                if (relativePath != null && !pair.containsRelativePath(relativePath)) {
                    add(InstantSyncIneligibilityReason.PATH_OUT_OF_SCOPE)
                }
            }
        return InstantSyncEligibilityDecision(isEligible = reasons.isEmpty(), reasons = reasons)
    }

    private fun SyncPair.containsRelativePath(relativePath: String): Boolean =
        SyncPathScope.isInScope(
            relativePath = relativePath,
            includeGlobs = includeGlobs,
            ignoreGlobs = excludeGlobs,
            excludeSubfolders = excludeSubfolders,
        )

    private fun SyncDirection.allowsUpload(): Boolean =
        this != SyncDirection.REMOTE_TO_LOCAL &&
            this != SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS
}

data class InstantSyncEligibilityDecision(
    val isEligible: Boolean,
    val reasons: Set<InstantSyncIneligibilityReason>,
)

enum class InstantSyncAccountState {
    READY,
    ACCOUNT_NOT_LINKED,
    NEEDS_REAUTH,
    NEEDS_RELINK,
}

enum class InstantSyncIneligibilityReason {
    GLOBAL_AUTO_SYNC_DISABLED,
    GLOBAL_INSTANT_SYNC_DISABLED,
    PAIR_INSTANT_SYNC_DISABLED,
    DIRECTION_NOT_UPLOAD_CAPABLE,
    ACCOUNT_NOT_LINKED,
    NEEDS_REAUTH,
    NEEDS_RELINK,
    PATH_OUT_OF_SCOPE,
}
