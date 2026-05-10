package com.synckro.ui.screens.pickfolder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.domain.auth.AuthManagerRegistry
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.provider.CloudProvider
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.provider.RemoteFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for [PickRemoteFolderScreen]. Browses a cloud provider's folder
 * hierarchy level-by-level so the user can select a sync-destination folder.
 *
 * The [CloudProviderType] is received from navigation via [SavedStateHandle] under
 * [ARG_PROVIDER]. The breadcrumb stack tracks the navigation path from the root
 * to the current folder.
 */
@HiltViewModel
class PickRemoteFolderViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val providerFactories: Map<CloudProviderType, @JvmSuppressWildcards CloudProviderFactory>,
        private val authRegistry: AuthManagerRegistry,
    ) : ViewModel() {
        /** One entry in the breadcrumb trail; [folderId] is null for the root level. */
        data class BreadcrumbEntry(
            val folderId: String?,
            val folderName: String,
        )

        data class UiState(
            val isLoading: Boolean = false,
            val error: String? = null,
            /** Folders at the current level (files are filtered out). */
            val items: List<RemoteFile> = emptyList(),
            /** Navigation path from root to the current folder. Always has at least one entry. */
            val breadcrumbs: List<BreadcrumbEntry> = listOf(BreadcrumbEntry(null, "/")),
            val currentFolderId: String? = null,
        )

        val providerType: CloudProviderType =
            run {
                val arg = savedStateHandle.get<String>(ARG_PROVIDER)
                val resolved = arg?.let { runCatching { CloudProviderType.valueOf(it) }.getOrNull() }
                if (resolved == null && arg != null) {
                    Timber.w("PickRemoteFolderViewModel: unknown provider arg '%s', falling back to GOOGLE_DRIVE", arg)
                }
                resolved ?: CloudProviderType.GOOGLE_DRIVE
            }

        private val requestedAccountId: String? = savedStateHandle.get<String>(ARG_ACCOUNT_ID)

        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state.asStateFlow()

        init {
            loadFolder(null)
        }

        /** Navigate into [folder], pushing it onto the breadcrumb trail and loading its contents. */
        fun navigateInto(folder: RemoteFile) {
            _state.update { s ->
                s.copy(breadcrumbs = s.breadcrumbs + BreadcrumbEntry(folder.id, folder.name))
            }
            loadFolder(folder.id)
        }

        /**
         * Navigate up one level, popping the top breadcrumb.
         *
         * @return `true` if navigation up succeeded, `false` if already at the root level.
         */
        fun navigateUp(): Boolean {
            val crumbs = _state.value.breadcrumbs
            if (crumbs.size <= 1) return false
            val newCrumbs = crumbs.dropLast(1)
            _state.update { it.copy(breadcrumbs = newCrumbs) }
            loadFolder(newCrumbs.last().folderId)
            return true
        }

        /** Navigate to the breadcrumb at [index], discarding deeper entries. */
        fun navigateToBreadcrumb(index: Int) {
            val crumbs = _state.value.breadcrumbs
            if (index !in crumbs.indices || index == crumbs.lastIndex) return
            val newCrumbs = crumbs.take(index + 1)
            _state.update { it.copy(breadcrumbs = newCrumbs) }
            loadFolder(newCrumbs.last().folderId)
        }

        /** Retry loading the current folder after an error. */
        fun retry() {
            loadFolder(_state.value.currentFolderId)
        }

        private fun loadFolder(folderId: String?) {
            _state.update { it.copy(isLoading = true, error = null, currentFolderId = folderId) }
            viewModelScope.launch {
                runCatching {
                    val p = resolveProvider()
                    p.list(folderId)
                        .filter { it.isFolder }
                        .sortedBy { it.name.lowercase() }
                }.onSuccess { folders ->
                    _state.update { it.copy(isLoading = false, items = folders) }
                }.onFailure { t ->
                    Timber.e(t, "PickRemoteFolderViewModel: failed to list folder %s", folderId)
                    _state.update {
                        it.copy(isLoading = false, error = t.message ?: "Failed to load folders")
                    }
                }
            }
        }

        private suspend fun resolveProvider(): CloudProvider {
            val factory =
                providerFactories[providerType]
                    ?: error("Provider not available: $providerType")
            if (providerType == CloudProviderType.FAKE) return factory.providerFor("__fake__")

            val manager = authRegistry.find(providerType)
            val accountId =
                when {
                    !requestedAccountId.isNullOrBlank() -> requestedAccountId
                    manager == null -> error("No AuthManager registered for provider: $providerType")
                    else -> manager.currentAccounts().firstOrNull()?.id
                }
            return factory.providerFor(accountId ?: error("No signed-in account available for provider: $providerType"))
        }

        companion object {
            /** Navigation argument key for the [CloudProviderType] to browse. */
            const val ARG_PROVIDER = "provider"
            const val ARG_ACCOUNT_ID = "accountId"
        }
    }
