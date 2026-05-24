package com.synckro.ui.navigation

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.synckro.domain.model.CloudProviderType
import com.synckro.ui.screens.OnboardingScreen
import com.synckro.ui.screens.onboarding.OnboardingViewModel
import com.synckro.ui.screens.paireditor.PairEditorScreen
import com.synckro.ui.screens.paireditor.PairEditorViewModel
import com.synckro.ui.screens.pickfolder.PickLocalFolderScreen
import com.synckro.ui.screens.pickfolder.PickRemoteFolderScreen
import com.synckro.ui.screens.pickfolder.PickRemoteFolderViewModel
import com.synckro.util.navigation.AppNavEvent
import com.synckro.util.navigation.AppNavigationDispatcher
import kotlinx.coroutines.flow.filterNotNull

object Routes {
    const val ONBOARDING = "onboarding"

    /**
     * The top-level host route. Renders [MainScaffold] with its primary
     * destinations (Status / Sync history / Synced folders). Secondary
     * destinations (Conflicts / Accounts / Settings) are full-screen routes
     * pushed on top of this scaffold so they get their own Scaffold + back
     * arrow instead of being squeezed into an overflow tab.
     */
    const val MAIN = "main"

    /** Full-screen conflicts inbox, pushed on top of [MAIN]. */
    const val CONFLICTS = "conflicts"

    /** Full-screen accounts management screen, pushed on top of [MAIN]. */
    const val ACCOUNTS = "accounts?accountId={accountId}"

    /** Full-screen settings root, pushed on top of [MAIN]. */
    const val SETTINGS = "settings"

    /** Optional query parameter `pairId`; defaults to 0 (create mode). */
    const val PAIR_EDITOR = "pair_editor?pairId={pairId}"

    /** Per-pair detail screen (Phase 5c). Requires a `pairId` path arg. */
    const val PAIR_DETAIL = "pair_detail/{pairId}"

    const val PICK_FOLDER = "pick_folder"

    /** Route template for the remote folder browser; requires a `provider` query parameter. */
    const val PICK_REMOTE_FOLDER =
        "pick_remote_folder" +
            "?${PickRemoteFolderViewModel.ARG_PROVIDER}={${PickRemoteFolderViewModel.ARG_PROVIDER}}" +
            "&${PickRemoteFolderViewModel.ARG_ACCOUNT_ID}={${PickRemoteFolderViewModel.ARG_ACCOUNT_ID}}"

    fun pairEditor(pairId: Long = 0L) = "pair_editor?pairId=$pairId"

    fun pairDetail(pairId: Long) = "pair_detail/$pairId"

    fun logs(pairId: Long) = "main?destination=logs&pairId=$pairId"

    fun accounts(accountId: String? = null): String =
        if (accountId.isNullOrBlank()) {
            "accounts?accountId="
        } else {
            "accounts?accountId=${Uri.encode(accountId)}"
        }

    fun pickRemoteFolder(provider: CloudProviderType, accountId: String?): String {
        val base = "pick_remote_folder?${PickRemoteFolderViewModel.ARG_PROVIDER}=${provider.name}"
        return if (accountId.isNullOrBlank()) {
            base
        } else {
            "$base&${PickRemoteFolderViewModel.ARG_ACCOUNT_ID}=${Uri.encode(accountId)}"
        }
    }
}

