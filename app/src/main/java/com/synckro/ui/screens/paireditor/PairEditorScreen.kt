package com.synckro.ui.screens.paireditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection

/**
 * Screen for creating or editing a [SyncPair]. The title and save-button label
 * adjust based on whether [pairId] is 0 (create) or non-zero (edit).
 *
 * The local folder URI is received from [PickLocalFolderScreen] via the nav
 * back-stack's [SavedStateHandle] under key [PairEditorViewModel.KEY_LOCAL_TREE_URI].
 *
 * The remote folder ID and name are received from [PickRemoteFolderScreen] via the
 * nav back-stack's [SavedStateHandle] under keys [PairEditorViewModel.KEY_REMOTE_FOLDER_ID]
 * and [PairEditorViewModel.KEY_REMOTE_FOLDER_NAME].
 *
 * @param pairId Row ID of the pair to edit, or 0 to create a new one.
 * @param onBack Called when the user presses the back / up button.
 * @param onPickFolder Called when the user taps "Pick local folder".
 * @param onPickRemoteFolder Called when the user taps the cloud folder browse button,
 *   receiving the currently selected provider type.
 * @param onSaved Called with the saved pair ID after a successful save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairEditorScreen(
    pairId: Long = 0L,
    onBack: () -> Unit,
    onPickFolder: (String?) -> Unit,
    onPickRemoteFolder: (CloudProviderType) -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: PairEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.saveError) {
        val err = state.saveError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        viewModel.clearSaveError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (pairId == 0L) {
                            stringResource(R.string.pair_editor_title_create)
                        } else {
                            stringResource(R.string.pair_editor_title_edit)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Display name
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = viewModel::onDisplayNameChange,
                    label = { Text(stringResource(R.string.pair_editor_display_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Local folder picker
                val localFolderDisplayName =
                    remember(state.localTreeUri) {
                        if (state.localTreeUri.isEmpty()) {
                            ""
                        } else {
                            runCatching {
                                val uri = android.net.Uri.parse(state.localTreeUri)
                                DocumentFile.fromTreeUri(context, uri)?.name ?: state.localTreeUri
                            }.getOrDefault(state.localTreeUri)
                        }
                    }
                OutlinedTextField(
                    value = localFolderDisplayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.pair_editor_local_folder)) },
                    placeholder = { Text(stringResource(R.string.pair_editor_local_folder_hint)) },
                    trailingIcon = {
                        IconButton(onClick = {
                            onPickFolder(state.localTreeUri.ifEmpty { null })
                        }) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = stringResource(R.string.pair_editor_pick_folder),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Cloud provider selector
                ProviderDropdown(
                    selected = state.provider,
                    onSelect = viewModel::onProviderChange,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Remote folder path
                val remoteFolderDisplayName =
                    remember(state.remoteFolderId, state.remoteFolderName) {
                        state.remoteFolderName.ifEmpty { state.remoteFolderId }
                    }
                OutlinedTextField(
                    value = remoteFolderDisplayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.pair_editor_remote_folder)) },
                    placeholder = { Text(stringResource(R.string.pair_editor_remote_folder_hint)) },
                    trailingIcon = {
                        IconButton(onClick = { onPickRemoteFolder(state.provider) }) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = stringResource(R.string.pair_editor_pick_remote_folder),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Conflict policy selector
                ConflictPolicyDropdown(
                    selected = state.conflictPolicy,
                    onSelect = viewModel::onConflictPolicyChange,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Sync direction selector
                DirectionDropdown(
                    selected = state.direction,
                    onSelect = viewModel::onDirectionChange,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Wi-Fi only toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.pair_editor_wifi_only),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = state.wifiOnly,
                        onCheckedChange = viewModel::onWifiOnlyChange,
                    )
                }

                // Requires charging toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.pair_editor_requires_charging),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = state.requiresCharging,
                        onCheckedChange = viewModel::onRequiresChargingChange,
                    )
                }

                // Auto-sync toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.pair_editor_auto_sync),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = state.autoSyncEnabled,
                        onCheckedChange = viewModel::onAutoSyncEnabledChange,
                    )
                }

                // Schedule preset dropdown + custom interval — only shown when auto-sync is on
                if (state.autoSyncEnabled) {
                    SchedulePresetDropdown(
                        selected = state.schedulePreset,
                        onSelect = viewModel::onSchedulePresetChange,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (state.schedulePreset == SyncSchedulePreset.CUSTOM) {
                        OutlinedTextField(
                            value = state.customIntervalMinutes.toString(),
                            onValueChange = { v ->
                                v.toLongOrNull()?.let { viewModel.onCustomIntervalChange(it) }
                            },
                            label = { Text(stringResource(R.string.pair_editor_schedule_custom_interval)) },
                            supportingText = {
                                if (state.customIntervalMinutes < 15L) {
                                    Text(
                                        text = stringResource(R.string.pair_editor_schedule_interval_min_warning),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                            isError = state.customIntervalMinutes < 15L,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // Include globs
                OutlinedTextField(
                    value = state.includeGlobsText,
                    onValueChange = viewModel::onIncludeGlobsChange,
                    label = { Text(stringResource(R.string.pair_editor_include_globs)) },
                    placeholder = { Text(stringResource(R.string.pair_editor_globs_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                // Exclude globs
                OutlinedTextField(
                    value = state.excludeGlobsText,
                    onValueChange = viewModel::onExcludeGlobsChange,
                    label = { Text(stringResource(R.string.pair_editor_exclude_globs)) },
                    placeholder = { Text(stringResource(R.string.pair_editor_globs_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.save(onSaved) },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.pair_editor_save))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDropdown(
    selected: CloudProviderType,
    onSelect: (CloudProviderType) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectableProviders =
        remember {
            CloudProviderType.entries.filter { it != CloudProviderType.FAKE }
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = providerLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.pair_editor_provider)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            selectableProviders.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(providerLabel(provider)) },
                    onClick = {
                        onSelect(provider)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConflictPolicyDropdown(
    selected: ConflictPolicy,
    onSelect: (ConflictPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = conflictPolicyLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.pair_editor_conflict_policy)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ConflictPolicy.entries.forEach { policy ->
                DropdownMenuItem(
                    text = { Text(conflictPolicyLabel(policy)) },
                    onClick = {
                        onSelect(policy)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectionDropdown(
    selected: SyncDirection,
    onSelect: (SyncDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = directionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.pair_editor_direction)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SyncDirection.entries.forEach { dir ->
                DropdownMenuItem(
                    text = { Text(directionLabel(dir)) },
                    onClick = {
                        onSelect(dir)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulePresetDropdown(
    selected: SyncSchedulePreset,
    onSelect: (SyncSchedulePreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = schedulePresetLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.pair_editor_schedule_preset)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SyncSchedulePreset.entries.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(schedulePresetLabel(preset)) },
                    onClick = {
                        onSelect(preset)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun providerLabel(provider: CloudProviderType): String =
    when (provider) {
        CloudProviderType.FAKE -> stringResource(R.string.provider_label_fake)
        CloudProviderType.ONEDRIVE -> stringResource(R.string.provider_label_onedrive)
        CloudProviderType.GOOGLE_DRIVE -> stringResource(R.string.provider_label_google_drive)
    }

@Composable
private fun conflictPolicyLabel(policy: ConflictPolicy): String =
    when (policy) {
        ConflictPolicy.NEWEST_WINS -> stringResource(R.string.conflict_policy_newest_wins)
        ConflictPolicy.PREFER_LOCAL -> stringResource(R.string.conflict_policy_prefer_local)
        ConflictPolicy.PREFER_REMOTE -> stringResource(R.string.conflict_policy_prefer_remote)
        ConflictPolicy.KEEP_BOTH -> stringResource(R.string.conflict_policy_keep_both)
    }

@Composable
private fun directionLabel(dir: SyncDirection): String =
    when (dir) {
        SyncDirection.LOCAL_TO_REMOTE -> stringResource(R.string.direction_local_to_remote)
        SyncDirection.REMOTE_TO_LOCAL -> stringResource(R.string.direction_remote_to_local)
        SyncDirection.BIDIRECTIONAL -> stringResource(R.string.direction_bidirectional)
    }

@Composable
private fun schedulePresetLabel(preset: SyncSchedulePreset): String =
    when (preset) {
        SyncSchedulePreset.FIFTEEN_MINUTES -> stringResource(R.string.pair_editor_schedule_15min)
        SyncSchedulePreset.THIRTY_MINUTES -> stringResource(R.string.pair_editor_schedule_30min)
        SyncSchedulePreset.HOURLY -> stringResource(R.string.pair_editor_schedule_hourly)
        SyncSchedulePreset.DAILY -> stringResource(R.string.pair_editor_schedule_daily)
        SyncSchedulePreset.CUSTOM -> stringResource(R.string.pair_editor_schedule_custom)
    }
