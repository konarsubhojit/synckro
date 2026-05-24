package com.synckro.ui.screens.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.ui.components.CoachTooltip
import com.synckro.ui.components.EmptyState
import com.synckro.ui.theme.SynckroTheme
import com.synckro.util.logging.LogExportConfig
import com.synckro.util.logging.LogExportSink
import com.synckro.util.logging.LogVisibilityConfig
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: (() -> Unit)? = null,
    onTriggerSync: () -> Unit = { onBack?.invoke() },
    requestedPairId: Long? = null,
    onRequestedPairIdHandled: () -> Unit = {},
    showExportCoachTooltip: Boolean = false,
    onExportCoachTooltipShown: () -> Unit = {},
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportConfig by viewModel.exportConfig.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMsg = stringResource(R.string.logs_copied)
    val rowCopiedMsg = stringResource(R.string.logs_row_copy_done)
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    val exportSavedMsg = stringResource(R.string.logs_export_saved)
    val exportFailedMsg = stringResource(R.string.logs_export_failed)
    val exportSubject = stringResource(R.string.logs_export_subject)
    val exportChooser = stringResource(R.string.logs_export_chooser)
    val exportMediaStoreError = stringResource(R.string.logs_export_mediastore_error)
    val exportIoError = stringResource(R.string.logs_export_io_error)

    LaunchedEffect(requestedPairId) {
        if (requestedPairId != null) {
            viewModel.setPairFilter(requestedPairId)
            onRequestedPairIdHandled()
        }
    }

    // Observe one-shot export results from the ViewModel.
    LaunchedEffect(Unit) {
        viewModel.exportResult.collect { result ->
            result.fold(
                onSuccess = { uri ->
                    LogExportSink.handleExportUri(
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
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.nav_back),
                            )
                        }
                    }
                },
                actions = {
                    LogsActions(
                        events = state.events,
                        dateFormat = dateFormat,
                        snackbarHostState = snackbarHostState,
                        copiedMsg = copiedMsg,
                        exportConfig = exportConfig,
                        onRedactPathsChanged = viewModel::setExportRedactPaths,
                        onRedactAccountIdsChanged = viewModel::setExportRedactAccountIds,
                        onExport = { viewModel.exportLogs() },
                        showExportCoachTooltip = showExportCoachTooltip,
                        onExportCoachTooltipShown = onExportCoachTooltipShown,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LogsTabContent(
            state = state,
            onSearchQueryChange = viewModel::setSearchQuery,
            onLevelFilterChange = viewModel::setLevelFilter,
            onTagFilterChange = viewModel::setTagFilter,
            onProviderFilterChange = viewModel::setProviderFilter,
            onAccountFilterChange = viewModel::setAccountFilter,
            onTimeWindowFilterChange = viewModel::setTimeWindowFilter,
            onClearFilters = viewModel::clearFilters,
            onTriggerSync = onTriggerSync,
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
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The copy / share / export action cluster used in the Logs top-app-bar.
 *
 * Exposed (internal) so the bottom-nav host ([com.synckro.ui.navigation.MainScaffold])
 * can render the same actions in its own scaffold when the Logs tab is selected.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun LogsActions(
    events: List<SyncEvent>,
    dateFormat: SimpleDateFormat,
    snackbarHostState: SnackbarHostState,
    copiedMsg: String,
    exportConfig: LogExportConfig,
    onRedactPathsChanged: (Boolean) -> Unit,
    onRedactAccountIdsChanged: (Boolean) -> Unit,
    onExport: () -> Unit,
    showExportCoachTooltip: Boolean = false,
    onExportCoachTooltipShown: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSheet by rememberSaveable { mutableStateOf(false) }

    fun copyLogs() {
        val text = buildLogExportText(events, dateFormat, exportConfig)
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE)
                as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.logs_title), text),
        )
        scope.launch { snackbarHostState.showSnackbar(copiedMsg) }
    }

    fun shareLogsAsText() {
        val text = buildLogExportText(events, dateFormat, exportConfig)
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    context.getString(R.string.logs_share_subject),
                )
            }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.logs_share_chooser)),
        )
    }

    CoachTooltip(
        visible = showExportCoachTooltip,
        tooltipText = stringResource(R.string.coach_tooltip_logs_export),
        onShown = onExportCoachTooltipShown,
    ) {
        TextButton(onClick = { showSheet = true }) {
            Icon(
                Icons.Default.Share,
                contentDescription = stringResource(R.string.logs_share_export),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.logs_share_export))
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.logs_share_export),
                    style = MaterialTheme.typography.titleMedium,
                )
                ExportToggleRow(
                    label = stringResource(R.string.logs_redact_paths),
                    checked = exportConfig.redactPaths,
                    onCheckedChange = onRedactPathsChanged,
                )
                ExportToggleRow(
                    label = stringResource(R.string.logs_redact_account_ids),
                    checked = exportConfig.redactAccountIds,
                    onCheckedChange = onRedactAccountIdsChanged,
                )
                Button(
                    onClick = {
                        copyLogs()
                        showSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.logs_copy))
                }
                Button(
                    onClick = {
                        shareLogsAsText()
                        showSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.logs_share_text))
                }
                Button(
                    onClick = {
                        onExport()
                        showSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.logs_export))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ExportToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

