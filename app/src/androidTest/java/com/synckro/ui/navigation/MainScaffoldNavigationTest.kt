package com.synckro.ui.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synckro.MainActivity
import com.synckro.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScaffoldNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun primaryTabsShowTheirScreens() {
        val activity = composeRule.activity
        dismissOnboardingIfPresent()

        // Sync history (Logs) is a primary top tab.
        val logsTab = activity.getString(R.string.nav_dest_logs)
        val logsShareExport = activity.getString(R.string.logs_share_export)
        composeRule.onNodeWithText(logsTab).performClick()
        assertTrue(composeRule.onAllNodesWithText(logsShareExport).fetchSemanticsNodes().isNotEmpty())

        // Synced folders (Pairs) is a primary top tab.
        val pairsTab = activity.getString(R.string.nav_dest_pairs)
        composeRule.onNodeWithText(pairsTab).performClick()
        composeRule.waitForIdle()
        // PairsScreen exposes an "Add sync pair" FAB content description; use
        // it as a stable, visible signal that the screen rendered.
        val addSyncPair = activity.getString(R.string.add_sync_pair)
        assertTrue(
            composeRule.onAllNodesWithContentDescription(addSyncPair).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun overflowMenuExposesSecondaryDestinations() {
        val activity = composeRule.activity
        dismissOnboardingIfPresent()

        // Open the top-right overflow ("more options") menu.
        val more = activity.getString(R.string.action_more)
        // The content description on the overflow icon may include the badge
        // suffix; match by prefix using onAllNodesWithContentDescription.
        val overflowNodes = composeRule.onAllNodesWithContentDescription(more, substring = true)
        assertTrue(overflowNodes.fetchSemanticsNodes().isNotEmpty())
        overflowNodes.onFirst().performClick()
        composeRule.waitForIdle()

        // Tapping Settings in the overflow pushes the full-screen Settings route.
        val settingsItem = activity.getString(R.string.nav_dest_settings)
        val settingsSection = activity.getString(R.string.settings_section_appearance)
        composeRule.onNodeWithText(settingsItem).performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText(settingsSection).fetchSemanticsNodes().isNotEmpty())

        // Settings now lives on its own route — pop back to MainScaffold so the
        // overflow icon is visible again, then pick Accounts.
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithContentDescription(more, substring = true)
            .onFirst().performClick()
        composeRule.waitForIdle()
        val accountsItem = activity.getString(R.string.nav_dest_accounts)
        val accountsBody = activity.getString(R.string.accounts_body)
        composeRule.onNodeWithText(accountsItem).performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText(accountsBody).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun headerShowsAppBrandingAndCloseAction() {
        val activity = composeRule.activity
        dismissOnboardingIfPresent()

        val appName = activity.getString(R.string.app_name)
        assertTrue(composeRule.onAllNodesWithText(appName).fetchSemanticsNodes().isNotEmpty())

        val close = activity.getString(R.string.action_close)
        composeRule.onNodeWithContentDescription(close).assertExists()
    }

    private fun dismissOnboardingIfPresent() {
        val onboardingSkip = composeRule.activity.getString(R.string.onboarding_skip)
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithText(onboardingSkip).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText(onboardingSkip).performClick()
            composeRule.waitForIdle()
        }
    }
}
