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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import com.synckro.ui.screens.accounts.AccountsScreen
import com.synckro.ui.screens.conflictinbox.ConflictInboxScreen
import com.synckro.ui.screens.home.HomeViewModel
import com.synckro.ui.screens.logs.LogsScreen
import com.synckro.ui.screens.pairs.PairsScreen
import com.synckro.ui.screens.settings.SettingsScreen
import com.synckro.ui.screens.status.StatusScreen

/**
 * Top-level destinations rendered by [MainScaffold].
 *
 * Each destination belongs to a [Category]:
 *  * [Category.PRIMARY] — exposed as a top tab in the header [TabRow].
 *  * [Category.OVERFLOW] — accessed via the top-right "more options" menu.
 *
 * The enum order here is the order shown in the tab row / overflow menu.
 */
enum class MainDestination(
    val icon: ImageVector,
    val labelRes: Int,
    val category: Category,
) {
    Status(Icons.Filled.Dashboard, R.string.nav_dest_status, Category.PRIMARY),
    Logs(Icons.Filled.History, R.string.nav_dest_logs, Category.PRIMARY),
    Pairs(Icons.Filled.Folder, R.string.nav_dest_pairs, Category.PRIMARY),
    Conflicts(Icons.Filled.Inbox, R.string.nav_dest_conflicts, Category.OVERFLOW),
    Accounts(Icons.Filled.AccountCircle, R.string.nav_dest_accounts, Category.OVERFLOW),
    Settings(Icons.Filled.Settings, R.string.nav_dest_settings, Category.OVERFLOW),
    ;

    enum class Category { PRIMARY, OVERFLOW }
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
    pendingDestination: MainDestination? = null,
    onPendingDestinationHandled: () -> Unit = {},
    pendingAccountHighlight: String? = null,
    onPendingAccountHighlightHandled: () -> Unit = {},
    pendingLogsPairId: Long? = null,
    onPendingLogsPairHandled: () -> Unit = {},
    // The conflicts badge reads from the shared HomeViewModel (which already
    // exposes pendingConflictCount). hiltViewModel() here is scoped to the
    // MainScaffold's NavBackStackEntry so the inner PairsScreen gets the same
    // instance.
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    var selected by rememberSaveable { mutableStateOf(MainDestination.Status) }

    // The account id (if any) that AccountsScreen should briefly highlight on
    // its next composition. Set either by an external deep-link
    // ([pendingAccountHighlight]) or by an internal request from PairsScreen's
    // reauth banner — both paths land on the same AccountsScreen consumer.
    var currentAccountHighlight by remember { mutableStateOf<String?>(null) }
    var currentLogsPairId by remember { mutableStateOf<Long?>(null) }
    var overflowExpanded by remember { mutableStateOf(false) }

    // Honour external deep-links (e.g. re-auth notification → Accounts).
    LaunchedEffect(pendingDestination) {
        if (pendingDestination != null) {
            selected = pendingDestination
            onPendingDestinationHandled()
        }
    }
    LaunchedEffect(pendingAccountHighlight) {
        if (pendingAccountHighlight != null) {
            currentAccountHighlight = pendingAccountHighlight
            onPendingAccountHighlightHandled()
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
            CoachTooltipIds.LogsExport !in state.seenTooltips

    val primaryDestinations =
        remember {
            MainDestination.entries.filter { it.category == MainDestination.Category.PRIMARY }
        }
    val overflowDestinations =
        remember {
            MainDestination.entries.filter { it.category == MainDestination.Category.OVERFLOW }
        }

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
                                    CoachTooltipIds.ConflictsTab !in state.seenTooltips
                                ) {
                                    homeViewModel.markTooltipSeen(CoachTooltipIds.ConflictsTab)
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
                            overflowDestinations.forEach { destination ->
                                val label = stringResource(destination.labelRes)
                                DropdownMenuItem(
                                    text = {
                                        OverflowMenuLabel(
                                            destination = destination,
                                            label = label,
                                            pendingConflictCount = pendingConflictCount,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(destination.icon, contentDescription = null)
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        selected = destination
                                    },
                                )
                            }
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
            val primaryIndex = primaryDestinations.indexOf(selected).takeIf { it >= 0 }
            // When an overflow destination is active there is no primary tab
            // selection; we still need a non-negative index for the TabRow API
            // but we suppress the indicator entirely so the user is not misled
            // into thinking the first tab is selected.
            TabRow(
                selectedTabIndex = primaryIndex ?: 0,
                indicator = { tabPositions ->
                    if (primaryIndex != null) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[primaryIndex]),
                        )
                    }
                },
            ) {
                primaryDestinations.forEachIndexed { index, destination ->
                    val label = stringResource(destination.labelRes)
                    Tab(
                        selected = primaryIndex == index,
                        onClick = { selected = destination },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        text = { Text(label) },
                    )
                }
            }

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
                                onOpenConflicts = { selected = MainDestination.Conflicts },
                                onOpenLogs = { pairId ->
                                    currentLogsPairId = pairId
                                    selected = MainDestination.Logs
                                },
                                onOpenReauth = { accountId ->
                                    // Switch to the Accounts overflow destination and
                                    // ask it to highlight the affected account.
                                    currentAccountHighlight = accountId
                                    selected = MainDestination.Accounts
                                },
                                viewModel = homeViewModel,
                            )
                        MainDestination.Conflicts ->
                            ConflictInboxScreen(
                                // No back arrow — hosted as a destination.
                                onBack = null,
                            )
                        MainDestination.Logs ->
                            LogsScreen(
                                onBack = null,
                                onTriggerSync = { selected = MainDestination.Pairs },
                                requestedPairId = currentLogsPairId,
                                onRequestedPairIdHandled = { currentLogsPairId = null },
                                showExportCoachTooltip = showLogsExportTooltip,
                                onExportCoachTooltipShown = {
                                    homeViewModel.markTooltipSeen(CoachTooltipIds.LogsExport)
                                },
                            )
                        MainDestination.Accounts ->
                            AccountsScreen(
                                activity = activity,
                                onBack = null,
                                highlightAccountId = currentAccountHighlight,
                                onHighlightConsumed = { currentAccountHighlight = null },
                            )
                        MainDestination.Settings ->
                            SettingsScreen(
                                onBack = null,
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
    destination: MainDestination,
    label: String,
    pendingConflictCount: Int,
) {
    if (destination == MainDestination.Conflicts && pendingConflictCount > 0) {
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
