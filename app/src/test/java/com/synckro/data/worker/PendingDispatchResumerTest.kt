package com.synckro.data.worker

import android.content.ContentResolver
import android.content.Context
import com.synckro.data.local.dao.PendingUploadDao
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import com.synckro.domain.sync.InstantSyncEligibilityPolicy
import com.synckro.domain.sync.PairSignalCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingDispatchResumerTest {
    private val dispatcher = StandardTestDispatcher()
    private val contentResolver = mockk<ContentResolver>()
    private val context =
        mockk<Context> {
            every { getContentResolver() } returns this@PendingDispatchResumerTest.contentResolver
        }
    private val pendingUploadDao = mockk<PendingUploadDao>(relaxed = true)
    private val syncPairRepository = mockk<SyncPairRepository>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val syncScheduler = mockk<SyncScheduler>(relaxed = true)

    @Test
    fun `eligible pair is dispatched after the debounce window`() =
        runTest(dispatcher) {
            val pair = pair()
            givenQueue(pairIds = listOf(pair.id), pairs = listOf(pair))
            givenFlags()
            val resumer = resumer(CoroutineScope(this.coroutineContext))

            resumer.resume(nowMs = NOW_MS)
            runCurrent()
            verify(exactly = 0) { syncScheduler.enqueueInstant(any()) }

            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()
            verify(exactly = 1) { syncScheduler.enqueueInstant(pair) }
        }

    @Test
    fun `stale claims are recovered before eligible pairs are queried`() =
        runTest(dispatcher) {
            val pair = pair()
            givenQueue(pairIds = listOf(pair.id), pairs = listOf(pair))
            givenFlags()

            resumer(CoroutineScope(this.coroutineContext)).resume(nowMs = NOW_MS)
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            coVerify(exactly = 1) {
                pendingUploadDao.recoverStaleClaims(
                    staleBeforeMs = NOW_MS - SyncWorker.INSTANT_CLAIM_TIMEOUT_MS,
                    recoveredAtMs = NOW_MS,
                )
            }
        }

    @Test
    fun `ineligible pairs stay queued and undispatched`() =
        runTest(dispatcher) {
            val instantDisabled = pair(id = 1L, instantSyncEnabled = false)
            val needsRelink = pair(id = 2L, needsReLink = true)
            val notLinked = pair(id = 3L, accountId = null)
            val downloadOnly = pair(id = 4L, direction = SyncDirection.REMOTE_TO_LOCAL)
            val needsReauth = pair(id = 5L, lastSyncResult = SyncWorker.RESULT_NEEDS_REAUTH)
            val pairs = listOf(instantDisabled, needsRelink, notLinked, downloadOnly, needsReauth)
            givenQueue(pairIds = pairs.map(SyncPair::id) + 99L, pairs = pairs)
            givenFlags()

            resumer(CoroutineScope(this.coroutineContext)).resume(nowMs = NOW_MS)
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            verify(exactly = 0) { syncScheduler.enqueueInstant(any()) }
        }

    @Test
    fun `global instant sync opt-out blocks dispatch`() =
        runTest(dispatcher) {
            val pair = pair()
            givenQueue(pairIds = listOf(pair.id), pairs = listOf(pair))
            givenFlags(globalInstantSyncEnabled = false)

            resumer(CoroutineScope(this.coroutineContext)).resume(nowMs = NOW_MS)
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            verify(exactly = 0) { syncScheduler.enqueueInstant(any()) }
        }

    @Test
    fun `repeated resume calls dispatch the pair only once`() =
        runTest(dispatcher) {
            val pair = pair()
            givenQueue(pairIds = listOf(pair.id), pairs = listOf(pair))
            givenFlags()
            val resumer = resumer(CoroutineScope(this.coroutineContext))

            resumer.resume(nowMs = NOW_MS)
            resumer.resume(nowMs = NOW_MS)
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            verify(exactly = 1) { syncScheduler.enqueueInstant(pair) }
            coVerify(exactly = 1) { pendingUploadDao.pairIdsWithEligibleRows(NOW_MS) }
        }

    @Test
    fun `a failed resume can be retried`() =
        runTest(dispatcher) {
            val pair = pair()
            givenQueue(pairIds = listOf(pair.id), pairs = listOf(pair))
            givenFlags()
            coEvery {
                pendingUploadDao.recoverStaleClaims(any(), any(), any(), any())
            } throws IllegalStateException("database unavailable") andThen 0
            val resumer = resumer(CoroutineScope(this.coroutineContext))

            assertThrows(IllegalStateException::class.java) {
                runBlocking { resumer.resume(nowMs = NOW_MS) }
            }
            resumer.resume(nowMs = NOW_MS)
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            verify(exactly = 1) { syncScheduler.enqueueInstant(pair) }
        }

    private fun resumer(scope: CoroutineScope) =
        PendingDispatchResumer(
            context = context,
            pendingUploadDao = pendingUploadDao,
            syncPairRepository = syncPairRepository,
            settingsRepository = settingsRepository,
            eligibilityPolicy = InstantSyncEligibilityPolicy(),
            pairSignalCoordinator = PairSignalCoordinator(scope),
            syncScheduler = syncScheduler,
        )

    private fun givenQueue(
        pairIds: List<Long>,
        pairs: List<SyncPair>,
    ) {
        coEvery { pendingUploadDao.pairIdsWithEligibleRows(eq(NOW_MS), any()) } returns pairIds
        coEvery { syncPairRepository.getAll(contentResolver) } returns pairs
    }

    private fun givenFlags(
        globalAutoSyncEnabled: Boolean = true,
        globalInstantSyncEnabled: Boolean = true,
    ) {
        every { settingsRepository.globalAutoSyncEnabled } returns flowOf(globalAutoSyncEnabled)
        every { settingsRepository.globalInstantSyncEnabled } returns flowOf(globalInstantSyncEnabled)
    }

    private fun pair(
        id: Long = 7L,
        accountId: String? = "account-1",
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        instantSyncEnabled: Boolean = true,
        needsReLink: Boolean = false,
        lastSyncResult: String? = null,
    ) = SyncPair(
        id = id,
        displayName = "Pair $id",
        localTreeUri = "content://tree/$id",
        provider = CloudProviderType.FAKE,
        accountId = accountId,
        remoteFolderId = "remote-$id",
        direction = direction,
        instantSyncEnabled = instantSyncEnabled,
        needsReLink = needsReLink,
        lastSyncResult = lastSyncResult,
    )

    private companion object {
        const val NOW_MS = 10_000_000L
    }
}
