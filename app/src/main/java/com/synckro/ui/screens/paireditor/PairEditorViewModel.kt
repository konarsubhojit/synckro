package com.synckro.ui.screens.paireditor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synckro.R
import com.synckro.data.repository.AccountRepository
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.domain.auth.Account
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.domain.model.SyncPair
import com.synckro.domain.model.isDestructive
import com.synckro.util.StringProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Friendly schedule preset options shown in the pair editor.
 * [CUSTOM] falls back to the user's manually entered interval.
 */
enum class SyncSchedulePreset(
    /** Interval in minutes for this preset (0 for CUSTOM – interval is user-supplied). */
    val minutes: Long,
) {
    FIFTEEN_MINUTES(15L),
    THIRTY_MINUTES(30L),
    HOURLY(60L),
    DAILY(1440L),
    CUSTOM(0L),
    ;

    companion object {
        /** Maps a stored interval to the matching named preset, or [CUSTOM] if none matches exactly. */
        fun fromMinutes(minutes: Long): SyncSchedulePreset =
            entries.firstOrNull { it != CUSTOM && it.minutes == minutes } ?: CUSTOM
    }
}

/**
 * ViewModel for [PairEditorScreen]. Supports both create (pairId == 0) and edit
 * (pairId > 0) modes. The local folder URI result from [PickLocalFolderScreen] is
 * delivered by the navigation layer (which observes the back-stack entry's own
 * [SavedStateHandle]) via [onLocalFolderPicked]. The Hilt-injected [SavedStateHandle]
 * here is a distinct bucket from [androidx.navigation.NavBackStackEntry.savedStateHandle],
 * so we cannot rely on it to receive nav results — the navigation layer must forward
 * them explicitly.
 *
 * [strings] is injected as a [StringProvider] rather than [android.content.Context]
 * so the ViewModel stays decoupled from Android framework types and is straightforward
 * to test without Robolectric.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class PairEditorViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val strings: StringProvider,
        private val syncPairRepository: SyncPairRepository,
        private val syncScheduler: SyncScheduler,
        private val syncEventRepository: SyncEventRepository,
        private val accountRepository: AccountRepository,
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val pairId: Long = savedStateHandle.get<Long>("pairId") ?: 0L

        data class UiState(
            val isLoading: Boolean = false,
            val displayName: String = "",
            val localTreeUri: String = "",
            /** Human-readable path under the local-folder field (e.g. `Downloads/Photos`).
             *  Plumbed from the SAF [DocumentFile] tree by [onLocalFolderPicked] so the
             *  screen does not have to recompute it on every recomposition. Empty when
             *  no folder is selected or when no path could be derived. */
            val localPathDisplay: String = "",
            val provider: CloudProviderType = CloudProviderType.GOOGLE_DRIVE,
            /** The id of the account this pair is bound to; null until the user selects one. */
            val accountId: String? = null,
            /** Accounts available for the currently selected [provider], updated reactively. */
            val availableAccounts: List<Account> = emptyList(),
            val remoteFolderId: String = "",
            /** Human-readable display name for [remoteFolderId], set when the user browses
             *  and picks a folder via [PickRemoteFolderScreen]. Empty when the ID was loaded
             *  from the database (name is not persisted) or typed manually. */
            val remoteFolderName: String = "",
            /** Joined breadcrumb path from the root of the cloud provider to the selected
             *  remote folder, e.g. `"My Drive › Backups › Photos"`. Set by
             *  [onRemoteFolderPicked] when the picker provides it; otherwise empty. */
            val remoteBreadcrumb: String = "",
            val conflictPolicy: ConflictPolicy = ConflictPolicy.NEWEST_WINS,
            val direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
            val wifiOnly: Boolean = true,
            val requiresCharging: Boolean = false,
            /** Whether periodic auto-sync is enabled for this pair. */
            val autoSyncEnabled: Boolean = true,
            /** Currently selected schedule preset. */
            val schedulePreset: SyncSchedulePreset = SyncSchedulePreset.HOURLY,
            /** Raw text for the custom interval field; only used when [schedulePreset] is [SyncSchedulePreset.CUSTOM]. */
            val customIntervalText: String = "60",
            /** Newline-separated glob patterns. */
            val includeGlobsText: String = "",
            /** Newline-separated glob patterns. */
            val excludeGlobsText: String = "",
            /** When true, only root-level files are synced; sub-directories are ignored. */
            val excludeSubfolders: Boolean = false,
            /** When true, empty directories are excluded from the sync scope. */
            val excludeEmptyFolders: Boolean = false,
            /**
             * Text representation of the retention period in days. Only meaningful
             * when [direction] is [SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS]
             * or [SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS].
             * Empty string means no automatic deletion (null retention).
             */
            val retentionDaysText: String = "",
            val isSaving: Boolean = false,
            /**
             * Persistent error message shown as an inline banner on the editor.
             * Set by [save] when validation or persistence fails; cleared when the
             * user dismisses it via [clearSaveError] or starts editing the
             * relevant field. Distinct from [saveErrorEvent] which drives the
             * one-shot snackbar.
             */
            val saveError: String? = null,
            /**
             * Monotonically increasing counter used as a one-shot trigger for the
             * "save failed" snackbar. The screen observes this in a
             * [androidx.compose.runtime.LaunchedEffect] keyed on the value;
             * clearing [saveError] does not retract the snackbar.
             */
            val saveErrorEvent: Long = 0L,
            /**
             * True when the user attempted to save with a blank display name.
             * Drives the [OutlinedTextField]'s `isError` + supporting text and is
             * independent of [saveError] so non-validation save failures do not
             * incorrectly mark this field as invalid.
             */
            val nameRequiredError: Boolean = false,
            /**
             * Non-null when the user has selected a destructive sync direction that
             * requires an explicit confirmation before being applied. The screen
             * should display a warning dialog and call [confirmDestructiveDirection]
             * or [dismissDestructiveDirection] based on the user's choice.
             */
            val pendingDestructiveDirection: SyncDirection? = null,
            /**
             * True when the previously-persisted [accountId] could not be found in
             * the latest [availableAccounts] snapshot (e.g. the account was
             * disconnected on another screen). Drives an inline error under the
             * account dropdown in the editor so the user notices and re-picks
             * before saving.
             */
            val accountDisappeared: Boolean = false,
            /**
             * Current wizard step (1..[TOTAL_STEPS]) in create mode. Ignored in
             * edit mode, which renders all sections in a single scroll. Step 1
             * collects name + local folder, step 2 collects provider + account +
             * remote folder, step 3 collects direction + conflict policy +
             * auto-sync.
             */
            val currentStep: Int = 1,
        ) {
            /** Parses [customIntervalText] as a non-negative Long, or 0 if the text is blank/invalid. */
            private val parsedCustomInterval: Long
                get() = customIntervalText.trim().toLongOrNull() ?: 0L

            /** Effective sync interval in minutes derived from the active preset or custom value.
             *  Custom text that is blank or non-numeric is treated as 0 and clamped here to the
             *  15-minute WorkManager floor. */
            val scheduleIntervalMinutes: Long
                get() {
                    if (schedulePreset != SyncSchedulePreset.CUSTOM) return schedulePreset.minutes
                    return parsedCustomInterval.coerceAtLeast(15L)
                }

            /** True when the custom interval text is non-empty but does not parse as a valid long ≥ 15. */
            val customIntervalError: Boolean
                get() =
                    schedulePreset == SyncSchedulePreset.CUSTOM &&
                        customIntervalText.isNotEmpty() &&
                        parsedCustomInterval < 15L

            /**
             * Whether the form is in a state that should allow Save. Used to
             * disable the Save button when required fields are missing — most
             * importantly, when no account has been picked for the chosen
             * provider. Save is also disabled while a save is in flight.
             */
            val canSave: Boolean
                get() =
                    !isSaving &&
                        displayName.isNotBlank() &&
                        localTreeUri.isNotBlank() &&
                        remoteFolderId.isNotBlank() &&
                        accountId != null

            /**
             * Wizard step-1 validation: name + local folder. Required so the
             * "Next" button is disabled until both fields are populated.
             */
            val step1Valid: Boolean
                get() = displayName.isNotBlank() && localTreeUri.isNotBlank()

            /**
             * Wizard step-2 validation: provider + account + remote folder. The
             * provider field always has a default so it's effectively just an
             * account-and-remote-folder check.
             */
            val step2Valid: Boolean
                get() = accountId != null && remoteFolderId.isNotBlank()

            /**
             * Wizard step-3 validation: direction + conflict policy + auto-sync
             * + (optional) retention-days range. The first two are enums with
             * defaults so they always validate; retention only matters for
             * destructive directions.
             */
            val step3Valid: Boolean
                get() {
                    if (!direction.isDestructive) return true
                    if (retentionDaysText.isBlank()) return true
                    val parsed = retentionDaysText.toIntOrNull() ?: return false
                    return parsed in 0..MAX_RETENTION_DAYS
                }

            /**
             * Whether the wizard's "Next" button (create mode) should be
             * enabled for the current step. Step 3 has no "Next" — it shows
             * Save instead — but we still expose the validation result via
             * [canSave] for that case.
             */
            val canGoToNextStep: Boolean
                get() =
                    when (currentStep) {
                        1 -> step1Valid
                        2 -> step2Valid
                        else -> false
                    }
        }

        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state.asStateFlow()

        /**
         * Tracks whether the user has freshly picked a folder during this VM's lifetime.
         * Used to prevent a slow [loadExisting] coroutine from clobbering a newly chosen
         * URI that arrived via [onLocalFolderPicked] before the DB load completed.
         */
        private var userPickedFolder: Boolean = false

        /**
         * Snapshot of the form fields taken once the editor finishes its initial
         * data load. Used by [isDirty] to detect whether the user has made any
         * unsaved changes — drives the "discard changes?" confirmation when the
         * user presses Back. `null` until the initial load completes; while
         * `null`, [isDirty] returns `false`.
         */
        private var initialFingerprint: FormFingerprint? = null

        /** True when [pairId] is zero, i.e. the editor was opened to create a new pair. */
        val isCreateMode: Boolean get() = pairId == 0L

        /** True when the form has unsaved changes relative to its initial snapshot. */
        val isDirty: Boolean
            get() {
                val baseline = initialFingerprint ?: return false
                return FormFingerprint.of(_state.value) != baseline
            }

        init {
            // Re-apply any URI restored from process death (the Hilt-injected SavedStateHandle
            // can survive process recreation even though it does NOT receive nav-result writes).
            savedStateHandle
                .get<String?>(KEY_LOCAL_TREE_URI)
                ?.takeIf { it.isNotBlank() }
                ?.let { restored ->
                    userPickedFolder = true
                    _state.update { it.copy(localTreeUri = restored) }
                }

            // Restore the remote folder pick result across process death in the same way.
            val restoredRemoteId = savedStateHandle.get<String?>(KEY_REMOTE_FOLDER_ID)?.takeIf { it.isNotEmpty() }
            val restoredRemoteName = savedStateHandle.get<String?>(KEY_REMOTE_FOLDER_NAME).orEmpty()
            val restoredRemoteBreadcrumb = savedStateHandle.get<String?>(KEY_REMOTE_FOLDER_BREADCRUMB).orEmpty()
            if (restoredRemoteId != null) {
                _state.update {
                    it.copy(
                        remoteFolderId = restoredRemoteId,
                        remoteFolderName = restoredRemoteName,
                        remoteBreadcrumb = restoredRemoteBreadcrumb,
                    )
                }
            }

            if (pairId > 0L) {
                loadExisting(pairId)
            } else {
                // Brand-new pair: seed with the user's sync defaults from Settings
                // so the form reflects their preferences from the start. Captured
                // once — subsequent changes to the defaults do not retroactively
                // mutate an in-progress form.
                viewModelScope.launch {
                    val wifi = settingsRepository.defaultWifiOnly.first()
                    val charging = settingsRepository.defaultChargingOnly.first()
                    val policy = settingsRepository.defaultConflictPolicy.first()
                    _state.update {
                        it.copy(
                            wifiOnly = wifi,
                            requiresCharging = charging,
                            conflictPolicy = policy,
                        )
                    }
                    // The empty-form baseline only stabilises after defaults have
                    // been applied — otherwise the very first defaults would look
                    // like user edits and incorrectly trip the dirty guard.
                    initialFingerprint = FormFingerprint.of(_state.value)
                }
            }

            // Observe accounts for the currently selected provider and update the
            // available accounts list. When the provider changes, the previous
            // subscription is automatically cancelled via flatMapLatest.
            viewModelScope.launch {
                _state
                    .map { it.provider }
                    .distinctUntilChanged()
                    .flatMapLatest { provider -> accountRepository.observeByProvider(provider) }
                    .collect { accounts ->
                        _state.update { s ->
                            // If the selected account is no longer in the list (e.g. disconnected),
                            // clear the selection so validation catches it rather than silently
                            // persisting a stale ID. Set the [accountDisappeared] flag so the
                            // editor screen can show an inline error explaining what happened —
                            // but only when the user actually had something selected previously
                            // (we don't want to flash an error on the very first load).
                            val hadSelection = s.accountId != null
                            val stillThere = accounts.any { it.id == s.accountId }
                            val autoSelectedAccountId =
                                if (!hadSelection && accounts.size == 1) {
                                    accounts.single().id
                                } else {
                                    null
                                }
                            s.copy(
                                availableAccounts = accounts,
                                accountId =
                                    when {
                                        stillThere -> s.accountId
                                        autoSelectedAccountId != null -> autoSelectedAccountId
                                        else -> null
                                    },
                                accountDisappeared = hadSelection && !stillThere,
                            )
                        }
                    }
            }
        }

        private fun loadExisting(id: Long) {
            _state.update { it.copy(isLoading = true) }
            viewModelScope.launch {
                val entity = syncPairRepository.getById(id)
                if (entity != null) {
                    val preset = SyncSchedulePreset.fromMinutes(entity.scheduleIntervalMinutes)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            displayName = entity.displayName,
                            // If the user already picked a folder before this coroutine
                            // finished, keep their choice instead of clobbering it with
                            // the persisted value.
                            localTreeUri = if (userPickedFolder) it.localTreeUri else entity.localTreeUri,
                            provider = entity.provider,
                            accountId = entity.accountId,
                            remoteFolderId = entity.remoteFolderId,
                            conflictPolicy = entity.conflictPolicy,
                            direction = entity.direction,
                            wifiOnly = entity.wifiOnly,
                            requiresCharging = entity.requiresCharging,
                            autoSyncEnabled = entity.autoSyncEnabled,
                            schedulePreset = preset,
                            customIntervalText = entity.scheduleIntervalMinutes.toString(),
                            includeGlobsText = entity.includeGlobs.joinToString("\n"),
                            excludeGlobsText = entity.excludeGlobs.joinToString("\n"),
                            excludeSubfolders = entity.excludeSubfolders,
                            excludeEmptyFolders = entity.excludeEmptyFolders,
                            retentionDaysText = entity.retentionDays?.toString() ?: "",
                        )
                    }
                    // Capture the loaded baseline so unsaved-changes detection
                    // is correct for edit mode (any subsequent mutation counts
                    // as dirty).
                    initialFingerprint = FormFingerprint.of(_state.value)
                } else {
                    Timber.w("PairEditorViewModel: no entity found for id=%d", id)
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }

        /**
         * Called by the navigation layer when [PickLocalFolderScreen] has returned a
         * confirmed folder URI. The navigation host observes the back-stack entry's
         * own [SavedStateHandle] (which is a different instance from the Hilt-injected
         * [savedStateHandle] here) and forwards the result through this method.
         *
         * @param uri Tree URI returned by SAF.
         * @param displayPath Optional human-readable path derived from
         *   [androidx.documentfile.provider.DocumentFile] by the screen (which
         *   has [android.content.Context] access); shown beneath the local
         *   folder field so the user knows exactly which directory they
         *   selected. Pass an empty string to leave the displayed path
         *   unchanged (e.g. on URI-only restores from process death).
         */
        fun onLocalFolderPicked(uri: String, displayPath: String = "") {
            if (uri.isBlank()) return
            userPickedFolder = true
            // Persist into the Hilt-injected SavedStateHandle too so the URI survives
            // process death / configuration changes for this ViewModel.
            savedStateHandle[KEY_LOCAL_TREE_URI] = uri
            _state.update {
                it.copy(
                    localTreeUri = uri,
                    localPathDisplay = if (displayPath.isNotEmpty()) displayPath else it.localPathDisplay,
                )
            }
        }

        fun onDisplayNameChange(value: String) =
            _state.update {
                // Clear the name-required validation flag as soon as the user types
                // something so the inline error doesn't linger past the fix.
                it.copy(
                    displayName = value,
                    nameRequiredError = if (value.isBlank()) it.nameRequiredError else false,
                )
            }

        /**
         * Called by the navigation layer when [PickRemoteFolderScreen] has returned a confirmed
         * cloud folder. Both the folder ID and human-readable name are stored so the editor can
         * display the name while still persisting the opaque provider ID.
         *
         * @param breadcrumb Optional joined navigation path from the root of the
         *   provider to the selected folder (e.g. `"My Drive › Backups › Photos"`).
         *   Surfaced beneath the cloud folder field so the user has unambiguous
         *   context about where their data will go. Empty string preserves the
         *   previously-known breadcrumb (e.g. on process-death restore).
         */
        fun onRemoteFolderPicked(id: String, name: String, breadcrumb: String = "") {
            savedStateHandle[KEY_REMOTE_FOLDER_ID] = id
            savedStateHandle[KEY_REMOTE_FOLDER_NAME] = name
            if (breadcrumb.isNotEmpty()) {
                savedStateHandle[KEY_REMOTE_FOLDER_BREADCRUMB] = breadcrumb
            }
            _state.update {
                it.copy(
                    remoteFolderId = id,
                    remoteFolderName = name,
                    remoteBreadcrumb = if (breadcrumb.isNotEmpty()) breadcrumb else it.remoteBreadcrumb,
                )
            }
        }

        // ---------------------------------------------------------------------
        // Wizard navigation (create mode)
        // ---------------------------------------------------------------------

        /**
         * Advances to the next wizard step if the current step validates. Caps
         * at [TOTAL_STEPS] so callers can invoke it unconditionally without
         * worrying about overflow.
         */
        fun goToNextStep() {
            _state.update { s ->
                if (!s.canGoToNextStep || s.currentStep >= TOTAL_STEPS) s
                else s.copy(currentStep = s.currentStep + 1)
            }
        }

        /** Goes back one wizard step, bottomed out at step 1. */
        fun goToPreviousStep() {
            _state.update { s ->
                if (s.currentStep <= 1) s else s.copy(currentStep = s.currentStep - 1)
            }
        }

        /** Jumps directly to [step] (clamped to 1..[TOTAL_STEPS]). Used by tests and previews. */
        fun goToStep(step: Int) {
            val clamped = step.coerceIn(1, TOTAL_STEPS)
            _state.update { it.copy(currentStep = clamped) }
        }

        fun onProviderChange(value: CloudProviderType) =
            _state.update {
                // Clear the remote folder and account selection when the provider changes because
                // folder IDs and account IDs are provider-specific and the previously chosen
                // values no longer apply.
                it.copy(
                    provider = value,
                    remoteFolderId = "",
                    remoteFolderName = "",
                    accountId = null,
                    accountDisappeared = false,
                )
            }

        /** Updates the selected account for this pair. Pass null to clear the selection. */
        fun onAccountChange(accountId: String?) =
            _state.update { it.copy(accountId = accountId, accountDisappeared = false) }

        fun onConflictPolicyChange(value: ConflictPolicy) = _state.update { it.copy(conflictPolicy = value) }

        /**
         * Called when the user selects a sync direction in the pair editor. For non-destructive
         * directions the change is applied immediately. For directions that can automatically delete
         * files (see [SyncDirection.isDestructive]) the selection is held in
         * [UiState.pendingDestructiveDirection] so the screen can show a confirmation dialog before
         * the direction is committed.
         */
        fun onDirectionChangeRequested(value: SyncDirection) {
            if (value.isDestructive) {
                _state.update { it.copy(pendingDestructiveDirection = value) }
            } else {
                _state.update { it.copy(direction = value, pendingDestructiveDirection = null) }
            }
        }

        /** Commits the pending destructive direction after the user confirmed the warning dialog. */
        fun confirmDestructiveDirection() {
            _state.update { s ->
                s.copy(
                    direction = s.pendingDestructiveDirection ?: s.direction,
                    pendingDestructiveDirection = null,
                )
            }
        }

        /** Discards the pending destructive direction when the user dismisses the warning dialog. */
        fun dismissDestructiveDirection() = _state.update { it.copy(pendingDestructiveDirection = null) }

        fun onWifiOnlyChange(value: Boolean) = _state.update { it.copy(wifiOnly = value) }

        fun onRequiresChargingChange(value: Boolean) = _state.update { it.copy(requiresCharging = value) }

        fun onAutoSyncEnabledChange(value: Boolean) = _state.update { it.copy(autoSyncEnabled = value) }

        fun onSchedulePresetChange(value: SyncSchedulePreset) = _state.update { it.copy(schedulePreset = value) }

        fun onCustomIntervalChange(value: String) = _state.update { it.copy(customIntervalText = value) }

        fun onIncludeGlobsChange(value: String) = _state.update { it.copy(includeGlobsText = value) }

        fun onExcludeGlobsChange(value: String) = _state.update { it.copy(excludeGlobsText = value) }

        fun onExcludeSubfoldersChange(value: Boolean) = _state.update { it.copy(excludeSubfolders = value) }

        fun onExcludeEmptyFoldersChange(value: Boolean) = _state.update { it.copy(excludeEmptyFolders = value) }

        fun onRetentionDaysChange(value: String) = _state.update { it.copy(retentionDaysText = value.filter { ch -> ch.isDigit() }) }

        /**
         * Validates and persists the current form state. Calls [onSaved] with the
         * persisted row ID on success, or sets [UiState.saveError] on failure.
         *
         * Validation errors set [UiState.saveError] (banner) **and** bump
         * [UiState.saveErrorEvent] so the screen can show a one-shot snackbar
         * without coupling the snackbar lifecycle to the persistent banner.
         * Field-specific errors (currently only the display name) also flip
         * dedicated flags such as [UiState.nameRequiredError] so the
         * `OutlinedTextField` can render its own inline error independently of
         * what the banner is showing.
         */
        fun save(onSaved: (Long) -> Unit) {
            val s = _state.value
            val retentionDaysText = s.retentionDaysText.trim()
            val retentionDays = retentionDaysText.takeIf { it.isNotBlank() }?.toIntOrNull()

            // Run all validations first; if any fail, log a single event and return.
            val validationError: String? =
                when {
                    s.displayName.isBlank() -> strings.getString(R.string.pair_editor_error_name_required)
                    s.localTreeUri.isBlank() -> strings.getString(R.string.pair_editor_error_folder_required)
                    s.remoteFolderId.isBlank() -> strings.getString(R.string.pair_editor_error_remote_folder_required)
                    s.accountId == null -> strings.getString(R.string.pair_editor_account_required)
                    retentionDaysText.isNotBlank() &&
                        (retentionDays == null || retentionDays !in 0..MAX_RETENTION_DAYS) ->
                        strings.getString(R.string.pair_editor_retention_days_error)
                    else -> null
                }

            if (validationError != null) {
                val nameError = s.displayName.isBlank()
                viewModelScope.launch {
                    syncEventRepository.log(
                        pairId = if (pairId != 0L) pairId else null,
                        level = SyncEventLevel.WARN,
                        tag = SyncEventTag.PairEditor,
                        message = "Save validation failed: $validationError",
                    )
                }
                _state.update {
                    it.copy(
                        saveError = validationError,
                        saveErrorEvent = it.saveErrorEvent + 1L,
                        nameRequiredError = nameError,
                    )
                }
                return
            }

            _state.update {
                it.copy(
                    isSaving = true,
                    saveError = null,
                    nameRequiredError = false,
                )
            }
            viewModelScope.launch {
                syncEventRepository.log(
                    pairId = if (pairId != 0L) pairId else null,
                    level = SyncEventLevel.INFO,
                    tag = SyncEventTag.PairEditor,
                    message = "Save started: '${s.displayName.trim()}' provider=${s.provider.name}",
                )
                runCatching {
                    val pair =
                        SyncPair(
                            id = pairId,
                            displayName = s.displayName.trim(),
                            localTreeUri = s.localTreeUri,
                            provider = s.provider,
                            accountId = s.accountId,
                            remoteFolderId = s.remoteFolderId.trim(),
                            conflictPolicy = s.conflictPolicy,
                            direction = s.direction,
                            wifiOnly = s.wifiOnly,
                            requiresCharging = s.requiresCharging,
                            autoSyncEnabled = s.autoSyncEnabled,
                            // Enforce WorkManager's 15-minute floor here so the persisted value
                            // always matches what the scheduler will actually use.
                            scheduleIntervalMinutes = s.scheduleIntervalMinutes,
                            includeGlobs =
                                s.includeGlobsText
                                    .split('\n')
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() },
                            excludeGlobs =
                                s.excludeGlobsText
                                    .split('\n')
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() },
                            excludeSubfolders = s.excludeSubfolders,
                            excludeEmptyFolders = s.excludeEmptyFolders,
                            retentionDays = retentionDays,
                        )
                    val savedId = syncPairRepository.upsert(pair)
                    // Schedule or cancel depending on both the global setting and
                    // the pair's own autoSyncEnabled flag.
                    val globalEnabled = settingsRepository.globalAutoSyncEnabled.first()
                    syncScheduler.scheduleOrCancel(pair.copy(id = savedId), globalEnabled)
                    syncEventRepository.log(
                        pairId = savedId,
                        level = SyncEventLevel.INFO,
                        tag = SyncEventTag.Scheduler,
                        message = "scheduleOrCancel pairId=$savedId globalAutoSync=$globalEnabled pairAutoSync=${s.autoSyncEnabled} interval=${s.scheduleIntervalMinutes}min",
                    )
                    savedId
                }.onSuccess { savedId ->
                    _state.update { it.copy(isSaving = false) }
                    Timber.i("PairEditorViewModel.save: saved pair id=$savedId")
                    syncEventRepository.log(
                        pairId = savedId,
                        level = SyncEventLevel.INFO,
                        tag = SyncEventTag.PairEditor,
                        message = "Save succeeded: pairId=$savedId '${s.displayName.trim()}'",
                    )
                    onSaved(savedId)
                }.onFailure { t ->
                    Timber.e(t, "PairEditorViewModel.save: failed")
                    syncEventRepository.log(
                        pairId = if (pairId != 0L) pairId else null,
                        level = SyncEventLevel.ERROR,
                        tag = SyncEventTag.PairEditor,
                        message = "Save failed: ${t.message}",
                    )
                    _state.update {
                        it.copy(
                            isSaving = false,
                            saveError = t.message ?: strings.getString(R.string.pair_editor_error_save_failed),
                            saveErrorEvent = it.saveErrorEvent + 1L,
                        )
                    }
                }
            }
        }

        /** Clears the persistent inline error banner. The transient snackbar is
         *  driven by [UiState.saveErrorEvent] and is unaffected by this call. */
        fun clearSaveError() = _state.update { it.copy(saveError = null) }

        companion object {
            /**
             * Key used by the navigation layer to deliver the chosen folder URI from
             * [PickLocalFolderScreen] back to the editor via the back-stack entry's
             * own [SavedStateHandle]. The navigation host observes this key and forwards
             * the value to the ViewModel through [onLocalFolderPicked]; it is also used
             * by this ViewModel to persist the picked URI across process death.
             */
            const val KEY_LOCAL_TREE_URI = "localTreeUri"

            /**
             * Key used to pass the current folder URI from [PairEditorScreen] to
             * [PickLocalFolderScreen] so the picker can pre-populate the current selection.
             */
            const val KEY_PICK_FOLDER_INITIAL_URI = "pickFolderInitialUri"

            /**
             * Key used by the navigation layer to deliver the chosen cloud folder ID from
             * [PickRemoteFolderScreen] back to the editor via the back-stack entry's
             * [SavedStateHandle]. Also used to persist the value across process death.
             */
            const val KEY_REMOTE_FOLDER_ID = "remotePickedFolderId"

            /**
             * Key used to deliver the human-readable name of the chosen cloud folder alongside
             * [KEY_REMOTE_FOLDER_ID].
             */
            const val KEY_REMOTE_FOLDER_NAME = "remotePickedFolderName"

            /**
             * Key used to deliver the joined breadcrumb path of the chosen cloud
             * folder (e.g. `"My Drive › Backups › Photos"`) alongside
             * [KEY_REMOTE_FOLDER_ID]. Optional — picker implementations that
             * don't track a navigation trail may omit it.
             */
            const val KEY_REMOTE_FOLDER_BREADCRUMB = "remotePickedFolderBreadcrumb"

            /** Maximum retention period accepted by the pair editor. */
            const val MAX_RETENTION_DAYS = 36500

            /** Number of wizard steps when creating a new pair. */
            const val TOTAL_STEPS = 3
        }

        /**
         * Subset of [UiState] used to detect "unsaved changes" via structural
         * equality. Excludes transient flags ([UiState.isLoading],
         * [UiState.isSaving], [UiState.saveError], wizard navigation state,
         * etc.) so they don't spuriously mark the form dirty.
         */
        internal data class FormFingerprint(
            val displayName: String,
            val localTreeUri: String,
            val provider: CloudProviderType,
            val accountId: String?,
            val remoteFolderId: String,
            val conflictPolicy: ConflictPolicy,
            val direction: SyncDirection,
            val wifiOnly: Boolean,
            val requiresCharging: Boolean,
            val autoSyncEnabled: Boolean,
            val schedulePreset: SyncSchedulePreset,
            val customIntervalText: String,
            val includeGlobsText: String,
            val excludeGlobsText: String,
            val excludeSubfolders: Boolean,
            val excludeEmptyFolders: Boolean,
            val retentionDaysText: String,
        ) {
            companion object {
                fun of(s: UiState) =
                    FormFingerprint(
                        displayName = s.displayName,
                        localTreeUri = s.localTreeUri,
                        provider = s.provider,
                        accountId = s.accountId,
                        remoteFolderId = s.remoteFolderId,
                        conflictPolicy = s.conflictPolicy,
                        direction = s.direction,
                        wifiOnly = s.wifiOnly,
                        requiresCharging = s.requiresCharging,
                        autoSyncEnabled = s.autoSyncEnabled,
                        schedulePreset = s.schedulePreset,
                        customIntervalText = s.customIntervalText,
                        includeGlobsText = s.includeGlobsText,
                        excludeGlobsText = s.excludeGlobsText,
                        excludeSubfolders = s.excludeSubfolders,
                        excludeEmptyFolders = s.excludeEmptyFolders,
                        retentionDaysText = s.retentionDaysText,
                    )
            }
        }
    }
