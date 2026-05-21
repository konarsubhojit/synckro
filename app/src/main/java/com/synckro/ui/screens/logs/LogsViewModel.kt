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
import com.synckro.util.logging.LogExportConfig
import com.synckro.util.logging.LogVisibilityConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Quick time-window presets for the Logs filter row. */
enum class TimeWindow(val durationMs: Long) {
    LAST_HOUR(60 * 60 * 1_000L),
    LAST_24H(24 * 60 * 60 * 1_000L),
    LAST_7D(7 * 24 * 60 * 60 * 1_000L),
}

/**
 * ViewModel for the [LogsScreen].
 *
 * If a non-zero [pairId] was passed via [SavedStateHandle], the log is filtered
 * to that pair; otherwise all events are shown (global view).
 *
 * The screen-level filters ([levelFilter], [tagFilter], [accountFilter],
 * [providerFilter], [searchQuery], [timeWindowFilter]) are applied in-memory on
 * top of the already-observed event list — no extra DB queries fire when the
 * user toggles a chip or types in the search box.
 *
 * Account / provider filtering relies on a side-channel mapping
 * `pairId → (provider, accountId)` derived from the `sync_pair` table.
 * Events with `pairId == null` (global / non-pair events) are matched only
 * when the relevant filter is null, so toggling an Account or Provider
 * filter narrows the view to that scope without dropping pair-level events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LogsViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val syncEventRepository: SyncEventRepository,
        private val logExporter: LogExporter,
        accountRepository: AccountRepository,
        syncPairDao: SyncPairDao,
    ) : ViewModel() {
        /** 0 means "show all pairs". */
        private val savedStatePairId: Long = savedStateHandle[KEY_PAIR_ID] ?: 0L
        /**
         * Time source (millis since epoch). Defaults to [System.currentTimeMillis].
         * Exposed as `internal var` so unit tests can inject a fixed clock without
         * requiring a Hilt binding for [() -> Long].
         */
        internal var clock: () -> Long = System::currentTimeMillis

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
            /** Active time-window filter; null means show all time. */
            val timeWindowFilter: TimeWindow? = null,
            /** Free-text search applied to message + tag (case-insensitive). */
            val searchQuery: String = "",
            /** All known accounts, used to populate the Account filter chip row. */
            val knownAccounts: List<Account> = emptyList(),
            /** Active pair filter; null means show all pairs. */
            val pairIdFilter: Long? = null,
            /** True when no user filters or search are active. */
            val hasActiveFilters: Boolean = false,
        )

        private val _pairIdFilter = MutableStateFlow(savedStatePairId.takeIf { it != 0L })
        private val _levelFilter = MutableStateFlow<SyncEventLevel?>(null)
        private val _tagFilter = MutableStateFlow<String?>(null)
        private val _accountFilter = MutableStateFlow<String?>(null)
        private val _providerFilter = MutableStateFlow<CloudProviderType?>(null)
        private val _timeWindowFilter = MutableStateFlow<TimeWindow?>(null)
        private val _searchQuery = MutableStateFlow("")
        private val _exportConfig = MutableStateFlow(LogExportConfig())

        val exportConfig: StateFlow<LogExportConfig> = _exportConfig

        private val eventsFlow =
            _pairIdFilter.flatMapLatest { pairId ->
                if (pairId != null) {
                    syncEventRepository.observeForPair(pairId)
                } else {
                    syncEventRepository.observeAll()
                }
            }

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
                eventsFlow,
                combine(_levelFilter, _tagFilter, _accountFilter, _providerFilter, _timeWindowFilter) {
                    level, tag, account, provider, timeWindow ->
                    PartialFilters(level, tag, account, provider, timeWindow)
                }.combine(_searchQuery) { pf, query ->
                    Filters(pf.level, pf.tag, pf.account, pf.provider, query, pf.timeWindow)
                },
                _pairIdFilter,
                pairContexts,
                accountsFlow,
            ) { events, filters, pairIdFilter, contexts, accounts ->
                val q = filters.query.trim()
                val filtered = events.filter { e -> matches(e, filters, contexts, q) }
                UiState(
                    events = filtered,
                    isLoading = false,
                    levelFilter = filters.level,
                    tagFilter = filters.tag,
                    accountFilter = filters.account,
                    providerFilter = filters.provider,
                    timeWindowFilter = filters.timeWindow,
                    searchQuery = filters.query,
                    knownAccounts = accounts,
                    pairIdFilter = pairIdFilter,
                    hasActiveFilters =
                        pairIdFilter != null ||
                        filters.level != null ||
                            filters.tag != null ||
                            filters.account != null ||
                            filters.provider != null ||
                            filters.timeWindow != null ||
                            q.isNotEmpty(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = UiState(),
            )

        private data class PartialFilters(
            val level: SyncEventLevel?,
            val tag: String?,
            val account: String?,
            val provider: CloudProviderType?,
            val timeWindow: TimeWindow?,
        )

        private data class Filters(
            val level: SyncEventLevel?,
            val tag: String?,
            val account: String?,
            val provider: CloudProviderType?,
            val query: String,
            val timeWindow: TimeWindow?,
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
            if (f.timeWindow != null) {
                val cutoff = clock() - f.timeWindow.durationMs
                if (e.timestampMs < cutoff) return false
            }
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
                val result = runCatching { logExporter.export(_exportConfig.value) }
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

        /** Sets (or clears, when [pairId] is null/invalid) the active pair filter. */
        fun setPairFilter(pairId: Long?) {
            val normalized = pairId?.takeIf { it > 0L }
            _pairIdFilter.value = normalized
            savedStateHandle[KEY_PAIR_ID] = normalized ?: 0L
        }

        /** Sets (or clears, when [window] is null) the active time-window filter. */
        fun setTimeWindowFilter(window: TimeWindow?) {
            _timeWindowFilter.value = window
        }

        /** Updates the free-text search query (case-insensitive over message + tag). */
        fun setSearchQuery(query: String) {
            _searchQuery.value = query
        }

        fun setExportRedactPaths(enabled: Boolean) {
            _exportConfig.value = _exportConfig.value.copy(redactPaths = enabled)
        }

        fun setExportRedactAccountIds(enabled: Boolean) {
            _exportConfig.value = _exportConfig.value.copy(redactAccountIds = enabled)
        }

        /** Clears every active filter and resets the search query. */
        fun clearFilters() {
            setPairFilter(null)
            _levelFilter.value = null
            _tagFilter.value = null
            _accountFilter.value = null
            _providerFilter.value = null
            _timeWindowFilter.value = null
            _searchQuery.value = ""
        }

        companion object {
            const val KEY_PAIR_ID = "pairId"
        }
    }
