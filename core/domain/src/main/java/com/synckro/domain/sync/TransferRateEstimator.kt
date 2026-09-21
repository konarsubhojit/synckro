package com.synckro.domain.sync

/**
 * Smoothed transfer-rate and ETA estimate for a single in-flight [ActiveTransfer].
 *
 * @param bytesPerSecond Exponentially-smoothed transfer rate, or `null` until at least two
 *   samples have been observed for this transfer.
 * @param etaMillis Estimated time remaining, or `null` when [bytesPerSecond] is unavailable,
 *   the rate is not (yet) positive, or the total size is unknown ([TransferRateEstimator.update]'s
 *   `totalBytes == 0`). Callers should omit the ETA from the UI rather than show a stale or
 *   wildly inaccurate value in these cases.
 */
data class TransferRateEstimate(
    val bytesPerSecond: Double?,
    val etaMillis: Long?,
)

/**
 * Tracks successive [TransferProgress]/[ActiveTransfer] byte-count samples for a single
 * transfer and derives a jitter-resistant transfer rate and ETA.
 *
 * The instantaneous rate between two samples (`Δbytes / Δtime`) is combined into a running
 * exponential moving average so a single slow or fast tick does not cause the displayed
 * speed/ETA to jump around. This class is pure Kotlin (no Android dependency) so it can be
 * unit-tested on the JVM and reused by any UI layer that renders per-file transfer progress.
 *
 * Not thread-safe; callers should confine a single instance to one transfer and one thread
 * (e.g. the Compose UI thread), typically via [TransferRateTracker].
 *
 * @param smoothingFactor Weight (0f, 1f] given to the newest instantaneous sample when
 *   updating the moving average. Higher values react faster to real rate changes but jitter
 *   more; lower values are steadier but slower to reflect a genuine speed change.
 */
class TransferRateEstimator(
    private val smoothingFactor: Double = 0.35,
) {
    private var lastTimestampMs: Long? = null
    private var lastBytesTransferred: Long? = null
    private var smoothedBytesPerSecond: Double? = null

    /**
     * Records a new sample and returns the current smoothed rate/ETA estimate.
     *
     * @param timestampMs Wall-clock time of this sample, in epoch milliseconds. Must be
     *   monotonically non-decreasing across calls for a given instance.
     * @param bytesTransferred Cumulative bytes transferred so far for this transfer.
     * @param totalBytes Total expected bytes for this transfer; `0` means "unknown size".
     */
    fun update(
        timestampMs: Long,
        bytesTransferred: Long,
        totalBytes: Long,
    ): TransferRateEstimate {
        val previousTimestampMs = lastTimestampMs
        val previousBytesTransferred = lastBytesTransferred
        lastTimestampMs = timestampMs
        lastBytesTransferred = bytesTransferred

        if (previousTimestampMs == null || previousBytesTransferred == null) {
            // First sample: no elapsed interval to derive a rate from yet.
            return TransferRateEstimate(bytesPerSecond = null, etaMillis = null)
        }

        val elapsedMs = timestampMs - previousTimestampMs
        if (elapsedMs > 0) {
            val deltaBytes = (bytesTransferred - previousBytesTransferred).coerceAtLeast(0L)
            val instantRate = deltaBytes * MILLIS_PER_SECOND / elapsedMs.toDouble()
            smoothedBytesPerSecond =
                when (val previousRate = smoothedBytesPerSecond) {
                    null -> instantRate
                    else -> previousRate + smoothingFactor * (instantRate - previousRate)
                }
        }

        return TransferRateEstimate(
            bytesPerSecond = smoothedBytesPerSecond,
            etaMillis = estimateEtaMillis(smoothedBytesPerSecond, bytesTransferred, totalBytes),
        )
    }

    private fun estimateEtaMillis(
        bytesPerSecond: Double?,
        bytesTransferred: Long,
        totalBytes: Long,
    ): Long? {
        // Unknown total size, or a rate that is not (yet) established or has stalled to ~0:
        // omit the ETA rather than show an unknown or wildly inaccurate value.
        if (totalBytes <= 0L || bytesPerSecond == null || bytesPerSecond < MIN_RATE_FOR_ETA) return null
        val remainingBytes = (totalBytes - bytesTransferred).coerceAtLeast(0L)
        if (remainingBytes == 0L) return 0L
        return (remainingBytes * MILLIS_PER_SECOND / bytesPerSecond).toLong()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0

        /** Rates below this are treated as "stalled" for ETA purposes. */
        const val MIN_RATE_FOR_ETA = 1.0
    }
}

/**
 * Owns one [TransferRateEstimator] per [ActiveTransfer.relativePath] so a UI layer can derive
 * a rate/ETA per concurrently-running transfer without manually managing estimator lifetimes.
 *
 * Call [update] once per new [TransferProgress] emission; paths no longer present in the
 * active list are dropped so a finished-then-restarted transfer for the same path starts a
 * fresh estimate rather than resuming a stale one.
 */
class TransferRateTracker(
    private val smoothingFactor: Double = 0.35,
) {
    private val estimatorsByPath = mutableMapOf<String, TransferRateEstimator>()

    /** Updates every tracked transfer with [timestampMs] and returns a rate/ETA per path. */
    fun update(
        timestampMs: Long,
        transfers: List<ActiveTransfer>,
    ): Map<String, TransferRateEstimate> {
        val activePaths = transfers.mapTo(mutableSetOf()) { it.relativePath }
        estimatorsByPath.keys.retainAll(activePaths)
        return transfers.associate { transfer ->
            val estimator = estimatorsByPath.getOrPut(transfer.relativePath) { TransferRateEstimator(smoothingFactor) }
            transfer.relativePath to estimator.update(timestampMs, transfer.bytesTransferred, transfer.totalBytes)
        }
    }
}
