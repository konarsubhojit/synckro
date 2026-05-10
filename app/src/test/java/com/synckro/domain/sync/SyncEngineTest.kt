package com.synckro.domain.sync

import com.synckro.data.repository.ConflictRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.ConflictRecord
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import com.synckro.domain.provider.CloudProvider
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.providers.fake.FakeCloudProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {
    private val mockConflictRepo: ConflictRepository = mockk(relaxed = true)
    private val fakeProvider = FakeCloudProvider()
    private val providers: Map<CloudProviderType, CloudProviderFactory> =
        mapOf(
            CloudProviderType.FAKE to singleProviderFactory(fakeProvider),
        )
    private val engine = SyncEngine(mockConflictRepo, providers)

    private fun pair(
        provider: CloudProviderType,
        conflictPolicy: ConflictPolicy = ConflictPolicy.NEWEST_WINS,
    ) = SyncPair(
        id = 1L,
        displayName = "Test",
        localTreeUri = "content://test",
        provider = provider,
        remoteFolderId = "root",
        direction = SyncDirection.BIDIRECTIONAL,
        conflictPolicy = conflictPolicy,
    )

    @Test
    fun `FAKE provider returns Success`() =
        runTest {
            coEvery { mockConflictRepo.getResolvedForPair(any()) } returns emptyList()
            val result = engine.runOnce(pair(CloudProviderType.FAKE))
            assertTrue("Expected Success for FAKE provider", result is SyncEngine.Result.Success)
        }

    @Test
    fun `FAKE provider success has zero applied and conflicts`() =
        runTest {
            coEvery { mockConflictRepo.getResolvedForPair(any()) } returns emptyList()
            val result = engine.runOnce(pair(CloudProviderType.FAKE)) as SyncEngine.Result.Success
            assertEquals(0, result.applied)
            assertEquals(0, result.conflicts)
        }

    @Test
    fun `FAKE provider with KEEP_BOTH policy writes ConflictRecord for forced conflicts`() =
        runTest {
            coEvery { mockConflictRepo.getResolvedForPair(any()) } returns emptyList()
            coEvery { mockConflictRepo.insert(any()) } returns 1L

            fakeProvider.forceConflict("docs/readme.txt", localLastModifiedMs = 2_000L, remoteLastModifiedMs = 1_000L)

            val result = engine.runOnce(pair(CloudProviderType.FAKE, ConflictPolicy.KEEP_BOTH))

            assertTrue(result is SyncEngine.Result.Success)
            assertEquals(1, (result as SyncEngine.Result.Success).conflicts)
        }

    @Test
    fun `FAKE provider with KEEP_BOTH uses actual timestamps not synthesized values`() =
        runTest {
            val inserted = mutableListOf<ConflictRecord>()
            coEvery { mockConflictRepo.getResolvedForPair(any()) } returns emptyList()
            coEvery { mockConflictRepo.insert(any()) } coAnswers {
                inserted += firstArg<ConflictRecord>()
                inserted.size.toLong()
            }

            fakeProvider.forceConflict("docs/readme.txt", localLastModifiedMs = 2_000L, remoteLastModifiedMs = 1_000L)
            engine.runOnce(pair(CloudProviderType.FAKE, ConflictPolicy.KEEP_BOTH))

            assertEquals(1, inserted.size)
            assertEquals(2_000L, inserted[0].localLastModifiedMs)
            assertEquals(1_000L, inserted[0].remoteLastModifiedMs)
        }

    @Test
    fun `FAKE provider with PREFER_LOCAL does not write ConflictRecord for forced conflicts`() =
        runTest {
            coEvery { mockConflictRepo.getResolvedForPair(any()) } returns emptyList()

            fakeProvider.forceConflict("docs/readme.txt", localLastModifiedMs = 2_000L, remoteLastModifiedMs = 1_000L)

            val result = engine.runOnce(pair(CloudProviderType.FAKE, ConflictPolicy.PREFER_LOCAL))

            assertTrue(result is SyncEngine.Result.Success)
            assertEquals(0, (result as SyncEngine.Result.Success).conflicts)
        }

    @Test
    fun `FAKE provider applies pending resolutions on next run`() =
        runTest {
            coEvery { mockConflictRepo.getResolvedForPair(any()) } returns
                listOf(
                    ConflictRecord(
                        id = 99L,
                        pairId = 1L,
                        relativePath = "docs/readme.txt",
                        localLastModifiedMs = 2_000L,
                        remoteLastModifiedMs = 1_000L,
                        detectedAtMs = 3_000L,
                        resolution = ConflictRecord.RESOLUTION_KEEP_LOCAL,
                    ),
                )

            val result = engine.runOnce(pair(CloudProviderType.FAKE))

            assertTrue(result is SyncEngine.Result.Success)
            assertEquals(1, (result as SyncEngine.Result.Success).applied)
        }

    @Test
    fun `ONEDRIVE provider returns Terminal (not yet implemented)`() =
        runTest {
            val result = engine.runOnce(pair(CloudProviderType.ONEDRIVE))
            assertTrue("Expected Terminal for ONEDRIVE provider", result is SyncEngine.Result.Terminal)
        }

    @Test
    fun `GOOGLE_DRIVE provider returns Terminal (not yet implemented)`() =
        runTest {
            val result = engine.runOnce(pair(CloudProviderType.GOOGLE_DRIVE))
            assertTrue(
                "Expected Terminal for GOOGLE_DRIVE provider",
                result is SyncEngine.Result.Terminal,
            )
        }

    @Test
    fun `provider not in map returns Terminal with unsupported message`() =
        runTest {
            // Engine with only the FAKE provider registered — ONEDRIVE is absent.
            val partialProviders: Map<CloudProviderType, CloudProviderFactory> =
                mapOf(
                    CloudProviderType.FAKE to singleProviderFactory(fakeProvider),
                )
            val engineWithPartialMap = SyncEngine(mockConflictRepo, partialProviders)
            val result = engineWithPartialMap.runOnce(pair(CloudProviderType.ONEDRIVE))
            assertTrue("Expected Terminal when provider missing from map", result is SyncEngine.Result.Terminal)
        }

    @Test
    fun `provider map wiring resolves correct provider per CloudProviderType`() =
        runTest {
            coEvery { mockConflictRepo.getResolvedForPair(any()) } returns emptyList()

            val localFake = FakeCloudProvider()
            val mockOneDrive = mockk<com.synckro.domain.provider.CloudProvider>(relaxed = true)
            val mockGoogleDrive = mockk<com.synckro.domain.provider.CloudProvider>(relaxed = true)

            val multiEngine =
                SyncEngine(
                    mockConflictRepo,
                    mapOf(
                        CloudProviderType.FAKE to singleProviderFactory(localFake),
                        CloudProviderType.ONEDRIVE to singleProviderFactory(mockOneDrive),
                        CloudProviderType.GOOGLE_DRIVE to singleProviderFactory(mockGoogleDrive),
                    ),
                )

            // FAKE resolves to Success (real in-memory run)
            val fakeResult = multiEngine.runOnce(pair(CloudProviderType.FAKE))
            assertTrue("FAKE provider resolves to Success", fakeResult is SyncEngine.Result.Success)

            // ONEDRIVE resolves to Terminal (not yet implemented)
            val oneDriveResult = multiEngine.runOnce(pair(CloudProviderType.ONEDRIVE))
            assertTrue("ONEDRIVE resolves to Terminal", oneDriveResult is SyncEngine.Result.Terminal)

            // GOOGLE_DRIVE resolves to Terminal (not yet implemented)
            val googleDriveResult = multiEngine.runOnce(pair(CloudProviderType.GOOGLE_DRIVE))
            assertTrue("GOOGLE_DRIVE resolves to Terminal", googleDriveResult is SyncEngine.Result.Terminal)
        }

    @Test
    fun `parallel runOnce for same provider with different account ids uses isolated fake providers`() =
        runTest {
            coEvery { mockConflictRepo.getResolvedForPair(any()) } returns emptyList()
            coEvery { mockConflictRepo.insert(any()) } returns 1L

            val fakeByAccount = linkedMapOf<String, FakeCloudProvider>()
            val fakeFactory =
                object : CloudProviderFactory {
                    override fun providerFor(accountId: String): CloudProvider =
                        fakeByAccount.getOrPut(accountId) { FakeCloudProvider() }
                }

            val multiAccountEngine =
                SyncEngine(
                    conflictRepository = mockConflictRepo,
                    providers = mapOf(CloudProviderType.FAKE to fakeFactory),
                )

            val pairA = pair(CloudProviderType.FAKE).copy(id = 101L, accountId = "acct-A")
            val pairB = pair(CloudProviderType.FAKE).copy(id = 102L, accountId = "acct-B")

            (fakeFactory.providerFor("acct-A") as FakeCloudProvider).forceConflict(
                "docs/conflict-A.txt",
                localLastModifiedMs = 2_000L,
                remoteLastModifiedMs = 1_000L,
            )

            val (resultA, resultB) =
                coroutineScope {
                    val a = async { multiAccountEngine.runOnce(pairA.copy(conflictPolicy = ConflictPolicy.KEEP_BOTH)) }
                    val b = async { multiAccountEngine.runOnce(pairB.copy(conflictPolicy = ConflictPolicy.KEEP_BOTH)) }
                    Pair(a.await(), b.await())
                }

            assertTrue(resultA is SyncEngine.Result.Success)
            assertTrue(resultB is SyncEngine.Result.Success)
            assertEquals(1, (resultA as SyncEngine.Result.Success).conflicts)
            assertEquals(0, (resultB as SyncEngine.Result.Success).conflicts)
            assertNotSame(fakeByAccount["acct-A"], fakeByAccount["acct-B"])
        }

    private fun singleProviderFactory(provider: CloudProvider): CloudProviderFactory =
        object : CloudProviderFactory {
            override fun providerFor(accountId: String) = provider
        }

    // =========================================================================
    // conflictCopyPath
    // =========================================================================

    @Test
    fun `conflictCopyPath appends conflict date suffix before extension`() {
        // 1_700_000_000_000L = 2023-11-14T22:13:20 UTC → date label "2023-11-14"
        val result = SyncEngine.conflictCopyPath("document.txt", 1_700_000_000_000L)
        assertEquals("document (conflict 2023-11-14).txt", result)
    }

    @Test
    fun `conflictCopyPath preserves directory prefix`() {
        val result = SyncEngine.conflictCopyPath("photos/vacation.jpg", 1_700_000_000_000L)
        assertEquals("photos/vacation (conflict 2023-11-14).jpg", result)
    }

    @Test
    fun `conflictCopyPath works for files with no extension`() {
        val result = SyncEngine.conflictCopyPath("README", 1_700_000_000_000L)
        assertEquals("README (conflict 2023-11-14)", result)
    }

    @Test
    fun `conflictCopyPath treats leading-dot hidden files as extension-free`() {
        // ".gitignore" has a leading dot but no real extension — conflict copy should be
        // ".gitignore (conflict 2023-11-14)", not " (conflict 2023-11-14).gitignore".
        val result = SyncEngine.conflictCopyPath(".gitignore", 1_700_000_000_000L)
        assertEquals(".gitignore (conflict 2023-11-14)", result)
    }

    @Test
    fun `conflictCopyPath handles hidden files with real extension`() {
        // ".hidden.txt" has a leading dot AND a real extension.
        val result = SyncEngine.conflictCopyPath(".hidden.txt", 1_700_000_000_000L)
        assertEquals(".hidden (conflict 2023-11-14).txt", result)
    }

    @Test
    fun `conflictCopyPath works for nested path with no extension`() {
        val result = SyncEngine.conflictCopyPath("docs/notes", 1_700_000_000_000L)
        assertEquals("docs/notes (conflict 2023-11-14)", result)
    }
}
