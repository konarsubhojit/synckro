package com.synckro.domain.sync

import com.synckro.domain.model.SyncPair
import com.synckro.domain.model.allowsUpload

/** What caused the watcher host lifecycle to be re-evaluated. */
enum class WatcherLifecycleTrigger {
    /** The app became visible, so a foreground-service start is always permitted. */
    APP_FOREGROUND,

    /** `ACTION_BOOT_COMPLETED` (or its locked-boot variant) was received. */
    BOOT_COMPLETED,

    /** `ACTION_MY_PACKAGE_REPLACED` was received after an app update. */
    PACKAGE_REPLACED,

    /** Pairs or global sync settings changed while the host may already be running. */
    CONFIGURATION_CHANGED,
}

/** The action a lifecycle owner must take for the watcher foreground service. */
enum class WatcherLifecycleAction {
    /** Start (or reconcile) the watcher foreground service now. */
    START,

    /** Stop the watcher foreground service; nothing is eligible for watching. */
    STOP,

    /**
     * Watching is wanted but the platform does not allow starting a `dataSync` foreground service
     * from this trigger. Durable periodic work remains the fallback and the host is started again
     * the next time the app is in the foreground.
     */
    DEFER_UNTIL_APP_FOREGROUND,
}

/**
 * Decides whether the watcher foreground service may be started for a given trigger.
 *
 * The policy never assumes unrestricted background starts:
 * - Android 12 (API 31) forbids background foreground-service starts except for a fixed set of
 *   exemptions, which includes `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` receivers and apps that
 *   are already running a foreground service.
 * - Android 15 (API 35) additionally forbids starting a `dataSync` foreground service from
 *   `BOOT_COMPLETED`, so boot restoration defers to durable work instead.
 *
 * @param sdkInt the running platform API level
 */
class WatcherLifecyclePolicy(
    private val sdkInt: Int,
) {
    /**
     * @param trigger what caused the evaluation
     * @param hasWatchablePairs whether at least one pair is currently eligible for watching
     * @param isHostRunning whether the watcher foreground service is already running, which itself
     *   exempts the app from the background-start restriction
     */
    fun decide(
        trigger: WatcherLifecycleTrigger,
        hasWatchablePairs: Boolean,
        isHostRunning: Boolean = false,
    ): WatcherLifecycleAction {
        if (!hasWatchablePairs) return WatcherLifecycleAction.STOP
        if (isHostRunning) return WatcherLifecycleAction.START
        return when (trigger) {
            WatcherLifecycleTrigger.APP_FOREGROUND -> WatcherLifecycleAction.START
            WatcherLifecycleTrigger.PACKAGE_REPLACED -> WatcherLifecycleAction.START
            WatcherLifecycleTrigger.BOOT_COMPLETED ->
                if (sdkInt >= ANDROID_15) {
                    WatcherLifecycleAction.DEFER_UNTIL_APP_FOREGROUND
                } else {
                    WatcherLifecycleAction.START
                }
            // A configuration change outside the foreground (for example from a background worker)
            // carries no start exemption once the host has stopped.
            WatcherLifecycleTrigger.CONFIGURATION_CHANGED ->
                WatcherLifecycleAction.DEFER_UNTIL_APP_FOREGROUND
        }
    }

    private companion object {
        /** `Build.VERSION_CODES.VANILLA_ICE_CREAM`, inlined to keep this policy platform-free. */
        const val ANDROID_15 = 35
    }
}

/**
 * Selects the pairs whose local trees the watcher host should observe.
 *
 * This is the persisted-configuration half of Instant Sync eligibility only. Account readiness is
 * resolved later by [InstantSyncEligibilityPolicy] when a change is actually dispatched, so a
 * temporarily unauthenticated account does not tear down watcher registrations.
 */
object WatcherPairSelection {
    fun selectWatchablePairIds(
        pairs: List<SyncPair>,
        globalAutoSyncEnabled: Boolean,
        globalInstantSyncEnabled: Boolean,
    ): Set<Long> {
        if (!globalAutoSyncEnabled || !globalInstantSyncEnabled) return emptySet()
        return pairs
            .asSequence()
            .filter { it.isWatchable() }
            .mapTo(LinkedHashSet()) { it.id }
    }

    private fun SyncPair.isWatchable(): Boolean =
        instantSyncEnabled &&
            autoSyncEnabled &&
            localTreeUri.isNotBlank() &&
            !needsReLink &&
            direction.allowsUpload
}
