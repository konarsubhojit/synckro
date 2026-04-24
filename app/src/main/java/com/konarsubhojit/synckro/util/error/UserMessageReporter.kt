package com.konarsubhojit.synckro.util.error

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single piece of user-facing feedback. [Severity] drives the visual
 * treatment of the snackbar (e.g. using `colorScheme.error` for [Severity.ERROR])
 * and, more importantly, whether the message is also written to Timber at
 * warn/error level so it lands in the on-disk debug log.
 */
data class UserMessage(
    val text: String,
    val severity: Severity = Severity.INFO,
    /** Optional short label for an action button (e.g. "Retry"). */
    val actionLabel: String? = null,
    /** Invoked when the user taps the action button. */
    val onAction: (() -> Unit)? = null,
) {
    enum class Severity { INFO, WARNING, ERROR }
}

/**
 * Application-scoped bus for surfacing human-readable errors and status
 * updates. Any ViewModel / repository / worker can call [report] and the
 * single `SnackbarHost` installed in `MainActivity` will display the result.
 *
 * Every reported message is also logged via Timber so the FileLoggingTree
 * records it — this is what makes "silent failures" impossible: either the
 * user sees a snackbar, or the developer finds the entry in the log file,
 * or (usually) both.
 */
@Singleton
class UserMessageReporter @Inject constructor() {

    // Extra buffer so rapid bursts during e.g. an OAuth error flurry don't
    // get dropped before the UI collector has a chance to subscribe.
    private val _messages = MutableSharedFlow<UserMessage>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Stream of messages consumed by the UI. */
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    /**
     * Publishes [message] to the UI and mirrors it into Timber so it also
     * lands in the on-disk log. Errors include [cause] in the log entry when
     * provided.
     */
    fun report(message: UserMessage, cause: Throwable? = null) {
        when (message.severity) {
            UserMessage.Severity.INFO -> Timber.i("UX: %s", message.text)
            UserMessage.Severity.WARNING -> Timber.w(cause, "UX: %s", message.text)
            UserMessage.Severity.ERROR -> Timber.e(cause, "UX: %s", message.text)
        }
        _messages.tryEmit(message)
    }

    /** Convenience for the common "something failed" path. */
    fun reportError(text: String, cause: Throwable? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        report(UserMessage(text, UserMessage.Severity.ERROR, actionLabel, onAction), cause)
    }
}

/**
 * Composition-local handle to the app-wide [UserMessageReporter]. Installed
 * in `MainActivity`. Defaults to throwing so a missing provider is caught at
 * the first call site rather than silently dropping messages.
 */
val LocalUserMessageReporter = compositionLocalOf<UserMessageReporter> {
    error("UserMessageReporter not provided. Did you forget CompositionLocalProvider?")
}
