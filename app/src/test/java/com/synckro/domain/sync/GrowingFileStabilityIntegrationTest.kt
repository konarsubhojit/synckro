package com.synckro.domain.sync

import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import com.synckro.providers.fake.FakeCloudProvider
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Deterministic end-to-end coverage of the "only upload quiet, eligible files" contract.
 *
 * The test wires the real [FileCandidatePolicy], [QuietPeriodFileStabilityDetector] and
 * [SyncOpApplier] against an in-memory file whose size, mtime, name, MediaStore pending state
 * and openability are driven from the test. Writers run on the same virtual-time scheduler as
 * the detector, so growth during a quiet period is reproduced without any real sleeping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GrowingFileStabilityIntegrationTest {
    private val provider = FakeCloudProvider()
    private val localIndexDao: LocalIndexDao = mockk(relaxed = true)
    private val conflictRepository: ConflictRepository = mockk(relaxed = true)
    private val eventRepository: SyncEventRepository = mockk(relaxed = true)

    @Test
    fun `file growing during the quiet period is requeued and never uploaded`() =
        runTest {
            val file = FakeLocalFile(name = "video.mp4", bytes = chunk(), mtimeMs = 1_000L)
            val gate = gate(file)
            growDuringQuietPeriod(file, appends = 2)

            val outcome = async { gate.process(file) }
            advanceUntilIdle()

            assertEquals(
                GateOutcome.Requeued(FileStabilityDeferralReason.CHANGED_DURING_QUIET_PERIOD.name),
                outcome.await(),
            )
            assertEquals(0, file.reads)
            assertTrue(provider.list(REMOTE_ROOT).isEmpty())
        }

    @Test
    fun `repeated attempts on a still growing file never upload a partial copy`() =
        runTest {
            val file = FakeLocalFile(name = "video.mp4", bytes = chunk(), mtimeMs = 1_000L)
            val gate = gate(file)

            repeat(3) {
                // The writer keeps appending for the whole of each attempt's quiet period.
                growDuringQuietPeriod(file, appends = 1)
                val outcome = async { gate.process(file) }
                advanceUntilIdle()
                assertEquals(
                    GateOutcome.Requeued(
                        FileStabilityDeferralReason.CHANGED_DURING_QUIET_PERIOD.name,
                    ),
                    outcome.await(),
                )
            }

            assertEquals(3, gate.requeued.size)
            assertEquals(0, file.reads)
            assertTrue(provider.list(REMOTE_ROOT).isEmpty())
        }

    @Test
    fun `temporary download name is excluded until the rename settles and then uploads once`() =
        runTest {
            val file = FakeLocalFile(name = "video.mp4.crdownload", bytes = chunk(), mtimeMs = 1_000L)
            val gate = gate(file)

            val whileTemporary = async { gate.process(file) }
            advanceUntilIdle()
            assertEquals(
                GateOutcome.Excluded(FileCandidateExclusionReason.TEMPORARY_NAME.name),
                whileTemporary.await(),
            )
            assertEquals(0, file.metadataReads)

            // The download finishes: the temporary file grows one last time and is renamed.
            file.bytes = chunk() + chunk()
            file.mtimeMs += 100L
            file.name = "video.mp4"
            val afterRename = async { gate.process(file) }
            advanceUntilIdle()

            assertEquals(GateOutcome.Uploaded("video.mp4"), afterRename.await())
            assertEquals(listOf("video.mp4"), provider.list(REMOTE_ROOT).map { it.name })
            assertEquals(2, file.reads)
        }

    @Test
    fun `mediastore pending file is excluded without polling or uploading`() =
        runTest {
            val file =
                FakeLocalFile(
                    name = "photo.jpg",
                    bytes = chunk(),
                    mtimeMs = 1_000L,
                    pendingState = MediaStorePendingState.PENDING,
                )
            val gate = gate(file)

            val outcome = async { gate.process(file) }
            advanceUntilIdle()

            assertEquals(
                GateOutcome.Excluded(FileCandidateExclusionReason.MEDIASTORE_PENDING.name),
                outcome.await(),
            )
            assertEquals(0, file.metadataReads)
            assertEquals(0, file.reads)
            assertTrue(provider.list(REMOTE_ROOT).isEmpty())
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `unreadable mediastore pending state is requeued without uploading`() =
        runTest {
            val file =
                FakeLocalFile(
                    name = "photo.jpg",
                    bytes = chunk(),
                    mtimeMs = 1_000L,
                    pendingState = MediaStorePendingState.UNAVAILABLE,
                )
            val gate = gate(file)

            val outcome = async { gate.process(file) }
            advanceUntilIdle()

            assertEquals(
                GateOutcome.Requeued(
                    FileCandidateInconclusiveReason.MEDIASTORE_PENDING_UNAVAILABLE.name,
                ),
                outcome.await(),
            )
            assertTrue(provider.list(REMOTE_ROOT).isEmpty())
        }

    @Test
    fun `file that cannot be opened after the quiet period is requeued without uploading`() =
        runTest {
            val file =
                FakeLocalFile(
                    name = "locked.bin",
                    bytes = chunk(),
                    mtimeMs = 1_000L,
                    openable = false,
                )
            val gate = gate(file)

            val outcome = async { gate.process(file) }
            advanceUntilIdle()

            assertEquals(
                GateOutcome.Requeued(FileStabilityDeferralReason.OPENABILITY_PROBE_FAILED.name),
                outcome.await(),
            )
            assertEquals(0, file.reads)
            assertTrue(provider.list(REMOTE_ROOT).isEmpty())
        }

    @Test
    fun `file removed during the quiet period is requeued without uploading`() =
        runTest {
            val file = FakeLocalFile(name = "video.mp4", bytes = chunk(), mtimeMs = 1_000L)
            val gate = gate(file)
            launch {
                delay(FileStabilityConfig.DEFAULT_POLL_INTERVAL_MS / 2)
                file.bytes = null
            }

            val outcome = async { gate.process(file) }
            advanceUntilIdle()

            assertEquals(
                GateOutcome.Requeued(FileStabilityDeferralReason.UNKNOWN_METADATA.name),
                outcome.await(),
            )
            assertTrue(provider.list(REMOTE_ROOT).isEmpty())
        }

    @Test
    fun `cancelling the quiet period leaves no upload behind`() =
        runTest {
            val file = FakeLocalFile(name = "video.mp4", bytes = chunk(), mtimeMs = 1_000L)
            val gate = gate(file)

            val outcome = async { gate.process(file) }
            runCurrent()
            outcome.cancel()
            advanceUntilIdle()

            assertTrue(outcome.isCancelled)
            try {
                outcome.await()
            } catch (_: CancellationException) {
                // Expected: cancellation is never converted into a stable/upload decision.
            }
            assertEquals(0, file.reads)
            assertTrue(provider.list(REMOTE_ROOT).isEmpty())
        }

    @Test
    fun `stable file uploads exactly once using virtual time only`() =
        runTest {
            val file = FakeLocalFile(name = "video.mp4", bytes = chunk(), mtimeMs = 1_000L)
            val gate = gate(file)

            val outcome = async { gate.process(file) }
            advanceUntilIdle()

            assertEquals(GateOutcome.Uploaded("video.mp4"), outcome.await())
            assertEquals(2, file.reads)
            assertEquals(3, file.metadataReads)
            assertEquals(1, provider.list(REMOTE_ROOT).size)
            assertTrue(gate.requeued.isEmpty())
            assertEquals(
                FileStabilityConfig.DEFAULT_POLL_INTERVAL_MS *
                    FileStabilityConfig.DEFAULT_QUIET_INTERVALS,
                testScheduler.currentTime,
            )
        }

    @Test
    fun `mutation after the stability gate cleans up the remote and requeues the file`() =
        runTest {
            val file = FakeLocalFile(name = "video.mp4", bytes = chunk(), mtimeMs = 1_000L)
            file.onRead = {
                file.bytes = chunk() + chunk()
                file.mtimeMs += 5_000L
            }
            val gate = gate(file)

            val outcome = async { gate.process(file) }
            advanceUntilIdle()

            val requeued = outcome.await() as GateOutcome.Requeued
            assertTrue(requeued.reason.contains("changed_during_upload"))
            assertEquals(listOf("video.mp4"), gate.requeued)
            assertTrue(provider.list(REMOTE_ROOT).isEmpty())
        }

    private fun TestScope.growDuringQuietPeriod(
        file: FakeLocalFile,
        appends: Int,
    ) {
        launch {
            repeat(appends) {
                // Write between two polls so every sample observes a different size and mtime.
                delay(FileStabilityConfig.DEFAULT_POLL_INTERVAL_MS / 2)
                file.bytes = (file.bytes ?: ByteArray(0)) + chunk()
                file.mtimeMs += 10L
                delay(FileStabilityConfig.DEFAULT_POLL_INTERVAL_MS / 2)
            }
        }
    }

    private fun gate(file: FakeLocalFile): StabilityUploadGate {
        val detector =
            QuietPeriodFileStabilityDetector<FakeLocalFile>(
                metadataReader = { target -> target.readStabilityMetadata() },
                openabilityProbe = { target -> target.bytes != null && target.openable },
            )
        val applier =
            SyncOpApplier(
                provider = provider,
                localIndexDao = localIndexDao,
                conflictRepository = conflictRepository,
                eventRepository = eventRepository,
                localFileAccess = FakeLocalFileAccess(file),
                ioDispatcher = Dispatchers.Unconfined,
            )
        return StabilityUploadGate(detector, applier, PAIR)
    }

    /**
     * Test harness that mirrors the intended watcher pipeline: candidate policy first, then the
     * quiet-period stability gate, and only then the real applier upload.
     */
    private class StabilityUploadGate(
        private val detector: FileStabilityDetector<FakeLocalFile>,
        private val applier: SyncOpApplier,
        private val pair: SyncPair,
    ) {
        val requeued = mutableListOf<String>()

        suspend fun process(file: FakeLocalFile): GateOutcome {
            when (val decision = FileCandidatePolicy.evaluate(file.name, file.pendingState)) {
                is FileCandidateDecision.Excluded -> return GateOutcome.Excluded(decision.reason.name)
                is FileCandidateDecision.Inconclusive -> return requeue(file, decision.reason.name)
                FileCandidateDecision.Eligible -> Unit
            }
            when (val stability = detector.awaitStable(file)) {
                is FileStabilityResult.Deferred -> return requeue(file, stability.reason.name)
                FileStabilityResult.Stable -> Unit
            }
            val path = file.name
            val result =
                applier.apply(
                    ops = listOf(SyncOp.UploadNew(path)),
                    pair = pair,
                    remoteFilesByPath = emptyMap(),
                    localIndexByPath = emptyMap(),
                )
            return if (result.appliedPaths.contains(path)) {
                GateOutcome.Uploaded(path)
            } else {
                requeue(file, result.errors.joinToString())
            }
        }

        private fun requeue(
            file: FakeLocalFile,
            reason: String,
        ): GateOutcome {
            requeued += file.name
            return GateOutcome.Requeued(reason)
        }
    }

    private sealed interface GateOutcome {
        data class Uploaded(
            val path: String,
        ) : GateOutcome

        data class Requeued(
            val reason: String,
        ) : GateOutcome

        data class Excluded(
            val reason: String,
        ) : GateOutcome
    }

    /**
     * In-memory local file whose observable state is fully controlled by the test.
     * A null [bytes] value means the file no longer exists.
     */
    private class FakeLocalFile(
        var name: String,
        var bytes: ByteArray?,
        var mtimeMs: Long,
        val pendingState: MediaStorePendingState = MediaStorePendingState.NOT_APPLICABLE,
        val openable: Boolean = true,
    ) {
        var metadataReads = 0
        var reads = 0

        /** Invoked right after the upload stream is handed out, to simulate a mid-upload writer. */
        var onRead: (() -> Unit)? = null

        fun readStabilityMetadata(): FileStabilityMetadata? {
            metadataReads += 1
            val content = bytes ?: return null
            return FileStabilityMetadata(sizeBytes = content.size.toLong(), mtimeMs = mtimeMs)
        }
    }

    private class FakeLocalFileAccess(
        private val file: FakeLocalFile,
    ) : LocalFileAccess {
        override fun openRead(relativePath: String): InputStream? {
            if (relativePath != file.name) return null
            val content = file.bytes ?: return null
            file.reads += 1
            val stream = ByteArrayInputStream(content)
            file.onRead?.invoke()
            return stream
        }

        override fun write(
            relativePath: String,
            content: InputStream,
            mimeType: String?,
        ): LocalFileStat = error("downloads are not exercised")

        override fun delete(relativePath: String): Boolean = false

        override fun stat(relativePath: String): LocalFileStat? {
            if (relativePath != file.name) return null
            val content = file.bytes ?: return null
            return LocalFileStat(sizeBytes = content.size.toLong(), mtimeMs = file.mtimeMs)
        }
    }

    private companion object {
        const val REMOTE_ROOT = "root"

        val PAIR =
            SyncPair(
                id = 1L,
                displayName = "Stability pair",
                localTreeUri = "content://test",
                provider = CloudProviderType.FAKE,
                remoteFolderId = REMOTE_ROOT,
                direction = SyncDirection.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.NEWEST_WINS,
            )

        fun chunk(): ByteArray = ByteArray(1_024) { 7 }
    }
}
