package com.synckro.util.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Navigation destinations that can be requested from outside the Compose tree (e.g. from a notification). */
sealed class AppNavEvent {
    /**
     * Open the Accounts screen so the user can reconnect a cloud account.
     *
     * @param accountId Optional id of the account that should be brought into view and
     *   briefly highlighted on arrival (Phase 5d reauth deep-link). `null` opens the
     *   Accounts tab without any specific highlight (the original Phase 1 behaviour).
     */
    data class OpenAccounts(val accountId: String? = null) : AppNavEvent()
}

/**
 * Singleton dispatcher used to push navigation commands into the Compose nav tree from
 * outside it (e.g. from a notification tap handled in [com.synckro.MainActivity.onNewIntent]).
 *
 * The pending event is modelled as a [StateFlow] so that:
 * - A command emitted before the NavHost collects is not silently dropped (unlike a
 *   zero-replay [kotlinx.coroutines.flow.SharedFlow]).
 * - Only the most recent command is kept; rapid taps on the notification don't queue up.
 *
 * The consumer (SynckroNavHost) must call [consumeEvent] after handling the event so
 * that the same command is not re-applied on recomposition.
 */
@Singleton
class AppNavigationDispatcher
    @Inject
    constructor() {
        private val _pendingEvent = MutableStateFlow<AppNavEvent?>(null)

        /**
         * The pending navigation event, or `null` when there is nothing to act on.
         * Collectors should call [consumeEvent] immediately after processing to avoid
         * re-navigation on subsequent recompositions.
         */
        val pendingEvent: StateFlow<AppNavEvent?> = _pendingEvent.asStateFlow()

        /** Schedules a navigation event to be consumed by the next active NavHost collector. */
        fun navigateTo(event: AppNavEvent) {
            _pendingEvent.value = event
        }

        /** Marks the pending event as consumed. Call this after the NavHost has acted on the event. */
        fun consumeEvent() {
            _pendingEvent.value = null
        }
    }
