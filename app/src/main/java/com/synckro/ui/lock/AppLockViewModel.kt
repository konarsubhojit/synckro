package com.synckro.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the state of the optional biometric app lock.
 *
 * The lock is opt-in: while [SettingsRepository.biometricAppLockEnabled] is
 * `false` (the default) content stays visible and no prompt is ever requested.
 * When the user opts in, app content is hidden until an authentication attempt
 * succeeds; declining, cancelling or failing the prompt keeps the app locked
 * rather than silently proceeding.
 *
 * The transitions here are platform-free so they can be unit tested; the actual
 * `BiometricPrompt` is driven by [AppLockGate].
 */
@HiltViewModel
class AppLockViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(AppLockUiState())
        val state: StateFlow<AppLockUiState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                settingsRepository.biometricAppLockEnabled.collect { enabled ->
                    _state.update { current ->
                        // Turning the lock on from Settings must not eject the user
                        // from the screen they are on; the gate re-locks on the next
                        // foreground transition instead.
                        val enabledWhileRunning = enabled && current.initialized && !current.lockEnabled
                        val unlocked =
                            when {
                                !enabled -> false
                                enabledWhileRunning -> true
                                else -> current.unlocked
                            }
                        current.copy(
                            initialized = true,
                            lockEnabled = enabled,
                            unlocked = unlocked,
                            promptRequested =
                                when {
                                    !enabled || unlocked -> false
                                    // The preference may load after the app is already
                                    // visible, in which case no foreground event will
                                    // arrive to ask for the prompt.
                                    !current.initialized && current.appForegrounded -> true
                                    else -> current.promptRequested
                                },
                            error = if (enabled) current.error else null,
                        )
                    }
                }
            }
        }

        /** Called when the app becomes visible (launch or return to foreground). */
        fun onAppForegrounded() {
            _state.update { current ->
                val shouldPrompt = current.lockEnabled && !current.unlocked && !current.promptRequested
                current.copy(
                    appForegrounded = true,
                    promptRequested = current.promptRequested || shouldPrompt,
                    error = if (shouldPrompt) null else current.error,
                )
            }
        }

        /** Called when the app leaves the foreground: re-lock so the next launch prompts again. */
        fun onAppBackgrounded() {
            _state.update { current ->
                current.copy(
                    appForegrounded = false,
                    unlocked = if (current.lockEnabled) false else current.unlocked,
                    promptRequested = false,
                )
            }
        }

        /** Called once the prompt has actually been shown so it is not requested twice. */
        fun onPromptShown() {
            _state.update { it.copy(promptRequested = false) }
        }

        /** Called when the user explicitly retries after a failed or cancelled attempt. */
        fun onRetryRequested() {
            _state.update { current ->
                if (!current.lockEnabled || current.unlocked) {
                    current
                } else {
                    current.copy(promptRequested = true, error = null)
                }
            }
        }

        /** Successful authentication: reveal the app content. */
        fun onAuthSucceeded() {
            _state.update { it.copy(unlocked = true, promptRequested = false, error = null) }
        }

        /**
         * Failed, cancelled or declined authentication. The app stays locked and
         * the optional [error] is surfaced so the user can retry.
         */
        fun onAuthFailed(error: AppLockError? = null) {
            _state.update { it.copy(unlocked = false, promptRequested = false, error = error) }
        }

        /** Turns the app lock off, e.g. when the device no longer has a secure lock configured. */
        fun disableLock() {
            viewModelScope.launch { settingsRepository.setBiometricAppLockEnabled(false) }
        }
    }

/**
 * Why an authentication attempt did not unlock the app. [Unavailable] means the
 * device currently offers no biometric or device-credential authenticator, in
 * which case the user can only proceed by turning the lock off.
 */
enum class AppLockError {
    Cancelled,
    Failed,
    Unavailable,
}

/** Immutable app-lock state consumed by [AppLockGate]. */
data class AppLockUiState(
    /** `false` until the persisted preference has been read for the first time. */
    val initialized: Boolean = false,
    val lockEnabled: Boolean = false,
    val unlocked: Boolean = false,
    /** `true` when the gate should show the biometric prompt. */
    val promptRequested: Boolean = false,
    /** `true` while the app is in the foreground; drives the initial prompt. */
    val appForegrounded: Boolean = false,
    val error: AppLockError? = null,
) {
    /** App content may be rendered only when the lock is off or already satisfied. */
    val contentVisible: Boolean
        get() = initialized && (!lockEnabled || unlocked)

    /** The blocking lock screen is shown while the lock is enabled and unsatisfied. */
    val lockScreenVisible: Boolean
        get() = initialized && lockEnabled && !unlocked
}
