package com.synckro.domain.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileStabilityDetectorTest {
    @Test
    fun `stable readable file passes after two default quiet intervals`() =
        runTest {
            val metadataReader =
                SequenceMetadataReader(
                    metadata(sizeBytes = 10, mtimeMs = 100),
                    metadata(sizeBytes = 10, mtimeMs = 100),
                    metadata(sizeBytes = 10, mtimeMs = 100),
                )
            val openabilityProbe = RecordingOpenabilityProbe(canOpen = true)
            val detector =
                QuietPeriodFileStabilityDetector(
                    metadataReader = metadataReader,
                    openabilityProbe = openabilityProbe,
                )

            val result = async { detector.awaitStable(FILE_ID) }
            runCurrent()
            assertEquals(1, metadataReader.calls)
            advanceTimeBy(FileStabilityConfig.DEFAULT_POLL_INTERVAL_MS - 1)
            runCurrent()
            assertFalse(result.isCompleted)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, metadataReader.calls)
            assertFalse(result.isCompleted)

            advanceTimeBy(FileStabilityConfig.DEFAULT_POLL_INTERVAL_MS)
            runCurrent()

            assertEquals(FileStabilityResult.Stable, result.await())
            assertEquals(3, metadataReader.calls)
            assertEquals(1, openabilityProbe.calls)
        }

    @Test
    fun `growing file defers without openability probe`() =
        runTest {
            val metadataReader =
                SequenceMetadataReader(
                    metadata(sizeBytes = 10, mtimeMs = 100),
                    metadata(sizeBytes = 11, mtimeMs = 100),
                )
            val openabilityProbe = RecordingOpenabilityProbe(canOpen = true)
            val detector =
                QuietPeriodFileStabilityDetector(
                    metadataReader = metadataReader,
                    openabilityProbe = openabilityProbe,
                )

            assertEquals(
                FileStabilityResult.Deferred(
                    FileStabilityDeferralReason.CHANGED_DURING_QUIET_PERIOD,
                ),
                detector.awaitStable(FILE_ID),
            )
            assertEquals(0, openabilityProbe.calls)
        }

    @Test
    fun `touched file defers without openability probe`() =
        runTest {
            val detector =
                QuietPeriodFileStabilityDetector(
                    metadataReader =
                        SequenceMetadataReader(
                            metadata(sizeBytes = 10, mtimeMs = 100),
                            metadata(sizeBytes = 10, mtimeMs = 101),
                        ),
                    openabilityProbe = RecordingOpenabilityProbe(canOpen = true),
                )

            assertEquals(
                FileStabilityResult.Deferred(
                    FileStabilityDeferralReason.CHANGED_DURING_QUIET_PERIOD,
                ),
                detector.awaitStable(FILE_ID),
            )
        }

    @Test
    fun `replaced file defers without openability probe`() =
        runTest {
            val detector =
                QuietPeriodFileStabilityDetector(
                    metadataReader =
                        SequenceMetadataReader(
                            metadata(sizeBytes = 10, mtimeMs = 100),
                            metadata(sizeBytes = 20, mtimeMs = 200),
                        ),
                    openabilityProbe = RecordingOpenabilityProbe(canOpen = true),
                )

            assertEquals(
                FileStabilityResult.Deferred(
                    FileStabilityDeferralReason.CHANGED_DURING_QUIET_PERIOD,
                ),
                detector.awaitStable(FILE_ID),
            )
        }

    @Test
    fun `unknown metadata defers without openability probe`() =
        runTest {
            val openabilityProbe = RecordingOpenabilityProbe(canOpen = true)
            val detector =
                QuietPeriodFileStabilityDetector(
                    metadataReader = SequenceMetadataReader(metadata(sizeBytes = null, mtimeMs = 100)),
                    openabilityProbe = openabilityProbe,
                )

            assertEquals(
                FileStabilityResult.Deferred(FileStabilityDeferralReason.UNKNOWN_METADATA),
                detector.awaitStable(FILE_ID),
            )
            assertEquals(0, openabilityProbe.calls)
        }

    @Test
    fun `openability failure defers after quiet period`() =
        runTest {
            val detector =
                QuietPeriodFileStabilityDetector(
                    metadataReader =
                        SequenceMetadataReader(
                            metadata(sizeBytes = 10, mtimeMs = 100),
                            metadata(sizeBytes = 10, mtimeMs = 100),
                            metadata(sizeBytes = 10, mtimeMs = 100),
                        ),
                    openabilityProbe = RecordingOpenabilityProbe(canOpen = false),
                )

            assertEquals(
                FileStabilityResult.Deferred(
                    FileStabilityDeferralReason.OPENABILITY_PROBE_FAILED,
                ),
                detector.awaitStable(FILE_ID),
            )
        }

    @Test
    fun `cancellation during quiet period is propagated`() =
        runTest {
            val metadataReader =
                SequenceMetadataReader(
                    metadata(sizeBytes = 10, mtimeMs = 100),
                    metadata(sizeBytes = 10, mtimeMs = 100),
                    metadata(sizeBytes = 10, mtimeMs = 100),
                )
            val openabilityProbe = RecordingOpenabilityProbe(canOpen = true)
            val detector =
                QuietPeriodFileStabilityDetector(
                    metadataReader = metadataReader,
                    openabilityProbe = openabilityProbe,
                )

            val result = async { detector.awaitStable(FILE_ID) }
            runCurrent()
            result.cancel()

            try {
                result.await()
                fail("Expected cancellation to propagate")
            } catch (_: CancellationException) {
                assertEquals(1, metadataReader.calls)
                assertEquals(0, openabilityProbe.calls)
            }
        }

    private fun metadata(
        sizeBytes: Long?,
        mtimeMs: Long?,
    ) = FileStabilityMetadata(sizeBytes = sizeBytes, mtimeMs = mtimeMs)

    private class SequenceMetadataReader(
        vararg samples: FileStabilityMetadata?,
    ) : FileStabilityMetadataReader<String> {
        private val samples = samples.toList()
        private var nextIndex = 0
        var calls = 0
            private set

        override suspend fun readMetadata(target: String): FileStabilityMetadata? {
            check(target == FILE_ID)
            if (nextIndex >= samples.size) {
                throw AssertionError("No metadata sample configured for call ${nextIndex + 1}")
            }
            calls += 1
            return samples[nextIndex++]
        }
    }

    private class RecordingOpenabilityProbe(
        private val canOpen: Boolean,
    ) : FileOpenabilityProbe<String> {
        var calls = 0
            private set

        override suspend fun canOpen(target: String): Boolean {
            check(target == FILE_ID)
            calls += 1
            return canOpen
        }
    }

    private companion object {
        const val FILE_ID = "local-file"
    }
}
