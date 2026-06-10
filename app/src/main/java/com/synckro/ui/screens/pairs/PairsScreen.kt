package com.synckro.ui.screens.pairs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.synckro.R
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import com.synckro.domain.sync.TransferProgress
import com.synckro.ui.components.CoachTooltip
import com.synckro.ui.components.CoachTooltipIds
import com.synckro.ui.components.EmptyState
import com.synckro.ui.components.SectionCard
import com.synckro.ui.components.SyncProgressRows
import com.synckro.ui.screens.home.HomeViewModel
import com.synckro.ui.screens.home.PairSummary
import com.synckro.ui.screens.pairdetail.PairDetailScreen
import kotlinx.coroutines.launch

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun PairsScreen(
    onAddSyncPair: () -> Unit,
    onEditSyncPair: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPairDetail: (Long) -> Unit = {},
    onOpenConflicts: () -> Unit = {},
    onOpenLogs: (Long) -> Unit = {},
    onOpenReauth: (accountId: String?) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val isLargeListDetail = LocalConfiguration.current.screenWidthDp >= TABLET_LIST_DETAIL_MIN_WIDTH_DP
    val scaffoldNavigator = rememberListDetailPaneScaffoldNavigator<Any>()
    val coroutineScope = rememberCoroutineScope()
    var selectedPairId by rememberSaveable { mutableStateOf<Long?>(null) }

    val pendingDelete = state.pendingDelete
    val undoLabel = stringResource(R.string.home_delete_undo_action)
    val undoMessageFmt = stringResource(R.string.home_delete_undo_message_format)
    LaunchedEffect(pendingDelete?.pair?.id) {
        val pd = pendingDelete ?: return@LaunchedEffect
        val result =
            snackbarHostState.showSnackbar(
                message = String.format(undoMessageFmt, pd.pair.displayName),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
        }
    }

    // Phase 5b: surface a snackbar after a "Sync all now" / pull-to-refresh.
    val syncedFmt = stringResource(R.string.home_sync_all_result_synced_format)
    val skippedFmt = stringResource(R.string.home_sync_all_result_skipped_format)
    val noneMsg = stringResource(R.string.home_sync_all_result_none)
    LaunchedEffect(viewModel) {
        viewModel.syncAllResults.collect { result ->
            val message =
                when {
                    result.synced == 0 && result.skipped == 0 -> noneMsg
                    result.skipped == 0 -> String.format(syncedFmt, result.synced)
                    else ->
                        String.format(syncedFmt, result.synced) +
                            " · " + String.format(skippedFmt, result.skipped)
                }
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }

    // Issue #250: explain why a manual "Sync now" tap was rejected instead of
    // failing silently.
    val blockedAlreadySyncing = stringResource(R.string.home_sync_now_blocked_already_syncing)
    val blockedNeedsRelink = stringResource(R.string.home_sync_now_blocked_needs_relink)
    val blockedNeedsReauth = stringResource(R.string.home_sync_now_blocked_needs_reauth)
    val blockedPaused = stringResource(R.string.home_sync_now_blocked_paused)
    LaunchedEffect(viewModel) {
        viewModel.syncNowBlocked.collect { reason ->
            val message =
                when (reason) {
                    HomeViewModel.ManualSyncBlockedReason.ALREADY_SYNCING -> blockedAlreadySyncing
                    HomeViewModel.ManualSyncBlockedReason.NEEDS_RELINK -> blockedNeedsRelink
                    HomeViewModel.ManualSyncBlockedReason.NEEDS_REAUTH -> blockedNeedsReauth
                    HomeViewModel.ManualSyncBlockedReason.PAUSED -> blockedPaused
                }
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }

    // Issue #262: surface the terminal sync result so the user always gets
    // explicit feedback after a "Sync now" tap, regardless of how many files
    // changed. If conflicts were detected, an action deep-links to the inbox.
    val syncNowResultApplied = stringResource(R.string.home_sync_now_result_applied_format)
    val syncNowResultAppliedConflicts = stringResource(R.string.home_sync_now_result_applied_conflicts_format)
    val syncNowResultNothing = stringResource(R.string.home_sync_now_result_nothing_to_sync)
    val syncNowResultFailed = stringResource(R.string.home_sync_now_result_failed)
    val syncNowResultViewConflicts = stringResource(R.string.home_sync_now_result_view_conflicts)
    LaunchedEffect(viewModel) {
        viewModel.syncNowResult.collect { result ->
            val summary = result.summary ?: return@collect
            val isFailure =
                summary.outcome == PairSummary.Outcome.FAILURE ||
                    summary.outcome == PairSummary.Outcome.NEEDS_REAUTH ||
                    summary.outcome == PairSummary.Outcome.NEEDS_RELINK
            val message =
                when {
                    isFailure -> syncNowResultFailed
                    summary.applied == 0 && summary.conflicts == 0 -> syncNowResultNothing
                    summary.conflicts > 0 ->
                        String.format(syncNowResultAppliedConflicts, summary.applied, summary.conflicts)
                    else -> String.format(syncNowResultApplied, summary.applied)
                }
            val actionLabel = if (!isFailure && summary.conflicts > 0) syncNowResultViewConflicts else null
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

    LaunchedEffect(isLargeListDetail, selectedPairId) {
        val currentId = selectedPairId ?: return@LaunchedEffect
        if (isLargeListDetail && !scaffoldNavigator.canNavigateBack()) {
            scaffoldNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail, currentId as Any)
        }
    }

    val openPairDetail: (Long) -> Unit = { pairId ->
        if (!isLargeListDetail) {
            onOpenPairDetail(pairId)
        } else {
            selectedPairId = pairId
            coroutineScope.launch {
                scaffoldNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail, pairId as Any)
            }
        }
    }

    if (!isLargeListDetail) {
        PairsListScaffold(
            state = state,
            onAddSyncPair = onAddSyncPair,
            onMarkFabTooltipSeen = { viewModel.markTooltipSeen(CoachTooltipIds.PAIRS_FAB) },
            onEditSyncPair = onEditSyncPair,
            onOpenPairDetail = openPairDetail,
            onOpenReauth = onOpenReauth,
            onRequestDelete = viewModel::requestDelete,
            onSyncNow = viewModel::syncNow,
            onSyncAllNow = viewModel::syncAllNow,
            onSetGlobalAutoSync = viewModel::setGlobalAutoSync,
            onSetPairAutoSync = viewModel::setPairAutoSync,
            globalAutoSyncEnabled = state.globalAutoSyncEnabled,
            snackbarHostState = snackbarHostState,
            modifier = modifier,
        )
        return
    }

    BackHandler(enabled = scaffoldNavigator.canNavigateBack()) {
        coroutineScope.launch {
            scaffoldNavigator.navigateBack()
            selectedPairId = null
        }
    }

    NavigableListDetailPaneScaffold(
        modifier = modifier,
        navigator = scaffoldNavigator,
        listPane = {
            AnimatedPane {
                PairsListScaffold(
                    state = state,
                    onAddSyncPair = onAddSyncPair,
                    onMarkFabTooltipSeen = { viewModel.markTooltipSeen(CoachTooltipIds.PAIRS_FAB) },
                    onEditSyncPair = onEditSyncPair,
                    onOpenPairDetail = openPairDetail,
                    onOpenReauth = onOpenReauth,
                    onRequestDelete = viewModel::requestDelete,
                    onSyncNow = viewModel::syncNow,
                    onSyncAllNow = viewModel::syncAllNow,
                    onSetGlobalAutoSync = viewModel::setGlobalAutoSync,
                    onSetPairAutoSync = viewModel::setPairAutoSync,
                    globalAutoSyncEnabled = state.globalAutoSyncEnabled,
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val pairId = selectedPairId
                if (pairId == null) {
                    EmptyState(
                        title = stringResource(R.string.pair_detail_title),
                        body = stringResource(R.string.pair_detail_missing),
                        icon = Icons.Filled.Inbox,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    PairDetailPane(
                        pairId = pairId,
                        onEditSyncPair = onEditSyncPair,
                        onSyncPairNow = { id ->
                            state.pairs.firstOrNull { it.id == id }?.let(viewModel::syncNow)
                        },
                        onDeletePair = { id ->
                            state.pairs.firstOrNull { it.id == id }?.let(viewModel::requestDelete)
                            selectedPairId = null
                            coroutineScope.launch { scaffoldNavigator.navigateBack() }
                        },
                        onOpenConflicts = onOpenConflicts,
                        onOpenLogs = onOpenLogs,
                        onBack = {
                            selectedPairId = null
                            coroutineScope.launch { scaffoldNavigator.navigateBack() }
                        },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairsListScaffold(
    state: HomeViewModel.UiState,
    onAddSyncPair: () -> Unit,
    onMarkFabTooltipSeen: () -> Unit,
    onEditSyncPair: (Long) -> Unit,
    onOpenPairDetail: (Long) -> Unit,
    onOpenReauth: (String?) -> Unit,
    onRequestDelete: (SyncPair) -> Unit,
    onSyncNow: (SyncPair) -> Unit,
    onSyncAllNow: () -> Unit,
    onSetGlobalAutoSync: (Boolean) -> Unit,
    onSetPairAutoSync: (SyncPair, Boolean) -> Unit,
    globalAutoSyncEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val showFabTooltip =
        state.onboardingCompletedAtMs == null &&
            CoachTooltipIds.PAIRS_FAB !in state.seenTooltips
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    val syncAllLabel = stringResource(R.string.home_sync_all_now)
                    TextButton(
                        onClick = { onSyncAllNow() },
                        enabled =
                            state.pairs.any { p ->
                                HomeViewModel.isPairEligibleForManualSync(p, state.syncingPairIds)
                            },
                    ) {
                        Text(syncAllLabel)
                    }
                },
            )
        },
        floatingActionButton = {
            CoachTooltip(
                visible = showFabTooltip,
                tooltipText = stringResource(R.string.coach_tooltip_pairs_fab),
                onShown = onMarkFabTooltipSeen,
            ) {
                FloatingActionButton(onClick = onAddSyncPair) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_sync_pair))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PairsList(
            state = state,
            onEditSyncPair = onEditSyncPair,
            onRequestDelete = onRequestDelete,
            onSyncNow = onSyncNow,
            onSyncAllNow = onSyncAllNow,
            onAddSyncPair = onAddSyncPair,
            onOpenPairDetail = onOpenPairDetail,
            onOpenReauth = onOpenReauth,
            onSetGlobalAutoSync = onSetGlobalAutoSync,
            onSetPairAutoSync = onSetPairAutoSync,
            globalAutoSyncEnabled = globalAutoSyncEnabled,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
private fun PairDetailPane(
    pairId: Long,
    onEditSyncPair: (Long) -> Unit,
    onSyncPairNow: (Long) -> Unit,
    onDeletePair: (Long) -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenLogs: (Long) -> Unit,
    onBack: () -> Unit,
) {
    key(pairId) {
        val navController = rememberNavController()
        NavHost(
            navController = navController,
            startDestination = "pair_detail/$pairId",
        ) {
            composable(
                route = "pair_detail/{pairId}",
                arguments =
                    listOf(
                        navArgument("pairId") { type = NavType.LongType },
                    ),
            ) {
                PairDetailScreen(
                    onBack = onBack,
                    onEdit = onEditSyncPair,
                    onSyncNow = onSyncPairNow,
                    onDelete = onDeletePair,
                    onOpenConflicts = onOpenConflicts,
                    onOpenLogs = onOpenLogs,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairsList(
    state: HomeViewModel.UiState,
    onEditSyncPair: (Long) -> Unit,
    onRequestDelete: (SyncPair) -> Unit,
    onSyncNow: (SyncPair) -> Unit,
    onSyncAllNow: () -> Unit,
    onAddSyncPair: () -> Unit,
    onOpenPairDetail: (Long) -> Unit,
    onOpenReauth: (accountId: String?) -> Unit,
    onSetGlobalAutoSync: (Boolean) -> Unit,
    onSetPairAutoSync: (SyncPair, Boolean) -> Unit,
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
        // Phase 5b: PullToRefreshBox triggers a "sync all now" on drag-release.
        // The indicator is owned by Material 3 and dismisses itself as soon as
        // [isRefreshing] flips back to false (we toggle it through a brief
        // LaunchedEffect so the user sees the spinner even though the actual
        // work happens off-thread inside WorkManager).
        val pullState = rememberPullToRefreshState()
        var refreshing by remember { mutableStateOf(false) }
        LaunchedEffect(refreshing) {
            if (refreshing) {
                onSyncAllNow()
                // Dismiss quickly; the per-pair spinners take over from here.
                kotlinx.coroutines.delay(400L)
                refreshing = false
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true },
            modifier = modifier,
            state = pullState,
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Phase 3 (Synced Folders redesign): master "Enable auto-sync"
                // card pinned to the top of the list. Toggling it reschedules
                // every pair via HomeViewModel.setGlobalAutoSync.
                item(key = "master_autosync") {
                    GlobalAutoSyncCard(
                        enabled = globalAutoSyncEnabled,
                        onCheckedChange = onSetGlobalAutoSync,
                    )
                }
                items(state.pairs, key = { it.id }) { pair ->
                    SyncPairRow(
                        pair = pair,
                        accountEmail = pair.accountId?.let { state.accountEmailById[it] },
                        isSyncing = pair.id in state.syncingPairIds,
                        progress = state.progressByPairId[pair.id],
                        nextRunAtMs = state.nextRunByPairId[pair.id],
                        lastSummary = state.lastSummaryByPairId[pair.id],
                        onEdit = { onEditSyncPair(pair.id) },
                        onDelete = { onRequestDelete(pair) },
                        onSyncNow = { onSyncNow(pair) },
                        onOpenDetail = { onOpenPairDetail(pair.id) },
                        onOpenReauth = { onOpenReauth(pair.accountId) },
                        onSetPairAutoSync = { enabled -> onSetPairAutoSync(pair, enabled) },
                        globalAutoSyncEnabled = globalAutoSyncEnabled,
                    )
                }
                item { Spacer(Modifier.height(80.dp)) } // leave room for FAB
            }
        }
    }
}

/**
 * Phase 3 (Synced Folders redesign): top "Enable auto-sync" master card.
 *
 * Mirrors the Settings → Sync defaults switch so users can pause/resume
 * background sync without leaving the Pairs tab. The body text changes to
 * reflect the paused state and reuses existing strings to communicate the
 * effect on individual pairs.
 */
@Composable
private fun GlobalAutoSyncCard(
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SectionCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.pairs_master_autosync_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        stringResource(
                            if (enabled) {
                                R.string.pairs_master_autosync_body
                            } else {
                                R.string.pairs_master_autosync_body_off
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun SyncPairRow(
    pair: SyncPair,
    accountEmail: String?,
    isSyncing: Boolean,
    progress: TransferProgress?,
    nextRunAtMs: Long?,
    lastSummary: PairSummary?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenReauth: () -> Unit,
    onSetPairAutoSync: (Boolean) -> Unit,
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

    val status = pairCardStatus(pair = pair, isSyncing = isSyncing, summary = lastSummary)
    val needsReauth = pair.lastSyncResult == "NEEDS_REAUTH"
    val cardColor =
        when (status) {
            PairCardStatus.NEEDS_ACTION -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    val cardContentColor =
        when (status) {
            PairCardStatus.NEEDS_ACTION -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val stripeColor: Color =
        when (status) {
            PairCardStatus.SUCCESS -> MaterialTheme.colorScheme.primary
            PairCardStatus.SYNCING -> MaterialTheme.colorScheme.tertiary
            PairCardStatus.NEEDS_ACTION -> MaterialTheme.colorScheme.error
            PairCardStatus.IDLE -> MaterialTheme.colorScheme.outline
        }
    val stripeDescription = stringResource(R.string.home_card_status_stripe)
    val reauthDeepLinkDescription = stringResource(R.string.home_needs_reauth_action_description)
    // TODO(#28): Trigger HapticHelper.light() when pair-card swipe gestures are introduced.

    SectionCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onOpenDetail),
        containerColor = cardColor,
        contentColor = cardContentColor,
        // Stripe sits flush to the left edge of the card; reserve zero left padding
        // and add it back inside the content column so the stripe runs full-height.
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Phase 5a: full-height color stripe communicating status at-a-glance.
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier =
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(stripeColor)
                        .semantics { contentDescription = stripeDescription },
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(12.dp),
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
                        val providerLabel =
                            when (pair.provider.name) {
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
                        isSyncing -> Unit
                        pair.needsReLink ->
                            Icon(
                                Icons.Default.FolderOff,
                                contentDescription = stringResource(R.string.home_needs_relink),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        needsReauth ->
                            Icon(
                                Icons.Default.Error,
                                contentDescription = stringResource(R.string.home_needs_reauth_hint),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        pair.lastSyncResult == "SUCCESS" ->
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        else -> Unit
                    }
                }

                // Phase 3 (Synced Folders redesign): explicit cloud + local
                // folder + mode rows so users can identify a pair at a glance
                // without opening Pair Detail.
                PairInfoRow(
                    icon = Icons.Default.Cloud,
                    text =
                        stringResource(
                            R.string.pairs_card_remote_folder_label,
                            // Phase 6: prefer the human-readable folder name
                            // captured at pair-creation time so the card no
                            // longer shows the opaque provider id once a sync
                            // run starts. Falls back to the id and then to
                            // "Root" for legacy pairs.
                            pair.remoteFolderName
                                ?.takeIf { it.isNotBlank() }
                                ?: pair.remoteFolderId.ifBlank { stringResource(R.string.pairs_card_remote_root) },
                        ),
                )
                val localFolderName = rememberLocalFolderName(pair.localTreeUri)
                PairInfoRow(
                    icon = Icons.Default.Folder,
                    text = stringResource(R.string.pairs_card_local_folder_label, localFolderName),
                )
                PairInfoRow(
                    icon = Icons.Default.CompareArrows,
                    text =
                        stringResource(
                            R.string.pairs_card_direction_label,
                            directionShortLabel(pair.direction),
                        ),
                )

                when {
                    pair.needsReLink ->
                        StatusBanner(
                            text = stringResource(R.string.home_needs_relink),
                            isError = true,
                        )
                    needsReauth ->
                        StatusBanner(
                            text = stringResource(R.string.home_needs_reauth_hint),
                            isError = true,
                            // Phase 5d: deep-link to the Accounts tab and highlight the
                            // affected account. The card's outer Modifier.clickable would
                            // otherwise open Pair Detail — clickable here consumes the
                            // click first so the more-specific recovery action wins.
                            modifier =
                                Modifier
                                    .clickable(onClick = onOpenReauth)
                                    .semantics {
                                        contentDescription = reauthDeepLinkDescription
                                    },
                        )
                }

                if (isSyncing) {
                    val syncingLabel = stringResource(R.string.sync_now_in_progress)
                    SyncProgressRows(
                        progress = progress,
                        syncingLabel = syncingLabel,
                        // Per-file active transfer rows now live in the Status
                        // screen's Sync status card; keep this card compact.
                        showActiveTransfers = false,
                    )
                }

                // Phase 5a: last-result summary (parsed from the latest terminal SyncEvent).
                lastSummary?.let { summary ->
                    Text(
                        text = lastSummaryLabel(summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = summaryColor(summary.outcome),
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Phase 5a: replaced the standalone "Auto-sync on/off" label with
                    // the next-run ETA, which subsumes the on/off state (paused = no ETA).
                    Text(
                        text =
                            nextRunLabel(
                                nextRunAtMs = nextRunAtMs,
                                globalAutoSyncEnabled = globalAutoSyncEnabled,
                                autoSyncEnabled = pair.autoSyncEnabled,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (nextRunAtMs == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )
                    val dateFormatter =
                        remember {
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Phase 3 (Synced Folders redesign): per-folder auto-sync
                    // switch. Mirrors the master card switch but scoped to a
                    // single pair (SyncPair.autoSyncEnabled).
                    val pairAutoSyncDescription = stringResource(R.string.pairs_pair_autosync_switch_description)
                    Switch(
                        checked = pair.autoSyncEnabled,
                        onCheckedChange = onSetPairAutoSync,
                        modifier =
                            Modifier.semantics {
                                contentDescription = pairAutoSyncDescription
                            },
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onSyncNow, enabled = !isSyncing) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = stringResource(R.string.sync_now),
                            )
                        }
                        // Phase 3 (Synced Folders redesign): collapse the
                        // standalone Edit/Delete icon buttons into a single
                        // overflow menu so the action row matches the
                        // reference screenshot.
                        PairOverflowMenu(
                            onEdit = onEdit,
                            onDelete = { showDeleteDialog = true },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact row used to surface a labelled property (cloud/local folder, mode)
 * on a pair card. Phase 3 (Synced Folders redesign).
 */
@Composable
private fun PairInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Overflow menu shown on each pair card (Phase 3 — Synced Folders redesign).
 *
 * Houses the secondary "Edit" and "Delete" actions that previously lived as
 * standalone icon buttons. The primary "Sync now" remains a top-level icon
 * so the most common action stays one tap away.
 */
@Composable
private fun PairOverflowMenu(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.pairs_overflow_menu_description),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_edit_pair)) },
                leadingIcon = {
                    Icon(Icons.Default.Edit, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.home_delete_pair),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/**
 * Resolves a SAF tree URI to its display name (the user-visible folder name)
 * using [DocumentFile.fromTreeUri]. Falls back to a stable placeholder so the
 * card never renders an empty path label.
 */
@Composable
private fun rememberLocalFolderName(localTreeUri: String): String {
    val context = LocalContext.current
    val fallback = stringResource(R.string.pairs_card_local_folder_unknown)
    return remember(localTreeUri) {
        if (localTreeUri.isBlank()) {
            fallback
        } else {
            runCatching {
                val uri = android.net.Uri.parse(localTreeUri)
                DocumentFile.fromTreeUri(context, uri)?.name
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
        }
    }
}

/** Compact direction label used on pair cards (Phase 3 — Synced Folders redesign). */
@Composable
private fun directionShortLabel(direction: SyncDirection): String =
    stringResource(
        when (direction) {
            SyncDirection.LOCAL_TO_REMOTE -> R.string.direction_local_to_remote
            SyncDirection.REMOTE_TO_LOCAL -> R.string.direction_remote_to_local
            SyncDirection.BIDIRECTIONAL -> R.string.direction_bidirectional
            SyncDirection.UPLOAD_AND_DELETE_LOCAL_AFTER_N_DAYS -> R.string.direction_upload_delete_local
            SyncDirection.DOWNLOAD_AND_DELETE_REMOTE_AFTER_N_DAYS -> R.string.direction_download_delete_remote
        },
    )

/**
 * Coarse status used to colour the left-edge stripe on a pair card. Derived
 * once at composition time so the stripe colour and the icon/banner choices
 * stay in lock-step.
 */
private enum class PairCardStatus { SUCCESS, SYNCING, NEEDS_ACTION, IDLE }

private fun pairCardStatus(
    pair: SyncPair,
    isSyncing: Boolean,
    summary: PairSummary?,
): PairCardStatus =
    when {
        isSyncing -> PairCardStatus.SYNCING
        pair.needsReLink -> PairCardStatus.NEEDS_ACTION
        pair.lastSyncResult == "NEEDS_REAUTH" -> PairCardStatus.NEEDS_ACTION
        pair.lastSyncResult == "FAILURE" -> PairCardStatus.NEEDS_ACTION
        pair.lastSyncResult == "SUCCESS" -> PairCardStatus.SUCCESS
        summary?.outcome == PairSummary.Outcome.SUCCESS -> PairCardStatus.SUCCESS
        else -> PairCardStatus.IDLE
    }

@Composable
private fun summaryColor(outcome: PairSummary.Outcome): Color =
    when (outcome) {
        PairSummary.Outcome.SUCCESS -> MaterialTheme.colorScheme.primary
        PairSummary.Outcome.PARTIAL_FAILURE -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

@Composable
private fun lastSummaryLabel(summary: PairSummary): String =
    when (summary.outcome) {
        PairSummary.Outcome.SUCCESS ->
            stringResource(R.string.home_last_result_success_format, summary.applied, summary.conflicts)
        PairSummary.Outcome.PARTIAL_FAILURE ->
            stringResource(R.string.home_last_result_partial_format, summary.applied, summary.errors)
        PairSummary.Outcome.FAILURE -> stringResource(R.string.home_last_result_failure)
        PairSummary.Outcome.NEEDS_REAUTH -> stringResource(R.string.home_last_result_needs_reauth)
        PairSummary.Outcome.NEEDS_RELINK -> stringResource(R.string.home_last_result_needs_relink)
    }

@Composable
private fun nextRunLabel(
    nextRunAtMs: Long?,
    globalAutoSyncEnabled: Boolean,
    autoSyncEnabled: Boolean,
): String {
    if (nextRunAtMs == null) {
        return if (!globalAutoSyncEnabled) {
            stringResource(R.string.home_auto_sync_paused)
        } else if (!autoSyncEnabled) {
            stringResource(R.string.home_next_sync_paused)
        } else {
            stringResource(R.string.home_next_sync_overdue)
        }
    }
    val deltaMs = nextRunAtMs - System.currentTimeMillis()
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

private const val TABLET_LIST_DETAIL_MIN_WIDTH_DP = 720
