package com.synckro.ui.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synckro.MainActivity
import com.synckro.R
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScaffoldNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomTabsShowTheirScreenTitles() {
        val activity = composeRule.activity
        dismissOnboardingIfPresent()

        val settingsTab = activity.getString(R.string.nav_dest_settings)
        val settingsSection = activity.getString(R.string.settings_section_appearance)
        composeRule.onNodeWithText(settingsTab).performClick()
        assertTrue(composeRule.onAllNodesWithText(settingsSection).fetchSemanticsNodes().isNotEmpty())

        val accountsTab = activity.getString(R.string.nav_dest_accounts)
        val accountsBody = activity.getString(R.string.accounts_body)
        composeRule.onNodeWithText(accountsTab).performClick()
        assertTrue(composeRule.onAllNodesWithText(accountsBody).fetchSemanticsNodes().isNotEmpty())

        val logsTab = activity.getString(R.string.nav_dest_logs)
        val logsShareExport = activity.getString(R.string.logs_share_export)
        composeRule.onNodeWithText(logsTab).performClick()
        assertTrue(composeRule.onAllNodesWithText(logsShareExport).fetchSemanticsNodes().isNotEmpty())
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
