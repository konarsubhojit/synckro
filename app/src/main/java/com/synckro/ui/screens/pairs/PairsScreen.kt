package com.synckro.ui.screens.pairs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.domain.model.SyncPair
import com.synckro.ui.components.EmptyState
import com.synckro.ui.components.SectionCard
import com.synckro.ui.screens.home.HomeViewModel

/**
 * Sync-pairs destination — the user's list of configured sync pairs plus a FAB
 * to create a new one. This screen owns the FAB and the per-pair actions
 * (sync now / edit / delete with undo).
 *
 * Extracted from the legacy 752-line `HomeScreen.kt` in UX Phase 1 so the
 * bottom-nav scaffold can host it as one of several top-level destinations.
 *
 * The `viewModel` is supplied by the caller (the bottom-nav host hands in a
 * shared instance scoped to the `main` route entry so the conflicts badge and
 * the pair list stay in lock-step).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairsScreen(
    onAddSyncPair: () -> Unit,
    onEditSyncPair: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val pendingDelete = state.pendingDelete
    val undoLabel = stringResource(R.string.home_delete_undo_action)
    val undoMessageFmt = stringResource(R.string.home_delete_undo_message_format)
    LaunchedEffect(pendingDelete?.pair?.id) {
        val pd = pendingDelete ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = String.format(undoMessageFmt, pd.pair.displayName),
            actionLabel = undoLabel,
            duration = SnackbarDuration.Short,
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
        }
    }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = onAddSyncPair) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.add_sync_pair),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PairsList(
            state = state,
            onEditSyncPair = onEditSyncPair,
            onRequestDelete = viewModel::requestDelete,
            onSyncNow = viewModel::syncNow,
            onAddSyncPair = onAddSyncPair,
            globalAutoSyncEnabled = state.globalAutoSyncEnabled,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
private fun PairsList(
    state: HomeViewModel.UiState,
    onEditSyncPair: (Long) -> Unit,
    onRequestDelete: (SyncPair) -> Unit,
    onSyncNow: (SyncPair) -> Unit,
    onAddSyncPair: () -> Unit,
    globalAutoSyncEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!state.isLoading && state.pairs.isEmpty()) {
        // Phase 2: replaced the bespoke 3-step LazyColumn empty state with the
        // shared [EmptyState] component. The deeper onboarding flow lives in
        // Phase 7.
        EmptyState(
            title = stringResource(R.string.home_empty_title),
            body = stringResource(R.string.home_empty_body),
            icon = Icons.Filled.CloudOff,
            primaryActionLabel = stringResource(R.string.home_empty_cta),
            onPrimaryAction = onAddSyncPair,
            modifier = modifier,
        )
    } else {
        LazyColumn(
            modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.pairs, key = { it.id }) { pair ->
                SyncPairRow(
                    pair = pair,
                    accountEmail = pair.accountId?.let { state.accountEmailById[it] },
                    isSyncing = pair.id in state.syncingPairIds,
                    onEdit = { onEditSyncPair(pair.id) },
                    onDelete = { onRequestDelete(pair) },
                    onSyncNow = { onSyncNow(pair) },
                    globalAutoSyncEnabled = globalAutoSyncEnabled,
                )
            }
            item { Spacer(Modifier.height(80.dp)) } // leave room for FAB
        }
    }
}

@Composable
private fun SyncPairRow(
    pair: SyncPair,
    accountEmail: String?,
    isSyncing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSyncNow: () -> Unit,
    globalAutoSyncEnabled: Boolean,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.home_delete_title)) },
            text = {
                Text(stringResource(R.string.home_delete_body_format, pair.displayName))
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text(
                        stringResource(R.string.home_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.home_delete_cancel))
                }
            },
        )
    }

    val needsReauth = pair.lastSyncResult == "NEEDS_REAUTH"
    val cardColor = when {
        pair.needsReLink -> MaterialTheme.colorScheme.errorContainer
        needsReauth -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val cardContentColor = when {
        pair.needsReLink || needsReauth -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    SectionCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = cardColor,
        contentColor = cardContentColor,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pair.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val providerLabel = when (pair.provider.name) {
                        "GOOGLE_DRIVE" -> stringResource(R.string.provider_label_google_drive)
                        "ONEDRIVE" -> stringResource(R.string.provider_label_onedrive)
                        else -> pair.provider.name
                    }
                    Text(
                        text = if (accountEmail != null) "$providerLabel · $accountEmail" else providerLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                when {
                    isSyncing -> {
                        val label = stringResource(R.string.sync_now_in_progress)
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics { contentDescription = label },
                            strokeWidth = 2.dp,
                        )
                    }
                    pair.needsReLink -> Icon(
                        Icons.Default.FolderOff,
                        contentDescription = stringResource(R.string.home_needs_relink),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    needsReauth -> Icon(
                        Icons.Default.Error,
                        contentDescription = stringResource(R.string.home_needs_reauth_hint),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    pair.lastSyncResult == "SUCCESS" -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    else -> Unit
                }
            }

            when {
                pair.needsReLink -> StatusBanner(
                    text = stringResource(R.string.home_needs_relink),
                    isError = true,
                )
                needsReauth -> StatusBanner(
                    text = stringResource(R.string.home_needs_reauth_hint),
                    isError = true,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        when {
                            !globalAutoSyncEnabled -> stringResource(R.string.home_auto_sync_paused)
                            pair.autoSyncEnabled -> stringResource(R.string.home_auto_sync_enabled)
                            else -> stringResource(R.string.home_auto_sync_disabled)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        when {
                            !globalAutoSyncEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                            pair.autoSyncEnabled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                val dateFormatter = remember {
                    java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT,
                        java.text.DateFormat.SHORT,
                    )
                }
                Text(
                    text =
                        if (pair.lastSyncAtMs != null) {
                            stringResource(
                                R.string.home_last_sync_format,
                                dateFormatter.format(java.util.Date(pair.lastSyncAtMs)),
                            )
                        } else {
                            stringResource(R.string.home_never_synced)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onSyncNow, enabled = !isSyncing) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = stringResource(R.string.sync_now),
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.home_edit_pair),
                    )
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.home_delete_pair),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
    }
}

@Composable
private fun StatusBanner(
    text: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isError) Icons.Default.Warning else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}
