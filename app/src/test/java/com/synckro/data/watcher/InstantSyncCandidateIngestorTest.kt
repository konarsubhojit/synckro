package com.synckro.data.watcher

import android.content.ContentResolver
import com.synckro.data.repository.PendingUploadRepository
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import com.synckro.domain.sync.FileStabilityDeferralReason
import com.synckro.domain.sync.FileStabilityDetector
import com.synckro.domain.sync.FileStabilityResult
import com.synckro.domain.sync.InstantSyncEligibilityPolicy
import com.synckro.domain.sync.LocalChangeEvent
import com.synckro.domain.sync.MediaStorePendingState
import com.synckro.domain.sync.PairSignalCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstantSyncCandidateIngestorTest {
    private val dispatcher = StandardTestDispatcher()
    private val contentResolver = mockk<ContentResolver>()
    private val syncPairRepository = mockk<SyncPairRepository>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val pendingUploadRepository = mockk<PendingUploadRepository>(relaxed = true)
    private val syncScheduler = mockk<SyncScheduler>(relaxed = true)
    private val pathResolver = mockk<InstantSyncChangedPathResolver>()
    private val candidateSampler = mockk<SafInstantSyncCandidateSampler>()
    private val stabilityDetector = mockk<FileStabilityDetector<InstantSyncCandidateTarget>>()

    @Test
    fun `ineligible pair is skipped without queueing`() =
        runTest(dispatcher) {
            val pair = pair(instantSyncEnabled = false)
            every { settingsRepository.globalAutoSyncEnabled } returns flowOf(true)
            every { settingsRepository.globalInstantSyncEnabled } returns flowOf(true)
            coEvery { syncPairRepository.getById(pair.id, contentResolver) } returns pair

            ingestor(CoroutineScope(this.coroutineContext)).onLocalChange(LocalChangeEvent.Changed(pair.id, "notes.txt"))

            verify(exactly = 0) { pathResolver.resolve(any(), any()) }
            verify(exactly = 0) { candidateSampler.sample(any()) }
            coVerify(exactly = 0) { pendingUploadRepository.upsertCandidate(any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { syncScheduler.enqueueInstant(any()) }
        }

    @Test
    fun `temporary candidate is skipped before queueing`() =
        runTest(dispatcher) {
            val pair = pair()
            val target = target("scratch.tmp")
            givenEligiblePair(pair)
            every { pathResolver.resolve(pair, any()) } returns listOf(target)

            ingestor(CoroutineScope(this.coroutineContext)).onLocalChange(LocalChangeEvent.Changed(pair.id, "scratch.tmp"))

            verify(exactly = 0) { candidateSampler.sample(any()) }
            coVerify(exactly = 0) { stabilityDetector.awaitStable(any()) }
            coVerify(exactly = 0) { pendingUploadRepository.upsertCandidate(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `stable eligible candidate is queued and dispatched after debounce`() =
        runTest(dispatcher) {
            val pair = pair()
            val target = target("notes.txt", documentIdHint = "doc-1")
            val sample = availableSample(documentId = "doc-1")
            givenEligiblePair(pair)
            every { pathResolver.resolve(pair, any()) } returns listOf(target)
            every { candidateSampler.sample(target) } returns sample
            coEvery { stabilityDetector.awaitStable(target) } returns FileStabilityResult.Stable
            val ingestor = ingestor(CoroutineScope(this.coroutineContext))

            ingestor.onLocalChange(LocalChangeEvent.Changed(pair.id, "notes.txt"))
            runCurrent()

            coVerify(exactly = 1) {
                pendingUploadRepository.upsertCandidate(
                    pairId = pair.id,
                    relativePath = "notes.txt",
                    documentIdHint = "doc-1",
                    observedSizeBytes = sample.sizeBytes,
                    observedMtimeMs = sample.mtimeMs,
                    eligibleAtMs = any(),
                    observedAtMs = any(),
                )
            }
            verify(exactly = 0) { syncScheduler.enqueueInstant(any()) }

            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            verify(exactly = 1) { syncScheduler.enqueueInstant(pair) }
        }

    @Test
    fun `unstable candidate is not queued`() =
        runTest(dispatcher) {
            val pair = pair()
            val target = target("notes.txt")
            givenEligiblePair(pair)
            every { pathResolver.resolve(pair, any()) } returns listOf(target)
            every { candidateSampler.sample(target) } returns availableSample()
            coEvery {
                stabilityDetector.awaitStable(target)
            } returns FileStabilityResult.Deferred(FileStabilityDeferralReason.CHANGED_DURING_QUIET_PERIOD)

            ingestor(CoroutineScope(this.coroutineContext)).onLocalChange(LocalChangeEvent.Changed(pair.id, "notes.txt"))
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            coVerify(exactly = 0) { pendingUploadRepository.upsertCandidate(any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { syncScheduler.enqueueInstant(any()) }
        }

    private fun ingestor(scope: CoroutineScope) =
        InstantSyncCandidateIngestor(
            contentResolver = contentResolver,
            syncPairRepository = syncPairRepository,
            settingsRepository = settingsRepository,
            eligibilityPolicy = InstantSyncEligibilityPolicy(),
            pendingUploadRepository = pendingUploadRepository,
            pairSignalCoordinator = PairSignalCoordinator(scope),
            syncScheduler = syncScheduler,
            pathResolver = pathResolver,
            candidateSampler = candidateSampler,
            stabilityDetector = stabilityDetector,
        )

    private fun givenEligiblePair(pair: SyncPair) {
        every { settingsRepository.globalAutoSyncEnabled } returns flowOf(true)
        every { settingsRepository.globalInstantSyncEnabled } returns flowOf(true)
        coEvery { syncPairRepository.getById(pair.id, contentResolver) } returns pair
    }

    private fun target(
        relativePath: String,
        documentIdHint: String? = null,
    ) = InstantSyncCandidateTarget(
        treeUri = mockk(relaxed = true),
        relativePath = relativePath,
        documentIdHint = documentIdHint,
    )

    private fun availableSample(documentId: String = "doc-1") =
        InstantSyncCandidateSample.Available(
            documentId = documentId,
            sizeBytes = 123L,
            mtimeMs = 456L,
            mimeType = "text/plain",
            openable = true,
            mediaStorePendingState = MediaStorePendingState.NOT_APPLICABLE,
            displayName = "notes.txt",
        )

    private fun pair(instantSyncEnabled: Boolean = true) =
        SyncPair(
            id = 7L,
            displayName = "Pair",
            localTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            provider = CloudProviderType.FAKE,
            accountId = "account-1",
            remoteFolderId = "remote",
            direction = SyncDirection.BIDIRECTIONAL,
            instantSyncEnabled = instantSyncEnabled,
        )
}
