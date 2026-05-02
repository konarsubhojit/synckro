package com.synckro.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.synckro.ui.screens.HomeScreen
import com.synckro.ui.screens.OnboardingScreen
import com.synckro.ui.screens.accounts.AccountsScreen
import com.synckro.ui.screens.conflictinbox.ConflictInboxScreen
import com.synckro.ui.screens.logs.LogsScreen
import com.synckro.ui.screens.paireditor.PairEditorScreen
import com.synckro.ui.screens.paireditor.PairEditorViewModel
import com.synckro.ui.screens.pickfolder.PickLocalFolderScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ACCOUNTS = "accounts"
    const val CONFLICT_INBOX = "conflict_inbox"

    /** Optional query parameter `pairId`; defaults to 0 (create mode). */
    const val PAIR_EDITOR = "pair_editor?pairId={pairId}"
    const val PICK_FOLDER = "pick_folder"

    /** Optional query parameter `pairId`; defaults to 0 (show all pairs). */
    const val LOGS = "logs?pairId={pairId}"

    fun pairEditor(pairId: Long = 0L) = "pair_editor?pairId=$pairId"

    fun logs(pairId: Long = 0L) = "logs?pairId=$pairId"
}

@Composable
fun SynckroNavHost(activity: ComponentActivity) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onContinue = {
                nav.navigate(Routes.HOME) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onAddSyncPair = {
                    nav.navigate(Routes.pairEditor()) { launchSingleTop = true }
                },
                onEditSyncPair = { pairId ->
                    nav.navigate(Routes.pairEditor(pairId)) { launchSingleTop = true }
                },
                onOpenAccounts = {
                    nav.navigate(Routes.ACCOUNTS) { launchSingleTop = true }
                },
                onOpenConflictInbox = {
                    nav.navigate(Routes.CONFLICT_INBOX) { launchSingleTop = true }
                },
                onOpenLogs = {
                    nav.navigate(Routes.logs()) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.ACCOUNTS) {
            AccountsScreen(
                activity = activity,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Routes.PAIR_EDITOR,
            arguments =
                listOf(
                    navArgument("pairId") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                ),
        ) { backStackEntry ->
            val pairId = backStackEntry.arguments?.getLong("pairId") ?: 0L
            // Obtain the editor's ViewModel here (scoped to this back-stack entry) so we
            // can forward folder-picker results to it. PairEditorScreen will get the same
            // instance via hiltViewModel() when we pass it in explicitly.
            val editorViewModel: PairEditorViewModel = hiltViewModel(backStackEntry)

            // The navigation back-stack entry's SavedStateHandle is a DIFFERENT instance
            // from the Hilt-injected SavedStateHandle inside PairEditorViewModel — they
            // are scoped under different keys in SavedStateRegistry. So we observe the
            // entry's handle here (which IS what PickLocalFolderScreen writes to) and
            // forward the value into the ViewModel explicitly.
            val pickedUri by backStackEntry.savedStateHandle
                .getStateFlow<String?>(PairEditorViewModel.KEY_LOCAL_TREE_URI, null)
                .collectAsStateWithLifecycle()
            LaunchedEffect(pickedUri) {
                pickedUri?.let { uri ->
                    editorViewModel.onLocalFolderPicked(uri)
                    // Clear the value so we don't reapply it on every recomposition or
                    // the next time this destination becomes active.
                    backStackEntry.savedStateHandle[PairEditorViewModel.KEY_LOCAL_TREE_URI] = null
                }
            }

            PairEditorScreen(
                pairId = pairId,
                onBack = { nav.popBackStack() },
                onPickFolder = { initialUri ->
                    // Store the current folder URI so PickLocalFolderScreen can pre-populate it.
                    backStackEntry.savedStateHandle[PairEditorViewModel.KEY_PICK_FOLDER_INITIAL_URI] =
                        initialUri
                    nav.navigate(Routes.PICK_FOLDER) { launchSingleTop = true }
                },
                onSaved = { nav.popBackStack() },
                viewModel = editorViewModel,
            )
        }
        composable(Routes.PICK_FOLDER) {
            // Read the current folder URI stored by PairEditorScreen before navigating here,
            // so the picker can show the already-selected folder.
            val initialUri =
                nav.previousBackStackEntry
                    ?.savedStateHandle
                    ?.get<String?>(PairEditorViewModel.KEY_PICK_FOLDER_INITIAL_URI)
            PickLocalFolderScreen(
                initialUri = initialUri,
                onFolderPicked = { uriString ->
                    // Pass the chosen URI back to the PairEditorScreen via its
                    // SavedStateHandle so PairEditorViewModel can update its state.
                    nav.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(PairEditorViewModel.KEY_LOCAL_TREE_URI, uriString)
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.CONFLICT_INBOX) {
            ConflictInboxScreen(
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Routes.LOGS,
            arguments =
                listOf(
                    navArgument("pairId") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                ),
        ) {
            LogsScreen(onBack = { nav.popBackStack() })
        }
    }
}
