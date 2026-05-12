package com.synckro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOff
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
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

    // Surface a snackbar with an Undo action when a delete is pending.
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
            // Guide the user through the 3 steps they need to take.
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item { Spacer(Modifier.height(16.dp)) }
                item {
                    Icon(
                        Icons.Filled.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.home_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.home_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { HorizontalDivider() }
                item {
                    Text(
                        text = stringResource(R.string.home_empty_how_to_start),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item {
                    HomeGuideStep(
                        number = 1,
                        text = stringResource(R.string.home_empty_step1),
                        actionLabel = stringResource(R.string.home_empty_step1_action),
                        onAction = onOpenAccounts,
                    )
                }
                item {
                    HomeGuideStep(
                        number = 2,
                        text = stringResource(R.string.home_empty_step2),
                        actionLabel = stringResource(R.string.home_empty_step2_action),
                        onAction = onAddSyncPair,
                    )
                }
                item {
                    HomeGuideStep(
                        number = 3,
                        text = stringResource(R.string.home_empty_step3),
                    )
                }
                item { Spacer(Modifier.height(80.dp)) } // leave room for FAB
            }
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
                        accountEmail = pair.accountId?.let { state.accountEmailById[it] },
                        isSyncing = pair.id in state.syncingPairIds,
                        onEdit = { onEditSyncPair(pair.id) },
                        onDelete = { viewModel.requestDelete(pair) },
                        onSyncNow = { viewModel.syncNow(pair) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) } // leave room for FAB
            }
        }
    }
}

@Composable
private fun HomeGuideStep(
    number: Int,
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
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

    // Determine card appearance from pair health.
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor, contentColor = cardContentColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Header: name + status icon
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

            // Status banner when action is needed
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

            // Metadata row: auto-sync + last sync
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
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

            // Action buttons
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

