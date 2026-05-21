package com.synckro.ui.screens.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.entity.SyncPairEntity
import com.synckro.data.repository.AccountRepository
import com.synckro.data.repository.SettingsRepository
import com.synckro.domain.auth.Account
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [OnboardingGateway]. Verifies that [OnboardingGateway.isRequired]
 * returns the correct value for each completion condition, and that
 * [OnboardingGateway.complete] suppresses subsequent calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingGatewayTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val accountRepository: AccountRepository = mockk(relaxed = true)
    private val syncPairDao: SyncPairDao = mockk(relaxed = true)

    private val accountsFlow = MutableStateFlow<List<Account>>(emptyList())
    private val pairsFlow = MutableStateFlow<List<SyncPairEntity>>(emptyList())

    @Before
    fun setUp() {
        every { accountRepository.observeAll() } returns accountsFlow
        every { syncPairDao.observeAll() } returns pairsFlow
    }

    private fun buildGateway(): OnboardingGateway {
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tempFolder.newFile("settings_gateway_test.preferences_pb") },
            )
        return OnboardingGateway(
            settingsRepository = SettingsRepository(dataStore),
            accountRepository = accountRepository,
            syncPairDao = syncPairDao,
        )
    }

    @Test
    fun `isRequired emits true on a fresh install with no accounts and no pairs`() =
        testScope.runTest {
            val gateway = buildGateway()
            assertTrue(
                "Expected onboarding to be required on first launch",
                gateway.isRequired().first(),
            )
        }

    @Test
    fun `isRequired emits false after complete() is called`() =
        testScope.runTest {
            val gateway = buildGateway()
            gateway.complete()
            assertFalse(
                "Expected onboarding to be skipped after complete()",
                gateway.isRequired().first(),
            )
        }

    @Test
    fun `isRequired emits false when at least one account exists (implicit completion)`() =
        testScope.runTest {
            accountsFlow.value =
                listOf(Account("id1", CloudProviderType.GOOGLE_DRIVE, "Test User", "test@example.com"))
            val gateway = buildGateway()
            assertFalse(
                "Expected onboarding to be skipped when an account already exists",
                gateway.isRequired().first(),
            )
        }

    @Test
    fun `isRequired emits false when at least one sync pair exists (implicit completion)`() =
        testScope.runTest {
            pairsFlow.value = listOf(fakeSyncPairEntity())
            val gateway = buildGateway()
            assertFalse(
                "Expected onboarding to be skipped when a sync pair already exists",
                gateway.isRequired().first(),
            )
        }

    @Test
    fun `isRequired emits true when completed_at is null and no accounts or pairs exist`() =
        testScope.runTest {
            accountsFlow.value = emptyList()
            pairsFlow.value = emptyList()
            val gateway = buildGateway()
            assertTrue(
                "Expected onboarding to be required with empty accounts and pairs",
                gateway.isRequired().first(),
            )
        }

    @Test
    fun `complete() called twice still results in isRequired emitting false`() =
        testScope.runTest {
            val gateway = buildGateway()
            gateway.complete()
            gateway.complete()
            assertFalse(
                "Expected onboarding to remain skipped after duplicate complete() calls",
                gateway.isRequired().first(),
            )
        }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun fakeSyncPairEntity() =
        SyncPairEntity(
            id = 1L,
            displayName = "Test Pair",
            localTreeUri = "content://tree/test",
            provider = CloudProviderType.GOOGLE_DRIVE,
            accountId = "account1",
            remoteFolderId = "remote1",
            direction = SyncDirection.LOCAL_TO_REMOTE,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
            includeGlobs = "",
            excludeGlobs = "",
            autoSyncEnabled = true,
            wifiOnly = true,
            requiresCharging = false,
            excludeEmptyFolders = false,
        )
}
