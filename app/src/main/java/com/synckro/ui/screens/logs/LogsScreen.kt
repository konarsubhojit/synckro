package com.synckro.ui.screens.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.ui.components.CoachTooltip
import com.synckro.ui.components.EmptyState
import com.synckro.ui.components.ErrorState
import com.synckro.ui.components.LoadingState
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
 * Stateless Sync History content: a search field over a vertical list of one-row-per-event
 * cards. Each row is a compact summary (status icon · message · right-aligned relative
 * timestamp) that can be tapped to reveal additional details (tag, level, full timestamp,
 * pair id). Long-press copies the row to the clipboard.
 *
 * The detailed structured log entries remain available behind the scenes through the
 * `Share / Export` action in the top app bar (see [LogsActions]), which bundles the
 * complete event history (all levels, including DEBUG when visible) and is reused as
 * the attachment for the "Send feedback" flow in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsTabContent(
    state: LogsViewModel.UiState,
    onSearchQueryChange: (String) -> Unit,
    onClearFilters: () -> Unit,
    onTriggerSync: () -> Unit,
    dateFormat: SimpleDateFormat,
    onRowLongPress: (SyncEvent) -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val maxContentWidth = Modifier.widthIn(max = 900.dp)
        // ── Search field ─────────────────────────────────────────────────
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier =
                maxContentWidth
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
        // ── Loading / error / empty / list ───────────────────────────────
        when {
            state.isLoading -> {
                LoadingState(
                    message = stringResource(R.string.loading_logs),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            state.error != null -> {
                ErrorState(
                    title = stringResource(R.string.error_state_logs_title),
                    body = stringResource(R.string.error_state_logs_body),
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            state.events.isEmpty() -> {
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
            }
            else -> {
                LazyColumn(
                    modifier = maxContentWidth.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.events, key = { it.id }) { event ->
                        SyncHistoryRow(
                            event = event,
                            dateFormat = dateFormat,
                            onLongPress = { onRowLongPress(event) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single sync-history card: status dot · message + tag subtitle · right-aligned
 * relative timestamp. Tapping the card expands an inline details block with the
 * full timestamp, level, tag, and originating pair id (or "All pairs" for
 * global events). Designed so the expanded region can grow in future without
 * reworking the list architecture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SyncHistoryRow(
    event: SyncEvent,
    dateFormat: SimpleDateFormat,
    onLongPress: () -> Unit = {},
) {
    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }

    val levelColor = levelColor(event.level)
    val levelIcon = levelIcon(event.level)

    val expandLabel = stringResource(R.string.logs_history_expand)
    val collapseLabel = stringResource(R.string.logs_history_collapse)
    val rowStateLabel = if (expanded) collapseLabel else expandLabel

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .animateContentSize()
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = onLongPress,
                )
                .semantics {
                    stateDescription = rowStateLabel
                },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status dot (colored circle with the level icon inside).
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .padding(0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = levelColor.copy(alpha = 0.16f),
                    modifier = Modifier.size(32.dp),
                ) {}
                Icon(
                    imageVector = levelIcon,
                    contentDescription = null,
                    tint = levelColor,
                    modifier = Modifier.size(18.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            // Message + tag subtitle.
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = event.tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            // Right-aligned relative timestamp + expand affordance.
            Column(
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = relativeTimestamp(event.timestampMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (expanded) {
            SyncHistoryRowDetails(
                event = event,
                dateFormat = dateFormat,
                levelColor = levelColor,
            )
        }
    }
}

@Composable
private fun SyncHistoryRowDetails(
    event: SyncEvent,
    dateFormat: SimpleDateFormat,
    levelColor: Color,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = DETAIL_LABEL_WIDTH + 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DetailLine(
            label = stringResource(R.string.logs_history_full_time_label),
            value = dateFormat.format(Date(event.timestampMs)),
            valueFontFamily = FontFamily.Monospace,
        )
        DetailLine(
            label = stringResource(R.string.logs_history_level_label),
            value = event.level.name,
            valueColor = levelColor,
        )
        DetailLine(
            label = stringResource(R.string.logs_history_tag_label),
            value = event.tag,
        )
        DetailLine(
            label = stringResource(R.string.logs_history_pair_label),
            value =
                event.pairId?.toString()
                    ?: stringResource(R.string.logs_history_pair_global),
        )
    }
}

/**
 * Width of the label column in [DetailLine]. Kept as a named constant so the
 * start indent of [SyncHistoryRowDetails] stays in lock-step with the label
 * column when one of them is tuned.
 */
private val DETAIL_LABEL_WIDTH = 56.dp

@Composable
private fun DetailLine(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueFontFamily: FontFamily? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(DETAIL_LABEL_WIDTH),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontFamily = valueFontFamily,
        )
    }
}

private fun levelColor(level: SyncEventLevel): Color =
    when (level) {
        SyncEventLevel.DEBUG -> Color(0xFF6B7280) // Slate-500
        SyncEventLevel.INFO -> Color(0xFF22C55E) // Green-500 (sync success-style)
        SyncEventLevel.WARN -> Color(0xFFF59E0B) // Amber-500
        SyncEventLevel.ERROR -> Color(0xFFEF4444) // Red-500
    }

private fun levelIcon(level: SyncEventLevel): ImageVector =
    when (level) {
        SyncEventLevel.DEBUG -> Icons.Outlined.BugReport
        SyncEventLevel.INFO -> Icons.Default.CheckCircle
        SyncEventLevel.WARN -> Icons.Default.Warning
        SyncEventLevel.ERROR -> Icons.Default.ErrorOutline
    }

/**
 * Short, human-friendly relative timestamp ("2 min ago", "yesterday", "Mar 5", …)
 * used for the right-aligned time column in the sync-history list. Falls back
 * to a numeric date when the event is older than ~a week.
 *
 * Future timestamps (negative delta — e.g. caused by a clock skew between the
 * device and the sync provider) are clamped to "just now" so the UI never
 * displays a confusing "in 3 minutes" line for an event that has already been
 * recorded.
 */
internal fun relativeTimestamp(timestampMs: Long, now: Long = System.currentTimeMillis()): String {
    val delta = now - timestampMs
    return when {
        delta < DateUtils.MINUTE_IN_MILLIS -> "just now"
        else ->
            DateUtils
                .getRelativeTimeSpanString(
                    timestampMs,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE,
                )
                .toString()
    }
}

internal fun SyncEvent.toLogLine(dateFormat: SimpleDateFormat): String {
    val ts = dateFormat.format(Date(timestampMs))
    return "$ts ${level.name.padEnd(5)} [$tag] $message"
}

@Preview(name = "SyncHistoryRow — all levels (light)", showBackground = true, widthDp = 360)
@Preview(
    name = "SyncHistoryRow — all levels (dark)",
    showBackground = true,
    widthDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SyncHistoryRowPreview() {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }
    val baseMs = System.currentTimeMillis()
    val events =
        listOf(
            SyncEvent(
                id = 1,
                pairId = 42L,
                timestampMs = baseMs - 30_000,
                level = SyncEventLevel.INFO,
                tag = SyncEventTag.SYNC_WORKER,
                message = "Sync completed — 12 files uploaded, 3 downloaded.",
            ),
            SyncEvent(
                id = 2,
                pairId = 42L,
                timestampMs = baseMs - 5 * 60_000,
                level = SyncEventLevel.WARN,
                tag = SyncEventTag.AUTH,
                message = "Token nearing expiry, refreshing in background.",
            ),
            SyncEvent(
                id = 3,
                pairId = null,
                timestampMs = baseMs - 2 * 60 * 60_000,
                level = SyncEventLevel.ERROR,
                tag = SyncEventTag.SYNC_WORKER,
                message = "Upload failed: cloud quota exceeded.",
            ),
            SyncEvent(
                id = 4,
                pairId = 42L,
                timestampMs = baseMs - 25 * 60 * 60_000,
                level = SyncEventLevel.DEBUG,
                tag = SyncEventTag.SYNC_WORKER,
                message = "Enumerating remote files…",
            ),
        )
    SynckroTheme {
        Surface {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                events.forEach { event ->
                    SyncHistoryRow(event = event, dateFormat = fmt)
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
