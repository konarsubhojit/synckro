package com.synckro.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [SettingsRepository]. Exercises the read/write contract of the
 * global auto-sync preference using an in-memory (temp-file) DataStore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun buildRepository(): SettingsRepository {
        val dataStore =
            PreferenceDataStoreFactory.create(
                // Use backgroundScope so DataStore's internal collection coroutine does not
                // trigger UncompletedCoroutinesError when runTest finishes.
                scope = testScope.backgroundScope,
                produceFile = { tempFolder.newFile("settings_test.preferences_pb") },
            )
        return SettingsRepository(dataStore)
    }

    @Test
    fun `globalAutoSyncEnabled defaults to true`() =
        testScope.runTest {
            val repo = buildRepository()
            assertTrue(
                "Expected globalAutoSyncEnabled to default to true",
                repo.globalAutoSyncEnabled.first(),
            )
        }

    @Test
    fun `setGlobalAutoSync false persists false`() =
        testScope.runTest {
            val repo = buildRepository()
            repo.setGlobalAutoSync(false)
            assertFalse(
                "Expected globalAutoSyncEnabled to be false after setGlobalAutoSync(false)",
                repo.globalAutoSyncEnabled.first(),
            )
        }

    @Test
    fun `setGlobalAutoSync toggles back to true`() =
        testScope.runTest {
            val repo = buildRepository()
            repo.setGlobalAutoSync(false)
            repo.setGlobalAutoSync(true)
            assertTrue(
                "Expected globalAutoSyncEnabled to be true after toggling back",
                repo.globalAutoSyncEnabled.first(),
            )
        }

    @Test
    fun `multiple writes reflect the latest value`() =
        testScope.runTest {
            val repo = buildRepository()
            repo.setGlobalAutoSync(false)
            repo.setGlobalAutoSync(false)
            repo.setGlobalAutoSync(true)
            assertTrue(
                "Expected last write (true) to be reflected",
                repo.globalAutoSyncEnabled.first(),
            )
        }

    @Test
    fun `enableHaptics defaults to true`() =
        testScope.runTest {
            val repo = buildRepository()
            assertTrue(
                "Expected enableHaptics to default to true",
                repo.enableHaptics.first(),
            )
        }

    @Test
    fun `setEnableHaptics false persists false`() =
        testScope.runTest {
            val repo = buildRepository()
            repo.setEnableHaptics(false)
            assertFalse(
                "Expected enableHaptics to be false after setEnableHaptics(false)",
                repo.enableHaptics.first(),
            )
        }
}
