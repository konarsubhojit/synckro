package com.synckro.ui.screens.accounts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.R
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.repository.AccountRepository
import com.synckro.domain.auth.Account
import com.synckro.domain.auth.AuthManager
import com.synckro.domain.auth.AuthManagerRegistry
import com.synckro.domain.auth.AuthResult
import com.synckro.domain.model.CloudProviderType
import com.synckro.util.error.UserMessage
import com.synckro.util.error.UserMessageReporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

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
class AccountsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val registry: AuthManagerRegistry,
        private val accountRepository: AccountRepository,
        private val syncPairDao: SyncPairDao,
        private val userMessages: UserMessageReporter,
    ) : ViewModel() {
        data class AccountRow(
            val providerDisplayName: String,
            val providerKey: String,
            val isConfigured: Boolean,
            val accounts: List<Account>,
            val isBusy: Boolean = false,
            /**
             * True when at least one sync pair for this provider has reported a
             * terminal authentication failure (token expired, account removed,
             * scope revoked, `MsalUiRequiredException`, missing client ID, …) on
             * its most recent run. The Accounts screen renders a "Re-authenticate"
             * CTA whenever this is true so the user has an obvious recovery path
             * and the periodic worker isn't silently looping in the background.
             */
            val needsReauth: Boolean = false,
        )

        data class UiState(
            val rows: List<AccountRow> = emptyList(),
            val isLoading: Boolean = true,
        )

        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state.asStateFlow()

        /** Latest snapshot of providers that have a pair stuck in NEEDS_REAUTH. */
        private var providersNeedingReauth: Set<CloudProviderType> = emptySet()

        init {
            // Observe re-auth signals from sync_pair so the CTA appears as soon as
            // SyncWorker persists a NEEDS_REAUTH outcome — without forcing the user
            // to navigate away and back.
            syncPairDao
                .observeProvidersNeedingReauth()
                .onEach { providers ->
                    providersNeedingReauth = providers.toSet()
                    _state.update { cur ->
                        cur.copy(
                            rows =
                                cur.rows.map { row ->
                                    row.copy(needsReauth = row.matchesAnyOf(providersNeedingReauth))
                                },
                        )
                    }
                }.launchIn(viewModelScope)
            refresh()
        }

        private fun AccountRow.matchesAnyOf(types: Set<CloudProviderType>): Boolean = types.any { it.name == providerKey }

        fun refresh() {
            viewModelScope.launch {
                Timber.d("AccountsViewModel.refresh()")
                val flagged = providersNeedingReauth
                val rows =
                    registry.all.map { manager ->
                        // Reconcile: merge accounts from manager's token cache with persisted accounts
                        val providerAccounts = reconcileAccounts(manager)
                        AccountRow(
                            providerDisplayName = manager.displayName,
                            providerKey = manager.providerType.name,
                            isConfigured = manager.isConfigured(),
                            accounts = providerAccounts,
                            needsReauth = manager.providerType in flagged,
                        )
                    }
                _state.value = UiState(rows = rows, isLoading = false)
            }
        }

        /**
         * Reconciles accounts for the given manager by merging token-cache accounts
         * with persisted accounts, ensuring consistency between the two.
         *
         * Deletions are skipped when the provider is not configured, to avoid
         * false-negative cache results wiping legitimately persisted accounts.
         * Metadata (displayName, email) is updated whenever it drifts from the cache.
         *
         * All persistent writes run inside a single Room transaction via
         * [AccountRepository.reconcileProvider], so a mid-way failure rolls back all writes.
         * Errors are logged and surfaced to the user via [UserMessageReporter], after which
         * we fall back to returning the persisted list so the UI isn't left empty.
         */
        private suspend fun reconcileAccounts(manager: AuthManager): List<Account> {
            if (!manager.isConfigured()) {
                // Provider not configured; skip cache query to avoid wiping persisted accounts
                // due to an empty result that reflects missing config, not a real sign-out.
                return accountRepository.getByProvider(manager.providerType)
            }

            val cachedAccounts = manager.currentAccounts()

            return try {
                accountRepository.reconcileProvider(manager.providerType, cachedAccounts)
                cachedAccounts
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Timber.e(t, "AccountsViewModel.reconcile: transactional reconcile failed for ${manager.providerType}")
                userMessages.reportError(
                    context.getString(
                        R.string.accounts_reconcile_failed_format,
                        manager.displayName,
                        t.message ?: t.javaClass.simpleName,
                    ),
                    t,
                )
                // Fall back to the persisted list so the UI reflects the last known good state
                // rather than an empty row.
                accountRepository.getByProvider(manager.providerType)
            }
        }

        /**
         * Kicks off an interactive sign-in for [providerKey]. The caller supplies
         * a [launchSignIn] lambda that, given an [AuthManager], actually calls
         * `signIn(activity)` — this keeps Activity references out of the
         * ViewModel and avoids leaks.
         */
        fun connect(
            providerKey: String,
            launchSignIn: suspend (AuthManager) -> AuthResult<Account>,
        ) {
            setBusy(providerKey, true)
            viewModelScope.launch {
                val manager =
                    registry.all.firstOrNull { it.providerType.name == providerKey } ?: run {
                        setBusy(providerKey, false)
                        userMessages.reportError(
                            context.getString(R.string.accounts_unknown_provider_format, providerKey),
                        )
                        return@launch
                    }
                val result =
                    runCatching { launchSignIn(manager) }.getOrElse { t ->
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
                val manager =
                    registry.find(account.provider) ?: run {
                        // Stale Account rows (e.g. from a removed provider) would
                        // otherwise crash the app; surface the same failure path as
                        // the "unknown provider" branch in [connect].
                        userMessages.reportError(
                            context.getString(
                                R.string.accounts_unknown_provider_format,
                                account.provider.name,
                            ),
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
                            ),
                        )
                    }
                    is AuthResult.Error -> {
                        // Do NOT delete from DB on sign-out failure — that would hide the
                        // error and leave MSAL's token cache and Room out of sync. The user
                        // can retry disconnect; a forced-local removal would be a separate flow.
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
                        // Same rationale as the Error branch: preserve DB row when sign-out
                        // didn't positively succeed.
                        userMessages.reportError(
                            context.getString(
                                R.string.accounts_disconnect_failed_generic_format,
                                manager.displayName,
                            ),
                        )
                    }
                }
                refresh()
            }
        }

        private suspend fun handleResult(
            manager: AuthManager,
            result: AuthResult<Account>,
        ) {
            when (result) {
                is AuthResult.Success -> {
                    // Persist the account to Room
                    accountRepository.upsert(result.value)
                    // Clear any lingering NEEDS_REAUTH state so the CTA disappears immediately
                    // instead of waiting for the next worker run to overwrite lastSyncResult.
                    runCatching { syncPairDao.clearNeedsReauthForProvider(manager.providerType) }
                        .onFailure { Timber.w(it, "AccountsViewModel: failed to clear NEEDS_REAUTH for ${manager.providerType}") }
                    userMessages.report(
                        UserMessage(
                            context.getString(
                                R.string.accounts_connected_format,
                                manager.displayName,
                                result.value.email ?: result.value.displayName,
                            ),
                            UserMessage.Severity.INFO,
                        ),
                    )
                }
                AuthResult.Cancelled ->
                    userMessages.report(
                        UserMessage(
                            context.getString(R.string.error_auth_cancelled),
                            UserMessage.Severity.WARNING,
                        ),
                    )
                AuthResult.NeedsInteractiveSignIn ->
                    userMessages.report(
                        UserMessage(
                            context.getString(R.string.error_auth_needs_interactive),
                            UserMessage.Severity.WARNING,
                        ),
                    )
                is AuthResult.NotConfigured ->
                    userMessages.reportError(
                        context.getString(
                            R.string.accounts_not_configured_detail_format,
                            manager.displayName,
                            result.message,
                        ),
                    )
                is AuthResult.Error ->
                    userMessages.reportError(
                        context.getString(
                            R.string.error_auth_failed_format,
                            manager.displayName,
                            result.message,
                        ),
                        result.cause,
                    )
            }
        }

        private fun setBusy(
            providerKey: String,
            busy: Boolean,
        ) {
            _state.update { cur ->
                cur.copy(rows = cur.rows.map { if (it.providerKey == providerKey) it.copy(isBusy = busy) else it })
            }
        }
    }
