package com.synckro.ui.screens.paireditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.synckro.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairEditorInstantSyncControlTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun enabledControl_exposesSwitchSemanticsAndToggles() {
        val title = targetString(R.string.pair_editor_instant_sync)
        var checked by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                InstantSyncControl(
                    checked = checked,
                    enabled = true,
                    unavailableReason = null,
                    onCheckedChange = { checked = it },
                )
            }
        }

        val control = composeRule.onNodeWithText(title)
        control
            .assertIsEnabled()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
            .performClick()
            .assertIsOn()
    }

    @Test
    fun ineligibleControl_isDisabledAndExplainsWhy() {
        val title = targetString(R.string.pair_editor_instant_sync)
        val reason = targetString(R.string.pair_editor_instant_sync_requires_upload)
        composeRule.setContent {
            MaterialTheme {
                InstantSyncControl(
                    checked = false,
                    enabled = false,
                    unavailableReason = InstantSyncUnavailableReason.DIRECTION_NOT_UPLOAD_CAPABLE,
                    onCheckedChange = {},
                )
            }
        }

        composeRule
            .onNodeWithText(title)
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        composeRule.onNodeWithText(reason).assertExists()
    }

    private fun targetString(id: Int): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(id)
}