/**
 * Stateless logs content (search, filter chips, events list) used by both the
 * standalone [LogsScreen] and the Logs tab on the Home screen.
 *
 * Renders no [Scaffold] or top app bar of its own; callers wrap or embed it as
 * appropriate. The DEBUG filter chip is omitted when
 * [LogVisibilityConfig.minVisibleLevel] is above DEBUG (i.e. release builds) so
 * users can't pick a level that will never match.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsTabContent(
    state: LogsViewModel.UiState,
    onSearchQueryChange: (String) -> Unit,
    onLevelFilterChange: (SyncEventLevel?) -> Unit,
    onTagFilterChange: (String?) -> Unit,
    onProviderFilterChange: (CloudProviderType?) -> Unit,
    onAccountFilterChange: (String?) -> Unit,
    onTimeWindowFilterChange: (TimeWindow?) -> Unit,
    onClearFilters: () -> Unit,
    onTriggerSync: () -> Unit,
    dateFormat: SimpleDateFormat,
    onRowLongPress: (SyncEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleLevels = LogVisibilityConfig.visibleLevels()
    Column(
        modifier =
            modifier
                .fillMaxSize(),
    ) {
        // ── Search field ─────────────────────────────────────────────────
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.logs_search_placeholder)) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = stringResource(R.string.logs_search_clear),
                        )
                    }
                }
            },
        )
        // ── Time-window filter chips ─────────────────────────────────────
        Row(
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.logs_filter_time_label),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            FilterChip(
                selected = state.timeWindowFilter == null,
                onClick = { onTimeWindowFilterChange(null) },
                label = { Text(stringResource(R.string.logs_filter_all)) },
            )
            FilterChip(
                selected = state.timeWindowFilter == TimeWindow.LAST_HOUR,
                onClick = {
                    onTimeWindowFilterChange(
                        if (state.timeWindowFilter == TimeWindow.LAST_HOUR) null else TimeWindow.LAST_HOUR,
                    )
                },
                label = { Text(stringResource(R.string.logs_filter_time_1h)) },
            )
            FilterChip(
                selected = state.timeWindowFilter == TimeWindow.LAST_24H,
                onClick = {
                    onTimeWindowFilterChange(
                        if (state.timeWindowFilter == TimeWindow.LAST_24H) null else TimeWindow.LAST_24H,
                    )
                },
                label = { Text(stringResource(R.string.logs_filter_time_24h)) },
            )
            FilterChip(
                selected = state.timeWindowFilter == TimeWindow.LAST_7D,
                onClick = {
                    onTimeWindowFilterChange(
                        if (state.timeWindowFilter == TimeWindow.LAST_7D) null else TimeWindow.LAST_7D,
                    )
                },
                label = { Text(stringResource(R.string.logs_filter_time_7d)) },
            )
        }
        // ── Level filter chips ───────────────────────────────────────────
        Row(
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.logs_filter_level_label),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            FilterChip(
                selected = state.levelFilter == null,
                onClick = { onLevelFilterChange(null) },
                label = { Text(stringResource(R.string.logs_filter_all)) },
            )
            visibleLevels.forEach { level ->
                FilterChip(
                    selected = state.levelFilter == level,
                    onClick = {
                        onLevelFilterChange(if (state.levelFilter == level) null else level)
                    },
                    label = { Text(level.name) },
                )
            }
        }
        // ── Tag filter chips ─────────────────────────────────────────────
        Row(
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.logs_filter_tag_label),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            FilterChip(
                selected = state.tagFilter == null,
                onClick = { onTagFilterChange(null) },
                label = { Text(stringResource(R.string.logs_filter_all)) },
            )
            listOf(
                SyncEventTag.Auth,
                SyncEventTag.Account,
                SyncEventTag.PairEditor,
                SyncEventTag.Scheduler,
                SyncEventTag.SyncWorker,
                SyncEventTag.RemoteEnum,
                SyncEventTag.OpApplier,
                SyncEventTag.UI,
                SyncEventTag.Export,
            ).forEach { tag ->
                FilterChip(
                    selected = state.tagFilter == tag,
                    onClick = {
                        onTagFilterChange(if (state.tagFilter == tag) null else tag)
                    },
                    label = { Text(tag) },
                )
            }
        }
        // ── Provider filter chips ────────────────────────────────────────
        Row(
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.logs_filter_provider_label),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            FilterChip(
                selected = state.providerFilter == null,
                onClick = { onProviderFilterChange(null) },
                label = { Text(stringResource(R.string.logs_filter_all)) },
            )
            CloudProviderType.entries.forEach { provider ->
                FilterChip(
                    selected = state.providerFilter == provider,
                    onClick = {
                        onProviderFilterChange(
                            if (state.providerFilter == provider) null else provider,
                        )
                    },
                    label = { Text(provider.name) },
                )
            }
        }
        // ── Account filter chips ─────────────────────────────────────────
        if (state.knownAccounts.isNotEmpty()) {
            Row(
                modifier =
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.logs_filter_account_label),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                FilterChip(
                    selected = state.accountFilter == null,
                    onClick = { onAccountFilterChange(null) },
                    label = { Text(stringResource(R.string.logs_filter_all)) },
                )
                state.knownAccounts.forEach { account ->
                    val label = account.email ?: account.displayName
                    FilterChip(
                        selected = state.accountFilter == account.id,
                        onClick = {
                            onAccountFilterChange(
                                if (state.accountFilter == account.id) null else account.id,
                            )
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
        // ── Events list / empty state ────────────────────────────────────
        if (!state.isLoading && state.events.isEmpty()) {
            if (state.hasActiveFilters) {
                EmptyState(
                    title = stringResource(R.string.logs_empty_filtered_title),
                    body = stringResource(R.string.logs_empty_filtered_body),
                    icon = Icons.Filled.History,
                    primaryActionLabel = stringResource(R.string.logs_empty_filtered_cta),
                    onPrimaryAction = onClearFilters,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                EmptyState(
                    title = stringResource(R.string.logs_empty_title),
                    body = stringResource(R.string.logs_empty_body),
                    icon = Icons.Filled.History,
                    primaryActionLabel = stringResource(R.string.logs_empty_trigger_sync_cta),
                    onPrimaryAction = onTriggerSync,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.events, key = { it.id }) { event ->
                    LogEntryRow(
                        event = event,
                        dateFormat = dateFormat,
                        onLongPress = { onRowLongPress(event) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogEntryRow(
    event: SyncEvent,
    dateFormat: SimpleDateFormat,
    onLongPress: () -> Unit = {},
) {
    val levelColor =
        when (event.level) {
            SyncEventLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
            SyncEventLevel.INFO -> MaterialTheme.colorScheme.onSurface
            SyncEventLevel.WARN -> Color(0xFFF59E0B) // Amber-500
            SyncEventLevel.ERROR -> MaterialTheme.colorScheme.error
        }
    val levelIcon: ImageVector =
        when (event.level) {
            SyncEventLevel.DEBUG -> Icons.Outlined.BugReport
            SyncEventLevel.INFO -> Icons.Default.Info
            SyncEventLevel.WARN -> Icons.Default.Warning
            SyncEventLevel.ERROR -> Icons.Default.ErrorOutline
        }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                ),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = levelIcon,
                    contentDescription = null,
                    tint = levelColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = dateFormat.format(Date(event.timestampMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = event.level.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = levelColor,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = event.tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = event.message,
                style = MaterialTheme.typography.bodySmall,
                color = levelColor,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

internal fun SyncEvent.toLogLine(dateFormat: SimpleDateFormat): String {
    val ts = dateFormat.format(Date(timestampMs))
    return "$ts ${level.name.padEnd(5)} [$tag] $message"
}

@Preview(name = "LogEntryRow — all levels (light)", showBackground = true, widthDp = 360)
@Preview(
    name = "LogEntryRow — all levels (dark)",
    showBackground = true,
    widthDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LogEntryRowPreview() {
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val baseMs = 1_700_000_000_000L
    val events =
        listOf(
            SyncEvent(
                id = 1,
                pairId = null,
                timestampMs = baseMs,
                level = SyncEventLevel.DEBUG,
                tag = SyncEventTag.SyncWorker,
                message = "Enumerating remote files…",
            ),
            SyncEvent(
                id = 2,
                pairId = null,
                timestampMs = baseMs + 1_000,
                level = SyncEventLevel.INFO,
                tag = SyncEventTag.OpApplier,
                message = "Uploaded 3 files successfully.",
            ),
            SyncEvent(
                id = 3,
                pairId = null,
                timestampMs = baseMs + 2_000,
                level = SyncEventLevel.WARN,
                tag = SyncEventTag.Auth,
                message = "Token nearing expiry, refreshing.",
            ),
            SyncEvent(
                id = 4,
                pairId = null,
                timestampMs = baseMs + 3_000,
                level = SyncEventLevel.ERROR,
                tag = SyncEventTag.SyncWorker,
                message = "Upload failed: quota exceeded.",
            ),
        )
    SynckroTheme {
        Surface {
            Column {
                events.forEach { event ->
                    LogEntryRow(event = event, dateFormat = fmt)
                }
            }
        }
    }
}

/**
 * Maximum number of log entries to include in copy / share output.
 *
 * Building one giant string for many thousands of entries can cause UI jank,
 * out-of-memory errors, or ANRs (issue #93). When the event count exceeds
 * this cap, only the most recent entries are exported and the truncation is
 * indicated with a leading `… N earlier entries omitted …` line.
 */
internal const val MAX_COPY_SHARE_ENTRIES: Int = 1_000

/**
 * Builds the plain-text log payload used by the copy and share actions.
 *
 * Limits the output to the most recent [MAX_COPY_SHARE_ENTRIES] events. When
 * truncation occurs, the first line of the result describes how many earlier
 * entries were omitted.
 */
internal fun buildLogExportText(
    events: List<SyncEvent>,
    dateFormat: SimpleDateFormat,
    config: LogExportConfig = LogExportConfig(),
): String {
    val total = events.size
    if (total <= MAX_COPY_SHARE_ENTRIES) {
        val text = events.joinToString("\n") { it.toLogLine(dateFormat) }
        return LogVisibilityConfig.redactForExport(text, config)
    }
    val omitted = total - MAX_COPY_SHARE_ENTRIES
    val recent = events.subList(total - MAX_COPY_SHARE_ENTRIES, total)
    val text =
        buildString {
            append("… $omitted earlier entries omitted (showing most recent $MAX_COPY_SHARE_ENTRIES)\n")
            recent.joinTo(this, separator = "\n") { it.toLogLine(dateFormat) }
        }
    return LogVisibilityConfig.redactForExport(text, config)
}
