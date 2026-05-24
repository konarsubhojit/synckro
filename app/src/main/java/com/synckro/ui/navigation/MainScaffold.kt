package com.synckro.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.ui.components.CoachTooltipIds
import com.synckro.ui.components.MainDestinationTabRow
import com.synckro.ui.screens.home.HomeViewModel
import com.synckro.ui.screens.logs.LogsScreen
import com.synckro.ui.screens.pairs.PairsScreen
import com.synckro.ui.screens.status.StatusScreen

/**
 * Top-level destinations rendered inline by [MainScaffold] as primary tabs.
 *
 * Secondary destinations (Conflicts / Accounts / Settings) are no longer
 * embedded tabs — they have been promoted to dedicated full-screen routes
 * pushed on top of [MainScaffold] via [SynckroNavHost]. The overflow menu
 * navigates to those routes instead of selecting an inner tab.
 */
enum class MainDestination(
    val icon: ImageVector,
    val labelRes: Int,
) {
    Status(Icons.Filled.Dashboard, R.string.nav_dest_status),
    Logs(Icons.Filled.History, R.string.nav_dest_logs),
    Pairs(Icons.Filled.Folder, R.string.nav_dest_pairs),
}

/**
 * The primary navigation pattern (Phase 1 redesign): a top [TopAppBar] with the
 * app branding plus a close action and an overflow menu, followed by a [TabRow]
 * exposing the three primary destinations (Status / Sync history / Synced
 * folders). Secondary destinations (Conflicts / Accounts / Settings) live in
 * the overflow menu.
 *
 * Hosted as the start destination by [SynckroNavHost] post-onboarding;
 * full-screen flows (pair editor, folder pickers) remain separate routes that
 * push on top of this scaffold.
 *
 * Tab state is preserved across configuration changes via [rememberSaveable].
 *
 * @param activity Threaded through to the Accounts destination so it can host
 *   interactive sign-in flows.
 * @param onClose Invoked when the user taps the close action in the header
 *   (typically finishes the host activity).
 * @param onEditSyncPair Pushed up to [SynckroNavHost] which navigates to the
 *   `pair_editor` route.
 * @param onAddSyncPair Same — pushes the `pair_editor` route with no pairId.
 * @param pendingDestination When non-null, the scaffold selects this destination
 *   on first composition (used to honour an
 *   [com.synckro.util.navigation.AppNavEvent] deep-link from a re-auth
 *   notification). Works for both primary tabs and overflow destinations.
 * @param onPendingDestinationHandled Invoked once [pendingDestination] has been
 *   applied so the host can clear its one-shot state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    activity: ComponentActivity,
    onAddSyncPair: () -> Unit,
    onEditSyncPair: (Long) -> Unit,
    onClose: () -> Unit = {},
    onOpenPairDetail: (Long) -> Unit = {},
    onOpenConflicts: () -> Unit = {},
    onOpenAccounts: (String?) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    pendingDestination: MainDestination? = null,
    onPendingDestinationHandled: () -> Unit = {},
    pendingLogsPairId: Long? = null,
    onPendingLogsPairHandled: () -> Unit = {},
    // The conflicts badge reads from the shared HomeViewModel (which already
    // exposes pendingConflictCount). hiltViewModel() here is scoped to the
    // MainScaffold's NavBackStackEntry so the inner PairsScreen gets the same
    // instance.
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    var selected by rememberSaveable { mutableStateOf(MainDestination.Status) }

    var currentLogsPairId by remember { mutableStateOf<Long?>(null) }
    var overflowExpanded by remember { mutableStateOf(false) }

    // Honour external deep-links (e.g. re-auth notification → Logs).
    LaunchedEffect(pendingDestination) {
        if (pendingDestination != null) {
            selected = pendingDestination
            onPendingDestinationHandled()
        }
    }
    LaunchedEffect(pendingLogsPairId) {
        if (pendingLogsPairId != null) {
            currentLogsPairId = pendingLogsPairId
            onPendingLogsPairHandled()
        }
    }

    val state by homeViewModel.state.collectAsStateWithLifecycle()
    val pendingConflictCount = state.pendingConflictCount
    val showLogsExportTooltip =
        selected == MainDestination.Logs &&
            state.hasCompletedSyncRun &&
            CoachTooltipIds.LOGS_EXPORT !in state.seenTooltips

    val primaryDestinations = remember { MainDestination.entries.toList() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = {
                                overflowExpanded = true
                                // The conflicts-tab coach tooltip used to anchor to
                                // a dedicated bottom-nav item; with conflicts now
                                // living in the overflow menu we mark it seen the
                                // first time the user discovers the menu so we don't
                                // re-show stale guidance.
                                if (pendingConflictCount > 0 &&
                                    CoachTooltipIds.CONFLICTS_TAB !in state.seenTooltips
                                ) {
                                    homeViewModel.markTooltipSeen(CoachTooltipIds.CONFLICTS_TAB)
                                }
                            },
                        ) {
                            OverflowIcon(
                                pendingConflictCount = pendingConflictCount,
                                description = stringResource(R.string.action_more),
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    OverflowMenuLabel(
                                        labelRes = R.string.nav_dest_conflicts,
                                        showBadge = pendingConflictCount > 0,
                                        pendingConflictCount = pendingConflictCount,
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.Inbox, contentDescription = null)
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onOpenConflicts()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_dest_accounts)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.AccountCircle, contentDescription = null)
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onOpenAccounts(null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_dest_settings)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Settings, contentDescription = null)
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onOpenSettings()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            MainDestinationTabRow(
                primaryDestinations = primaryDestinations,
                selectedDestination = selected,
                onSelectDestination = { selected = it },
            )

            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = selected,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    },
                    label = "main_tab_transition",
                ) { destination ->
                    when (destination) {
                        MainDestination.Status ->
                            StatusScreen(
                                viewModel = homeViewModel,
                            )
                        MainDestination.Pairs ->
                            PairsScreen(
                                onAddSyncPair = onAddSyncPair,
                                onEditSyncPair = onEditSyncPair,
                                onOpenPairDetail = onOpenPairDetail,
                                onOpenConflicts = onOpenConflicts,
                                onOpenLogs = { pairId ->
                                    currentLogsPairId = pairId
                                    selected = MainDestination.Logs
                                },
                                onOpenReauth = { accountId ->
                                    // Phase 6: Accounts is now its own full-screen
                                    // route, so navigate (with optional highlight)
                                    // instead of switching an inner tab.
                                    onOpenAccounts(accountId)
                                },
                                viewModel = homeViewModel,
                            )
                        MainDestination.Logs ->
                            LogsScreen(
                                onBack = null,
                                onTriggerSync = { selected = MainDestination.Pairs },
                                requestedPairId = currentLogsPairId,
                                onRequestedPairIdHandled = { currentLogsPairId = null },
                                showExportCoachTooltip = showLogsExportTooltip,
                                onExportCoachTooltipShown = {
                                    homeViewModel.markTooltipSeen(CoachTooltipIds.LOGS_EXPORT)
                                },
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverflowIcon(
    pendingConflictCount: Int,
    description: String,
) {
    if (pendingConflictCount > 0) {
        val badgeDescription =
            stringResource(
                R.string.nav_dest_conflicts_badge_format,
                pendingConflictCount,
            )
        BadgedBox(
            modifier =
                Modifier.semantics {
                    contentDescription = "$description, $badgeDescription"
                },
            badge = {
                Badge { Text(pendingConflictCount.toString()) }
            },
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = null)
        }
    } else {
        Icon(Icons.Filled.MoreVert, contentDescription = description)
    }
}

@Composable
private fun OverflowMenuLabel(
    labelRes: Int,
    showBadge: Boolean,
    pendingConflictCount: Int,
) {
    val label = stringResource(labelRes)
    if (showBadge && pendingConflictCount > 0) {
        val badgeDescription =
            stringResource(
                R.string.nav_dest_conflicts_badge_format,
                pendingConflictCount,
            )
        BadgedBox(
            modifier =
                Modifier.semantics {
                    contentDescription = "$label, $badgeDescription"
                },
            badge = {
                Badge { Text(pendingConflictCount.toString()) }
            },
        ) {
            Text(label)
        }
    } else {
        Text(label)
    }
}
