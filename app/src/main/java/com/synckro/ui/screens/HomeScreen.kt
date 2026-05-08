package com.synckro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.domain.model.SyncPair
import com.synckro.ui.components.EmptyState
import com.synckro.ui.screens.home.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddSyncPair: () -> Unit,
    onEditSyncPair: (Long) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenConflictInbox: () -> Unit,
    onOpenLogs: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface a snackbar with an Undo action when a delete is pending. The VM
    // commits the delete on its own UNDO_WINDOW_MS timer, so this composable's
    // job is purely to react to the user's action: tapping Undo cancels the
    // pending delete; otherwise we leave the VM timer to commit on schedule.
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
        // On Dismissed (timeout / dismiss icon) we deliberately do nothing — the
        // VM's pendingDeleteJob will commit at UNDO_WINDOW_MS, preserving the
        // intended grace period rather than shortening it to the snackbar duration.
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenConflictInbox) {
                        BadgedBox(
                            badge = {
                                if (state.pendingConflictCount > 0) {
                                    Badge { Text(state.pendingConflictCount.toString()) }
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.Inbox,
                                contentDescription = stringResource(R.string.conflict_inbox_title),
                            )
                        }
                    }
                    IconButton(onClick = onOpenLogs) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = stringResource(R.string.logs_action),
                        )
                    }
                    IconButton(onClick = onOpenAccounts) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = stringResource(R.string.accounts_action),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddSyncPair) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_sync_pair))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (!state.isLoading && state.pairs.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.home_empty_title),
                body = stringResource(R.string.home_empty_body),
                icon = Icons.Filled.CloudOff,
                primaryActionLabel = stringResource(R.string.home_empty_cta),
                onPrimaryAction = onAddSyncPair,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.pairs, key = { it.id }) { pair ->
                    SyncPairRow(
                        pair = pair,
                        isSyncing = pair.id in state.syncingPairIds,
                        onEdit = { onEditSyncPair(pair.id) },
                        onDelete = { viewModel.requestDelete(pair) },
                        onSyncNow = { viewModel.syncNow(pair) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncPairRow(
    pair: SyncPair,
    isSyncing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSyncNow: () -> Unit,
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
                    Text(stringResource(R.string.home_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.home_delete_cancel))
                }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = pair.provider.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (pair.needsReLink) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = stringResource(R.string.home_needs_relink),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (pair.needsReLink) {
                Text(
                    text = stringResource(R.string.home_needs_relink),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Auto-sync status line
            Text(
                text =
                    if (pair.autoSyncEnabled) {
                        stringResource(R.string.home_auto_sync_enabled)
                    } else {
                        stringResource(R.string.home_auto_sync_disabled)
                    },
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (pair.autoSyncEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            // Last sync time
            val dateFormatter = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                // Sync now: while a one-shot is queued/running, replace the icon
                // with a spinner and disable the button so the user knows the action
                // was registered (without waiting for the foreground notification).
                IconButton(onClick = onSyncNow, enabled = !isSyncing) {
                    if (isSyncing) {
                        val inProgressLabel = stringResource(R.string.sync_now_in_progress)
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics { contentDescription = inProgressLabel },
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = stringResource(R.string.sync_now),
                        )
                    }
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
}
