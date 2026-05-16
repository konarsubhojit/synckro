package com.synckro.ui.screens.pickfolder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.domain.auth.Account
import com.synckro.domain.auth.AuthManager
import com.synckro.domain.auth.AuthManagerRegistry
import com.synckro.domain.auth.AuthResult
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.provider.CloudProvider
import com.synckro.domain.provider.CloudProviderException
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.provider.RemoteFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

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
            /** True while an interactive re-authentication flow is in progress. */
            val isReauthenticating: Boolean = false,
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

        /**
         * Emits [Unit] whenever a folder load fails due to an expired or missing
         * access token ([CloudProviderException.AuthenticationRequired]).  The screen
         * collects this flow and responds by calling [signInAndRetry] with a lambda
         * that launches the interactive sign-in UI.
         *
         * [extraBufferCapacity] = 1 so the event is never dropped even if the
         * collector hasn't resumed yet (e.g. during a recomposition).
         */
        private val _reauthEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val reauthEvent: SharedFlow<Unit> = _reauthEvent

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

        /** Creates a new folder in the currently displayed location and reloads that location. */
        fun createFolder(name: String) {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty()) return

            val parentId = _state.value.currentFolderId ?: ROOT_FOLDER_ID
            _state.update { it.copy(isLoading = true, error = null) }
            viewModelScope.launch {
                runCatching {
                    val p = resolveProvider()
                    p.createFolder(parentId = parentId, name = trimmedName)
                    p.list(_state.value.currentFolderId)
                        .filter { it.isFolder }
                        .sortedBy { it.name.lowercase() }
                }.onSuccess { folders ->
                    _state.update { it.copy(isLoading = false, items = folders) }
                }.onFailure { t ->
                    Timber.e(t, "PickRemoteFolderViewModel: failed to create folder '%s' in %s", trimmedName, parentId)
                    if (t is CloudProviderException.AuthenticationRequired) {
                        _state.update { it.copy(isLoading = true, isReauthenticating = true) }
                        _reauthEvent.tryEmit(Unit)
                    } else {
                        _state.update {
                            it.copy(isLoading = false, error = t.message ?: "Failed to load folders")
                        }
                    }
                }
            }
        }

        /**
         * Called by the screen after it has received a [reauthEvent].  Runs the
         * interactive sign-in flow supplied by [launchSignIn] (which has access to
         * the live Activity) and, on success, retries [loadFolder] automatically.
         *
         * Keeping Activity references in the composable layer (not the ViewModel)
         * matches the pattern used by [com.synckro.ui.screens.accounts.AccountsScreen].
         */
        fun signInAndRetry(launchSignIn: suspend (AuthManager) -> AuthResult<Account>) {
            viewModelScope.launch {
                val manager = authRegistry.find(providerType) ?: run {
                    Timber.e("PickRemoteFolderViewModel.signInAndRetry: no AuthManager for %s", providerType)
                    _state.update { it.copy(isLoading = false, isReauthenticating = false, error = "No auth manager for $providerType") }
                    return@launch
                }
                _state.update { it.copy(isLoading = true, isReauthenticating = true, error = null) }
                Timber.i("PickRemoteFolderViewModel.signInAndRetry: launching interactive sign-in for %s", providerType)

                val result =
                    runCatching { launchSignIn(manager) }.getOrElse { t ->
                        if (t is CancellationException) throw t
                        Timber.e(t, "PickRemoteFolderViewModel.signInAndRetry: sign-in threw an exception")
                        _state.update { it.copy(isLoading = false, isReauthenticating = false, error = t.message ?: "Sign-in failed") }
                        return@launch
                    }

                when (result) {
                    is AuthResult.Success -> {
                        Timber.i("PickRemoteFolderViewModel.signInAndRetry: sign-in succeeded, retrying folder load")
                        _state.update { it.copy(isReauthenticating = false) }
                        loadFolder(_state.value.currentFolderId)
                    }
                    is AuthResult.Cancelled -> {
                        Timber.i("PickRemoteFolderViewModel.signInAndRetry: sign-in cancelled by user")
                        _state.update {
                            it.copy(
                                isLoading = false,
                                isReauthenticating = false,
                                error = "Sign-in was cancelled. Tap Retry to try again.",
                            )
                        }
                    }
                    is AuthResult.NeedsInteractiveSignIn -> {
                        Timber.w("PickRemoteFolderViewModel.signInAndRetry: sign-in returned NeedsInteractiveSignIn")
                        _state.update {
                            it.copy(
                                isLoading = false,
                                isReauthenticating = false,
                                error = "Sign-in is required. Please go to Accounts and reconnect.",
                            )
                        }
                    }
                    is AuthResult.NotConfigured -> {
                        Timber.e("PickRemoteFolderViewModel.signInAndRetry: provider not configured — %s", result.message)
                        _state.update { it.copy(isLoading = false, isReauthenticating = false, error = result.message) }
                    }
                    is AuthResult.Error -> {
                        Timber.e(result.cause, "PickRemoteFolderViewModel.signInAndRetry: sign-in error — %s", result.message)
                        _state.update { it.copy(isLoading = false, isReauthenticating = false, error = result.message) }
                    }
                }
            }
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
                    if (t is CloudProviderException.AuthenticationRequired) {
                        // Keep isLoading = true while the screen processes the reauth event;
                        // the isReauthenticating flag lets the screen show a "Signing in…" message.
                        Timber.i("PickRemoteFolderViewModel: auth required — emitting reauthEvent")
                        _state.update { it.copy(isLoading = true, isReauthenticating = true) }
                        _reauthEvent.tryEmit(Unit)
                    } else {
                        _state.update {
                            it.copy(isLoading = false, error = t.message ?: "Failed to load folders")
                        }
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
            private const val ROOT_FOLDER_ID = "root"
        }
    }
