package com.synckro.domain.sync

import com.synckro.domain.provider.CloudProviderException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RetryPolicyTest {
    // -----------------------------------------------------------------------
    // Basic success / failure paths
    // -----------------------------------------------------------------------

    @Test
    fun `succeeds on first attempt without retrying`() =
        runTest {
            val calls = AtomicInteger(0)
            val result =
                withRetry(maxAttempts = 3, initialDelayMs = 0) {
                    calls.incrementAndGet()
                    "ok"
                }
            assertEquals("ok", result)
            assertEquals(1, calls.get())
        }

    @Test
    fun `retries on transient failure and succeeds`() =
        runTest {
            val calls = AtomicInteger(0)
            val result =
                withRetry(maxAttempts = 3, initialDelayMs = 0) {
                    if (calls.incrementAndGet() < 3) error("transient")
                    "success"
                }
            assertEquals("success", result)
            assertEquals(3, calls.get())
        }

    @Test
    fun `exhausts all attempts and rethrows last exception`() =
        runTest {
            val calls = AtomicInteger(0)
            try {
                withRetry(maxAttempts = 3, initialDelayMs = 0) {
                    calls.incrementAndGet()
                    error("always fails")
                }
                fail("Expected an exception to be thrown")
            } catch (e: IllegalStateException) {
                assertEquals("always fails", e.message)
            }
            assertEquals(3, calls.get())
        }

    // -----------------------------------------------------------------------
    // Non-retriable exceptions are rethrown immediately
    // -----------------------------------------------------------------------

    @Test
    fun `AuthenticationRequired is not retried`() =
        runTest {
            val calls = AtomicInteger(0)
            try {
                withRetry(maxAttempts = 5, initialDelayMs = 0) {
                    calls.incrementAndGet()
                    throw CloudProviderException.AuthenticationRequired("needs login")
                }
                fail("Expected AuthenticationRequired")
            } catch (e: CloudProviderException.AuthenticationRequired) {
                assertEquals("needs login", e.message)
            }
            assertEquals(1, calls.get())
        }

    @Test
    fun `AuthenticationFailed is not retried`() =
        runTest {
            val calls = AtomicInteger(0)
            try {
                withRetry(maxAttempts = 5, initialDelayMs = 0) {
                    calls.incrementAndGet()
                    throw CloudProviderException.AuthenticationFailed("token error")
                }
                fail("Expected AuthenticationFailed")
            } catch (e: CloudProviderException.AuthenticationFailed) {
                assertEquals("token error", e.message)
            }
            assertEquals(1, calls.get())
        }

    @Test
    fun `NotConfigured is not retried`() =
        runTest {
            val calls = AtomicInteger(0)
            try {
                withRetry(maxAttempts = 5, initialDelayMs = 0) {
                    calls.incrementAndGet()
                    throw CloudProviderException.NotConfigured("missing client ID")
                }
                fail("Expected NotConfigured")
            } catch (e: CloudProviderException.NotConfigured) {
                assertEquals("missing client ID", e.message)
            }
            assertEquals(1, calls.get())
        }

    // -----------------------------------------------------------------------
    // RateLimited (429) — retriable but honours Retry-After
    // -----------------------------------------------------------------------

    @Test
    fun `RateLimited is retried and eventually succeeds`() =
        runTest {
            val calls = AtomicInteger(0)
            val result =
                withRetry(maxAttempts = 5, initialDelayMs = 0) {
                    if (calls.incrementAndGet() < 4) {
                        throw CloudProviderException.RateLimited(retryAfterMs = 0, message = "429")
                    }
                    "recovered"
                }
            assertEquals("recovered", result)
            assertEquals(4, calls.get())
        }

    @Test
    fun `onRetry callback receives attempt and exception`() =
        runTest {
            val retryAttempts = mutableListOf<Int>()
            val retryExceptions = mutableListOf<Throwable>()
            withRetry(
                maxAttempts = 3,
                initialDelayMs = 0,
                onRetry = { attempt, cause ->
                    retryAttempts += attempt
                    retryExceptions += cause
                },
            ) {
                if (retryAttempts.size < 2) error("fail")
                "done"
            }
            assertEquals(listOf(1, 2), retryAttempts)
            assertTrue(retryExceptions.all { it is IllegalStateException })
        }

    // -----------------------------------------------------------------------
    // maxAttempts = 1 — no retries at all
    // -----------------------------------------------------------------------

    @Test
    fun `maxAttempts 1 does not retry on failure`() =
        runTest {
            val calls = AtomicInteger(0)
            try {
                withRetry(maxAttempts = 1, initialDelayMs = 0) {
                    calls.incrementAndGet()
                    error("fail")
                }
                fail("Expected exception")
            } catch (e: IllegalStateException) {
                // expected
            }
            assertEquals(1, calls.get())
        }
}
