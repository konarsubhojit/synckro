package com.synckro.ui.screens.accounts

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.synckro.R
import com.synckro.domain.provider.StorageQuota
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the inline storage-quota bar on account cards: it renders
 * proportionally for a fixture quota and is absent when no quota is available.
 */
@RunWith(AndroidJUnit4::class)
class StorageUsageRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val progressBars =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test
    fun quotaWithPositiveTotal_rendersProportionalBar() {
        setRow(quota = StorageQuota(usedBytes = 25L * GB, totalBytes = 100L * GB), quotaResolved = true)

        composeRule
            .onNode(progressBars)
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.25f, 0f..1f))
    }

    @Test
    fun resolvedNullQuota_rendersNoBarAndNoPlaceholder() {
        setRow(quota = null, quotaResolved = true)

        composeRule.onNode(progressBars).assertDoesNotExist()
        composeRule.onNodeWithText(targetString(R.string.accounts_storage_fetching)).assertDoesNotExist()
    }

    @Test
    fun unresolvedQuota_showsPlaceholderWithoutBar() {
        setRow(quota = null, quotaResolved = false)

        composeRule.onNode(progressBars).assertDoesNotExist()
        composeRule.onNodeWithText(targetString(R.string.accounts_storage_fetching)).assertExists()
    }

    @Test
    fun quotaWithoutTotal_rendersNoBar() {
        setRow(quota = StorageQuota(usedBytes = 5L * GB, totalBytes = 0L), quotaResolved = true)

        composeRule.onNode(progressBars).assertDoesNotExist()
    }

    private fun setRow(
        quota: StorageQuota?,
        quotaResolved: Boolean,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                StorageUsageRow(quota = quota, quotaResolved = quotaResolved, context = context)
            }
        }
    }

    private fun targetString(resId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private companion object {
        const val GB = 1024L * 1024L * 1024L
    }
}
