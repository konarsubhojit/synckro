package com.synckro.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [InstantDispatchRepository], covering the durable per-pair dispatch history that
 * keeps the instant-sync rate-limit window intact across restarts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstantDispatchRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun buildDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tempFolder.newFile("instant_dispatch_test.preferences_pb") },
        )

    @Test
    fun `unknown pair has no recorded dispatch`() =
        testScope.runTest {
            val repo = InstantDispatchRepository(buildDataStore())

            assertNull(repo.lastDispatchAtMs(1L))
        }

    @Test
    fun `recorded dispatch survives a new repository instance`() =
        testScope.runTest {
            val dataStore = buildDataStore()
            InstantDispatchRepository(dataStore).recordDispatch(1L, 1_234L)

            assertEquals(1_234L, InstantDispatchRepository(dataStore).lastDispatchAtMs(1L))
        }

    @Test
    fun `pairs keep independent dispatch times`() =
        testScope.runTest {
            val repo = InstantDispatchRepository(buildDataStore())

            repo.recordDispatch(1L, 10L)
            repo.recordDispatch(2L, 20L)

            assertEquals(10L, repo.lastDispatchAtMs(1L))
            assertEquals(20L, repo.lastDispatchAtMs(2L))
        }

    @Test
    fun `recordDispatch overwrites the previous time`() =
        testScope.runTest {
            val repo = InstantDispatchRepository(buildDataStore())

            repo.recordDispatch(1L, 10L)
            repo.recordDispatch(1L, 90L)

            assertEquals(90L, repo.lastDispatchAtMs(1L))
        }

    @Test
    fun `clearPair removes only that pair`() =
        testScope.runTest {
            val repo = InstantDispatchRepository(buildDataStore())
            repo.recordDispatch(1L, 10L)
            repo.recordDispatch(2L, 20L)

            repo.clearPair(1L)

            assertNull(repo.lastDispatchAtMs(1L))
            assertEquals(20L, repo.lastDispatchAtMs(2L))
        }
}
