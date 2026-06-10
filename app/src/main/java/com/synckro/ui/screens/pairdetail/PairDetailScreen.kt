package com.synckro.ui.screens.pairdetail

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.sync.TransferProgress
import com.synckro.ui.components.ErrorState
import com.synckro.ui.components.LoadingState
import com.synckro.ui.components.SectionCard
import com.synckro.ui.screens.home.HomeViewModel
import com.synckro.ui.screens.home.PairSummary
import com.synckro.ui.screens.home.buildSyncNowSnackbar

/**
 * Per-pair detail screen (Phase 5c — issue #163).
 *
 * Renders a focused view of a single sync pair: status, next-run ETA, last
 * result, recent events, unresolved-conflict shortcut, and the core actions
 * (Sync now, Edit, Delete). Hosted at `Routes.PAIR_DETAIL`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onSyncNow: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenLogs: (Long) -> Unit,
    viewModel: PairDetailViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pair = state.pair
    val snackbarHostState = remember { SnackbarHostState() }

    // Issue #262: show a post-sync result snackbar whenever a "Sync now" run
    // for THIS pair terminates, so the user always gets explicit feedback.
    val syncNowResultResources = LocalContext.current.resources
    val syncNowResultSeparator = stringResource(R.string.home_sync_now_result_separator)
    val syncNowResultNothing = stringResource(R.string.home_sync_now_result_nothing_to_sync)
    val syncNowResultFailed = stringResource(R.string.home_sync_now_result_failed)
    val syncNowResultViewConflicts = stringResource(R.string.home_sync_now_result_view_conflicts)
    LaunchedEffect(homeViewModel, viewModel.pairId) {
        homeViewModel.syncNowResult.collect { result ->
            if (result.pairId != viewModel.pairId) return@collect
            val summary = result.summary ?: return@collect
            val appliedStr =
                syncNowResultResources.getQuantityString(
                    R.plurals.home_sync_now_result_applied,
                    summary.applied,
                    summary.applied,
                )
            val appliedConflictsStr =
                appliedStr +
                    syncNowResultSeparator +
                    syncNowResultResources.getQuantityString(
                        R.plurals.home_sync_now_result_conflicts,
                        summary.conflicts,
                        summary.conflicts,
                    )
            val (message, actionLabel) =
                buildSyncNowSnackbar(
                    summary = summary,
                    appliedStr = appliedStr,
                    appliedConflictsStr = appliedConflictsStr,
                    nothingToSyncStr = syncNowResultNothing,
                    failedStr = syncNowResultFailed,
                    viewConflictsStr = syncNowResultViewConflicts,
                )
            val snackResult =
                snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = actionLabel,
                    duration = SnackbarDuration.Long,
                )
            if (snackResult == SnackbarResult.ActionPerformed) {
                onOpenConflicts()
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(pair?.displayName ?: stringResource(R.string.pair_detail_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                actions = {
                    if (pair != null) {
                        IconButton(onClick = { onEdit(pair.id) }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.home_edit_pair),
                            )
                        }
                        IconButton(onClick = { onDelete(pair.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.home_delete_pair),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            LoadingState(
                message = stringResource(R.string.pair_detail_loading),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }
        if (state.error != null) {
            ErrorState(
                title = stringResource(R.string.error_state_pair_detail_title),
                body = stringResource(R.string.error_state_pair_detail_body),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }
        if (pair == null) {
            // Defensive — should not happen unless the pair was deleted while open.
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.pair_detail_missing),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Scaffold
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(state = state, pair = pair, onOpenAccountsForReauth = { onEdit(pair.id) })

            // Quick actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onSyncNow(pair.id) },
                    // Disable for pairs that are not eligible for a manual sync
                    // (already syncing, paused, or needing re-link / re-auth) so the
                    // action never fails silently (issue #250). The status card above
                    // explains the needs-action state.
                    enabled =
                        !state.isSyncing &&
                            HomeViewModel.isPairEligibleForManualSync(pair, emptySet()),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.sync_now))
                }
                OutlinedButton(
                    onClick = onOpenConflicts,
                    enabled = state.unresolvedConflictCount > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Inbox, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (state.unresolvedConflictCount > 0) {
                            stringResource(
                                R.string.pair_detail_conflicts_count_format,
                                state.unresolvedConflictCount,
                            )
                        } else {
                            stringResource(R.string.pair_detail_no_conflicts)
                        },
                    )
                }
            }

            RecentEventsCard(events = state.recentEvents, onOpenLogs = { onOpenLogs(pair.id) })
        }
    }
}

@Composable
private fun StatusCard(
    state: PairDetailViewModel.UiState,
    pair: com.synckro.domain.model.SyncPair,
    onOpenAccountsForReauth: () -> Unit,
) {
    val needsReauth = pair.lastSyncResult == "NEEDS_REAUTH"
    val cardColor =
        if (pair.needsReLink || needsReauth) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }

    SectionCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = cardColor,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint) = statusIconAndTint(pair = pair)
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusText(pair = pair),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.isSyncing) {
                    SyncProgressBlock(progress = state.progress)
                } else {
                    Text(
                        text = nextRunDescription(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.lastSummary?.let { summary ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = lastSummaryText(summary),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (needsReauth || pair.needsReLink) {
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = onOpenAccountsForReauth) {
                Text(
                    if (needsReauth) {
                        stringResource(R.string.pair_detail_resolve_reauth)
                    } else {
                        stringResource(R.string.pair_detail_resolve_relink)
                    },
                )
            }
        }
    }
}

@Composable
private fun SyncProgressBlock(progress: TransferProgress?) {
    val syncingLabel = stringResource(R.string.sync_now_in_progress)
    val fraction: Float? =
        when {
            progress != null && progress.totalBytes > 0L ->
                (progress.bytesTransferred.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
            progress != null && progress.totalFiles > 0 ->
                (progress.filesCompleted.toFloat() / progress.totalFiles).coerceIn(0f, 1f)
            else -> null
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (fraction != null && progress != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier =
                        Modifier.weight(1f).semantics {
                            contentDescription = "$syncingLabel ${(fraction * 100f).toInt()}%"
                            liveRegion = LiveRegionMode.Polite
                        },
                )
                Text(
                    text =
                        stringResource(
                            R.string.home_sync_progress_files_format,
                            progress.filesCompleted,
                            progress.totalFiles,
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LinearProgressIndicator(
                modifier =
                    Modifier.fillMaxWidth().semantics {
                        contentDescription = syncingLabel
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }

        progress?.currentFileName?.let { fileName ->
            Text(
                text = stringResource(R.string.home_sync_current_file_format, fileName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.semantics {
                        contentDescription = "$syncingLabel: $fileName"
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
    }
}

@Composable
private fun RecentEventsCard(events: List<SyncEvent>, onOpenLogs: () -> Unit) {
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.pair_detail_recent_events),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(onClick = onOpenLogs) {
                Text(stringResource(R.string.pair_detail_open_logs))
            }
        }

        if (events.isEmpty()) {
            Text(
                text = stringResource(R.string.pair_detail_no_events),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        val dateFormatter =
            remember {
                java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT,
                )
            }
        events.forEach { event ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = dateFormatter.format(java.util.Date(event.timestampMs)) + " · ${event.level.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = eventLevelColor(event.level),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun eventLevelColor(level: SyncEventLevel): Color =
    when (level) {
        SyncEventLevel.ERROR -> MaterialTheme.colorScheme.error
        SyncEventLevel.WARN -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

@Composable
private fun statusIconAndTint(
    pair: com.synckro.domain.model.SyncPair,
): Pair<ImageVector, Color> =
    when {
        pair.needsReLink -> Icons.Default.FolderOff to MaterialTheme.colorScheme.error
        pair.lastSyncResult == "NEEDS_REAUTH" -> Icons.Default.Error to MaterialTheme.colorScheme.error
        pair.lastSyncResult == "FAILURE" -> Icons.Default.Error to MaterialTheme.colorScheme.error
        pair.lastSyncResult == "SUCCESS" -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        else -> Icons.Default.Sync to MaterialTheme.colorScheme.primary
    }

@Composable
private fun statusText(pair: com.synckro.domain.model.SyncPair): String =
    when {
        pair.needsReLink -> stringResource(R.string.pair_detail_status_needs_relink)
        pair.lastSyncResult == "NEEDS_REAUTH" -> stringResource(R.string.pair_detail_status_needs_reauth)
        pair.lastSyncResult == "FAILURE" -> stringResource(R.string.pair_detail_status_failure)
        pair.lastSyncResult == "SUCCESS" -> stringResource(R.string.pair_detail_status_success)
        else -> stringResource(R.string.pair_detail_status_idle)
    }

@Composable
private fun nextRunDescription(state: PairDetailViewModel.UiState): String {
    val next = state.nextRunAtMs
    if (next == null) {
        return if (!state.globalAutoSyncEnabled) {
            stringResource(R.string.home_auto_sync_paused)
        } else {
            stringResource(R.string.home_next_sync_paused)
        }
    }
    val deltaMs = next - System.currentTimeMillis()
    if (deltaMs <= 60_000L) return stringResource(R.string.home_next_sync_due_now)
    val minutes = (deltaMs + 30_000L) / 60_000L
    return if (minutes < 90L) {
        stringResource(R.string.home_next_sync_in_minutes_format, minutes.toInt())
    } else {
        val hours = ((deltaMs + 30L * 60_000L) / (60L * 60_000L)).toInt()
        stringResource(R.string.home_next_sync_in_hours_format, hours)
    }
}

@Composable
private fun lastSummaryText(summary: com.synckro.ui.screens.home.PairSummary): String =
    when (summary.outcome) {
        com.synckro.ui.screens.home.PairSummary.Outcome.SUCCESS ->
            stringResource(R.string.home_last_result_success_format, summary.applied, summary.conflicts)
        com.synckro.ui.screens.home.PairSummary.Outcome.PARTIAL_FAILURE ->
            stringResource(R.string.home_last_result_partial_format, summary.applied, summary.errors)
        com.synckro.ui.screens.home.PairSummary.Outcome.FAILURE ->
            stringResource(R.string.home_last_result_failure)
        com.synckro.ui.screens.home.PairSummary.Outcome.NEEDS_REAUTH ->
            stringResource(R.string.home_last_result_needs_reauth)
        com.synckro.ui.screens.home.PairSummary.Outcome.NEEDS_RELINK ->
            stringResource(R.string.home_last_result_needs_relink)
    }
