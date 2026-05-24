package com.synckro.ui.screens.accounts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.repository.AccountRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.domain.auth.Account
import com.synckro.domain.auth.AuthManager
import com.synckro.domain.auth.AuthManagerRegistry
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.SyncPair
import com.synckro.util.error.UserMessageReporter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var accountRepository: AccountRepository
    private lateinit var syncPairDao: SyncPairDao
    private lateinit var syncPairRepository: SyncPairRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var syncEventRepository: SyncEventRepository
    private lateinit var reauthRows: MutableStateFlow<List<SyncPairDao.AccountReauthRow>>

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        accountRepository = mockk(relaxed = true)
        syncPairDao = mockk(relaxed = true)
        syncPairRepository = mockk(relaxed = true)
        syncScheduler = mockk(relaxed = true)
        syncEventRepository = mockk(relaxed = true)
        reauthRows = MutableStateFlow(emptyList())
        every { syncPairDao.observeAccountsNeedingReauth() } returns reauthRows
        coEvery { accountRepository.reconcileProvider(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refresh lists accounts under their provider rows`() =
        runTest {
            val oneDriveAccount = account("od-1", CloudProviderType.ONEDRIVE, "onedrive@example.com")
            val googleA = account("gd-1", CloudProviderType.GOOGLE_DRIVE, "alpha@gmail.com")
            val googleB = account("gd-2", CloudProviderType.GOOGLE_DRIVE, "beta@gmail.com")
            val registry =
                AuthManagerRegistry(
                    mapOf(
                        CloudProviderType.ONEDRIVE to manager("OneDrive", CloudProviderType.ONEDRIVE, listOf(oneDriveAccount)),
                        CloudProviderType.GOOGLE_DRIVE to
                            manager(
                                "Google Drive",
                                CloudProviderType.GOOGLE_DRIVE,
                                listOf(googleA, googleB),
                            ),
                    ),
                )

            val vm = createVm(registry)
            advanceUntilIdle()

            val rows = vm.state.value.rows
            assertEquals(2, rows.size)
            assertEquals(listOf(oneDriveAccount), rows[0].accounts.map { it.account })
            assertEquals(listOf(googleA, googleB), rows[1].accounts.map { it.account })
        }

    @Test
    fun `reauth flags stay scoped to the affected provider account`() =
        runTest {
            val oneDriveAccount = account("od-1", CloudProviderType.ONEDRIVE, "onedrive@example.com")
            val googleA = account("gd-1", CloudProviderType.GOOGLE_DRIVE, "alpha@gmail.com")
            val googleB = account("gd-2", CloudProviderType.GOOGLE_DRIVE, "beta@gmail.com")
            val registry =
                AuthManagerRegistry(
                    mapOf(
                        CloudProviderType.ONEDRIVE to manager("OneDrive", CloudProviderType.ONEDRIVE, listOf(oneDriveAccount)),
                        CloudProviderType.GOOGLE_DRIVE to
                            manager(
                                "Google Drive",
                                CloudProviderType.GOOGLE_DRIVE,
                                listOf(googleA, googleB),
                            ),
                    ),
                )

            val vm = createVm(registry)
            advanceUntilIdle()

            reauthRows.value =
                listOf(
                    SyncPairDao.AccountReauthRow(
                        provider = CloudProviderType.GOOGLE_DRIVE,
                        accountId = googleB.id,
                    ),
                )
            advanceUntilIdle()

            val oneDriveRow = vm.state.value.rows.first { it.providerKey == CloudProviderType.ONEDRIVE.name }
            val googleRow = vm.state.value.rows.first { it.providerKey == CloudProviderType.GOOGLE_DRIVE.name }
            assertFalse(oneDriveRow.needsReauth)
            assertFalse(oneDriveRow.accounts.single().needsReauth)
            assertTrue(googleRow.needsReauth)
            assertEquals(
                listOf(false, true),
                googleRow.accounts.map { it.needsReauth },
            )
        }

    @Test
    fun `disconnect scopes confirmation and reassignment choices to the selected account`() =
        runTest {
            val target = account("gd-1", CloudProviderType.GOOGLE_DRIVE, "alpha@gmail.com")
            val survivor = account("gd-2", CloudProviderType.GOOGLE_DRIVE, "beta@gmail.com")
            coEvery { accountRepository.getByProvider(CloudProviderType.GOOGLE_DRIVE) } returns listOf(target, survivor)
            coEvery { syncPairRepository.getByAccountId(target.id) } returns
                listOf(
                    SyncPair(
                        id = 42L,
                        displayName = "Photos",
                        localTreeUri = "content://tree/photos",
                        provider = CloudProviderType.GOOGLE_DRIVE,
                        accountId = target.id,
                        remoteFolderId = "remote-photos",
                    ),
                )
            val registry =
                AuthManagerRegistry(
                    mapOf(
                        CloudProviderType.GOOGLE_DRIVE to manager("Google Drive", CloudProviderType.GOOGLE_DRIVE, listOf(target, survivor)),
                    ),
                )

            val vm = createVm(registry)
            advanceUntilIdle()
            vm.disconnect(target)
            advanceUntilIdle()

            val pending = vm.state.value.pendingDisconnect
            assertNotNull(pending)
            assertEquals(target, pending?.account)
            assertEquals(listOf(survivor), pending?.reassignableAccounts)
            assertEquals(listOf(42L), pending?.orphanedPairs?.map { it.id })
        }

    private fun createVm(registry: AuthManagerRegistry) =
        AccountsViewModel(
            context = context,
            registry = registry,
            accountRepository = accountRepository,
            syncPairDao = syncPairDao,
            syncPairRepository = syncPairRepository,
            syncScheduler = syncScheduler,
            userMessages = UserMessageReporter(),
            syncEventRepository = syncEventRepository,
        )

    private fun manager(
        displayName: String,
        providerType: CloudProviderType,
        accounts: List<Account>,
    ): AuthManager =
        mockk {
            every { this@mockk.displayName } returns displayName
            every { this@mockk.providerType } returns providerType
            coEvery { isConfigured() } returns true
            coEvery { currentAccounts() } returns accounts
        }

    @Test
    fun `setHighlight stores the account id and auto-clears after the highlight duration`() =
        runTest {
            val account = account("gd-1", CloudProviderType.GOOGLE_DRIVE, "alpha@gmail.com")
            val registry =
                AuthManagerRegistry(
                    mapOf(
                        CloudProviderType.GOOGLE_DRIVE to manager("Google Drive", CloudProviderType.GOOGLE_DRIVE, listOf(account)),
                    ),
                )
            val vm = createVm(registry)
            advanceUntilIdle()

            vm.setHighlight(account.id)
            // The flag is observable immediately so the UI can animate the highlight.
            assertEquals(account.id, vm.state.value.highlightedAccountId)

            // After HIGHLIGHT_DURATION_MS the timer fires and the flag is cleared.
            dispatcher.scheduler.advanceTimeBy(AccountsViewModel.HIGHLIGHT_DURATION_MS + 50L)
            dispatcher.scheduler.runCurrent()
            assertNull(vm.state.value.highlightedAccountId)
        }

    @Test
    fun `setHighlight with null clears the current highlight immediately`() =
        runTest {
            val account = account("gd-1", CloudProviderType.GOOGLE_DRIVE, "alpha@gmail.com")
            val registry =
                AuthManagerRegistry(
                    mapOf(
                        CloudProviderType.GOOGLE_DRIVE to manager("Google Drive", CloudProviderType.GOOGLE_DRIVE, listOf(account)),
                    ),
                )
            val vm = createVm(registry)
            advanceUntilIdle()

            vm.setHighlight(account.id)
            assertEquals(account.id, vm.state.value.highlightedAccountId)

            vm.setHighlight(null)
            assertNull(vm.state.value.highlightedAccountId)
        }

    @Test
    fun `setHighlight cancels the previous auto-clear timer when called again`() =
        runTest {
            val first = account("gd-1", CloudProviderType.GOOGLE_DRIVE, "alpha@gmail.com")
            val second = account("gd-2", CloudProviderType.GOOGLE_DRIVE, "beta@gmail.com")
            val registry =
                AuthManagerRegistry(
                    mapOf(
                        CloudProviderType.GOOGLE_DRIVE to
                            manager("Google Drive", CloudProviderType.GOOGLE_DRIVE, listOf(first, second)),
                    ),
                )
            val vm = createVm(registry)
            advanceUntilIdle()

            vm.setHighlight(first.id)
            // Advance most of the way through the first highlight window…
            dispatcher.scheduler.advanceTimeBy(AccountsViewModel.HIGHLIGHT_DURATION_MS - 200L)
            // …then start a new highlight; the first timer must NOT clear the new id.
            vm.setHighlight(second.id)
            dispatcher.scheduler.advanceTimeBy(300L)
            dispatcher.scheduler.runCurrent()
            assertEquals(second.id, vm.state.value.highlightedAccountId)

            // The new timer still fires on its own schedule.
            dispatcher.scheduler.advanceTimeBy(AccountsViewModel.HIGHLIGHT_DURATION_MS)
            dispatcher.scheduler.runCurrent()
            assertNull(vm.state.value.highlightedAccountId)
        }

    @Test
    fun `startRename stages PendingRename for the given account`() =
        runTest {
            val account = account("gd-1", CloudProviderType.GOOGLE_DRIVE, "alpha@gmail.com")
            val registry =
                AuthManagerRegistry(
                    mapOf(
                        CloudProviderType.GOOGLE_DRIVE to manager("Google Drive", CloudProviderType.GOOGLE_DRIVE, listOf(account)),
                    ),
                )
            val vm = createVm(registry)
            advanceUntilIdle()

            vm.startRename(account)

            val pending = vm.state.value.pendingRename
            assertNotNull(pending)
            assertEquals(account, pending?.account)
        }

    @Test
    fun `cancelRename clears pendingRename without changes`() =
        runTest {
            val account = account("gd-1", CloudProviderType.GOOGLE_DRIVE, "alpha@gmail.com")
            val registry =
                AuthManagerRegistry(
                    mapOf(
                        CloudProviderType.GOOGLE_DRIVE to manager("Google Drive", CloudProviderType.GOOGLE_DRIVE, listOf(account)),
                    ),
                )
            val vm = createVm(registry)
            advanceUntilIdle()

            vm.startRename(account)
            assertNotNull(vm.state.value.pendingRename)

            vm.cancelRename()
            assertNull(vm.state.value.pendingRename)
        }

    @Test
    fun `confirmRename calls repository rename with trimmed name`() =
        runTest {
            val account = account("gd-1", CloudProviderType.GOOGLE_DRIVE, "alpha@gmail.com")
            val registry =
                AuthManagerRegistry(
                    mapOf(
                        CloudProviderType.GOOGLE_DRIVE to manager("Google Drive", CloudProviderType.GOOGLE_DRIVE, listOf(account)),
                    ),
                )
            val vm = createVm(registry)
            advanceUntilIdle()

            vm.startRename(account)
            vm.confirmRename("  New Name  ")
            advanceUntilIdle()

            io.mockk.coVerify { accountRepository.rename(account.id, "New Name") }
            assertNull(vm.state.value.pendingRename)
        }

    @Test
    fun `confirmRename is a no-op when name is blank`() =
        runTest {
            val account = account("gd-1", CloudProviderType.GOOGLE_DRIVE, "alpha@gmail.com")
            val registry =
                AuthManagerRegistry(
                    mapOf(
                        CloudProviderType.GOOGLE_DRIVE to manager("Google Drive", CloudProviderType.GOOGLE_DRIVE, listOf(account)),
                    ),
                )
            val vm = createVm(registry)
            advanceUntilIdle()

            vm.startRename(account)
            vm.confirmRename("   ")
            advanceUntilIdle()

            io.mockk.coVerify(exactly = 0) { accountRepository.rename(any(), any()) }
        }

    private fun account(
        id: String,
        provider: CloudProviderType,
        email: String,
    ) = Account(
        id = id,
        provider = provider,
        displayName = email.substringBefore('@'),
        email = email,
    )
}
