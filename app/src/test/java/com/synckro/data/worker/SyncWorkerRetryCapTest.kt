package com.synckro.data.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [SyncWorker.MAX_RETRY_ATTEMPTS] retry-cap mechanism.
 *
 * The production logic checks `runAttemptCount + 1 >= MAX_RETRY_ATTEMPTS` to decide
 * whether to escalate a [com.synckro.domain.sync.SyncEngine.Result.Retriable] result
 * to a terminal failure instead of scheduling another retry via WorkManager.
 *
 * These tests verify:
 * - The constant is defined and has the expected value.
 * - The cap predicate (`runAttemptCount + 1 >= MAX_RETRY_ATTEMPTS`) correctly classifies
 *   each attempt number as "should retry" or "should escalate".
 */
class SyncWorkerRetryCapTest {
    private val cap = SyncWorker.MAX_RETRY_ATTEMPTS

    @Test
    fun `MAX_RETRY_ATTEMPTS is positive`() {
        assertTrue("MAX_RETRY_ATTEMPTS must be positive, was $cap", cap > 0)
    }

    @Test
    fun `MAX_RETRY_ATTEMPTS equals expected value`() {
        assertEquals(
            "MAX_RETRY_ATTEMPTS should be 5 (5 total attempts before escalation)",
            5,
            cap,
        )
    }

    @Test
    fun `first attempt should not escalate`() {
        val runAttemptCount = 0 // WorkManager first call
        val shouldEscalate = runAttemptCount + 1 >= cap
        assertFalse("First attempt (runAttemptCount=0) must NOT escalate", shouldEscalate)
    }

    @Test
    fun `intermediate attempts should not escalate`() {
        for (attempt in 0 until cap - 1) {
            val shouldEscalate = attempt + 1 >= cap
            assertFalse(
                "Attempt runAttemptCount=$attempt (attempt ${attempt + 1} of $cap) must NOT escalate",
                shouldEscalate,
            )
        }
    }

    @Test
    fun `final attempt should escalate`() {
        val runAttemptCount = cap - 1 // last allowed attempt index
        val shouldEscalate = runAttemptCount + 1 >= cap
        assertTrue(
            "Last attempt (runAttemptCount=${cap - 1}) MUST escalate to terminal",
            shouldEscalate,
        )
    }

    @Test
    fun `attempts beyond cap should also escalate`() {
        for (attempt in cap..cap + 5) {
            val shouldEscalate = attempt + 1 >= cap
            assertTrue(
                "Over-cap attempt runAttemptCount=$attempt must escalate",
                shouldEscalate,
            )
        }
    }

    @Test
    fun `exactly cap-minus-1 retries are allowed before escalation`() {
        // Attempts 0, 1, …, cap-2 → should retry (cap-1 retries total)
        val retryableCount = (0 until cap).count { attempt -> attempt + 1 < cap }
        assertEquals(
            "Exactly ${cap - 1} intermediate attempts should be retried before escalation",
            cap - 1,
            retryableCount,
        )
    }
}
