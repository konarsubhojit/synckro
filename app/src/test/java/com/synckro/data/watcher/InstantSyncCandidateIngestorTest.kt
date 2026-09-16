package com.synckro.data.watcher

import android.content.ContentResolver
import android.net.Uri
import com.synckro.data.local.fs.EnumerationResult
import com.synckro.data.local.fs.LocalFileEntry
import com.synckro.data.local.fs.LocalFsEnumerator
import com.synckro.data.repository.PendingUploadRepository
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.domain.model.SyncEventTaxonomy
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class InstantSyncCandidateIngestorTest {
    private val dispatcher = StandardTestDispatcher()
    private val contentResolver = mockk<ContentResolver>()
    private val syncPairRepository = mockk<SyncPairRepository>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val pendingUploadRepository = mockk<PendingUploadRepository>(relaxed = true)
    private val syncScheduler = mockk<SyncScheduler>(relaxed = true)
    private val sourceProvider = mockk<LocalTreeWatchSourceProvider>()
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

    @Test
    fun `null hint queues rescan candidates with one dispatch and summary event`() =
        runTest(dispatcher) {
            val pair = pair()
            val enumerator = mockk<LocalFsEnumerator>()
            val eventRepository = mockk<SyncEventRepository>(relaxed = true)
            givenEligiblePair(pair)
            every { sourceProvider.sourceFor(pair.id) } returns LocalTreeWatchSource.Unsupported
            coEvery { enumerator.enumerate(pair.id, any(), emptyList(), emptyList(), false) } returns
                enumerationResult(added = setOf("first.txt", "second.txt"))
            givenAvailableRescanCandidates(pair, "first.txt", "second.txt")
            val ingestor =
                ingestor(
                    CoroutineScope(this.coroutineContext),
                    pathResolver = InstantSyncChangedPathResolver(sourceProvider),
                    localFsEnumerator = enumerator,
                    eventRepository = eventRepository,
                )

            ingestor.onLocalChange(LocalChangeEvent.Changed(pair.id))
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            coVerify(exactly = 2) { pendingUploadRepository.upsertCandidate(pair.id, any(), any(), any(), any(), any(), any()) }
            verify(exactly = 1) { syncScheduler.enqueueInstant(pair) }
            coVerify(exactly = 1) {
                eventRepository.log(
                    pair.id,
                    SyncEventLevel.INFO,
                    SyncEventTag.INSTANT_WATCH,
                    SyncEventTaxonomy.watchRescan(2),
                )
            }
        }

    @Test
    fun `SAF tree root hint queues candidates via rescan`() =
        runTest(dispatcher) {
            val pair = pair()
            val enumerator = mockk<LocalFsEnumerator>()
            givenEligiblePair(pair)
            every { sourceProvider.sourceFor(pair.id) } returns LocalTreeWatchSource.Unsupported
            coEvery { enumerator.enumerate(pair.id, any(), emptyList(), emptyList(), false) } returns
                enumerationResult(added = setOf("root.txt"))
            givenAvailableRescanCandidates(pair, "root.txt")

            ingestor(
                CoroutineScope(this.coroutineContext),
                pathResolver = InstantSyncChangedPathResolver(sourceProvider),
                localFsEnumerator = enumerator,
            ).onLocalChange(LocalChangeEvent.Changed(pair.id, pair.localTreeUri))
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            coVerify(exactly = 1) { pendingUploadRepository.upsertCandidate(pair.id, "root.txt", any(), any(), any(), any(), any()) }
            verify(exactly = 1) { syncScheduler.enqueueInstant(pair) }
        }

    @Test
    fun `FileObserver root filesystem hint queues candidates via rescan`() =
        runTest(dispatcher) {
            val pair = pair()
            val enumerator = mockk<LocalFsEnumerator>()
            givenEligiblePair(pair)
            every { sourceProvider.sourceFor(pair.id) } returns LocalTreeWatchSource.DirectPath("/storage/emulated/0/Documents")
            coEvery { enumerator.enumerate(pair.id, any(), emptyList(), emptyList(), false) } returns
                enumerationResult(added = setOf("camera.txt"))
            givenAvailableRescanCandidates(pair, "camera.txt")

            ingestor(
                CoroutineScope(this.coroutineContext),
                pathResolver = InstantSyncChangedPathResolver(sourceProvider),
                localFsEnumerator = enumerator,
            ).onLocalChange(LocalChangeEvent.Changed(pair.id, "/storage/emulated/0/Documents"))
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            coVerify(exactly = 1) { pendingUploadRepository.upsertCandidate(pair.id, "camera.txt", any(), any(), any(), any(), any()) }
            verify(exactly = 1) { syncScheduler.enqueueInstant(pair) }
        }

    @Test
    fun `file level hint uses targeted path without rescan`() =
        runTest(dispatcher) {
            val pair = pair()
            val target = target("notes.txt")
            val enumerator = mockk<LocalFsEnumerator>(relaxed = true)
            givenEligiblePair(pair)
            every { pathResolver.resolve(pair, any()) } returns listOf(target)
            every { candidateSampler.sample(target) } returns availableSample()
            coEvery { stabilityDetector.awaitStable(target) } returns FileStabilityResult.Stable

            ingestor(CoroutineScope(this.coroutineContext), localFsEnumerator = enumerator)
                .onLocalChange(LocalChangeEvent.Changed(pair.id, "notes.txt"))
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            coVerify(exactly = 0) { enumerator.enumerate(any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { pendingUploadRepository.upsertCandidate(pair.id, "notes.txt", any(), any(), any(), any(), any()) }
            verify(exactly = 1) { syncScheduler.enqueueInstant(pair) }
        }

    @Test
    fun `rescan passes pair scope filters to enumerator`() =
        runTest(dispatcher) {
            val pair = pair(includeGlobs = listOf("*.txt"), excludeGlobs = listOf("private/**"), excludeSubfolders = true)
            val enumerator = mockk<LocalFsEnumerator>()
            givenEligiblePair(pair)
            every { pathResolver.resolve(pair, any()) } returns emptyList()
            every { pathResolver.isCoarse(pair, any()) } returns true
            coEvery { enumerator.enumerate(pair.id, any(), pair.includeGlobs, pair.excludeGlobs, true) } returns
                enumerationResult(added = setOf("root.txt"))
            givenAvailableRescanCandidates(pair, "root.txt")

            ingestor(CoroutineScope(this.coroutineContext), localFsEnumerator = enumerator)
                .onLocalChange(LocalChangeEvent.Changed(pair.id, isCoarse = true))
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            coVerify(exactly = 1) { enumerator.enumerate(pair.id, any(), pair.includeGlobs, pair.excludeGlobs, true) }
            coVerify(exactly = 1) { pendingUploadRepository.upsertCandidate(pair.id, "root.txt", any(), any(), any(), any(), any()) }
        }

    @Test
    fun `burst coarse events coalesce into one rescan without deadlock`() =
        runTest(dispatcher) {
            val pair = pair()
            val enumerator = mockk<LocalFsEnumerator>()
            givenEligiblePair(pair)
            every { pathResolver.resolve(pair, any()) } returns emptyList()
            every { pathResolver.isCoarse(pair, any()) } returns true
            coEvery { enumerator.enumerate(pair.id, any(), emptyList(), emptyList(), false) } returns
                enumerationResult(added = setOf("first.txt"), modified = setOf("second.txt"))
            givenAvailableRescanCandidates(pair, "first.txt", "second.txt")

            val ingestor = ingestor(CoroutineScope(this.coroutineContext), localFsEnumerator = enumerator)
            ingestor.onLocalChange(LocalChangeEvent.Changed(pair.id, isCoarse = true))
            ingestor.onLocalChange(LocalChangeEvent.Changed(pair.id, isCoarse = true))
            advanceTimeBy(PairSignalCoordinator.DEFAULT_DEBOUNCE_MS)
            runCurrent()

            coVerify(exactly = 1) { enumerator.enumerate(pair.id, any(), emptyList(), emptyList(), false) }
            coVerify(exactly = 2) { pendingUploadRepository.upsertCandidate(pair.id, any(), any(), any(), any(), any(), any()) }
            verify(exactly = 1) { syncScheduler.enqueueInstant(pair) }
        }

    private fun ingestor(
        scope: CoroutineScope,
        pathResolver: InstantSyncChangedPathResolver = this.pathResolver,
        localFsEnumerator: LocalFsEnumerator? = null,
        eventRepository: SyncEventRepository? = null,
    ) = InstantSyncCandidateIngestor(
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
        localFsEnumerator = localFsEnumerator,
        eventRepository = eventRepository,
    )

    private fun givenEligiblePair(pair: SyncPair) {
        every { settingsRepository.globalAutoSyncEnabled } returns flowOf(true)
        every { settingsRepository.globalInstantSyncEnabled } returns flowOf(true)
        coEvery { syncPairRepository.getById(pair.id, contentResolver) } returns pair
    }

    private fun givenAvailableRescanCandidates(
        pair: SyncPair,
        vararg relativePaths: String,
    ) {
        val treeUri = Uri.parse(pair.localTreeUri)
        relativePaths.forEach { relativePath ->
            val target = InstantSyncCandidateTarget(treeUri, relativePath)
            every { candidateSampler.sample(target) } returns availableSample(documentId = "doc-$relativePath")
            coEvery { stabilityDetector.awaitStable(target) } returns FileStabilityResult.Stable
        }
    }

    private fun enumerationResult(
        added: Set<String> = emptySet(),
        modified: Set<String> = emptySet(),
    ) = EnumerationResult(
        snapshot = (added + modified).map { LocalFileEntry(it, 123L, 456L, null) },
        added = added,
        modified = modified,
        deleted = emptySet(),
    )

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

    private fun pair(
        instantSyncEnabled: Boolean = true,
        includeGlobs: List<String> = emptyList(),
        excludeGlobs: List<String> = emptyList(),
        excludeSubfolders: Boolean = false,
    ) = SyncPair(
        id = 7L,
        displayName = "Pair",
        localTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
        provider = CloudProviderType.FAKE,
        accountId = "account-1",
        remoteFolderId = "remote",
        direction = SyncDirection.BIDIRECTIONAL,
        instantSyncEnabled = instantSyncEnabled,
        includeGlobs = includeGlobs,
        excludeGlobs = excludeGlobs,
        excludeSubfolders = excludeSubfolders,
    )
}
