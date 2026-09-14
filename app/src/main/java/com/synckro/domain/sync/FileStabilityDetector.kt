package com.synckro.domain.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Determines whether a file-like object has remained unchanged for a quiet period.
 */
interface FileStabilityDetector<T> {
    /**
     * Suspends for [FileStabilityConfig.quietIntervals] intervals before declaring [target]
     * stable. Returns a deferred result instead of stable when comparable metadata is missing,
     * changes during the quiet period, or the final openability probe fails.
     *
     * Coroutine cancellation is propagated and never converted to a stable result.
     */
    suspend fun awaitStable(target: T): FileStabilityResult
}

/**
 * Polling configuration for [QuietPeriodFileStabilityDetector].
 *
 * By default, callers wait through two 1500 ms intervals, comparing the initial metadata sample
 * with one sample after each interval.
 */
data class FileStabilityConfig(
    /** Number of quiet intervals that must pass without a size or mtime change. */
    val quietIntervals: Int = DEFAULT_QUIET_INTERVALS,
    /** Delay between metadata samples, in milliseconds. */
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    init {
        require(quietIntervals > 0) { "quietIntervals must be positive" }
        require(pollIntervalMs >= 0) { "pollIntervalMs must be non-negative" }
    }

    companion object {
        const val DEFAULT_QUIET_INTERVALS: Int = 2
        const val DEFAULT_POLL_INTERVAL_MS: Long = 1_500L
    }
}

/**
 * Comparable file metadata sampled by [FileStabilityMetadataReader].
 *
 * [sizeBytes] and [mtimeMs] are nullable because some providers cannot report them. A null value
 * makes the stability decision inconclusive and defers the file.
 */
data class FileStabilityMetadata(
    val sizeBytes: Long?,
    /** Last-modified time in epoch milliseconds. */
    val mtimeMs: Long?,
)

fun interface FileStabilityMetadataReader<T> {
    /**
     * Returns the current metadata for [target], or null when metadata is not available.
     */
    suspend fun readMetadata(target: T): FileStabilityMetadata?
}

fun interface FileOpenabilityProbe<T> {
    /**
     * Returns true when [target] can be opened for reading.
     */
    suspend fun canOpen(target: T): Boolean
}

sealed interface FileStabilityResult {
    data object Stable : FileStabilityResult

    data class Deferred(
        val reason: FileStabilityDeferralReason,
    ) : FileStabilityResult
}

enum class FileStabilityDeferralReason {
    UNKNOWN_METADATA,
    INCOMPLETE_METADATA,
    METADATA_READ_FAILED,
    CHANGED_DURING_QUIET_PERIOD,
    OPENABILITY_PROBE_FAILED,
}

class QuietPeriodFileStabilityDetector<T>(
    private val metadataReader: FileStabilityMetadataReader<T>,
    private val openabilityProbe: FileOpenabilityProbe<T>,
    private val config: FileStabilityConfig = FileStabilityConfig(),
) : FileStabilityDetector<T> {
    override suspend fun awaitStable(target: T): FileStabilityResult {
        val initial =
            when (val metadata = readComparableMetadata(target)) {
                is MetadataReadOutcome.Available -> metadata.value
                MetadataReadOutcome.Incomplete -> return incompleteMetadata()
                MetadataReadOutcome.ReadFailed -> return metadataReadFailed()
                MetadataReadOutcome.Unavailable -> return unknownMetadata()
            }
        var previous = initial
        repeat(config.quietIntervals) {
            delay(config.pollIntervalMs)
            val current =
                when (val metadata = readComparableMetadata(target)) {
                    is MetadataReadOutcome.Available -> metadata.value
                    MetadataReadOutcome.Incomplete -> return incompleteMetadata()
                    MetadataReadOutcome.ReadFailed -> return metadataReadFailed()
                    MetadataReadOutcome.Unavailable -> return unknownMetadata()
                }
            if (current != previous) {
                return FileStabilityResult.Deferred(
                    FileStabilityDeferralReason.CHANGED_DURING_QUIET_PERIOD,
                )
            }
            previous = current
        }
        return if (probeOpenable(target)) {
            FileStabilityResult.Stable
        } else {
            FileStabilityResult.Deferred(FileStabilityDeferralReason.OPENABILITY_PROBE_FAILED)
        }
    }

    private suspend fun readComparableMetadata(target: T): MetadataReadOutcome =
        try {
            val metadata = metadataReader.readMetadata(target) ?: return MetadataReadOutcome.Unavailable
            val comparable = metadata.toComparable() ?: return MetadataReadOutcome.Incomplete
            MetadataReadOutcome.Available(comparable)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            MetadataReadOutcome.ReadFailed
        }

    private suspend fun probeOpenable(target: T): Boolean =
        try {
            openabilityProbe.canOpen(target)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }

    private fun FileStabilityMetadata.toComparable(): ComparableFileMetadata? {
        val size = sizeBytes ?: return null
        val mtime = mtimeMs ?: return null
        return ComparableFileMetadata(sizeBytes = size, mtimeMs = mtime)
    }

    private fun unknownMetadata(): FileStabilityResult =
        FileStabilityResult.Deferred(FileStabilityDeferralReason.UNKNOWN_METADATA)

    private fun incompleteMetadata(): FileStabilityResult =
        FileStabilityResult.Deferred(FileStabilityDeferralReason.INCOMPLETE_METADATA)

    private fun metadataReadFailed(): FileStabilityResult =
        FileStabilityResult.Deferred(FileStabilityDeferralReason.METADATA_READ_FAILED)

    private sealed interface MetadataReadOutcome {
        data class Available(
            val value: ComparableFileMetadata,
        ) : MetadataReadOutcome

        data object Unavailable : MetadataReadOutcome

        data object Incomplete : MetadataReadOutcome

        data object ReadFailed : MetadataReadOutcome
    }

    private data class ComparableFileMetadata(
        val sizeBytes: Long,
        val mtimeMs: Long,
    )
}
