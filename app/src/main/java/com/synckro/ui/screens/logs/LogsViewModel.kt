package com.synckro.ui.screens.logs

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.repository.AccountRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.auth.Account
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.util.logging.LogExporter
import com.synckro.util.logging.LogVisibilityConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the [LogsScreen].
 *
 * If a non-zero [pairId] was passed via [SavedStateHandle], the log is filtered
 * to that pair; otherwise all events are shown (global view).
 *
 * The screen-level filters ([levelFilter], [tagFilter], [accountFilter],
 * [providerFilter], [searchQuery]) are applied in-memory on top of the
 * already-observed event list — no extra DB queries fire when the user
 * toggles a chip or types in the search box.
 *
 * Account / provider filtering relies on a side-channel mapping
 * `pairId → (provider, accountId)` derived from the `sync_pair` table.
 * Events with `pairId == null` (global / non-pair events) are matched only
 * when the relevant filter is null, so toggling an Account or Provider
 * filter narrows the view to that scope without dropping pair-level events.
 */
@HiltViewModel
class LogsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val syncEventRepository: SyncEventRepository,
        private val logExporter: LogExporter,
        accountRepository: AccountRepository,
        syncPairDao: SyncPairDao,
    ) : ViewModel() {
        /** 0 means "show all pairs". */
        val pairId: Long = savedStateHandle[KEY_PAIR_ID] ?: 0L

        data class UiState(
            val events: List<SyncEvent> = emptyList(),
            val isLoading: Boolean = true,
            /** Active level filter; null means show all levels. */
            val levelFilter: SyncEventLevel? = null,
            /** Active tag filter; null means show all tags. */
            val tagFilter: String? = null,
            /** Active account filter (account id); null means show all. */
            val accountFilter: String? = null,
            /** Active provider filter; null means show all providers. */
            val providerFilter: CloudProviderType? = null,
            /** Free-text search applied to message + tag (case-insensitive). */
            val searchQuery: String = "",
            /** All known accounts, used to populate the Account filter chip row. */
            val knownAccounts: List<Account> = emptyList(),
            /** True when no user filters or search are active. */
            val hasActiveFilters: Boolean = false,
        )

        private val _levelFilter = MutableStateFlow<SyncEventLevel?>(null)
        private val _tagFilter = MutableStateFlow<String?>(null)
        private val _accountFilter = MutableStateFlow<String?>(null)
        private val _providerFilter = MutableStateFlow<CloudProviderType?>(null)
        private val _searchQuery = MutableStateFlow("")

        /** Maps pairId → (provider, accountId). Used to resolve account/provider filters. */
        private val pairContexts: StateFlow<Map<Long, Pair<CloudProviderType, String?>>> =
            syncPairDao.observeAll()
                .map { entities ->
                    entities.associate { it.id to (it.provider to it.accountId) }
                }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

        private val accountsFlow: StateFlow<List<Account>> =
            accountRepository.observeAll()
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val state: StateFlow<UiState> =
            combine(
                if (pairId != 0L) syncEventRepository.observeForPair(pairId) else syncEventRepository.observeAll(),
                combine(_levelFilter, _tagFilter, _accountFilter, _providerFilter, _searchQuery) {
                    level, tag, account, provider, query ->
                    Filters(level, tag, account, provider, query)
                },
                pairContexts,
                accountsFlow,
            ) { events, filters, contexts, accounts ->
                val q = filters.query.trim()
                val filtered = events.filter { e -> matches(e, filters, contexts, q) }
                UiState(
                    events = filtered,
                    isLoading = false,
                    levelFilter = filters.level,
                    tagFilter = filters.tag,
                    accountFilter = filters.account,
                    providerFilter = filters.provider,
                    searchQuery = filters.query,
                    knownAccounts = accounts,
                    hasActiveFilters =
                        filters.level != null ||
                            filters.tag != null ||
                            filters.account != null ||
                            filters.provider != null ||
                            q.isNotEmpty(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = UiState(),
            )

        private data class Filters(
            val level: SyncEventLevel?,
            val tag: String?,
            val account: String?,
            val provider: CloudProviderType?,
            val query: String,
        )

        private fun matches(
            e: SyncEvent,
            f: Filters,
            contexts: Map<Long, Pair<CloudProviderType, String?>>,
            query: String,
        ): Boolean {
            // Baseline build-variant gate: hide DEBUG entries in release regardless
            // of the user-selected level filter. Applied before any user filter so
            // even an explicit DEBUG selection (which shouldn't be reachable from
            // the UI in release) cannot surface DEBUG rows.
            if (!LogVisibilityConfig.isVisible(e.level)) return false
            if (f.level != null && e.level != f.level) return false
            if (f.tag != null && e.tag != f.tag) return false
            if (f.account != null) {
                val accId = e.pairId?.let { contexts[it]?.second }
                if (accId != f.account) return false
            }
            if (f.provider != null) {
                val prov = e.pairId?.let { contexts[it]?.first }
                if (prov != f.provider) return false
            }
            if (query.isNotEmpty()) {
                if (!e.message.contains(query, ignoreCase = true) &&
                    !e.tag.contains(query, ignoreCase = true)
                ) {
                    return false
                }
            }
            return true
        }

        /** One-shot export result: [Result.success] with the cache [Uri] on success,
         *  [Result.failure] on error. */
        private val _exportResult = MutableSharedFlow<Result<Uri>>()
        val exportResult: SharedFlow<Result<Uri>> = _exportResult

        /**
         * Kicks off a bundled log export (structured events + Timber files) and emits
         * the result via [exportResult].
         *
         * Writes a structured [SyncEventTag.Export] entry before and after the export
         * so the action itself is visible in the logs screen.
         */
        fun exportLogs() {
            viewModelScope.launch {
                syncEventRepository.log(
                    pairId = null,
                    level = SyncEventLevel.INFO,
                    tag = SyncEventTag.Export,
                    message = "Export started",
                )
                val result = runCatching { logExporter.export() }
                result.fold(
                    onSuccess = {
                        syncEventRepository.log(
                            pairId = null,
                            level = SyncEventLevel.INFO,
                            tag = SyncEventTag.Export,
                            message = "Export succeeded",
                        )
                    },
                    onFailure = { e ->
                        syncEventRepository.log(
                            pairId = null,
                            level = SyncEventLevel.ERROR,
                            tag = SyncEventTag.Export,
                            message = "Export failed: ${e.message}",
                        )
                    },
                )
                _exportResult.emit(result)
            }
        }

        /** Sets (or clears, when [level] is null) the active level filter. */
        fun setLevelFilter(level: SyncEventLevel?) {
            _levelFilter.value = level
        }

        /** Sets (or clears, when [tag] is null) the active tag filter. */
        fun setTagFilter(tag: String?) {
            _tagFilter.value = tag
        }

        /** Sets (or clears, when [accountId] is null) the active account filter. */
        fun setAccountFilter(accountId: String?) {
            _accountFilter.value = accountId
        }

        /** Sets (or clears, when [provider] is null) the active provider filter. */
        fun setProviderFilter(provider: CloudProviderType?) {
            _providerFilter.value = provider
        }

        /** Updates the free-text search query (case-insensitive over message + tag). */
        fun setSearchQuery(query: String) {
            _searchQuery.value = query
        }

        /** Clears every active filter and resets the search query. */
        fun clearFilters() {
            _levelFilter.value = null
            _tagFilter.value = null
            _accountFilter.value = null
            _providerFilter.value = null
            _searchQuery.value = ""
        }

        companion object {
            const val KEY_PAIR_ID = "pairId"
        }
    }
