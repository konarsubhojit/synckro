package com.konarsubhojit.synckro.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.konarsubhojit.synckro.ui.screens.HomeScreen
import com.konarsubhojit.synckro.ui.screens.OnboardingScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
}

@Composable
fun SynckroNavHost() {
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
            HomeScreen()
        }
    }
}
