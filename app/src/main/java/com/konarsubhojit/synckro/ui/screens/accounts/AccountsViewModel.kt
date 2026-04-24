package com.konarsubhojit.synckro.ui.screens.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konarsubhojit.synckro.domain.auth.Account
import com.konarsubhojit.synckro.domain.auth.AuthManager
import com.konarsubhojit.synckro.domain.auth.AuthManagerRegistry
import com.konarsubhojit.synckro.domain.auth.AuthResult
import com.konarsubhojit.synckro.util.error.UserMessage
import com.konarsubhojit.synckro.util.error.UserMessageReporter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State machine for the accounts screen. Exposes one [AccountRow] per
 * registered [AuthManager] — whether or not any user has signed in yet.
 * Reports every failure through [UserMessageReporter] so the user actually
 * sees what went wrong; this is the direct fix for the "+ button does
 * nothing" class of silent failure.
 */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val registry: AuthManagerRegistry,
    private val userMessages: UserMessageReporter,
) : ViewModel() {

    data class AccountRow(
        val providerDisplayName: String,
        val providerKey: String,
        val isConfigured: Boolean,
        val accounts: List<Account>,
        val isBusy: Boolean = false,
    )

    data class UiState(
        val rows: List<AccountRow> = emptyList(),
        val isLoading: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val rows = registry.all.map { manager ->
                AccountRow(
                    providerDisplayName = manager.displayName,
                    providerKey = manager.providerType.name,
                    isConfigured = manager.isConfigured(),
                    accounts = manager.currentAccounts(),
                )
            }
            _state.value = UiState(rows = rows, isLoading = false)
        }
    }

    /**
     * Kicks off an interactive sign-in for [providerKey]. The caller supplies
     * a [launchSignIn] lambda that, given an [AuthManager], actually calls
     * `signIn(activity)` — this keeps Activity references out of the
     * ViewModel and avoids leaks.
     */
    fun connect(providerKey: String, launchSignIn: suspend (AuthManager) -> AuthResult<Account>) {
        setBusy(providerKey, true)
        viewModelScope.launch {
            val manager = registry.all.firstOrNull { it.providerType.name == providerKey } ?: run {
                setBusy(providerKey, false)
                userMessages.reportError("Unknown provider: $providerKey")
                return@launch
            }
            val result = runCatching { launchSignIn(manager) }.getOrElse { t ->
                setBusy(providerKey, false)
                userMessages.reportError(
                    "Couldn't sign in to ${manager.displayName}: ${t.message ?: t.javaClass.simpleName}",
                    t,
                )
                return@launch
            }
            handleResult(manager, result)
            setBusy(providerKey, false)
            refresh()
        }
    }

    fun disconnect(account: Account) {
        viewModelScope.launch {
            val manager = registry.get(account.provider)
            when (val r = manager.signOut(account)) {
                is AuthResult.Success -> userMessages.report(
                    UserMessage("Disconnected ${manager.displayName}", UserMessage.Severity.INFO)
                )
                is AuthResult.Error -> userMessages.reportError(
                    "Couldn't disconnect ${manager.displayName}: ${r.message}", r.cause,
                )
                else -> userMessages.reportError("Couldn't disconnect ${manager.displayName}.")
            }
            refresh()
        }
    }

    private fun handleResult(manager: AuthManager, result: AuthResult<Account>) {
        when (result) {
            is AuthResult.Success -> userMessages.report(
                UserMessage(
                    "Connected ${manager.displayName} as ${result.value.email ?: result.value.displayName}",
                    UserMessage.Severity.INFO,
                )
            )
            AuthResult.Cancelled -> userMessages.report(
                UserMessage("Sign-in was cancelled.", UserMessage.Severity.WARNING)
            )
            AuthResult.NeedsInteractiveSignIn -> userMessages.report(
                UserMessage("Session expired — please sign in again.", UserMessage.Severity.WARNING)
            )
            is AuthResult.NotConfigured -> userMessages.reportError(
                "${manager.displayName} isn't configured: ${result.message}"
            )
            is AuthResult.Error -> userMessages.reportError(
                "Couldn't sign in to ${manager.displayName}: ${result.message}", result.cause,
            )
        }
    }

    private fun setBusy(providerKey: String, busy: Boolean) {
        _state.update { cur ->
            cur.copy(rows = cur.rows.map { if (it.providerKey == providerKey) it.copy(isBusy = busy) else it })
        }
    }
}
