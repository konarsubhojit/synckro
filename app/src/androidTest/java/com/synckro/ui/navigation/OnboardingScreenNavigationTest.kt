package com.synckro.ui.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synckro.MainActivity
import com.synckro.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the first-run [com.synckro.ui.screens.OnboardingScreen]
 * pager: verifies the **Next** CTA advances through all three steps (and its
 * label swaps to the final-page CTA), and that **Skip** dismisses the wizard
 * without blocking access to the rest of the app.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun nextButtonAdvancesThroughAllStepsAndSwapsFinalCta() {
        val activity = composeRule.activity
        composeRule.waitForIdle()

        // Step 1: Welcome page.
        val title = activity.getString(R.string.onboarding_title)
        assertTrue(composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
        val nextLabel = activity.getString(R.string.onboarding_next)
        composeRule.onNodeWithText(nextLabel).performClick()
        composeRule.waitForIdle()

        // Step 2: Connect-account page.
        val page2Title = activity.getString(R.string.onboarding_page2_title)
        assertTrue(composeRule.onAllNodesWithText(page2Title).fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithText(nextLabel).performClick()
        composeRule.waitForIdle()

        // Step 3: First-pair page. The CTA now reads "Create my first sync pair".
        val page3Title = activity.getString(R.string.onboarding_page3_title)
        assertTrue(composeRule.onAllNodesWithText(page3Title).fetchSemanticsNodes().isNotEmpty())
        val createFirstPairLabel = activity.getString(R.string.onboarding_create_first_pair)
        assertTrue(
            composeRule.onAllNodesWithText(createFirstPairLabel).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun skipDismissesWizardWithoutBlockingAccessToTheApp() {
        val activity = composeRule.activity
        composeRule.waitForIdle()

        val skipLabel = activity.getString(R.string.onboarding_skip)
        composeRule.onNodeWithText(skipLabel).performClick()
        composeRule.waitForIdle()

        // The wizard is gone and MainScaffold's primary destinations are reachable.
        val pairsTab = activity.getString(R.string.nav_dest_pairs)
        assertTrue(composeRule.onAllNodesWithText(pairsTab).fetchSemanticsNodes().isNotEmpty())
        val onboardingTitle = activity.getString(R.string.onboarding_title)
        assertTrue(composeRule.onAllNodesWithText(onboardingTitle).fetchSemanticsNodes().isEmpty())
    }
}
