package com.synckro.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.synckro.ui.screens.logs.LogsTabContent
import com.synckro.ui.screens.logs.LogsViewModel
import com.synckro.ui.screens.logs.buildLogExportText
import com.synckro.ui.screens.logs.toLogLine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** Tabs hosted by [HomeScreen]. */
enum class HomeTab { SyncPairs, Logs }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddSyncPair: () -> Unit,
    onEditSyncPair: (Long) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenConflictInbox: () -> Unit,
    onOpenSettings: () -> Unit,
    initialTab: HomeTab = HomeTab.SyncPairs,
    viewModel: HomeViewModel = hiltViewModel(),
    logsViewModel: LogsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logsState by logsViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    val pagerState = rememberPagerState(
        initialPage = initialTab.ordinal,
        pageCount = { HomeTab.entries.size },
    )
    val selectedTab = HomeTab.entries[pagerState.currentPage]

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

    // Logs export side-effect handling (mirrors the standalone LogsScreen).
    val rowCopiedMsg = stringResource(R.string.logs_row_copy_done)
    val copiedMsg = stringResource(R.string.logs_copied)
    val exportSavedMsg = stringResource(R.string.logs_export_saved)
    val exportFailedMsg = stringResource(R.string.logs_export_failed)
    val exportSubject = stringResource(R.string.logs_export_subject)
    val exportChooser = stringResource(R.string.logs_export_chooser)
    val exportMediaStoreError = stringResource(R.string.logs_export_mediastore_error)
    val exportIoError = stringResource(R.string.logs_export_io_error)
    LaunchedEffect(Unit) {
        logsViewModel.exportResult.collect { result ->
            result.fold(
                onSuccess = { uri ->
                    handleHomeExportUri(
                        context = context,
                        uri = uri,
                        savedMsg = exportSavedMsg,
                        failedMsg = exportFailedMsg,
                        mediaStoreError = exportMediaStoreError,
                        ioError = exportIoError,
                        subject = exportSubject,
                        chooser = exportChooser,
                    )
                },
                onFailure = { e ->
                    snackbarHostState.showSnackbar(
                        exportFailedMsg.format(e.message ?: e.javaClass.simpleName),
                    )
                },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (selectedTab == HomeTab.Logs) {
                        IconButton(onClick = {
                            val text = buildLogExportText(logsState.events, dateFormat)
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    context.getString(R.string.logs_title),
                                    text,
                                ),
                            )
                            scope.launch { snackbarHostState.showSnackbar(copiedMsg) }
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.logs_copy),
                            )
                        }
                        IconButton(onClick = {
                            val text = buildLogExportText(logsState.events, dateFormat)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                                putExtra(
                                    Intent.EXTRA_SUBJECT,
                                    context.getString(R.string.logs_share_subject),
                                )
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    intent,
                                    context.getString(R.string.logs_share_chooser),
                                ),
                            )
                        }) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(R.string.logs_share),
                            )
                        }
                        IconButton(onClick = { logsViewModel.exportLogs() }) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = stringResource(R.string.logs_export),
                            )
                        }
                    }
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
                    IconButton(onClick = onOpenAccounts) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = stringResource(R.string.accounts_action),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_action),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // FAB is scoped to the Sync pairs tab only.
            if (selectedTab == HomeTab.SyncPairs) {
                FloatingActionButton(onClick = onAddSyncPair) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_sync_pair))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                HomeTab.entries.forEachIndexed { index, tab ->
                    val label = when (tab) {
                        HomeTab.SyncPairs -> stringResource(R.string.home_tab_sync_pairs)
                        HomeTab.Logs -> stringResource(R.string.home_tab_logs)
                    }
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(label) },
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (HomeTab.entries[page]) {
                    HomeTab.SyncPairs -> SyncPairsTabContent(
                        state = state,
                        onEditSyncPair = onEditSyncPair,
                        onRequestDelete = viewModel::requestDelete,
                        onSyncNow = viewModel::syncNow,
                        onOpenAccounts = onOpenAccounts,
                        onAddSyncPair = onAddSyncPair,
                        globalAutoSyncEnabled = state.globalAutoSyncEnabled,
                    )
                    HomeTab.Logs -> LogsTabContent(
                        state = logsState,
                        onSearchQueryChange = logsViewModel::setSearchQuery,
                        onLevelFilterChange = logsViewModel::setLevelFilter,
                        onTagFilterChange = logsViewModel::setTagFilter,
                        onProviderFilterChange = logsViewModel::setProviderFilter,
                        onAccountFilterChange = logsViewModel::setAccountFilter,
                        onClearFilters = logsViewModel::clearFilters,
                        onTriggerSync = {
                            // Switch to the Sync pairs tab so the user can act on a pair.
                            scope.launch {
                                pagerState.animateScrollToPage(HomeTab.SyncPairs.ordinal)
                            }
                        },
                        dateFormat = dateFormat,
                        onRowLongPress = { event ->
                            val text = event.toLogLine(dateFormat)
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    context.getString(R.string.logs_title),
                                    text,
                                ),
                            )
                            scope.launch { snackbarHostState.showSnackbar(rowCopiedMsg) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncPairsTabContent(
    state: HomeViewModel.UiState,
    onEditSyncPair: (Long) -> Unit,
    onRequestDelete: (SyncPair) -> Unit,
    onSyncNow: (SyncPair) -> Unit,
    onOpenAccounts: () -> Unit,
    onAddSyncPair: () -> Unit,
    globalAutoSyncEnabled: Boolean = true,
) {
    if (!state.isLoading && state.pairs.isEmpty()) {
        // Guide the user through the 3 steps they need to take.
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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

/**
 * Mirrors [com.synckro.ui.screens.logs.handleExportUri] but lives in this file so the
 * Home Logs tab can write into MediaStore Downloads or share via [Intent.ACTION_SEND]
 * without depending on a function that is private to [com.synckro.ui.screens.logs].
 */
private fun handleHomeExportUri(
    context: Context,
    uri: Uri,
    savedMsg: String,
    failedMsg: String,
    mediaStoreError: String,
    ioError: String,
    subject: String,
    chooser: String,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val fileName = uri.lastPathSegment ?: "synckro-logs.zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: run {
            Toast.makeText(context, mediaStoreError, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val out = resolver.openOutputStream(itemUri)
                ?: throw IllegalStateException("Failed to open output stream for MediaStore Downloads entry: $itemUri")
            val ins = resolver.openInputStream(uri)
                ?: throw IllegalStateException("Failed to open input stream for export zip: $uri")
            out.use { o -> ins.use { i -> i.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            Toast.makeText(context, savedMsg.format(fileName), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            Toast.makeText(context, failedMsg.format(e.localizedMessage ?: ioError), Toast.LENGTH_LONG).show()
        }
    } else {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooser))
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
    globalAutoSyncEnabled: Boolean = true,
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