@Composable
fun SynckroNavHost(
    activity: ComponentActivity,
    appNavigationDispatcher: AppNavigationDispatcher? = null,
) {
    val nav = rememberNavController()

    // One-shot tab selection forwarded to MainScaffold when an external deep-link
    // (e.g. re-auth notification) requests a specific destination. Cleared by
    // MainScaffold via [onPendingDestinationHandled].
    var pendingMainDestination by remember { mutableStateOf<MainDestination?>(null) }

    var pendingLogsPairId by remember { mutableStateOf<Long?>(null) }

    // Observe navigation commands dispatched from outside the Compose tree
    // (e.g. from a re-auth notification tap handled in MainActivity.onNewIntent).
    LaunchedEffect(appNavigationDispatcher) {
        appNavigationDispatcher?.pendingEvent
            ?.filterNotNull()
            ?.collect { event ->
                appNavigationDispatcher.consumeEvent()
                when (event) {
                    is AppNavEvent.OpenAccounts -> {
                        // Pop any full-screen routes (pair editor, folder pickers)
                        // back to the MainScaffold, then push the standalone Accounts
                        // route (Phase 6 promotion to top-level navigation).
                        nav.popBackStack(Routes.MAIN, inclusive = false)
                        nav.navigate(Routes.accounts(event.accountId)) { launchSingleTop = true }
                    }
                    is AppNavEvent.OpenLogs -> {
                        pendingMainDestination = MainDestination.Logs
                        pendingLogsPairId = event.pairId
                        nav.popBackStack(Routes.MAIN, inclusive = false)
                    }
                }
            }
    }

    NavHost(navController = nav, startDestination = Routes.ONBOARDING) {
        composable(
            route = Routes.ONBOARDING,
            enterTransition = { topLevelEnterTransition() },
            exitTransition = { topLevelExitTransition() },
            popEnterTransition = { topLevelPopEnterTransition() },
            popExitTransition = { topLevelPopExitTransition() },
        ) {
            val onboardingVm: OnboardingViewModel = hiltViewModel()
            val shouldShow by onboardingVm.shouldShowOnboarding.collectAsStateWithLifecycle()

            when (shouldShow) {
                null -> {
                    // Gateway is still resolving — render nothing to avoid a flash.
                }
                false -> {
                    // Onboarding already completed (prior run or restore).
                    // Navigate to main; LaunchedEffect ensures a single execution.
                    LaunchedEffect(Unit) {
                        nav.navigate(Routes.MAIN) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                }
                true -> {
                    OnboardingScreen(
                        activity = activity,
                        onSkip = {
                            onboardingVm.completeOnboarding()
                            nav.navigate(Routes.MAIN) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                        onCreateFirstSyncPair = {
                            onboardingVm.completeOnboarding()
                            nav.navigate(Routes.pairEditor()) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }
            }
        }
        composable(
            route = Routes.MAIN,
            enterTransition = { topLevelEnterTransition() },
            exitTransition = { topLevelExitTransition() },
            popEnterTransition = { topLevelPopEnterTransition() },
            popExitTransition = { topLevelPopExitTransition() },
        ) {
            MainScaffold(
                activity = activity,
                onClose = { activity.finish() },
                onAddSyncPair = {
                    nav.navigate(Routes.pairEditor()) { launchSingleTop = true }
                },
                onEditSyncPair = { pairId ->
                    nav.navigate(Routes.pairEditor(pairId)) { launchSingleTop = true }
                },
                onOpenPairDetail = { pairId ->
                    nav.navigate(Routes.pairDetail(pairId)) { launchSingleTop = true }
                },
                onOpenConflicts = {
                    nav.navigate(Routes.CONFLICTS) { launchSingleTop = true }
                },
                onOpenAccounts = { accountId ->
                    nav.navigate(Routes.accounts(accountId)) { launchSingleTop = true }
                },
                onOpenSettings = {
                    nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
                pendingDestination = pendingMainDestination,
                onPendingDestinationHandled = { pendingMainDestination = null },
                pendingLogsPairId = pendingLogsPairId,
                onPendingLogsPairHandled = { pendingLogsPairId = null },
            )
        }
        composable(
            route = Routes.CONFLICTS,
            enterTransition = { detailEnterTransition() },
            exitTransition = { detailExitTransition() },
            popEnterTransition = { detailPopEnterTransition() },
            popExitTransition = { detailPopExitTransition() },
        ) {
            com.synckro.ui.screens.conflictinbox.ConflictInboxScreen(
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Routes.ACCOUNTS,
            enterTransition = { detailEnterTransition() },
            exitTransition = { detailExitTransition() },
            popEnterTransition = { detailPopEnterTransition() },
            popExitTransition = { detailPopExitTransition() },
            arguments =
                listOf(
                    navArgument("accountId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) { backStackEntry ->
            val highlightId =
                backStackEntry.arguments
                    ?.getString("accountId")
                    ?.takeUnless { it.isBlank() }
            com.synckro.ui.screens.accounts.AccountsScreen(
                activity = activity,
                onBack = { nav.popBackStack() },
                highlightAccountId = highlightId,
            )
        }
        composable(
            route = Routes.SETTINGS,
            enterTransition = { detailEnterTransition() },
            exitTransition = { detailExitTransition() },
            popEnterTransition = { detailPopEnterTransition() },
            popExitTransition = { detailPopExitTransition() },
        ) {
            com.synckro.ui.screens.settings.SettingsScreen(
                onBack = { nav.popBackStack() },
                onNavigateToAccounts = { nav.navigate(Routes.accounts()) { launchSingleTop = true } },
            )
        }
        composable(
            route = Routes.PAIR_EDITOR,
            enterTransition = { detailEnterTransition() },
            exitTransition = { detailExitTransition() },
            popEnterTransition = { detailPopEnterTransition() },
            popExitTransition = { detailPopExitTransition() },
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

            // Observe the remote folder pick result in the same way.
            val pickedRemoteFolderId by backStackEntry.savedStateHandle
                .getStateFlow<String?>(PairEditorViewModel.KEY_REMOTE_FOLDER_ID, null)
                .collectAsStateWithLifecycle()
            LaunchedEffect(pickedRemoteFolderId) {
                pickedRemoteFolderId?.let { id ->
                    val name =
                        backStackEntry.savedStateHandle
                            .get<String?>(PairEditorViewModel.KEY_REMOTE_FOLDER_NAME)
                            .orEmpty()
                    val breadcrumb =
                        backStackEntry.savedStateHandle
                            .get<String?>(PairEditorViewModel.KEY_REMOTE_FOLDER_BREADCRUMB)
                            .orEmpty()
                    editorViewModel.onRemoteFolderPicked(id, name, breadcrumb)
                    backStackEntry.savedStateHandle[PairEditorViewModel.KEY_REMOTE_FOLDER_ID] = null
                    backStackEntry.savedStateHandle[PairEditorViewModel.KEY_REMOTE_FOLDER_NAME] = null
                    backStackEntry.savedStateHandle[PairEditorViewModel.KEY_REMOTE_FOLDER_BREADCRUMB] = null
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
                onPickRemoteFolder = { provider, accountId ->
                    nav.navigate(Routes.pickRemoteFolder(provider, accountId)) { launchSingleTop = true }
                },
                onSaved = { nav.popBackStack() },
                viewModel = editorViewModel,
            )
        }
        composable(
            route = Routes.PICK_FOLDER,
            enterTransition = { detailEnterTransition() },
            exitTransition = { detailExitTransition() },
            popEnterTransition = { detailPopEnterTransition() },
            popExitTransition = { detailPopExitTransition() },
        ) {
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
        composable(
            route = Routes.PICK_REMOTE_FOLDER,
            enterTransition = { detailEnterTransition() },
            exitTransition = { detailExitTransition() },
            popEnterTransition = { detailPopEnterTransition() },
            popExitTransition = { detailPopExitTransition() },
            arguments =
                listOf(
                    navArgument(PickRemoteFolderViewModel.ARG_PROVIDER) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(PickRemoteFolderViewModel.ARG_ACCOUNT_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) {
            PickRemoteFolderScreen(
                activity = activity,
                onFolderPicked = { id, name, breadcrumb ->
                    // Pass the chosen folder back to the PairEditorScreen via its
                    // SavedStateHandle so PairEditorViewModel can update its state.
                    nav.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(PairEditorViewModel.KEY_REMOTE_FOLDER_ID, id)
                    nav.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(PairEditorViewModel.KEY_REMOTE_FOLDER_NAME, name)
                    nav.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(PairEditorViewModel.KEY_REMOTE_FOLDER_BREADCRUMB, breadcrumb)
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Routes.PAIR_DETAIL,
            enterTransition = { detailEnterTransition() },
            exitTransition = { detailExitTransition() },
            popEnterTransition = { detailPopEnterTransition() },
            popExitTransition = { detailPopExitTransition() },
            arguments =
                listOf(
                    navArgument("pairId") {
                        type = NavType.LongType
                    },
                ),
        ) { backStackEntry ->
            // pairId is read by PairDetailViewModel from its SavedStateHandle.
            // The HomeViewModel scoped to the MAIN nav entry owns syncNow / requestDelete
            // so triggering Sync now / Delete from the detail screen reuses the
            // existing optimistic-spinner and undo flows.
            val mainEntry = remember(nav) { nav.getBackStackEntry(Routes.MAIN) }
            val sharedHomeViewModel: com.synckro.ui.screens.home.HomeViewModel = hiltViewModel(mainEntry)
            com.synckro.ui.screens.pairdetail.PairDetailScreen(
                onBack = { nav.popBackStack() },
                onEdit = { id -> nav.navigate(Routes.pairEditor(id)) { launchSingleTop = true } },
                onSyncNow = { id ->
                    val current = sharedHomeViewModel.state.value.pairs.firstOrNull { it.id == id }
                    if (current != null) sharedHomeViewModel.syncNow(current)
                },
                onDelete = { id ->
                    val current = sharedHomeViewModel.state.value.pairs.firstOrNull { it.id == id }
                    if (current != null) {
                        sharedHomeViewModel.requestDelete(current)
                        nav.popBackStack()
                    }
                },
                onOpenConflicts = {
                    nav.navigate(Routes.CONFLICTS) { launchSingleTop = true }
                },
                onOpenLogs = { pairId ->
                    pendingMainDestination = MainDestination.Logs
                    pendingLogsPairId = pairId
                    nav.popBackStack(Routes.MAIN, inclusive = false)
                },
            )
        }
    }
}

internal fun topLevelEnterTransition(): EnterTransition = fadeIn()

internal fun topLevelExitTransition(): ExitTransition = fadeOut()

internal fun topLevelPopEnterTransition(): EnterTransition = fadeIn()

internal fun topLevelPopExitTransition(): ExitTransition = fadeOut()

internal fun detailEnterTransition(): EnterTransition =
    slideInHorizontally(initialOffsetX = ::detailEnterInitialOffset)

internal fun detailExitTransition(): ExitTransition =
    slideOutHorizontally(targetOffsetX = ::detailExitTargetOffset)

internal fun detailPopEnterTransition(): EnterTransition =
    slideInHorizontally(initialOffsetX = ::detailPopEnterInitialOffset)

internal fun detailPopExitTransition(): ExitTransition =
    slideOutHorizontally(targetOffsetX = ::detailPopExitTargetOffset)

internal fun detailEnterInitialOffset(width: Int): Int = width

internal fun detailExitTargetOffset(width: Int): Int = -width

internal fun detailPopEnterInitialOffset(width: Int): Int = -width

internal fun detailPopExitTargetOffset(width: Int): Int = width
