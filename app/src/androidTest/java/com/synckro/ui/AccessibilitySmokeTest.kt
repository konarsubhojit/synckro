package com.synckro.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synckro.MainActivity
import com.synckro.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pairsTab_leafNodesExposeTextOrContentDescription() {
        val pairsLabel = composeRule.activity.getString(R.string.nav_dest_pairs)
        val onboardingSkip = composeRule.activity.getString(R.string.onboarding_skip)

        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithText(onboardingSkip).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText(onboardingSkip).performClick()
            composeRule.waitForIdle()
        }

        composeRule.onNodeWithText(pairsLabel).performClick()
        composeRule.waitForIdle()

        val root = composeRule.onRoot(useUnmergedTree = false).fetchSemanticsNode()
        val failures = mutableListOf<Int>()

        collectLeafAccessibilityFailures(root, failures)

        assertTrue(
            "Found leaf semantics nodes without text or contentDescription: $failures",
            failures.isEmpty(),
        )
    }
}

private fun collectLeafAccessibilityFailures(
    node: SemanticsNode,
    failures: MutableList<Int>,
) {
    if (node.children.isEmpty()) {
        val config = node.config
        val hasText =
            config.contains(SemanticsProperties.Text) &&
                config[SemanticsProperties.Text].any { it.text.isNotBlank() }
        val hasContentDescription =
            config.contains(SemanticsProperties.ContentDescription) &&
                config[SemanticsProperties.ContentDescription].any { it.isNotBlank() }

        if (!hasText && !hasContentDescription) {
            failures += node.id
        }
        return
    }
    node.children.forEach { child ->
        collectLeafAccessibilityFailures(child, failures)
    }
}
