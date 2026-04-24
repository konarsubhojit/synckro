package com.konarsubhojit.synckro.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.konarsubhojit.synckro.ui.screens.HomeScreen
import com.konarsubhojit.synckro.ui.screens.OnboardingScreen
import com.konarsubhojit.synckro.ui.screens.accounts.AccountsScreen
import com.konarsubhojit.synckro.ui.screens.addpair.AddSyncPairScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ACCOUNTS = "accounts"
    const val ADD_PAIR = "add_pair"
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
                onAddSyncPair = { nav.navigate(Routes.ADD_PAIR) },
                onOpenAccounts = { nav.navigate(Routes.ACCOUNTS) },
            )
        }
        composable(Routes.ACCOUNTS) {
            AccountsScreen(
                activity = activity,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.ADD_PAIR) {
            AddSyncPairScreen(
                onBack = { nav.popBackStack() },
                onOpenAccounts = {
                    // Pop the placeholder off the back stack before pushing
                    // accounts so the user returns to Home, not back here.
                    nav.popBackStack()
                    nav.navigate(Routes.ACCOUNTS)
                },
            )
        }
    }
}
