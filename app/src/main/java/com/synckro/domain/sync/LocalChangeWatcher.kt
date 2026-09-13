package com.synckro.domain.sync

/**
 * A platform-neutral source of coarse notifications that local sync content may have changed.
 *
 * A notification is a prompt to scan the pair again, not an authoritative file operation. In
 * particular, notifications can be duplicated, coalesced, delayed, or omitted while the watcher
 * is not registered.
 *
 * Implementations must support concurrent lifecycle calls. Listeners may be invoked on any thread,
 * so callers must dispatch UI work themselves.
 */
interface LocalChangeWatcher {
    /** Reports whether this watcher can provide notifications and the required fallback if not. */
    val capability: LocalChangeWatcherCapability

    /**
     * Registers [listener] for notifications concerning [pairId].
     *
     * [LocalChangeWatchRegistrationResult.Unavailable] means registration did not start. Once
     * registered, runtime failures are delivered to [listener] as [LocalChangeEvent.Failure].
     * After [shutdown], returns [LocalChangeWatchRegistrationResult.Failed] with
     * [LocalChangeWatchFailure.Shutdown].
     *
     * Each call creates an independent registration, including calls that repeat [listener] for
     * the same [pairId]. A listener may be registered again after it is unregistered.
     */
    fun register(
        pairId: Long,
        listener: (LocalChangeEvent) -> Unit,
    ): LocalChangeWatchRegistrationResult

    /**
     * Stops all registrations and releases watcher resources.
     *
     * Implementations must make this operation idempotent.
     */
    fun shutdown()
}

/** Whether local-change notifications are available, including the required fallback when not. */
sealed interface LocalChangeWatcherCapability {
    data object Available : LocalChangeWatcherCapability

    data class Unavailable(
        val fallback: LocalChangeWatcherFallback,
    ) : LocalChangeWatcherCapability
}

/** Work callers must use when notifications cannot be provided. */
enum class LocalChangeWatcherFallback {
    /** Schedule routine scans using the pair's configured periodic sync cadence. */
    PERIODIC_SCAN,

    /** Scan when the application returns to the foreground. */
    SCAN_ON_APP_RESUME,
}

/** The result of attempting to register a local-change listener. */
sealed interface LocalChangeWatchRegistrationResult {
    data class Registered(
        val registration: LocalChangeWatchRegistration,
    ) : LocalChangeWatchRegistrationResult

    data class Unavailable(
        val capability: LocalChangeWatcherCapability.Unavailable,
    ) : LocalChangeWatchRegistrationResult

    data class Failed(
        val failure: LocalChangeWatchFailure,
    ) : LocalChangeWatchRegistrationResult
}

/**
 * An opaque registration returned by [LocalChangeWatcher.register].
 *
 * [unregister] is safe to invoke more than once.
 */
fun interface LocalChangeWatchRegistration {
    fun unregister()
}

/**
 * A coarse watcher notification for one sync pair.
 */
sealed interface LocalChangeEvent {
    val pairId: Long

    /**
     * [locationHint] is only a best-effort URI or path hint. It can be absent, stale,
     * non-canonical, or refer to an item that has already moved or disappeared; callers must not
     * treat it as a stable identifier.
     */
    data class Changed(
        override val pairId: Long,
        val locationHint: String? = null,
    ) : LocalChangeEvent

    data class Failure(
        override val pairId: Long,
        val failure: LocalChangeWatchFailure,
    ) : LocalChangeEvent
}

/**
 * A failure preventing a local change watcher from observing a pair.
 *
 * A failure event is terminal for its specific registration, but callers may register a replacement
 * when recovery is appropriate. [Shutdown] is a registration-time failure and is never emitted as
 * an event.
 */
sealed interface LocalChangeWatchFailure {
    /**
     * The watcher has been shut down and cannot accept registrations.
     *
     * This is only returned from [LocalChangeWatcher.register], never emitted as an event.
     */
    data object Shutdown : LocalChangeWatchFailure

    /** The persisted local-tree permission is missing or has been revoked; the pair must be relinked. */
    data object PermissionDenied : LocalChangeWatchFailure

    /** The backing storage volume is absent; callers may register again after it becomes available. */
    data object VolumeUnavailable : LocalChangeWatchFailure

    /** An implementation-specific failure that callers should surface or retry according to policy. */
    data class Unknown(
        val message: String? = null,
    ) : LocalChangeWatchFailure
}
