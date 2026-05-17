package com.synckro.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.ui.screens.accounts.AccountsScreen
import com.synckro.ui.screens.conflictinbox.ConflictInboxScreen
import com.synckro.ui.screens.home.HomeViewModel
import com.synckro.ui.screens.logs.LogsScreen
import com.synckro.ui.screens.pairs.PairsScreen
import com.synckro.ui.screens.settings.SettingsScreen

/**
 * Top-level destinations rendered by [MainScaffold]'s bottom-nav / nav-rail.
 *
 * Order here is the order shown in the navigation bar.
 */
enum class MainDestination(
    val icon: ImageVector,
    val labelRes: Int,
) {
    Pairs(Icons.Filled.Folder, R.string.nav_dest_pairs),
    Conflicts(Icons.Filled.Inbox, R.string.nav_dest_conflicts),
    Logs(Icons.Filled.History, R.string.nav_dest_logs),
    Accounts(Icons.Filled.AccountCircle, R.string.nav_dest_accounts),
    Settings(Icons.Filled.Settings, R.string.nav_dest_settings),
}

/**
 * The primary navigation pattern: a Material 3 [NavigationBar] on phones and
 * [NavigationRail] on `sw600dp+` tablets. Each destination is a peer; tapping
 * a tab swaps the body and never grows the back stack.
 *
 * Hosted as the start destination by [SynckroNavHost] post-onboarding;
 * full-screen flows (pair editor, folder pickers) remain separate routes that
 * push on top of this scaffold.
 *
 * @param activity Threaded through to the Accounts destination so it can host
 *   interactive sign-in flows.
 * @param onEditSyncPair Pushed up to [SynckroNavHost] which navigates to the
 *   `pair_editor` route.
 * @param onAddSyncPair Same — pushes the `pair_editor` route with no pairId.
 * @param pendingDestination When non-null, the scaffold selects this tab on
 *   first composition (used to honour an [com.synckro.util.navigation.AppNavEvent]
 *   deep-link from a re-auth notification).
 * @param onPendingDestinationHandled Invoked once [pendingDestination] has been
 *   applied so the host can clear its one-shot state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    activity: ComponentActivity,
    onAddSyncPair: () -> Unit,
    onEditSyncPair: (Long) -> Unit,
    onOpenPairDetail: (Long) -> Unit = {},
    pendingDestination: MainDestination? = null,
    onPendingDestinationHandled: () -> Unit = {},
    // The conflicts badge reads from the shared HomeViewModel (which already
    // exposes pendingConflictCount). hiltViewModel() here is scoped to the
    // MainScaffold's NavBackStackEntry so the inner PairsScreen gets the same
    // instance.
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    var selected by rememberSaveable { mutableStateOf(MainDestination.Pairs) }

    // Honour external deep-links (e.g. re-auth notification → Accounts).
    LaunchedEffect(pendingDestination) {
        if (pendingDestination != null) {
            selected = pendingDestination
            onPendingDestinationHandled()
        }
    }

    val state by homeViewModel.state.collectAsStateWithLifecycle()
    val pendingConflictCount = state.pendingConflictCount

    // Material guidance: switch from bottom NavigationBar to a side NavigationRail
    // once the available width crosses sw600dp.
    val isExpanded = LocalConfiguration.current.screenWidthDp >= EXPANDED_WIDTH_DP

    val body: @Composable (Modifier) -> Unit = { bodyModifier ->
        Box(modifier = bodyModifier) {
            when (selected) {
                MainDestination.Pairs -> PairsScreen(
                    onAddSyncPair = onAddSyncPair,
                    onEditSyncPair = onEditSyncPair,
                    onOpenPairDetail = onOpenPairDetail,
                    viewModel = homeViewModel,
                )
                MainDestination.Conflicts -> ConflictInboxScreen(
                    // No back arrow — hosted as a tab.
                    onBack = null,
                )
                MainDestination.Logs -> LogsScreen(
                    onBack = null,
                    onTriggerSync = { selected = MainDestination.Pairs },
                )
                MainDestination.Accounts -> AccountsScreen(
                    activity = activity,
                    onBack = null,
                )
                MainDestination.Settings -> SettingsScreen(
                    onBack = null,
                )
            }
        }
    }

    if (isExpanded) {
        // sw600dp+ layout: NavigationRail on the side.
        Row(modifier = Modifier.fillMaxSize()) {
            MainNavigationRail(
                selected = selected,
                onSelect = { selected = it },
                pendingConflictCount = pendingConflictCount,
            )
            body(Modifier.fillMaxSize())
        }
    } else {
        // Compact layout: NavigationBar at the bottom.
        Scaffold(
            bottomBar = {
                MainNavigationBar(
                    selected = selected,
                    onSelect = { selected = it },
                    pendingConflictCount = pendingConflictCount,
                )
            },
        ) { padding ->
            body(Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun MainNavigationBar(
    selected: MainDestination,
    onSelect: (MainDestination) -> Unit,
    pendingConflictCount: Int,
) {
    NavigationBar {
        MainDestination.entries.forEach { destination ->
            val label = stringResource(destination.labelRes)
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = {
                    DestinationIcon(
                        destination = destination,
                        pendingConflictCount = pendingConflictCount,
                        label = label,
                    )
                },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun MainNavigationRail(
    selected: MainDestination,
    onSelect: (MainDestination) -> Unit,
    pendingConflictCount: Int,
) {
    NavigationRail {
        MainDestination.entries.forEach { destination ->
            val label = stringResource(destination.labelRes)
            NavigationRailItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = {
                    DestinationIcon(
                        destination = destination,
                        pendingConflictCount = pendingConflictCount,
                        label = label,
                    )
                },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun DestinationIcon(
    destination: MainDestination,
    pendingConflictCount: Int,
    label: String,
) {
    if (destination == MainDestination.Conflicts && pendingConflictCount > 0) {
        val badgeDescription = stringResource(
            R.string.nav_dest_conflicts_badge_format,
            pendingConflictCount,
        )
        BadgedBox(
            modifier = Modifier.semantics {
                contentDescription = "$label, $badgeDescription"
            },
            badge = {
                Badge { Text(pendingConflictCount.toString()) }
            },
        ) {
            Icon(destination.icon, contentDescription = null)
        }
    } else {
        Icon(destination.icon, contentDescription = null)
    }
}

/** Width threshold (in dp) above which we switch to a [NavigationRail]. */
private const val EXPANDED_WIDTH_DP = 600
