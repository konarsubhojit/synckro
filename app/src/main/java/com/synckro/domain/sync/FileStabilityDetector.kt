package com.synckro.domain.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Determines whether a file-like object has remained unchanged for a quiet period.
 */
interface FileStabilityDetector<T> {
    suspend fun awaitStable(target: T): FileStabilityResult
}

data class FileStabilityConfig(
    val quietIntervals: Int = DEFAULT_QUIET_INTERVALS,
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

data class FileStabilityMetadata(
    val sizeBytes: Long?,
    val mtimeMs: Long?,
)

fun interface FileStabilityMetadataReader<T> {
    suspend fun readMetadata(target: T): FileStabilityMetadata?
}

fun interface FileOpenabilityProbe<T> {
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
    CHANGED_DURING_QUIET_PERIOD,
    OPENABILITY_PROBE_FAILED,
}

class QuietPeriodFileStabilityDetector<T>(
    private val metadataReader: FileStabilityMetadataReader<T>,
    private val openabilityProbe: FileOpenabilityProbe<T>,
    private val config: FileStabilityConfig = FileStabilityConfig(),
) : FileStabilityDetector<T> {
    override suspend fun awaitStable(target: T): FileStabilityResult {
        val initial = readComparableMetadata(target) ?: return unknownMetadata()
        repeat(config.quietIntervals) {
            delay(config.pollIntervalMs)
            val current = readComparableMetadata(target) ?: return unknownMetadata()
            if (current != initial) {
                return FileStabilityResult.Deferred(
                    FileStabilityDeferralReason.CHANGED_DURING_QUIET_PERIOD,
                )
            }
        }
        return if (canOpen(target)) {
            FileStabilityResult.Stable
        } else {
            FileStabilityResult.Deferred(FileStabilityDeferralReason.OPENABILITY_PROBE_FAILED)
        }
    }

    private suspend fun readComparableMetadata(target: T): ComparableFileMetadata? =
        try {
            metadataReader.readMetadata(target)?.toComparable()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }

    private suspend fun canOpen(target: T): Boolean =
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

    private data class ComparableFileMetadata(
        val sizeBytes: Long,
        val mtimeMs: Long,
    )
}
