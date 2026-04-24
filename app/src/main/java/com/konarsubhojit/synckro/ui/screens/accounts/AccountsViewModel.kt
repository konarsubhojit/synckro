package com.konarsubhojit.synckro.ui.screens.accounts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konarsubhojit.synckro.R
import com.konarsubhojit.synckro.data.repository.AccountRepository
import com.konarsubhojit.synckro.domain.auth.Account
import com.konarsubhojit.synckro.domain.auth.AuthManager
import com.konarsubhojit.synckro.domain.auth.AuthManagerRegistry
import com.konarsubhojit.synckro.domain.auth.AuthResult
import com.konarsubhojit.synckro.util.error.UserMessage
import com.konarsubhojit.synckro.util.error.UserMessageReporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * State machine for the accounts screen. Exposes one [AccountRow] per
 * registered [AuthManager] — whether or not any user has signed in yet.
 * Reports every failure through [UserMessageReporter] so the user actually
 * sees what went wrong; this is the direct fix for the "+ button does
 * nothing" class of silent failure.
 *
 * Now with account persistence: signed-in accounts are saved to Room via
 * [AccountRepository] and reconciled with each provider's token cache on refresh.
 */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: AuthManagerRegistry,
    private val accountRepository: AccountRepository,
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
            Timber.d("AccountsViewModel.refresh()")
            val rows = registry.all.map { manager ->
                // Reconcile: merge accounts from manager's token cache with persisted accounts
                val providerAccounts = reconcileAccounts(manager)
                AccountRow(
                    providerDisplayName = manager.displayName,
                    providerKey = manager.providerType.name,
                    isConfigured = manager.isConfigured(),
                    accounts = providerAccounts,
                )
            }
            _state.value = UiState(rows = rows, isLoading = false)
        }
    }

    /**
     * Reconciles accounts for the given manager by merging token-cache accounts
     * with persisted accounts, ensuring consistency between the two.
     */
    private suspend fun reconcileAccounts(manager: AuthManager): List<Account> {
        val cachedAccounts = manager.currentAccounts()
        val persistedAccounts = accountRepository.getByProvider(manager.providerType)

        // If an account exists in the cache but not in the DB, persist it
        cachedAccounts.forEach { cached ->
            if (persistedAccounts.none { it.id == cached.id }) {
                Timber.i("AccountsViewModel.reconcile: persisting cached account ${cached.id}")
                accountRepository.upsert(cached)
            }
        }

        // If an account exists in the DB but not in the cache, remove it from DB
        persistedAccounts.forEach { persisted ->
            if (cachedAccounts.none { it.id == persisted.id }) {
                Timber.i("AccountsViewModel.reconcile: removing stale persisted account ${persisted.id}")
                accountRepository.delete(persisted.id)
            }
        }

        return cachedAccounts
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
                userMessages.reportError(
                    context.getString(R.string.accounts_unknown_provider_format, providerKey)
                )
                return@launch
            }
            val result = runCatching { launchSignIn(manager) }.getOrElse { t ->
                // Never swallow cooperative cancellation — let the coroutine
                // machinery propagate it so viewModelScope cancellation works.
                if (t is CancellationException) throw t
                setBusy(providerKey, false)
                userMessages.reportError(
                    context.getString(
                        R.string.error_auth_failed_format,
                        manager.displayName,
                        t.message ?: t.javaClass.simpleName,
                    ),
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
            val manager = registry.find(account.provider) ?: run {
                // Stale Account rows (e.g. from a removed provider) would
                // otherwise crash the app; surface the same failure path as
                // the "unknown provider" branch in [connect].
                userMessages.reportError(
                    context.getString(
                        R.string.accounts_unknown_provider_format,
                        account.provider.name,
                    )
                )
                refresh()
                return@launch
            }
            when (val r = manager.signOut(account)) {
                is AuthResult.Success -> {
                    // Remove from Room after successful sign-out
                    accountRepository.delete(account.id)
                    userMessages.report(
                        UserMessage(
                            context.getString(R.string.accounts_disconnected_format, manager.displayName),
                            UserMessage.Severity.INFO,
                        )
                    )
                }
                is AuthResult.Error -> {
                    // Even if sign-out failed, remove from DB as a best-effort cleanup
                    accountRepository.delete(account.id)
                    userMessages.reportError(
                        context.getString(
                            R.string.accounts_disconnect_failed_format,
                            manager.displayName,
                            r.message,
                        ),
                        r.cause,
                    )
                }
                else -> {
                    accountRepository.delete(account.id)
                    userMessages.reportError(
                        context.getString(
                            R.string.accounts_disconnect_failed_generic_format,
                            manager.displayName,
                        )
                    )
                }
            }
            refresh()
        }
    }

    private suspend fun handleResult(manager: AuthManager, result: AuthResult<Account>) {
        when (result) {
            is AuthResult.Success -> {
                // Persist the account to Room
                accountRepository.upsert(result.value)
                userMessages.report(
                    UserMessage(
                        context.getString(
                            R.string.accounts_connected_format,
                            manager.displayName,
                            result.value.email ?: result.value.displayName,
                        ),
                        UserMessage.Severity.INFO,
                    )
                )
            }
            AuthResult.Cancelled -> userMessages.report(
                UserMessage(
                    context.getString(R.string.error_auth_cancelled),
                    UserMessage.Severity.WARNING,
                )
            )
            AuthResult.NeedsInteractiveSignIn -> userMessages.report(
                UserMessage(
                    context.getString(R.string.error_auth_needs_interactive),
                    UserMessage.Severity.WARNING,
                )
            )
            is AuthResult.NotConfigured -> userMessages.reportError(
                context.getString(
                    R.string.accounts_not_configured_detail_format,
                    manager.displayName,
                    result.message,
                )
            )
            is AuthResult.Error -> userMessages.reportError(
                context.getString(
                    R.string.error_auth_failed_format,
                    manager.displayName,
                    result.message,
                ),
                result.cause,
            )
        }
    }

    private fun setBusy(providerKey: String, busy: Boolean) {
        _state.update { cur ->
            cur.copy(rows = cur.rows.map { if (it.providerKey == providerKey) it.copy(isBusy = busy) else it })
        }
    }
}
