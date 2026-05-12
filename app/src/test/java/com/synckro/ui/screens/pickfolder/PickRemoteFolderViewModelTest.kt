package com.synckro.ui.screens.pickfolder

import androidx.lifecycle.SavedStateHandle
import com.synckro.domain.auth.AuthManagerRegistry
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.provider.CloudProvider
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.provider.RemoteFile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class PickRemoteFolderViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockProvider: CloudProvider
    private lateinit var authRegistry: AuthManagerRegistry

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockProvider = mockk(relaxed = true)
        authRegistry = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm(
        provider: CloudProviderType = CloudProviderType.FAKE,
        providers: Map<CloudProviderType, CloudProvider> = mapOf(CloudProviderType.FAKE to mockProvider),
        accountId: String? = if (provider == CloudProviderType.FAKE) null else "test-account",
    ): PickRemoteFolderViewModel =
        PickRemoteFolderViewModel(
            savedStateHandle =
                SavedStateHandle(
                    buildMap {
                        put(PickRemoteFolderViewModel.ARG_PROVIDER, provider.name)
                        if (accountId != null) {
                            put(PickRemoteFolderViewModel.ARG_ACCOUNT_ID, accountId)
                        }
                    },
                ),
            providerFactories = providers.toFactoryMap(),
            authRegistry = authRegistry,
        )

    private fun Map<CloudProviderType, CloudProvider>.toFactoryMap(): Map<CloudProviderType, CloudProviderFactory> =
        mapValues { (_, provider) ->
            object : CloudProviderFactory {
                override fun providerFor(accountId: String): CloudProvider = provider
            }
        }

    private fun folder(id: String, name: String, parentId: String? = null): RemoteFile =
        RemoteFile(
            id = id,
            name = name,
            parentId = parentId,
            isFolder = true,
            size = null,
            lastModifiedMs = null,
            eTag = null,
            mimeType = null,
        )

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    fun `initial state starts at root with one breadcrumb`() =
        runTest {
            coEvery { mockProvider.list(null) } returns emptyList()

            val vm = createVm()
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(1, state.breadcrumbs.size)
            assertEquals("/", state.breadcrumbs.first().folderName)
            assertNull(state.breadcrumbs.first().folderId)
            assertNull(state.currentFolderId)
            assertFalse(state.isLoading)
        }

    @Test
    fun `initial load lists root folders sorted by name`() =
        runTest {
            val folders =
                listOf(
                    folder("b", "Beta"),
                    folder("a", "Alpha"),
                    folder("c", "Gamma"),
                )
            coEvery { mockProvider.list(null) } returns folders

            val vm = createVm()
            advanceUntilIdle()

            val names = vm.state.value.items.map { it.name }
            assertEquals(listOf("Alpha", "Beta", "Gamma"), names)
        }

    @Test
    fun `files are filtered out — only folders shown`() =
        runTest {
            val items =
                listOf(
                    folder("f1", "FolderA"),
                    RemoteFile("file1", "doc.txt", null, isFolder = false, null, null, null, null),
                    folder("f2", "FolderB"),
                )
            coEvery { mockProvider.list(null) } returns items

            val vm = createVm()
            advanceUntilIdle()

            assertEquals(2, vm.state.value.items.size)
            assertTrue(vm.state.value.items.all { it.isFolder })
        }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    @Test
    fun `navigateInto pushes breadcrumb and loads child folders`() =
        runTest {
            val childFolder = folder("child1", "Documents")
            coEvery { mockProvider.list(null) } returns listOf(childFolder)
            coEvery { mockProvider.list("child1") } returns emptyList()

            val vm = createVm()
            advanceUntilIdle()

            vm.navigateInto(childFolder)
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(2, state.breadcrumbs.size)
            assertEquals("Documents", state.breadcrumbs.last().folderName)
            assertEquals("child1", state.breadcrumbs.last().folderId)
            assertEquals("child1", state.currentFolderId)
            coVerify { mockProvider.list("child1") }
        }

    @Test
    fun `navigateUp pops breadcrumb and reloads parent folder`() =
        runTest {
            val childFolder = folder("child1", "Documents")
            coEvery { mockProvider.list(null) } returns listOf(childFolder)
            coEvery { mockProvider.list("child1") } returns emptyList()

            val vm = createVm()
            advanceUntilIdle()

            vm.navigateInto(childFolder)
            advanceUntilIdle()

            val didNavigate = vm.navigateUp()
            advanceUntilIdle()

            assertTrue(didNavigate)
            val state = vm.state.value
            assertEquals(1, state.breadcrumbs.size)
            assertNull(state.currentFolderId)
        }

    @Test
    fun `navigateUp at root returns false and does nothing`() =
        runTest {
            coEvery { mockProvider.list(null) } returns emptyList()

            val vm = createVm()
            advanceUntilIdle()

            val didNavigate = vm.navigateUp()
            advanceUntilIdle()

            assertFalse(didNavigate)
            assertEquals(1, vm.state.value.breadcrumbs.size)
        }

    @Test
    fun `navigateToBreadcrumb trims deeper entries and loads target folder`() =
        runTest {
            val folderA = folder("a", "A")
            val folderB = folder("b", "B", parentId = "a")
            coEvery { mockProvider.list(null) } returns listOf(folderA)
            coEvery { mockProvider.list("a") } returns listOf(folderB)
            coEvery { mockProvider.list("b") } returns emptyList()

            val vm = createVm()
            advanceUntilIdle()

            vm.navigateInto(folderA)
            advanceUntilIdle()
            vm.navigateInto(folderB)
            advanceUntilIdle()

            assertEquals(3, vm.state.value.breadcrumbs.size)

            // Navigate back to the root (index 0).
            vm.navigateToBreadcrumb(0)
            advanceUntilIdle()

            assertEquals(1, vm.state.value.breadcrumbs.size)
            assertNull(vm.state.value.currentFolderId)
        }

    @Test
    fun `navigateToBreadcrumb at last index does nothing`() =
        runTest {
            coEvery { mockProvider.list(null) } returns emptyList()

            val vm = createVm()
            advanceUntilIdle()

            vm.navigateToBreadcrumb(0) // already at last index (root)
            advanceUntilIdle()

            // State must remain unchanged — list() must only have been called once (on init).
            assertEquals(1, vm.state.value.breadcrumbs.size)
            coVerify(exactly = 1) { mockProvider.list(null) }
        }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    fun `load failure sets error state`() =
        runTest {
            coEvery { mockProvider.list(null) } throws RuntimeException("Network error")

            val vm = createVm()
            advanceUntilIdle()

            val state = vm.state.value
            assertNotNull(state.error)
            assertTrue(state.error!!.contains("Network error"))
            assertFalse(state.isLoading)
        }

    @Test
    fun `retry clears error and reloads current folder`() =
        runTest {
            coEvery { mockProvider.list(null) } throws RuntimeException("Network error") andThen emptyList()

            val vm = createVm()
            advanceUntilIdle()

            assertNotNull(vm.state.value.error)

            vm.retry()
            advanceUntilIdle()

            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.isLoading)
        }

    @Test
    fun `missing provider sets error state`() =
        runTest {
            val vm = createVm(provider = CloudProviderType.FAKE, providers = emptyMap())
            advanceUntilIdle()

            val state = vm.state.value
            assertNotNull(state.error)
            assertFalse(state.isLoading)
        }

    // -------------------------------------------------------------------------
    // Auth-required → reauthEvent + signInAndRetry
    // -------------------------------------------------------------------------

    @Test
    fun `AuthenticationRequired emits reauthEvent and sets isReauthenticating`() =
        runTest {
            val authError = com.synckro.domain.provider.CloudProviderException.AuthenticationRequired(
                "Token expired",
            )
            coEvery { mockProvider.list(null) } throws authError

            val vm = createVm()
            advanceUntilIdle()

            // The ViewModel should have set isReauthenticating = true (keeping isLoading = true)
            // and must NOT have set an error string — the screen handles the reauth flow.
            assertTrue(vm.state.value.isReauthenticating)
            assertTrue(vm.state.value.isLoading)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `signInAndRetry on success clears reauth state and reloads folders`() =
        runTest {
            val fakeAuthManager = com.synckro.domain.auth.FakeAuthManager(CloudProviderType.FAKE)
            val registry = mockk<com.synckro.domain.auth.AuthManagerRegistry>()
            coEvery { registry.find(CloudProviderType.FAKE) } returns fakeAuthManager

            val authError = com.synckro.domain.provider.CloudProviderException.AuthenticationRequired(
                "Token expired",
            )
            val childFolder = folder("f1", "Docs")
            // First call throws; second succeeds after re-auth.
            coEvery { mockProvider.list(null) } throws authError andThen listOf(childFolder)

            val vm =
                PickRemoteFolderViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(PickRemoteFolderViewModel.ARG_PROVIDER to CloudProviderType.FAKE.name),
                        ),
                    providerFactories =
                        mapOf(CloudProviderType.FAKE to mockProvider).toFactoryMap(),
                    authRegistry = registry,
                )

            advanceUntilIdle() // triggers auth error → reauthEvent

            // Simulate the screen calling signInAndRetry with a stub sign-in lambda.
            vm.signInAndRetry { manager ->
                manager.signIn(object : com.synckro.domain.auth.AuthUiHost {})
            }
            advanceUntilIdle()

            val state = vm.state.value
            assertFalse(state.isReauthenticating)
            assertFalse(state.isLoading)
            assertNull(state.error)
            assertEquals(1, state.items.size)
            assertEquals("Docs", state.items.first().name)
        }

    @Test
    fun `signInAndRetry on cancel shows error and clears loading`() =
        runTest {
            val fakeAuthManager = com.synckro.domain.auth.FakeAuthManager(CloudProviderType.FAKE)
            fakeAuthManager.nextSignInResult = com.synckro.domain.auth.AuthResult.Cancelled
            val registry = mockk<com.synckro.domain.auth.AuthManagerRegistry>()
            coEvery { registry.find(CloudProviderType.FAKE) } returns fakeAuthManager

            coEvery { mockProvider.list(null) } throws
                com.synckro.domain.provider.CloudProviderException.AuthenticationRequired("Token expired")

            val vm =
                PickRemoteFolderViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(PickRemoteFolderViewModel.ARG_PROVIDER to CloudProviderType.FAKE.name),
                        ),
                    providerFactories =
                        mapOf(CloudProviderType.FAKE to mockProvider).toFactoryMap(),
                    authRegistry = registry,
                )

            advanceUntilIdle()

            vm.signInAndRetry { manager ->
                manager.signIn(object : com.synckro.domain.auth.AuthUiHost {})
            }
            advanceUntilIdle()

            val state = vm.state.value
            assertFalse(state.isReauthenticating)
            assertFalse(state.isLoading)
            assertNotNull(state.error)
            assertTrue(state.error!!.contains("cancelled", ignoreCase = true))
        }

    @Test
    fun `Google Drive empty root shows empty items with null currentFolderId`() =
        runTest {
            val mockGDrive = mockk<CloudProvider>(relaxed = true)
            coEvery { mockGDrive.list(null) } returns emptyList()

            val vm =
                PickRemoteFolderViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                PickRemoteFolderViewModel.ARG_PROVIDER to CloudProviderType.GOOGLE_DRIVE.name,
                                PickRemoteFolderViewModel.ARG_ACCOUNT_ID to "test-account",
                            ),
                        ),
                    providerFactories = mapOf(CloudProviderType.GOOGLE_DRIVE to mockGDrive).toFactoryMap(),
                    authRegistry = authRegistry,
                )
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state.items.isEmpty())
            assertNull(state.error)
            assertNull(state.currentFolderId) // at root — UI shows root-level empty message
            assertFalse(state.isLoading)
        }

    @Test
    fun `Google Drive empty subfolder shows empty items with non-null currentFolderId`() =
        runTest {
            val mockGDrive = mockk<CloudProvider>(relaxed = true)
            val subFolder = RemoteFile("sub1", "SubFolder", null, isFolder = true, null, null, null, null)
            coEvery { mockGDrive.list(null) } returns listOf(subFolder)
            coEvery { mockGDrive.list("sub1") } returns emptyList()

            val vm =
                PickRemoteFolderViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                PickRemoteFolderViewModel.ARG_PROVIDER to CloudProviderType.GOOGLE_DRIVE.name,
                                PickRemoteFolderViewModel.ARG_ACCOUNT_ID to "test-account",
                            ),
                        ),
                    providerFactories = mapOf(CloudProviderType.GOOGLE_DRIVE to mockGDrive).toFactoryMap(),
                    authRegistry = authRegistry,
                )
            advanceUntilIdle()

            vm.navigateInto(subFolder)
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state.items.isEmpty())
            assertNull(state.error)
            assertEquals("sub1", state.currentFolderId) // inside subfolder — UI shows subfolder empty message
            assertFalse(state.isLoading)
        }

    // -------------------------------------------------------------------------
    // Provider type resolution
    // -------------------------------------------------------------------------

    @Test
    fun `provider type is resolved from saved state handle`() =
        runTest {
            coEvery { mockProvider.list(null) } returns emptyList()

            val vm = createVm(provider = CloudProviderType.FAKE)
            advanceUntilIdle()

            assertEquals(CloudProviderType.FAKE, vm.providerType)
        }

    @Test
    fun `unknown provider string falls back to GOOGLE_DRIVE`() =
        runTest {
            val mockGDrive = mockk<CloudProvider>(relaxed = true)
            coEvery { mockGDrive.list(null) } returns emptyList()

            val vm =
                PickRemoteFolderViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                PickRemoteFolderViewModel.ARG_PROVIDER to "NOT_A_PROVIDER",
                                PickRemoteFolderViewModel.ARG_ACCOUNT_ID to "test-account",
                            ),
                        ),
                    providerFactories = mapOf(CloudProviderType.GOOGLE_DRIVE to mockGDrive).toFactoryMap(),
                    authRegistry = authRegistry,
                )
            advanceUntilIdle()

            assertEquals(CloudProviderType.GOOGLE_DRIVE, vm.providerType)
        }
}
