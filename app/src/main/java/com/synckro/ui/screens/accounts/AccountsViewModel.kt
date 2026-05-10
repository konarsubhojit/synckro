package com.synckro.ui.screens.accounts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.R
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.repository.AccountRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.domain.auth.Account
import com.synckro.domain.auth.AccountKey
import com.synckro.domain.auth.AuthManager
import com.synckro.domain.auth.AuthManagerRegistry
import com.synckro.domain.auth.AuthResult
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.domain.model.SyncPair
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
        private val syncPairRepository: SyncPairRepository,
        private val syncScheduler: SyncScheduler,
        private val userMessages: UserMessageReporter,
        private val syncEventRepository: SyncEventRepository,
    ) : ViewModel() {
        /**
         * A single connected account, augmented with a flag that indicates
         * whether this specific account needs re-authentication.
         */
        data class AccountItem(
            val account: Account,
            val needsReauth: Boolean = false,
        )

        data class AccountRow(
            val providerDisplayName: String,
            val providerKey: String,
            val isConfigured: Boolean,
            val accounts: List<AccountItem>,
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

        /**
         * Pending disconnect of [account]. When [orphanedPairs] is non-empty the
         * UI must surface a confirmation dialog and call [confirmDisconnectDelete],
         * [confirmDisconnectReassign] or [cancelDisconnect]. When it is empty the
         * disconnect proceeds immediately.
         *
         * [reassignableAccounts] lists every other account on the same provider
         * that the user can re-bind the orphaned pairs to (always excluding the
         * one being disconnected).
         */
        data class PendingDisconnect(
            val account: Account,
            val providerDisplayName: String,
            val orphanedPairs: List<SyncPair>,
            val reassignableAccounts: List<Account>,
        )

        data class UiState(
            val rows: List<AccountRow> = emptyList(),
            val isLoading: Boolean = true,
            /**
             * Set when the user requested a disconnect that needs explicit
             * confirmation (i.e. the account currently owns one or more sync pairs).
             */
            val pendingDisconnect: PendingDisconnect? = null,
        )

        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state.asStateFlow()

        /** Latest snapshot of accounts that have a pair stuck in NEEDS_REAUTH. */
        private var accountsNeedingReauth: Set<AccountKey> = emptySet()

        init {
            // Observe re-auth signals from sync_pair so the CTA appears as soon as
            // SyncWorker persists a NEEDS_REAUTH outcome — without forcing the user
            // to navigate away and back.
            syncPairDao
                .observeAccountsNeedingReauth()
                .onEach { rows ->
                    accountsNeedingReauth =
                        rows.mapTo(LinkedHashSet(rows.size)) { row ->
                            AccountKey(provider = row.provider, accountId = row.accountId)
                        }
                    val providers = accountsNeedingReauth.mapTo(HashSet(accountsNeedingReauth.size)) { it.provider }
                    _state.update { cur ->
                        cur.copy(
                            rows =
                                cur.rows.map { row ->
                                    val providerType = CloudProviderType.entries.firstOrNull { it.name == row.providerKey }
                                    row.copy(
                                        needsReauth = row.matchesAnyOf(providers),
                                        accounts =
                                            row.accounts.map { item ->
                                                item.copy(
                                                    needsReauth = providerType != null &&
                                                        AccountKey(
                                                            provider = providerType,
                                                            accountId = item.account.id,
                                                        ) in accountsNeedingReauth,
                                                )
                                            },
                                    )
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
                val flaggedProviders = accountsNeedingReauth.mapTo(HashSet(accountsNeedingReauth.size)) { it.provider }
                val rows =
                    registry.all.map { manager ->
                        // Reconcile: merge accounts from manager's token cache with persisted accounts
                        val providerAccounts = reconcileAccounts(manager)
                        val accountItems =
                            providerAccounts.map { account ->
                                AccountItem(
                                    account = account,
                                    needsReauth = AccountKey(
                                        provider = manager.providerType,
                                        accountId = account.id,
                                    ) in accountsNeedingReauth,
                                )
                            }
                        AccountRow(
                            providerDisplayName = manager.displayName,
                            providerKey = manager.providerType.name,
                            isConfigured = manager.isConfigured(),
                            accounts = accountItems,
                            needsReauth = manager.providerType in flaggedProviders,
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
                if (cachedAccounts.isNotEmpty()) {
                    syncEventRepository.log(
                        pairId = null,
                        level = SyncEventLevel.DEBUG,
                        tag = SyncEventTag.Account,
                        message = "Reconcile ${manager.displayName}: ${cachedAccounts.size} account(s)",
                    )
                }
                cachedAccounts
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Timber.e(t, "AccountsViewModel.reconcile: transactional reconcile failed for ${manager.providerType}")
                syncEventRepository.log(
                    pairId = null,
                    level = SyncEventLevel.WARN,
                    tag = SyncEventTag.Account,
                    message = "Reconcile ${manager.displayName} failed: ${t.message}",
                )
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
                syncEventRepository.log(
                    pairId = null,
                    level = SyncEventLevel.INFO,
                    tag = SyncEventTag.Auth,
                    message = "signIn ${manager.displayName}: started",
                )
                val result =
                    runCatching { launchSignIn(manager) }.getOrElse { t ->
                        // Never swallow cooperative cancellation — let the coroutine
                        // machinery propagate it so viewModelScope cancellation works.
                        if (t is CancellationException) throw t
                        setBusy(providerKey, false)
                        syncEventRepository.log(
                            pairId = null,
                            level = SyncEventLevel.ERROR,
                            tag = SyncEventTag.Auth,
                            message = "signIn ${manager.displayName}: error — ${t.message}",
                        )
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

        /**
         * Entry point invoked from the Accounts screen when the user taps
         * "Disconnect" on an account row. If the account currently owns one or
         * more sync pairs we stage a [PendingDisconnect] so the screen can
         * surface a confirmation dialog (Delete / Reassign / Cancel). When no
         * pairs are bound to the account the disconnect proceeds immediately.
         */
        fun disconnect(account: Account) {
            viewModelScope.launch {
                val orphans = runCatching { syncPairRepository.getByAccountId(account.id) }
                    .getOrElse { t ->
                        if (t is CancellationException) throw t
                        Timber.w(t, "AccountsViewModel.disconnect: failed to query pairs for ${account.id}")
                        // If we can't read the pair list, fall through to the no-confirmation
                        // path — disconnect is still a user-initiated action and we shouldn't
                        // refuse it just because the orphan check failed.
                        emptyList()
                    }
                if (orphans.isEmpty()) {
                    performDisconnect(account)
                    return@launch
                }
                val manager = registry.find(account.provider)
                val providerDisplayName = manager?.displayName ?: account.provider.name
                // Other accounts on the same provider that the user can re-bind orphan pairs to.
                val reassignable = accountRepository
                    .getByProvider(account.provider)
                    .filter { it.id != account.id }
                _state.update { cur ->
                    cur.copy(
                        pendingDisconnect = PendingDisconnect(
                            account = account,
                            providerDisplayName = providerDisplayName,
                            orphanedPairs = orphans,
                            reassignableAccounts = reassignable,
                        ),
                    )
                }
            }
        }

        /** Cancels the pending disconnect dialog without making any changes. */
        fun cancelDisconnect() {
            _state.update { it.copy(pendingDisconnect = null) }
        }

        /**
         * Confirms the pending disconnect by deleting every orphaned sync pair
         * along with the account. Cancels each pair's scheduled work first so
         * the periodic worker doesn't keep firing for a deleted pair.
         */
        fun confirmDisconnectDelete() {
            val pending = _state.value.pendingDisconnect ?: return
            _state.update { it.copy(pendingDisconnect = null) }
            viewModelScope.launch {
                pending.orphanedPairs.forEach { pair ->
                    runCatching { syncScheduler.cancel(pair.id) }
                        .onFailure { Timber.w(it, "Failed to cancel scheduler for pair ${pair.id}") }
                }
                runCatching { syncPairRepository.deleteByAccountId(pending.account.id) }
                    .onFailure { t ->
                        Timber.e(t, "Failed to delete pairs for account ${pending.account.id}")
                    }
                syncEventRepository.log(
                    pairId = null,
                    level = SyncEventLevel.INFO,
                    tag = SyncEventTag.Account,
                    message =
                        "Disconnect ${pending.providerDisplayName} (${pending.account.email ?: pending.account.id}): " +
                            "deleted ${pending.orphanedPairs.size} orphan pair(s)",
                )
                performDisconnect(pending.account)
            }
        }

        /**
         * Confirms the pending disconnect by reassigning every orphaned pair
         * to [toAccountId] (an account on the same provider) before signing
         * out. The new account must already exist in [PendingDisconnect.reassignableAccounts].
         */
        fun confirmDisconnectReassign(toAccountId: String) {
            val pending = _state.value.pendingDisconnect ?: return
            if (pending.reassignableAccounts.none { it.id == toAccountId }) {
                Timber.w("confirmDisconnectReassign: target $toAccountId not in reassignable list; ignoring")
                return
            }
            _state.update { it.copy(pendingDisconnect = null) }
            viewModelScope.launch {
                runCatching {
                    syncPairRepository.reassignAccountId(
                        fromAccountId = pending.account.id,
                        toAccountId = toAccountId,
                    )
                }.onFailure { t ->
                    Timber.e(t, "Failed to reassign pairs from ${pending.account.id} to $toAccountId")
                }
                syncEventRepository.log(
                    pairId = null,
                    level = SyncEventLevel.INFO,
                    tag = SyncEventTag.Account,
                    message =
                        "Disconnect ${pending.providerDisplayName}: reassigned ${pending.orphanedPairs.size} pair(s) " +
                            "to $toAccountId",
                )
                performDisconnect(pending.account)
            }
        }

        /**
         * Performs the actual sign-out + Room delete, identical to the
         * pre-confirmation behaviour. Called once any required orphan handling
         * (delete or reassign) has completed.
         */
        private fun performDisconnect(account: Account) {
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
                syncEventRepository.log(
                    pairId = null,
                    level = SyncEventLevel.INFO,
                    tag = SyncEventTag.Auth,
                    message = "signOut ${manager.displayName} (${account.email ?: account.id}): started",
                )
                when (val r = manager.signOut(account)) {
                    is AuthResult.Success -> {
                        // Remove from Room after successful sign-out
                        accountRepository.delete(account.id)
                        syncEventRepository.log(
                            pairId = null,
                            level = SyncEventLevel.INFO,
                            tag = SyncEventTag.Auth,
                            message = "signOut ${manager.displayName} (${account.email ?: account.id}): success",
                        )
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
                        syncEventRepository.log(
                            pairId = null,
                            level = SyncEventLevel.ERROR,
                            tag = SyncEventTag.Auth,
                            message = "signOut ${manager.displayName}: error — ${r.message}",
                        )
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
                        syncEventRepository.log(
                            pairId = null,
                            level = SyncEventLevel.WARN,
                            tag = SyncEventTag.Auth,
                            message = "signOut ${manager.displayName}: unexpected result $r",
                        )
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
                    runCatching { syncPairDao.clearNeedsReauthForAccount(result.value.id) }
                        .onFailure { Timber.w(it, "AccountsViewModel: failed to clear NEEDS_REAUTH for accountId=${result.value.id}") }
                    syncEventRepository.log(
                        pairId = null,
                        level = SyncEventLevel.INFO,
                        tag = SyncEventTag.Auth,
                        message = "signIn ${manager.displayName} (${result.value.email ?: result.value.id}): success",
                    )
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
                AuthResult.Cancelled -> {
                    syncEventRepository.log(
                        pairId = null,
                        level = SyncEventLevel.INFO,
                        tag = SyncEventTag.Auth,
                        message = "signIn ${manager.displayName}: cancelled",
                    )
                    userMessages.report(
                        UserMessage(
                            context.getString(R.string.error_auth_cancelled),
                            UserMessage.Severity.WARNING,
                        ),
                    )
                }
                AuthResult.NeedsInteractiveSignIn -> {
                    syncEventRepository.log(
                        pairId = null,
                        level = SyncEventLevel.WARN,
                        tag = SyncEventTag.Auth,
                        message = "signIn ${manager.displayName}: needs interactive sign-in",
                    )
                    userMessages.report(
                        UserMessage(
                            context.getString(R.string.error_auth_needs_interactive),
                            UserMessage.Severity.WARNING,
                        ),
                    )
                }
                is AuthResult.NotConfigured -> {
                    syncEventRepository.log(
                        pairId = null,
                        level = SyncEventLevel.WARN,
                        tag = SyncEventTag.Auth,
                        message = "signIn ${manager.displayName}: not configured — ${result.message}",
                    )
                    userMessages.reportError(
                        context.getString(
                            R.string.accounts_not_configured_detail_format,
                            manager.displayName,
                            result.message,
                        ),
                    )
                }
                is AuthResult.Error -> {
                    syncEventRepository.log(
                        pairId = null,
                        level = SyncEventLevel.ERROR,
                        tag = SyncEventTag.Auth,
                        message = "signIn ${manager.displayName}: error — ${result.message}",
                    )
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
