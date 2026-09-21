package com.synckro.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRateEstimatorTest {
    @Test
    fun `first sample has no rate or eta`() {
        val estimator = TransferRateEstimator()

        val estimate = estimator.update(timestampMs = 0L, bytesTransferred = 0L, totalBytes = 1_000L)

        assertNull(estimate.bytesPerSecond)
        assertNull(estimate.etaMillis)
    }

    @Test
    fun `zero-byte total omits eta even once a rate is established`() {
        val estimator = TransferRateEstimator()
        estimator.update(timestampMs = 0L, bytesTransferred = 0L, totalBytes = 0L)

        val estimate = estimator.update(timestampMs = 1_000L, bytesTransferred = 500L, totalBytes = 0L)

        assertNotNull("Rate should still be derivable", estimate.bytesPerSecond)
        assertNull("ETA must be omitted when totalBytes is unknown (0)", estimate.etaMillis)
    }

    @Test
    fun `unknown total (never reported) never produces an eta across many samples`() {
        val estimator = TransferRateEstimator()
        var lastEstimate = estimator.update(timestampMs = 0L, bytesTransferred = 0L, totalBytes = 0L)
        for (i in 1..5) {
            lastEstimate = estimator.update(timestampMs = i * 1_000L, bytesTransferred = i * 100L, totalBytes = 0L)
        }

        assertNull(lastEstimate.etaMillis)
    }

    @Test
    fun `stalled transfer omits eta instead of showing a wild value`() {
        val estimator = TransferRateEstimator()
        estimator.update(timestampMs = 0L, bytesTransferred = 100L, totalBytes = 1_000L)
        estimator.update(timestampMs = 1_000L, bytesTransferred = 300L, totalBytes = 1_000L)

        // No further bytes transferred even though time keeps advancing: rate decays toward 0.
        var estimate = estimator.update(timestampMs = 2_000L, bytesTransferred = 300L, totalBytes = 1_000L)
        repeat(20) {
            estimate = estimator.update(timestampMs = 2_000L + it * 1_000L, bytesTransferred = 300L, totalBytes = 1_000L)
        }

        assertNull("A stalled transfer's decayed-to-zero rate must not produce an ETA", estimate.etaMillis)
    }

    @Test
    fun `normal ramp-up produces a positive smoothed rate and a shrinking eta`() {
        val estimator = TransferRateEstimator()
        estimator.update(timestampMs = 0L, bytesTransferred = 0L, totalBytes = 10_000L)
        estimator.update(timestampMs = 1_000L, bytesTransferred = 1_000L, totalBytes = 10_000L)
        val second = estimator.update(timestampMs = 2_000L, bytesTransferred = 2_000L, totalBytes = 10_000L)
        val third = estimator.update(timestampMs = 3_000L, bytesTransferred = 3_000L, totalBytes = 10_000L)

        assertNotNull(second.bytesPerSecond)
        assertTrue("Rate must be positive for a steady 1000 B/s ramp", second.bytesPerSecond!! > 0.0)
        assertNotNull(second.etaMillis)
        assertNotNull(third.etaMillis)
        assertTrue(
            "ETA should shrink (or hold steady) as more bytes complete at a steady rate",
            third.etaMillis!! <= second.etaMillis!! + 500L,
        )
    }

    @Test
    fun `reaching totalBytes yields a zero eta`() {
        val estimator = TransferRateEstimator()
        estimator.update(timestampMs = 0L, bytesTransferred = 0L, totalBytes = 1_000L)
        val done = estimator.update(timestampMs = 1_000L, bytesTransferred = 1_000L, totalBytes = 1_000L)

        assertEquals(0L, done.etaMillis)
    }

    @Test
    fun `non-increasing elapsed time does not throw or produce a negative rate`() {
        val estimator = TransferRateEstimator()
        estimator.update(timestampMs = 1_000L, bytesTransferred = 100L, totalBytes = 1_000L)

        // A duplicate/out-of-order timestamp (elapsedMs <= 0) must be tolerated gracefully.
        val estimate = estimator.update(timestampMs = 1_000L, bytesTransferred = 150L, totalBytes = 1_000L)

        estimate.bytesPerSecond?.let { assertTrue(it >= 0.0) }
    }

    @Test
    fun `TransferRateTracker derives independent estimates per relative path`() {
        val tracker = TransferRateTracker()
        val first =
            tracker.update(
                timestampMs = 0L,
                transfers =
                    listOf(
                        ActiveTransfer("a.txt", TransferDirection.UPLOAD, 0L, 1_000L),
                        ActiveTransfer("b.txt", TransferDirection.DOWNLOAD, 0L, 2_000L),
                    ),
            )
        assertNull(first["a.txt"]?.bytesPerSecond)
        assertNull(first["b.txt"]?.bytesPerSecond)

        val second =
            tracker.update(
                timestampMs = 1_000L,
                transfers =
                    listOf(
                        ActiveTransfer("a.txt", TransferDirection.UPLOAD, 500L, 1_000L),
                        ActiveTransfer("b.txt", TransferDirection.DOWNLOAD, 100L, 2_000L),
                    ),
            )

        assertNotNull(second["a.txt"]?.bytesPerSecond)
        assertNotNull(second["b.txt"]?.bytesPerSecond)
        assertTrue(second["a.txt"]!!.bytesPerSecond!! > second["b.txt"]!!.bytesPerSecond!!)
    }

    @Test
    fun `TransferRateTracker drops stale paths so a restarted transfer starts fresh`() {
        val tracker = TransferRateTracker()
        tracker.update(timestampMs = 0L, transfers = listOf(ActiveTransfer("a.txt", TransferDirection.UPLOAD, 0L, 1_000L)))
        tracker.update(timestampMs = 1_000L, transfers = listOf(ActiveTransfer("a.txt", TransferDirection.UPLOAD, 900L, 1_000L)))

        // "a.txt" disappears (finished) then a new transfer of the same path starts again.
        tracker.update(timestampMs = 2_000L, transfers = emptyList())
        val restarted =
            tracker.update(timestampMs = 3_000L, transfers = listOf(ActiveTransfer("a.txt", TransferDirection.UPLOAD, 0L, 500L)))

        assertNull("A freshly (re)started transfer must not resume a stale estimate", restarted["a.txt"]?.bytesPerSecond)
    }
}
